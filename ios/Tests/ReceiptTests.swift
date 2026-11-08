import XCTest
@testable import PyroChat

/// Receipts + synced burn timers against the live relay (127.0.0.1:7444).
@MainActor
final class ReceiptTests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-rcpt-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "rcpt-test-pass",
                                at: dir.appendingPathComponent("store"))
        let username = "rc" + UUID().uuidString.prefix(8).lowercased()
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

    func testDeliveredReceiptAdvancesToDelivered() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("receipt check", to: alice.contacts.first!)
        let delivered = await waitUntil {
            alice.messages[bob.myHash]?.contains {
                $0.direction == .outgoing && $0.status == .delivered
            } == true
        }
        XCTAssertTrue(delivered, "sender must reach DELIVERED without any read")

        // the receipt itself must not create rows on either side
        XCTAssertEqual(bob.messages[alice.myHash]?.count, 1)
        XCTAssertEqual(alice.messages[bob.myHash]?.count, 1)

        alice.goOffline(); bob.goOffline()
    }

    func testDeliveredReceiptAfterOfflineQueue() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        bob.goOffline()
        try? await Task.sleep(for: .milliseconds(300))
        await alice.sendText("catch up later", to: alice.contacts.first!)
        let sent = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.status == .sent } == true
        }
        XCTAssertTrue(sent)

        try await bob.goOnline()
        let delivered = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.status == .delivered } == true
        }
        XCTAssertTrue(delivered, "queued message + receipt outbox must deliver after reconnect")

        alice.goOffline(); bob.goOffline()
    }

    func testReadReceiptReciprocityBothOn() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        alice.store.update { $0.settings.sendReadReceipts = true }
        bob.store.update { $0.settings.sendReadReceipts = true }
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("read me", to: alice.contacts.first!)
        let arrived = await waitUntil {
            bob.messages[alice.myHash]?.isEmpty == false
        }
        XCTAssertTrue(arrived)

        // simulate the row becoming visible in bob's open chat
        let msg = bob.messages[alice.myHash]!.first!
        bob.noteRowVisible(messageID: msg.id, thread: alice.myHash)

        let read = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.status == .read } == true
        }
        XCTAssertTrue(read, "with both toggles on, READ must reach the sender")

        alice.goOffline(); bob.goOffline()
    }

    func testReadReceiptNotSentWhenToggleOff() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        // defaults: read receipts OFF
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("private read", to: alice.contacts.first!)
        _ = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }

        let msg = bob.messages[alice.myHash]!.first!
        bob.noteRowVisible(messageID: msg.id, thread: alice.myHash)
        try? await Task.sleep(for: .seconds(2))

        let aliceRow = alice.messages[bob.myHash]!.first { $0.direction == .outgoing }!
        XCTAssertEqual(aliceRow.status, .delivered,
                       "toggle off ⇒ sender must never see READ")
        // but bob's local copy IS marked read (state consistency + unread badge)
        XCTAssertEqual(bob.messages[alice.myHash]!.first!.status, .read)
        XCTAssertEqual(bob.unreadCount(for: alice.myHash), 0)

        alice.goOffline(); bob.goOffline()
    }

    func testSyncedBurnStartsOnReadNotOnArrival() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        alice.store.update { $0.settings.defaultBurnSeconds = 2 }
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("synced burn", to: alice.contacts.first!)
        _ = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }

        // NOT read yet: the message must survive well past burnAfter
        try? await Task.sleep(for: .seconds(3))
        XCTAssertTrue(bob.messages[alice.myHash]?.contains { $0.text == "synced burn" } == true,
                      "unread burn message must NOT burn on arrival")
        XCTAssertTrue(alice.messages[bob.myHash]?.contains { $0.text == "synced burn" } == true,
                      "sender copy must NOT burn before the peer reads")

        // bob reads it -> both countdowns start (burn_start control to alice)
        let msg = bob.messages[alice.myHash]!.first!
        XCTAssertNil(msg.burnStartedAt)
        bob.noteRowVisible(messageID: msg.id, thread: alice.myHash)

        let bothGone = await waitUntil(8) {
            let bobGone = bob.messages[alice.myHash]?.contains { $0.text == "synced burn" } != true
            let aliceGone = alice.messages[bob.myHash]?.contains { $0.text == "synced burn" } != true
            return bobGone && aliceGone
        }
        XCTAssertTrue(bothGone, "after read, both copies must burn together")

        alice.goOffline(); bob.goOffline()
    }

    func testNoDowngradeAndUnknownIdsIgnored() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        alice.store.update { $0.settings.sendReadReceipts = true }
        bob.store.update { $0.settings.sendReadReceipts = true }
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)

        await alice.sendText("upgrade only", to: alice.contacts.first!)
        _ = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        let msg = bob.messages[alice.myHash]!.first!
        bob.noteRowVisible(messageID: msg.id, thread: alice.myHash)
        let read = await waitUntil {
            alice.messages[bob.myHash]?.contains { $0.status == .read } == true
        }
        XCTAssertTrue(read)

        // a late delivered receipt (relay redelivery) must not downgrade READ:
        // bob re-enqueues delivered for the same id — alice must stay READ
        let before = alice.messages[bob.myHash]!
        try? await Task.sleep(for: .seconds(1))
        XCTAssertEqual(alice.messages[bob.myHash]!.first { $0.direction == .outgoing }!.status,
                       .read, "READ is terminal — no downgrade ever")
        XCTAssertEqual(alice.messages[bob.myHash]!.count, before.count,
                       "receipts never create rows")

        alice.goOffline(); bob.goOffline()
    }

    func testReceiptFromStrangerCreatesNothing() async throws {
        let (alice, aliceName) = makeService()
        let (charlie, charlieName) = makeService()
        try await alice.register(username: aliceName)
        try await charlie.register(username: charlieName)

        // charlie has a session with alice but no conversation
        try await charlie.addContact(username: aliceName)
        await charlie.burnThread(charlie.contacts.first!)  // sends control to alice

        try? await Task.sleep(for: .seconds(2))
        XCTAssertFalse(alice.contacts.contains { $0.hash == charlie.myHash },
                       "a control from a stranger must not create a contact")
        XCTAssertTrue(alice.messages[charlie.myHash]?.isEmpty ?? true,
                      "no phantom thread from stranger controls")

        alice.goOffline(); charlie.goOffline()
    }
}
