import XCTest
@testable import PyroChat

/// The P4 exit criterion: buy (dev mode) → redeem code via RFC 9474 blind
/// signing → the token-enforcing relay admits the connection — and rejects
/// missing tokens and cross-device token sharing.
///
/// Requires: billing on :8088 (devMode) and a relay on :7445 (token.mode=rsa-pss).
final class TokenGateTests: XCTestCase {
    static let billingURL = URL(string: "http://127.0.0.1:8088")!
    static let relayURL = URL(string: "ws://127.0.0.1:7445/ws")!

    @MainActor
    final class GatedUser {
        let store: SecureStore
        let crypto: CryptoEngine
        let client: RelayClient
        let username: String
        var hash: String { store.blob.usernameHash }
        private var backlog: [Wire.In] = []
        private var iterator: AsyncStream<RelayClient.Event>.Iterator

        init() {
            username = "gate" + UUID().uuidString.prefix(8).lowercased()
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("pyro-gate-\(UUID().uuidString)")
            try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            store = SecureStore(creatingWithPassword: "gate-test-pass",
                                at: dir.appendingPathComponent("store"))
            crypto = CryptoEngine(store: store)
            client = RelayClient(url: TokenGateTests.relayURL)
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
            throw NSError(domain: "test", code: 1)
        }

        func register() async throws {
            let bundle = try crypto.generateIdentity(username: username)
            try await client.send(Wire.Out(
                t: "register", user: hash,
                auth_pub: bundle.authPub.base64EncodedString(),
                reg_id: bundle.regId,
                identity_key: bundle.identityKey.base64EncodedString(),
                signed_prekey: bundle.signedPreKey,
                kyber_prekey: bundle.kyberPreKey,
                one_time_prekeys: bundle.oneTimePreKeys))
            _ = try await next { $0.t == "registered" }
        }

        /// Attempt login; returns the final frame ("ready" or "error").
        func login(token: AccessToken?) async throws -> Wire.In {
            try await client.send(Wire.Out(t: "login", user: hash))
            let ch = try await next { $0.t == "challenge" }
            let sig = try crypto.signChallenge(Data(base64Encoded: ch.nonce!)!)
            try await client.send(Wire.Out(
                t: "auth", sig: sig.base64EncodedString(),
                token: token?.token.base64EncodedString(),
                token_sig: token?.signature.base64EncodedString()))
            return try await next { $0.t == "ready" || $0.t == "error" }
        }
    }

    @MainActor
    func testRedeemedTokenOpensTheGate_missingAndSharedTokensDoNot() async throws {
        // 1. "buy": dev-mode mint (stands in for Stripe/BTCPay webhook)
        var mintReq = URLRequest(url: Self.billingURL.appendingPathComponent("api/checkout/dev"))
        mintReq.httpMethod = "POST"
        let (mintData, _) = try await URLSession.shared.data(for: mintReq)
        let code = try JSONDecoder().decode([String: String].self, from: mintData)["code"]!
        XCTAssertTrue(code.hasPrefix("PYRO-"))

        // 2. redeem: blind → sign → unblind → verified locally
        let tokens = try await RedeemService.redeem(code: code, billingURL: Self.billingURL, session: RelayClient.makeSession(socksProxy: nil))
        XCTAssertEqual(tokens.count, 31)
        let today = Int64(Date().timeIntervalSince1970 / 86_400)
        XCTAssertEqual(tokens.first?.epochDay, today)
        let todayToken = tokens.first { $0.epochDay == today }!

        // 3. the same code cannot be redeemed twice
        do {
            _ = try await RedeemService.redeem(code: code, billingURL: Self.billingURL, session: RelayClient.makeSession(socksProxy: nil))
            XCTFail("second redemption must be rejected")
        } catch {}

        // 4. no token -> relay refuses
        let anon = GatedUser()
        try await anon.register()
        let refused = try await anon.login(token: nil)
        XCTAssertEqual(refused.t, "error")
        XCTAssertEqual(refused.code, "bad_token")

        // 5. valid token -> relay admits
        let paid = GatedUser()
        try await paid.register()
        let admitted = try await paid.login(token: todayToken)
        XCTAssertEqual(admitted.t, "ready", "paid token must open the gate")

        // 6. the SAME token presented by a different identity -> token sharing, refused
        let freeloader = GatedUser()
        try await freeloader.register()
        let caught = try await freeloader.login(token: todayToken)
        XCTAssertEqual(caught.t, "error")
        XCTAssertEqual(caught.code, "token_spent", "cross-device token sharing must be refused")

        // 7. a stale-day token -> refused
        let stale = tokens.first { $0.epochDay == today + 5 }!
        let expired = GatedUser()
        try await expired.register()
        let refused2 = try await expired.login(token: stale)
        XCTAssertEqual(refused2.t, "error")
        XCTAssertEqual(refused2.code, "bad_token", "wrong-epoch token must be refused")
    }
}
