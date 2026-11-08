import XCTest
import CryptoKit
@testable import PyroChat

/// The scenario of the leak gate (docs/SEALED-SENDER.md §9, M1–M5 and X1), run by
/// `node relay/test/gate.mjs ios`. Four identities, each talking to the relay through its own
/// recording proxy, do everything the app does; the script then reads what went over the wire.
/// On its own this test skips: it has nothing to connect to.
///
/// The steps are the same in Android's `SealedGateTest`. What goes the old way on purpose is
/// written to `expect.json`, and the script holds the wire to exactly that.
@MainActor
final class SealedGateTests: XCTestCase {

    @MainActor private struct Identity {
        let svc: ChatService
        let name: String
        let proxy: Int
        var hash: String { svc.myHash }
    }

    private func identity(_ prefix: String, proxy: Int, relay: String, legacy: Bool = false) async throws -> Identity {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pyro-gate-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "gate-pass-123", at: dir.appendingPathComponent("store"))
        store.update { $0.settings.relayURL = relay; $0.settings.sendReadReceipts = true }
        let svc = ChatService(store: store)
        svc.legacyWireOnly = legacy
        let name = prefix + UUID().uuidString.prefix(8).lowercased()
        try await svc.register(username: name)
        return Identity(svc: svc, name: name, proxy: proxy)
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ what: String, file: StaticString = #filePath,
                           line: UInt = #line, _ cond: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(cond(), what, file: file, line: line)
    }

    private func contact(_ a: Identity, _ b: Identity) -> Contact { a.svc.contacts.first { $0.hash == b.hash }! }
    private func rows(_ a: Identity, _ thread: String) -> [ChatMessage] { a.svc.messages[thread] ?? [] }
    private func has(_ a: Identity, _ thread: String, text: String) -> Bool { rows(a, thread).contains { $0.text == text } }
    private func bytes(_ n: Int, _ seed: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* seed &+ seed) }) }

    /// `a` adds `b`, writes first, and both end up holding each other's delivery key.
    private func introduce(_ a: Identity, _ b: Identity, _ text: String) async throws {
        try await a.svc.addContact(username: b.name)
        await a.svc.sendText(text, to: contact(a, b))
        await waitUntil(10, "\(text): delivered, keys exchanged") {
            a.svc.peerKey(b.hash)?.dk != nil && b.svc.peerKey(a.hash)?.dk != nil
                && self.rows(a, b.hash).first { $0.text == text }?.status == .delivered
        }
    }

    func testTheGateScenario() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let relays = env["PYRO_GATE_RELAYS"]?.split(separator: ",").map(String.init), relays.count == 4,
              let out = env["PYRO_GATE_DIR"], let echoName = env["PYRO_GATE_ECHO"] else {
            throw XCTSkip("run by `node relay/test/gate.mjs ios`")
        }
        let alice = try await identity("ga", proxy: 0, relay: relays[0])
        let bob = try await identity("gb", proxy: 1, relay: relays[1])
        let carol = try await identity("gc", proxy: 2, relay: relays[2])
        let dora = try await identity("gd", proxy: 3, relay: relays[3], legacy: true)     // a build from before sealed sender
        let all = [alice, bob, carol, dora]
        defer { for i in all { i.svc.goOffline() } }

        /// What the script needs to read the recording. Written as soon as the four exist, so
        /// the wire is still checked when a step below fails, and again at the end.
        func publish() throws {
            let identities: [[String: Any]] = try all.map { i in
                let auth = try Curve25519.Signing.PrivateKey(rawRepresentation: i.svc.store.blob.authPrivateKey)
                return ["name": i.name, "proxy": i.proxy, "hash": i.hash,
                        "identityKey": try i.svc.crypto.myIdentityPublic().base64EncodedString(),
                        "authPub": auth.publicKey.rawRepresentation.base64EncodedString(),
                        "deliveryKey": (i.svc.store.blob.deliveryKey ?? Data()).base64EncodedString(),
                        "legacy": i.proxy == 3]
            }
            let expect: [String: Any] = [
                // messages sent the old way on purpose: two to carol around her old build, one each way with dora
                "legacyMsgs": ["0>2": 2, "0>3": 1, "3>0": 1],
                "legacyPairs": [[0, 2], [0, 3]],
            ]
            let dir = URL(fileURLWithPath: out)
            try JSONSerialization.data(withJSONObject: expect).write(to: dir.appendingPathComponent("expect.json"))
            try JSONSerialization.data(withJSONObject: identities).write(to: dir.appendingPathComponent("identities.json"))
        }
        try publish()
        for i in [alice, bob, carol] { XCTAssertEqual(i.svc.sealedStatus, .on, "\(i.name) is offered sealed sender") }
        XCTAssertEqual(dora.svc.sealedStatus, .notOffered)

        // 1 — first contact, a reply, receipts, typing
        try await introduce(alice, bob, "hello bob")
        let first = try XCTUnwrap(rows(alice, bob.hash).first { $0.text == "hello bob" })
        bob.svc.noteRowVisible(messageID: first.id, thread: alice.hash)
        await waitUntil(10, "read receipt") { self.rows(alice, bob.hash).first { $0.id == first.id }?.status == .read }
        await bob.svc.sendText("hello alice", to: contact(bob, alice))
        await waitUntil(10, "reply") { self.has(alice, bob.hash, text: "hello alice") }
        alice.svc.noteComposerActivity(in: bob.hash)
        await waitUntil(10, "typing reaches bob") { bob.svc.typingPeers[alice.hash] != nil }

        // 2 — attachments, both ways
        let image = bytes(150_000, 7)
        await alice.svc.sendImage(jpeg: image, width: 8, height: 8, to: contact(alice, bob))
        await waitUntil(15, "image to bob") { self.rows(bob, alice.hash).contains { $0.kind == .image } }
        XCTAssertEqual(bob.svc.attachmentData(for: rows(bob, alice.hash).first { $0.kind == .image }!), image)
        let file = bytes(80_000, 11)
        await bob.svc.sendFile(data: file, filename: "plan.bin", mime: "application/octet-stream", to: contact(bob, alice))
        await waitUntil(15, "file to alice") { self.rows(alice, bob.hash).contains { $0.kind == .file } }
        XCTAssertEqual(alice.svc.attachmentData(for: rows(alice, bob.hash).first { $0.kind == .file }!), file)

        // 3 — a group with text and an image; bob and carol have never spoken
        try await introduce(alice, carol, "hello carol")
        let g = try await alice.svc.createGroup(name: "gate", members: [contact(alice, bob), contact(alice, carol)])
        await waitUntil(10, "group created") {
            bob.svc.group(id: g.id)?.members.count == 3 && carol.svc.group(id: g.id)?.members.count == 3
        }
        await alice.svc.sendText("to the group", toGroup: g.id)
        await bob.svc.sendText("bob to the group", toGroup: g.id)
        await waitUntil(10, "group texts") {
            self.has(bob, g.id, text: "to the group") && self.has(carol, g.id, text: "to the group")
                && self.has(alice, g.id, text: "bob to the group") && self.has(carol, g.id, text: "bob to the group")
        }
        let groupImage = bytes(120_000, 13)
        await alice.svc.sendImage(jpeg: groupImage, width: 8, height: 8, toGroup: g.id)
        await waitUntil(15, "group image") {
            self.rows(bob, g.id).contains { $0.kind == .image } && self.rows(carol, g.id).contains { $0.kind == .image }
        }
        XCTAssertEqual(carol.svc.attachmentData(for: rows(carol, g.id).first { $0.kind == .image }!), groupImage)
        await waitUntil(10, "group receipts") { self.rows(alice, g.id).first { $0.kind == .image }?.status != .pending }

        // 4 — bob loses the session; the next message heals it, and an image follows on the new
        //     session (a PreKey message inside: the case sealing the blob exists for)
        bob.svc.store.update { $0.sessions[alice.hash] = nil }
        await alice.svc.sendText("this one is lost", to: contact(alice, bob))
        await waitUntil(15, "bob re-keys") { self.rows(bob, alice.hash).contains { $0.kind == .system && $0.text.contains("re-keyed") } }
        await waitUntil(15, "alice adopts it") { self.rows(alice, bob.hash).contains { $0.kind == .system && $0.text.contains("re-keyed") } }
        let second = bytes(90_000, 17)
        await alice.svc.sendImage(jpeg: second, width: 8, height: 8, to: contact(alice, bob))
        await waitUntil(15, "image after the reset") { self.rows(bob, alice.hash).filter { $0.kind == .image }.count == 2 }
        XCTAssertEqual(bob.svc.attachmentData(for: rows(bob, alice.hash).last { $0.kind == .image }!), second)

        // 5 — delete for both, and a screenshot alert
        let reply = try XCTUnwrap(rows(bob, alice.hash).first { $0.text == "hello alice" })
        let deleted = await bob.svc.deleteForBoth(id: reply.id, thread: alice.hash)
        XCTAssertTrue(deleted)
        await waitUntil(10, "deleted on alice's phone too") { !self.has(alice, bob.hash, text: "hello alice") }
        await alice.svc.reportCapture("screenshot", in: contact(alice, bob))
        await waitUntil(10, "screenshot alert") { self.rows(bob, alice.hash).contains { $0.text.contains("screenshot") } }

        // 6 — carol goes back to an old build, and returns
        carol.svc.goOffline()
        carol.svc.legacyWireOnly = true
        try await carol.svc.goOnline()
        await alice.svc.sendText("to carol on an old build", to: contact(alice, carol))          // legacy message 1 of 2 to carol
        await waitUntil(10, "reaches the old build") { self.has(carol, alice.hash, text: "to carol on an old build") }
        await waitUntil(10, "and is acknowledged") { self.rows(alice, carol.hash).last?.status == .delivered }
        carol.svc.goOffline()
        carol.svc.legacyWireOnly = false
        try await carol.svc.goOnline()
        await alice.svc.sendText("carol is back", to: contact(alice, carol))                     // legacy message 2 of 2: it carries alice's key
        await waitUntil(10, "back on the key lane") {
            alice.svc.sealedLane(for: carol.hash) == .key && self.rows(alice, carol.hash).last?.status == .delivered
        }
        await alice.svc.sendText("sealed again", to: contact(alice, carol))
        await waitUntil(10, "sealed again") { self.has(carol, alice.hash, text: "sealed again") }
        await waitUntil(10, "and acknowledged") { self.rows(alice, carol.hash).last?.status == .delivered }

        // 7 — bob's delivery key changes (a restore from a backup made before sealed sender)
        bob.svc.goOffline()
        bob.svc.store.update { $0.deliveryKey = nil; $0.peerKeys = nil }
        try await bob.svc.goOnline()
        await alice.svc.sendText("after bob's restore", to: contact(alice, bob))
        await waitUntil(5, "found again, promptly") { self.has(bob, alice.hash, text: "after bob's restore") }
        await waitUntil(10, "alice has his new key") {
            alice.svc.peerKey(bob.hash)?.dk == bob.svc.store.blob.deliveryKey && self.rows(alice, bob.hash).last?.status == .delivered
        }
        await alice.svc.sendText("and once more", to: contact(alice, bob))
        await waitUntil(10, "both on the key lane again") {
            bob.svc.sealedLane(for: alice.hash) == .key && self.rows(alice, bob.hash).last?.status == .delivered
        }

        // 8 — an old build
        try await alice.svc.addContact(username: dora.name)
        await alice.svc.sendText("to an old build", to: contact(alice, dora))                    // legacy, alice to dora
        await waitUntil(10, "old build gets it") { self.has(dora, alice.hash, text: "to an old build") }
        await dora.svc.sendText("from an old build", to: contact(dora, alice))                   // legacy, dora to alice
        await waitUntil(10, "and answers") { self.has(alice, dora.hash, text: "from an old build") }
        await waitUntil(10, "receipts settle") {
            self.rows(alice, dora.hash).first?.status == .delivered && self.rows(dora, alice.hash).last?.status == .delivered
        }

        // 9 — an independent libsignal phone: it opens what this app sealed, and seals back
        let echo = sha256Hex(echoName)
        try await alice.svc.addContact(username: echoName)
        // typing before the first message: nothing may leave (C9), and the echo phone would say so
        alice.svc.noteComposerActivity(in: echo)
        try? await Task.sleep(for: .milliseconds(300))
        await alice.svc.sendText("ping", to: alice.svc.contacts.first { $0.hash == echo }!)
        await waitUntil(10, "the echo answers") { self.has(alice, echo, text: "echo: ping") }
        await waitUntil(10, "and acknowledged") { self.rows(alice, echo).first { $0.text == "ping" }?.status == .delivered }
        let toEcho = bytes(100_000, 19)
        await alice.svc.sendFile(data: toEcho, filename: "to-echo.bin", mime: "application/octet-stream",
                                 to: alice.svc.contacts.first { $0.hash == echo }!)
        await waitUntil(20, "the echo's attachment arrives") {
            self.rows(alice, echo).contains { $0.kind == .file && $0.direction == .incoming }
        }
        let back = try XCTUnwrap(rows(alice, echo).first { $0.kind == .file && $0.direction == .incoming })
        XCTAssertEqual(alice.svc.attachmentData(for: back), Data(toEcho.reversed()), "sealed by another implementation, opened here")

        // let the last receipts leave before the sockets close
        try? await Task.sleep(for: .seconds(1))

        // again at the end: bob's delivery key changed on the way
        try publish()
    }
}
