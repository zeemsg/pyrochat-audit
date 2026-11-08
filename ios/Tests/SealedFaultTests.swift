import XCTest
import CryptoKit
@testable import PyroChat

/// The scenario of the fault gate (docs/SEALED-SENDER.md §9, F1, F3, F4, F5, F8), run by
/// `node relay/test/faults.mjs ios`. Three identities, each talking to the relay through its
/// own proxy, keep writing to each other while the script breaks things under them: a message
/// lost with its connection, a verdict that never comes back, a network that goes away, a
/// connection and then the relay itself dying in the middle of an attachment, the relay coming
/// back without sealed sender and then with it again, no second connection to be had, a path
/// that kills every connection a message goes out on, the end of an attachment lost after the
/// app had handed all of it over (on the new path and on the old one), and the relay's answer
/// to an attachment lost on its way back. Every message has to arrive, once, and nobody taps
/// anything to make that happen. Last, the relay refuses a piece of an attachment: that one
/// fails, says why, and goes again when asked to. On its own this test skips.
///
/// The steps are the same in Android's `SealedFaultTest`.
@MainActor
final class SealedFaultTests: XCTestCase {

    @MainActor private struct Identity {
        let svc: ChatService
        let name: String
        let proxy: Int
        var hash: String { svc.myHash }
    }

    private struct Failure: Error, CustomStringConvertible { let description: String }

    private var dir = URL(fileURLWithPath: "/")
    private var asked = 0

    /// Ask the script for a fault, and wait until it is in place. Its answer comes back.
    @discardableResult
    private func ask(_ what: String, _ fields: [String: Any] = [:]) async throws -> String {
        asked += 1
        var request = fields
        request["do"] = what
        try JSONSerialization.data(withJSONObject: request)
            .write(to: dir.appendingPathComponent("req-\(asked).json"), options: .atomic)
        let ack = dir.appendingPathComponent("ack-\(asked).json")
        let deadline = Date().addingTimeInterval(60)
        while !FileManager.default.fileExists(atPath: ack.path) {
            guard Date() < deadline else { throw Failure(description: "the fault script did not answer `\(what)`") }
            try await Task.sleep(for: .milliseconds(30))
        }
        let answer = String(decoding: try Data(contentsOf: ack), as: UTF8.self)
        guard answer.contains("\"ok\":true") else {
            throw Failure(description: "the fault script refused `\(what)`: \(answer)")
        }
        return answer
    }

    /// How many faults have happened so far, as the script counts them.
    private func faults() async throws -> Int {
        let answer = try await ask("count")
        guard let reply = try JSONSerialization.jsonObject(with: Data(answer.utf8)) as? [String: Any],
              let n = reply["faults"] as? Int else {
            throw Failure(description: "the fault script did not count: \(answer)")
        }
        return n
    }

    private func identity(_ prefix: String, proxy: Int, relay: String) async throws -> Identity {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pyro-fault-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "fault-pass-123", at: dir.appendingPathComponent("store"))
        store.update { $0.settings.relayURL = relay; $0.settings.sendReadReceipts = true }
        let svc = ChatService(store: store)
        let name = prefix + UUID().uuidString.prefix(8).lowercased()
        try await svc.register(username: name)
        return Identity(svc: svc, name: name, proxy: proxy)
    }

    private func until(_ timeout: TimeInterval, _ what: String, file: StaticString = #filePath,
                       line: UInt = #line, _ cond: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if cond() { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(cond(), what, file: file, line: line)
    }

    private func contact(_ a: Identity, _ b: Identity) -> Contact { a.svc.contacts.first { $0.hash == b.hash }! }
    private func rows(_ a: Identity, _ b: Identity) -> [ChatMessage] { a.svc.messages[b.hash] ?? [] }
    private func copies(_ a: Identity, _ b: Identity, _ text: String) -> Int { rows(a, b).filter { $0.text == text }.count }
    private func sent(_ a: Identity, _ b: Identity, _ text: String) -> ChatMessage? {
        rows(a, b).first { $0.text == text && $0.direction == .outgoing }
    }
    private func bytes(_ n: Int, _ seed: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* seed &+ seed) }) }
    private func up(_ i: Identity, _ status: ChatService.SealedStatus) -> Bool { i.svc.connState == .online && i.svc.sealedStatus == status }
    private func file(_ a: Identity, _ b: Identity, _ name: String) -> ChatMessage? { rows(a, b).first { $0.fileName == name } }

    /// The file `name` is with `b`, whole and once, `a`'s row is DELIVERED, and nothing is left half received.
    private func fileArrivesOnce(_ a: Identity, _ b: Identity, _ name: String, _ content: Data, _ timeout: TimeInterval,
                                 _ what: String, file: StaticString = #filePath, line: UInt = #line) async {
        await until(timeout, what, file: file, line: line) {
            self.file(b, a, name) != nil && self.file(a, b, name)?.status == .delivered
        }
        try? await Task.sleep(for: .milliseconds(600))        // a second copy would have had time to land
        XCTAssertEqual(rows(b, a).filter { $0.fileName == name }.count, 1, "`\(name)` arrived once", file: file, line: line)
        if let got = self.file(b, a, name) {
            XCTAssertEqual(b.svc.attachmentData(for: got), content, file: file, line: line)
        }
        XCTAssertTrue(b.svc.rxTransfers.isEmpty, "nothing is left there, still receiving", file: file, line: line)
    }

    /// `a` adds `b`, writes first, and both end up holding each other's delivery key.
    private func introduce(_ a: Identity, _ b: Identity, _ text: String) async throws {
        try await a.svc.addContact(username: b.name)
        await a.svc.sendText(text, to: contact(a, b))
        await until(10, "\(text): delivered, keys exchanged") {
            a.svc.peerKey(b.hash)?.dk != nil && b.svc.peerKey(a.hash)?.dk != nil
                && self.sent(a, b, text)?.status == .delivered
        }
    }

    /// `text` reaches `b` once and `a`'s row ends DELIVERED, without anyone doing anything more.
    private func arrivesOnce(_ a: Identity, _ b: Identity, _ text: String, _ timeout: TimeInterval,
                             file: StaticString = #filePath, line: UInt = #line) async {
        await until(timeout, "`\(text)` arrives and is acknowledged", file: file, line: line) {
            self.copies(b, a, text) >= 1 && self.sent(a, b, text)?.status == .delivered
        }
        try? await Task.sleep(for: .milliseconds(600))        // a second copy would have had time to land
        XCTAssertEqual(copies(b, a, text), 1, "`\(text)` arrived once", file: file, line: line)
        XCTAssertEqual(copies(a, b, text), 1, "and is one row on the sender's side", file: file, line: line)
    }

    func testTheFaultScenario() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let relays = env["PYRO_FAULT_RELAYS"]?.split(separator: ",").map(String.init), relays.count == 3,
              let out = env["PYRO_FAULT_DIR"] else {
            throw XCTSkip("run by `node relay/test/faults.mjs ios`")
        }
        dir = URL(fileURLWithPath: out)
        let alice = try await identity("fa", proxy: 0, relay: relays[0])
        let bob = try await identity("fb", proxy: 1, relay: relays[1])
        let carol = try await identity("fc", proxy: 2, relay: relays[2])
        let all = [alice, bob, carol]
        defer { for i in all { i.svc.goOffline() } }

        /// What the script needs to read the recording; written at once, so the wire is still
        /// checked when a step fails.
        func publish() throws {
            let identities: [[String: Any]] = try all.map { i in
                let auth = try Curve25519.Signing.PrivateKey(rawRepresentation: i.svc.store.blob.authPrivateKey)
                return ["name": i.name, "proxy": i.proxy, "hash": i.hash,
                        "identityKey": try i.svc.crypto.myIdentityPublic().base64EncodedString(),
                        "authPub": auth.publicKey.rawRepresentation.base64EncodedString(),
                        "deliveryKey": (i.svc.store.blob.deliveryKey ?? Data()).base64EncodedString()]
            }
            // the only messages sent the old way: one each way between alice and bob while the
            // relay had sealed sender off, and two from alice while she could not open an
            // anonymous connection
            let expect: [String: Any] = ["legacyMsgs": ["0>1": 3, "1>0": 1]]
            try JSONSerialization.data(withJSONObject: expect).write(to: dir.appendingPathComponent("expect.json"))
            try JSONSerialization.data(withJSONObject: identities).write(to: dir.appendingPathComponent("identities.json"))
        }
        for i in all { XCTAssertEqual(i.svc.sealedStatus, .on, "\(i.name) is offered sealed sender") }
        // the relay's answer after an attachment is given four seconds here, not sixty
        let usual = ChatService.uploadAnswerTimeout
        ChatService.uploadAnswerTimeout = .seconds(4)
        defer { ChatService.uploadAnswerTimeout = usual }
        try publish()
        try await introduce(alice, bob, "hello bob")
        try await introduce(alice, carol, "hello carol")

        // 1 — a message is lost on its way, and its connection dies with it
        try await ask("lose-deliver", ["proxy": 0])
        await alice.svc.sendText("lost with its connection", to: contact(alice, bob))
        await arrivesOnce(alice, bob, "lost with its connection", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "lost with its connection")!.id)?.t, "deliver",
                       "and it went again sealed")

        // 2 — the relay took a message, and its `sent` never came back
        bob.svc.goOffline()
        try await ask("lose-sent", ["proxy": 0])
        await alice.svc.sendText("its verdict was lost", to: contact(alice, bob))
        await until(25, "no verdict in fifteen seconds: the row fails, and is sent again by itself") {
            self.sent(alice, bob, "its verdict was lost")?.status == .sent
        }
        try await bob.svc.goOnline()
        await arrivesOnce(alice, bob, "its verdict was lost", 15)       // the relay held both copies for him

        // 3 — the network goes away with a message on its way, and comes back
        try await ask("blackout", ["proxy": 0, "ms": 3000])
        await alice.svc.sendText("sent into a dead network", to: contact(alice, bob))
        await arrivesOnce(alice, bob, "sent into a dead network", 40)
        await until(10, "alice is back, with sealed sender") { self.up(alice, .on) }

        // 4 — a connection dies in the middle of an attachment, large enough that the app is
        //     still writing it when that happens
        let cut = bytes(6 * 1024 * 1024, 7)
        try await ask("cut-on-chunk", ["proxy": 0, "chunk": 3])
        await alice.svc.sendFile(data: cut, filename: "cut.bin", mime: "application/octet-stream", to: contact(alice, bob))
        await until(45, "the attachment is sent again by itself, and arrives") {
            self.rows(bob, alice).contains { $0.fileName == "cut.bin" }
                && self.rows(alice, bob).first { $0.fileName == "cut.bin" }?.status == .delivered
        }
        XCTAssertEqual(rows(bob, alice).filter { $0.fileName == "cut.bin" }.count, 1)
        XCTAssertEqual(bob.svc.attachmentData(for: rows(bob, alice).first { $0.fileName == "cut.bin" }!), cut)
        XCTAssertTrue(bob.svc.rxTransfers.isEmpty, "the half that never finished is not left there, still receiving")

        // 4b — the end of an attachment is lost after every piece had been handed over. Only
        //      the answer that never comes says so: without it this row would stay SENT.
        let tail = bytes(600 * 1024, 5)
        try await ask("cut-on-last-chunk", ["proxy": 0])
        await alice.svc.sendFile(data: tail, filename: "tail.bin", mime: "application/octet-stream", to: contact(alice, bob))
        await fileArrivesOnce(alice, bob, "tail.bin", tail, 45, "it is sent again by itself, and arrives")

        // 5 — the relay restarts in the middle of an attachment
        let restarted = bytes(6 * 1024 * 1024, 11)
        try await ask("restart-on-chunk", ["proxy": 0, "chunk": 3])
        await alice.svc.sendFile(data: restarted, filename: "restart.bin", mime: "application/octet-stream", to: contact(alice, bob))
        await until(60, "everyone comes back, the attachment is sent again and arrives") {
            all.allSatisfy { self.up($0, .on) } && self.rows(bob, alice).contains { $0.fileName == "restart.bin" }
                && self.rows(alice, bob).first { $0.fileName == "restart.bin" }?.status == .delivered
        }
        XCTAssertEqual(rows(bob, alice).filter { $0.fileName == "restart.bin" }.count, 1)
        XCTAssertEqual(bob.svc.attachmentData(for: rows(bob, alice).first { $0.fileName == "restart.bin" }!), restarted)
        XCTAssertTrue(bob.svc.rxTransfers.isEmpty)

        // 6 — the relay comes back without sealed sender, and then with it again
        try await ask("relay", ["sealed": false])
        await until(40, "alice and bob are back, and told it is off") { self.up(alice, .notOffered) && self.up(bob, .notOffered) }
        await alice.svc.sendText("while it is off", to: contact(alice, bob))        // the old way, on purpose
        await bob.svc.sendText("still here", to: contact(bob, alice))               // the old way, on purpose
        await arrivesOnce(alice, bob, "while it is off", 15)
        await arrivesOnce(bob, alice, "still here", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "while it is off")!.id)?.t, "send")
        XCTAssertEqual(bob.svc.frameCapture.frame(for: sent(bob, alice, "still here")!.id)?.t, "send")
        // 6b — the same lost ending on the old path, where the pieces travel on the logged-in
        //      connection: it dies, comes back by itself, and the attachment goes again
        let old = bytes(600 * 1024, 9)
        try await ask("cut-on-last-chunk", ["proxy": 0, "main": true])
        await alice.svc.sendFile(data: old, filename: "old-path.bin", mime: "application/octet-stream", to: contact(alice, bob))
        await fileArrivesOnce(alice, bob, "old-path.bin", old, 45, "alice reconnects, and it is sent again by itself")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: file(alice, bob, "old-path.bin")!.id)?.t, "send")
        await until(10, "alice is back, and still told it is off") { self.up(alice, .notOffered) }

        // 6c — still on the old path: the relay took a whole attachment and its answer was lost.
        //      Bob is away, so no receipt of his settles it first. A link that does not answer
        //      is given up, and the login that follows sends the attachment again.
        let quiet = bytes(200 * 1024, 21)
        bob.svc.goOffline()
        try await ask("lose-pong", ["proxy": 0, "main": true])
        let before = try await faults()
        await alice.svc.sendFile(data: quiet, filename: "quiet.bin", mime: "application/octet-stream", to: contact(alice, bob))
        var gone = before
        let goneBy = Date().addingTimeInterval(20)
        while gone < before + 1, Date() < goneBy {
            try await Task.sleep(for: .milliseconds(200))
            gone = try await faults()
        }
        XCTAssertEqual(gone, before + 1, "the relay's answer is lost")
        try await Task.sleep(for: .seconds(9))        // four seconds with no answer, a new login, and time to send it again
        XCTAssertEqual(file(alice, bob, "quiet.bin")?.status, .sent, "it went again and the relay took it; bob is still away")
        try await bob.svc.goOnline()
        await fileArrivesOnce(alice, bob, "quiet.bin", quiet, 30, "bob comes back to two copies of it, and has one")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: file(alice, bob, "quiet.bin")!.id)?.t, "send")
        await until(10, "both are there, and still told it is off") { self.up(alice, .notOffered) && self.up(bob, .notOffered) }
        try? await Task.sleep(for: .seconds(1))       // the receipts of that exchange leave before the relay goes again
        try await ask("relay", ["sealed": true])
        await until(40, "everyone is back, with sealed sender") { all.allSatisfy { self.up($0, .on) } }
        XCTAssertEqual(alice.svc.sealedLane(for: bob.hash), .key, "both still hold each other's key: no message the old way is needed")
        XCTAssertEqual(bob.svc.sealedLane(for: alice.hash), .key)
        await alice.svc.sendText("on again", to: contact(alice, bob))
        await arrivesOnce(alice, bob, "on again", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "on again")!.id)?.t, "deliver")

        // 7 — carol sat through all of it
        await alice.svc.sendText("carol, after everything", to: contact(alice, carol))
        await arrivesOnce(alice, carol, "carol, after everything", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, carol, "carol, after everything")!.id)?.t, "deliver")

        // 8 — the private connection dies with a message on it, and no other can be had
        try await ask("refuse-anon", ["proxy": 0, "on": true])
        try await ask("lose-deliver", ["proxy": 0])
        await alice.svc.sendText("lost, with no way back in private", to: contact(alice, bob))
        await until(10, "it fails: the try the app makes by itself finds nothing to open") {
            self.sent(alice, bob, "lost, with no way back in private")?.status == .failed
        }
        XCTAssertEqual(alice.svc.sealedStatus, .on, "which decides nothing yet")
        await alice.svc.sendText("the one after it", to: contact(alice, bob))       // settles it: two real attempts, then the old way for both
        await arrivesOnce(alice, bob, "lost, with no way back in private", 20)
        await arrivesOnce(alice, bob, "the one after it", 20)
        XCTAssertEqual(alice.svc.sealedStatus, .noPrivateConnection, "and the status says why")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "lost, with no way back in private")!.id)?.t, "send")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "the one after it")!.id)?.t, "send")
        try? await Task.sleep(for: .seconds(1))       // her receipts for what bob answers leave while this still holds
        try await ask("refuse-anon", ["proxy": 0, "on": false])
        alice.svc.goOffline()                          // the next login tries again
        try await alice.svc.goOnline()
        await until(10, "sealed sender is back with the next login") { self.up(alice, .on) }
        await alice.svc.sendText("in private again", to: contact(alice, bob))
        await arrivesOnce(alice, bob, "in private again", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "in private again")!.id)?.t, "deliver")

        // 9 — a path that kills every connection a message goes out on. The app opens another by
        //     itself, twice; after that the message waits, because this must not become a loop.
        try await ask("dead-path", ["proxy": 0, "on": true])
        let base = try await faults()
        await alice.svc.sendText("on a path that kills its connection", to: contact(alice, bob))
        var lost = base
        let deadline = Date().addingTimeInterval(30)
        while lost < base + 3, Date() < deadline {
            try await Task.sleep(for: .milliseconds(200))
            lost = try await faults()
        }
        XCTAssertEqual(lost, base + 3, "lost three times: as it was written, and on the two connections the app opened by itself")
        try await Task.sleep(for: .seconds(5))        // a loop would have gone round many times by now
        lost = try await faults()
        XCTAssertEqual(lost, base + 3, "and not once more")
        XCTAssertEqual(sent(alice, bob, "on a path that kills its connection")?.status, .failed, "it waits")
        XCTAssertEqual(copies(bob, alice, "on a path that kills its connection"), 0)
        XCTAssertEqual(alice.svc.sealedStatus, .on, "and none of that says there is no private connection")
        try await ask("dead-path", ["proxy": 0, "on": false])
        await alice.svc.sendText("the path is back", to: contact(alice, bob))       // the next message opens a connection, and what waited goes with it
        await arrivesOnce(alice, bob, "on a path that kills its connection", 15)
        await arrivesOnce(alice, bob, "the path is back", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "on a path that kills its connection")!.id)?.t, "deliver")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "the path is back")!.id)?.t, "deliver")
        // a verdict came back in between, so the count starts again: the next loss is retried by itself
        try await ask("lose-deliver", ["proxy": 0])
        await alice.svc.sendText("lost once more", to: contact(alice, bob))
        await arrivesOnce(alice, bob, "lost once more", 15)
        XCTAssertEqual(alice.svc.frameCapture.frame(for: sent(alice, bob, "lost once more")!.id)?.t, "deliver")

        // 10 — the relay took a whole attachment, and its answer to that was lost on the way back.
        //      Bob is away, so no receipt of his settles it first (with him there, his receipt
        //      does, and nothing is sent twice): the app sends it again.
        let unanswered = bytes(300 * 1024, 13)
        bob.svc.goOffline()
        try await ask("lose-pong", ["proxy": 0])
        let asked = try await faults()
        await alice.svc.sendFile(data: unanswered, filename: "unanswered.bin", mime: "application/octet-stream", to: contact(alice, bob))
        var heard = asked
        let lostBy = Date().addingTimeInterval(20)
        while heard < asked + 1, Date() < lostBy {
            try await Task.sleep(for: .milliseconds(200))
            heard = try await faults()
        }
        XCTAssertEqual(heard, asked + 1, "the relay's answer is lost")
        try await Task.sleep(for: .seconds(8))        // four seconds with no answer, then time to send it again
        XCTAssertEqual(file(alice, bob, "unanswered.bin")?.status, .sent, "it went again and the relay took it; bob is still away")
        try await bob.svc.goOnline()
        await fileArrivesOnce(alice, bob, "unanswered.bin", unanswered, 30, "bob comes back to two copies of it, and has one")
        XCTAssertEqual(alice.svc.frameCapture.frame(for: file(alice, bob, "unanswered.bin")!.id)?.t, "deliver")

        // 11 — the relay refuses a piece of an attachment, as it does when the recipient's queue is full
        let refused = bytes(600 * 1024, 17)
        try await ask("refuse-chunk", ["proxy": 0, "chunk": 2])
        await alice.svc.sendFile(data: refused, filename: "refused.bin", mime: "application/octet-stream", to: contact(alice, bob))
        await until(15, "the row fails: it is not left as sent") { self.file(alice, bob, "refused.bin")?.status == .failed }
        XCTAssertEqual(alice.svc.lastError, "queue_full", "and it says what the relay said")
        try await Task.sleep(for: .seconds(3))        // a refusal is not a loss: nothing sends it again by itself
        XCTAssertEqual(file(alice, bob, "refused.bin")?.status, .failed)
        XCTAssertNil(file(bob, alice, "refused.bin"))
        await alice.svc.retryBlob(messageId: file(alice, bob, "refused.bin")!.id, thread: bob.hash)       // the [RETRY] tap
        await fileArrivesOnce(alice, bob, "refused.bin", refused, 30, "asked to, it goes again and arrives")

        // let the last receipts leave before the sockets close
        try? await Task.sleep(for: .seconds(1))
        try publish()
    }
}
