import XCTest
@testable import PyroChat

/// Drives two complete ChatService instances (the exact engine the UI binds
/// to) against the live relay: registration, contact add, send/ack/receive,
/// dedupe, and self-destruct timers.
@MainActor
final class ChatServiceTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-svc-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "svc-test-pass-123",
                                at: dir.appendingPathComponent("store"))
        let username = "svc" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: store), username)
    }

    private func waitUntil(_ timeout: TimeInterval = 8,
                           _ cond: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return true }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return cond()
    }

    func testFullConversationLifecycle() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()

        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        XCTAssertEqual(alice.connState, .online)
        XCTAssertEqual(bob.connState, .online)

        // alice opens the channel (prekey fetch + PQXDH bootstrap)
        try await alice.addContact(username: bobName)
        let contact = alice.contacts.first!
        XCTAssertEqual(contact.hash, bob.myHash)

        // alice -> bob
        await alice.sendText("first contact \u{1F56F}", to: contact)
        let ok1 = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.text.hasPrefix("first contact") } == true
        }
        XCTAssertTrue(ok1, "bob must receive and decrypt alice's message")

        // bob auto-created a contact for the unknown sender
        let bobsContactForAlice = bob.contacts.first { $0.hash == alice.myHash }
        XCTAssertNotNil(bobsContactForAlice)

        // bob -> alice over the established ratchet
        await bob.sendText("channel confirmed", to: bobsContactForAlice!)
        let ok2 = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.text == "channel confirmed" } == true
        }
        XCTAssertTrue(ok2, "alice must receive bob's reply")

        // sender-side status advanced to at least SENT (delivered receipts
        // may race past it)
        let ok3 = await waitUntil {
            alice.messages[bob.myHash]?.contains {
                $0.direction == .outgoing
                && $0.status.receiptRank >= ChatMessage.Status.sent.receiptRank
            } == true
        }
        XCTAssertTrue(ok3)

        alice.goOffline()
        bob.goOffline()
    }

    func testBurnAfterTimerDestroysMessageBothSides() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)

        // 2-second self-destruct on everything alice sends
        alice.store.update { $0.settings.defaultBurnSeconds = 2 }

        try await alice.addContact(username: bobName)
        await alice.sendText("this message will burn", to: alice.contacts.first!)

        let ok4 = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.text.contains("will burn") } == true
        }
        XCTAssertTrue(ok4, "message must arrive before it burns")

        // SYNCED BURN (v0.2): the countdown starts when the recipient READS,
        // not on arrival — simulate the row becoming visible
        if let msg = bob.messages[alice.myHash]?.first(where: { $0.text.contains("will burn") }) {
            bob.noteRowVisible(messageID: msg.id, thread: alice.myHash)
        }

        // both copies must be gone after the timer fires
        let ok5 = await waitUntil(6) {
            let bobGone = bob.messages[alice.myHash]?.contains { $0.text.contains("will burn") } != true
            let aliceGone = alice.messages[bob.myHash]?.contains { $0.text.contains("will burn") } != true
            return bobGone && aliceGone
        }
        XCTAssertTrue(ok5, "burn timer must wipe the message on both devices")

        alice.goOffline()
        bob.goOffline()
    }
}

extension ChatServiceTests {
    /// Voice pipeline: encrypt → chunk → binary frames → relay → reassemble
    /// → decrypt. Uses synthetic audio bytes; playback is a UI concern.
    func testVoiceNoteRoundTrip() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        // With sealed sender the first message to someone new is text only, and an
        // attachment goes once that text is DELIVERED (docs/SEALED-SENDER.md §5.2).
        // Without sealed sender the extra message changes nothing.
        await alice.sendText("hello", to: alice.contacts.first!)
        _ = await waitUntil { alice.messages[bob.myHash]?.first?.status == .delivered }

        // ~200 KB spans multiple 64 KiB chunks
        var audio = Data(count: 200_000)
        audio.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 200_000, $0.baseAddress!) }

        await alice.sendVoice(audio, duration: 12.5, to: alice.contacts.first!)

        let arrived = await waitUntil(10) {
            bob.messages[alice.myHash]?.contains {
                // docs/DECOY-ACCOUNT.md §1a: the bytes land in a sidecar now
                $0.kind == .voice && bob.voiceData(for: $0) == audio
            } == true
        }
        XCTAssertTrue(arrived, "voice bytes must survive encrypt/chunk/reassemble/decrypt intact")
        let received = bob.messages[alice.myHash]!.first { $0.kind == .voice }!
        XCTAssertEqual(received.voiceDuration, 12.5)

        alice.goOffline()
        bob.goOffline()
    }
}
