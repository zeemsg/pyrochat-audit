import XCTest
@testable import PyroChat

/// Full-stack integration: two users, real libsignal PQXDH + Double Ratchet,
/// real relay on 127.0.0.1:7444 (start `relay` with allow-all tokens first).
/// Verifies the complete pipeline the UI drives, plus that everything
/// crossing the wire is ciphertext.
final class RelayIntegrationTests: XCTestCase {
    static let relayURL = URL(string: "ws://127.0.0.1:7444/ws")!

    /// One in-process client: its own encrypted store + crypto + socket.
    @MainActor
    final class TestUser {
        let username: String
        let store: SecureStore
        let crypto: CryptoEngine
        let client: RelayClient
        var hash: String { store.blob.usernameHash }
        private var backlog: [Wire.In] = []
        private var iterator: AsyncStream<RelayClient.Event>.Iterator

        init(_ prefix: String) {
            username = prefix + UUID().uuidString.prefix(8).lowercased()
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("pyro-test-\(UUID().uuidString)")
            try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            store = SecureStore(creatingWithPassword: "test-password-123",
                                at: dir.appendingPathComponent("store"))
            crypto = CryptoEngine(store: store)
            client = RelayClient(url: RelayIntegrationTests.relayURL)
            iterator = client.events.makeAsyncIterator()
            client.connect()
        }

        func next(_ pred: @escaping (Wire.In) -> Bool) async throws -> Wire.In {
            if let i = backlog.firstIndex(where: pred) { return backlog.remove(at: i) }
            let deadline = Date().addingTimeInterval(8)
            var it = iterator
            defer { iterator = it }
            while Date() < deadline {
                guard let event = await it.next() else { break }
                if case .frame(let f) = event {
                    if pred(f) { return f }
                    backlog.append(f)
                }
            }
            throw NSError(domain: "test", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "timeout waiting for frame"])
        }

        func registerAndAuth() async throws {
            let bundle = try crypto.generateIdentity(username: username)
            try await client.send(Wire.Out(
                t: "register", user: hash,
                auth_pub: bundle.authPub.base64EncodedString(),
                reg_id: bundle.regId,
                identity_key: bundle.identityKey.base64EncodedString(),
                signed_prekey: bundle.signedPreKey,
                kyber_prekey: bundle.kyberPreKey,
                one_time_prekeys: bundle.oneTimePreKeys))
            let reg = try await next { $0.t == "registered" || $0.t == "error" }
            XCTAssertEqual(reg.t, "registered")

            try await client.send(Wire.Out(t: "login", user: hash))
            let challenge = try await next { $0.t == "challenge" }
            let sig = try crypto.signChallenge(Data(base64Encoded: challenge.nonce!)!)
            try await client.send(Wire.Out(t: "auth", sig: sig.base64EncodedString()))
            let ready = try await next { $0.t == "ready" || $0.t == "error" }
            XCTAssertEqual(ready.t, "ready")
        }
    }

    @MainActor
    func testFullE2EEncryptedExchangeThroughRelay() async throws {
        let alice = TestUser("alice-")
        let bob = TestUser("bob-")
        try await alice.registerAndAuth()
        try await bob.registerAndAuth()

        // ---- alice bootstraps a PQXDH session from bob's bundle ----
        try await alice.client.send(Wire.Out(t: "prekey_fetch", user: bob.hash))
        let bundle = try await alice.next { $0.t == "prekey_bundle" }
        XCTAssertNotNil(bundle.kyber_prekey, "bundle must carry a Kyber prekey (PQXDH)")
        try alice.crypto.processBundle(bundle, for: bob.hash)

        // ---- alice -> bob ----
        let secret = "hello bob \u{1F525} the candle burns"
        let body = try alice.crypto.encrypt(
            Wire.Payload(kind: "text", text: secret), for: bob.hash)

        // ciphertext hygiene: no plaintext fragment anywhere in the wire body
        let envelope = try JSONDecoder().decode(Wire.Envelope.self,
                                                from: Data(base64Encoded: body)!)
        XCTAssertEqual(envelope.type, 3, "first message must be a preKey message")
        XCTAssertFalse(String(decoding: envelope.data, as: UTF8.self).contains("hello bob"))
        XCTAssertFalse(String(decoding: envelope.data, as: UTF8.self).contains("candle"))

        let msgId = UUID().uuidString.lowercased()
        try await alice.client.send(Wire.Out(t: "send", id: msgId, to: bob.hash,
                                             ttl: 3600, kind: "msg", body: body))
        _ = try await alice.next { $0.t == "sent" && $0.id == msgId }

        let recv = try await bob.next { $0.t == "recv" && $0.id == msgId }
        XCTAssertEqual(recv.from, alice.hash)
        let decrypted = try bob.crypto.decrypt(recv.body!, from: alice.hash)
        XCTAssertEqual(decrypted.text, secret, "plaintext must survive the round trip")
        try await bob.client.send(Wire.Out(t: "ack", id: msgId))

        // ---- bob -> alice: ratchet now runs both ways without a bundle ----
        let reply = "confirmed. ratchet is turning."
        let replyBody = try bob.crypto.encrypt(
            Wire.Payload(kind: "text", text: reply), for: alice.hash)
        let replyEnvelope = try JSONDecoder().decode(Wire.Envelope.self,
                                                     from: Data(base64Encoded: replyBody)!)
        XCTAssertEqual(replyEnvelope.type, 2, "established session must use whisper messages")

        let replyId = UUID().uuidString.lowercased()
        try await bob.client.send(Wire.Out(t: "send", id: replyId, to: alice.hash,
                                           ttl: 3600, kind: "msg", body: replyBody))
        let recv2 = try await alice.next { $0.t == "recv" && $0.id == replyId }
        let decrypted2 = try alice.crypto.decrypt(recv2.body!, from: bob.hash)
        XCTAssertEqual(decrypted2.text, reply)
        try await alice.client.send(Wire.Out(t: "ack", id: replyId))

        // ---- forward secrecy sanity: same plaintext -> different ciphertext ----
        let c1 = try alice.crypto.encrypt(Wire.Payload(kind: "text", text: "same"), for: bob.hash)
        let c2 = try alice.crypto.encrypt(Wire.Payload(kind: "text", text: "same"), for: bob.hash)
        XCTAssertNotEqual(c1, c2, "ratchet must yield a fresh key per message")
    }

    @MainActor
    func testWrongPasswordCannotOpenStore() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("store")
        let store = SecureStore(creatingWithPassword: "correct horse", at: url)
        store.update { $0.usernameDisplay = "zee" }
        store.flush()

        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "battery staple", at: url))
        let reopened = try SecureStore(unlockingWithPassword: "correct horse", at: url)
        XCTAssertEqual(reopened.blob.usernameDisplay, "zee")

        // encrypted at rest: no plaintext in the file. Searched as BYTES, and
        // for fragments long enough that 12 MiB of slot noise (docs/DECOY-
        // ACCOUNT.md §1) cannot throw one up by chance — a bare "zee" turns up
        // in random bytes about half the time at this file size.
        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data(#""usernameDisplay":"zee""#.utf8)))
        XCTAssertNil(raw.range(of: Data("usernameDisplay".utf8)))
    }
}
