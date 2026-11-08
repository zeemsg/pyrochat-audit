import XCTest
import LibSignalClient
@testable import PyroChat

/// TRUST TRIO: safety-number fingerprints (pure crypto), key-change bridge
/// (SignalStore unit), and session auto-heal (two live ChatServices against
/// the relay on 127.0.0.1:7444).
@MainActor
final class TrustTests: XCTestCase {

    private func makeStore(_ tag: String) -> SecureStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-trust-\(tag)-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return SecureStore(creatingWithPassword: "trust-pass-123",
                           at: dir.appendingPathComponent("store"))
    }

    private func makeService() -> (ChatService, String) {
        let username = "tr" + UUID().uuidString.prefix(8).lowercased()
        return (ChatService(store: makeStore("svc")), username)
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

    // MARK: 1 — fingerprint determinism & symmetry (no relay)

    func testFingerprintDeterministicAndSymmetric() throws {
        let aliceKey = IdentityKeyPair.generate()
        let bobKey = IdentityKeyPair.generate()
        let aliceId = Data(sha256Hex("alice").utf8)
        let bobId = Data(sha256Hex("bob").utf8)
        let gen = NumericFingerprintGenerator(iterations: CryptoEngine.fingerprintIterations)

        let a1 = try gen.create(version: CryptoEngine.fingerprintVersion,
                                localIdentifier: aliceId, localKey: aliceKey.publicKey,
                                remoteIdentifier: bobId, remoteKey: bobKey.publicKey)
        let a2 = try gen.create(version: CryptoEngine.fingerprintVersion,
                                localIdentifier: aliceId, localKey: aliceKey.publicKey,
                                remoteIdentifier: bobId, remoteKey: bobKey.publicKey)
        XCTAssertEqual(a1.displayable.formatted, a2.displayable.formatted)

        // symmetry: bob computes with roles swapped, sees the SAME digits
        let b = try gen.create(version: CryptoEngine.fingerprintVersion,
                               localIdentifier: bobId, localKey: bobKey.publicKey,
                               remoteIdentifier: aliceId, remoteKey: aliceKey.publicKey)
        XCTAssertEqual(a1.displayable.formatted, b.displayable.formatted,
                       "both devices must render identical 60 digits")
        XCTAssertEqual(a1.displayable.formatted.count, 60)
        XCTAssertTrue(a1.displayable.formatted.allSatisfy(\.isNumber))

        // scannable halves cross-compare (differ per side, match crossed)
        XCTAssertTrue(try a1.scannable.compare(againstEncoding: b.scannable.encoding))
        XCTAssertTrue(try b.scannable.compare(againstEncoding: a1.scannable.encoding))
        XCTAssertNotEqual(a1.scannable.encoding, b.scannable.encoding)

        // a substituted key must change the number
        let mallory = IdentityKeyPair.generate()
        let forged = try gen.create(version: CryptoEngine.fingerprintVersion,
                                    localIdentifier: aliceId, localKey: mallory.publicKey,
                                    remoteIdentifier: bobId, remoteKey: bobKey.publicKey)
        XCTAssertNotEqual(forged.displayable.formatted, a1.displayable.formatted)
    }

    // MARK: 2 — key-change bridge (SignalStore unit, no relay)

    func testSaveIdentityFiresReplacementBridgeExactlyOnce() throws {
        let signal = SignalStore(store: makeStore("bridge"))
        var fired: [String] = []
        signal.onIdentityReplaced = { fired.append($0) }
        let peer = sha256Hex("peer")
        let addr = try ProtocolAddress(name: peer, deviceId: 1)
        let ctx = SignalStore.Context()
        let k1 = IdentityKeyPair.generate().identityKey
        let k2 = IdentityKeyPair.generate().identityKey

        guard case .newOrUnchanged = try signal.saveIdentity(k1, for: addr, context: ctx)
        else { return XCTFail("first sight must be newOrUnchanged") }
        XCTAssertTrue(fired.isEmpty, "TOFU first sight is not a change")
        guard case .newOrUnchanged = try signal.saveIdentity(k1, for: addr, context: ctx)
        else { return XCTFail("re-save of same key must be newOrUnchanged") }
        XCTAssertTrue(fired.isEmpty)
        guard case .replacedExisting = try signal.saveIdentity(k2, for: addr, context: ctx)
        else { return XCTFail("new key must be replacedExisting") }
        XCTAssertEqual(fired, [peer], "replacement fires the bridge exactly once")

        // accept-and-warn: trust stays permissive, new key is recorded
        XCTAssertTrue(try signal.isTrustedIdentity(k1, for: addr,
                                                   direction: .sending, context: ctx))
        XCTAssertEqual(try signal.identity(for: addr, context: ctx)?.serialize(),
                       k2.serialize())
    }

    // MARK: 3 — safety numbers agree across a live channel

    func testSafetyNumbersMatchAcrossLiveChannel() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        await alice.sendText("ping", to: alice.contacts.first!)
        let ok = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        XCTAssertTrue(ok)

        let aliceSN = alice.safetyNumber(with: alice.contacts.first!)
        let bobContact = bob.contacts.first { $0.hash == alice.myHash }!
        let bobSN = bob.safetyNumber(with: bobContact)
        XCTAssertNotNil(aliceSN); XCTAssertNotNil(bobSN)
        XCTAssertEqual(aliceSN!.digits, bobSN!.digits,
                       "both real clients must display the same safety number")

        bob.setVerified(true, for: alice.myHash)
        XCTAssertEqual(bob.contacts.first { $0.hash == alice.myHash }?.verified, true)

        alice.goOffline(); bob.goOffline()
    }

    // MARK: 4 — auto-heal integration (live relay)

    func testSessionAutoHealAfterPeerLosesSession() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)

        try await alice.addContact(username: bobName)
        await alice.sendText("hello bob", to: alice.contacts.first!)
        var ok = await waitUntil { bob.messages[alice.myHash]?.contains { $0.text == "hello bob" } == true }
        XCTAssertTrue(ok)
        let bobContact = bob.contacts.first { $0.hash == alice.myHash }!
        await bob.sendText("hello alice", to: bobContact)
        ok = await waitUntil { alice.messages[bob.myHash]?.contains { $0.text == "hello alice" } == true }
        XCTAssertTrue(ok)

        // surgically destroy bob's session record (reinstall-shaped damage)
        bob.store.update { $0.sessions[alice.myHash] = nil }

        // alice's session is intact -> her next send is a whisper bob can't decrypt
        await alice.sendText("you will never read this", to: alice.contacts.first!)

        ok = await waitUntil(15) {
            bob.messages[alice.myHash]?.contains {
                $0.kind == .system && $0.text.contains("re-keyed")
            } == true
        }
        XCTAssertTrue(ok, "bob must announce the re-key")
        XCTAssertFalse(bob.messages[alice.myHash]!.contains { $0.text.contains("never read this") },
                       "the undecryptable message is lost — and stays lost")

        ok = await waitUntil(15) {
            alice.messages[bob.myHash]?.contains {
                $0.kind == .system && $0.text.contains("re-keyed")
            } == true
        }
        XCTAssertTrue(ok, "alice must be told the channel was re-keyed")

        // channel works again in BOTH directions
        await alice.sendText("take two", to: alice.contacts.first!)
        ok = await waitUntil(10) { bob.messages[alice.myHash]?.contains { $0.text == "take two" } == true }
        XCTAssertTrue(ok, "alice -> bob flows after heal")
        await bob.sendText("read you loud and clear", to: bobContact)
        ok = await waitUntil(10) { alice.messages[bob.myHash]?.contains { $0.text == "read you loud and clear" } == true }
        XCTAssertTrue(ok, "bob -> alice flows after heal")

        // noise/loop guard: exactly one re-key row per side
        try? await Task.sleep(for: .seconds(2))
        XCTAssertEqual(bob.messages[alice.myHash]!.filter { $0.kind == .system && $0.text.contains("re-keyed") }.count, 1)
        XCTAssertEqual(alice.messages[bob.myHash]!.filter { $0.kind == .system && $0.text.contains("re-keyed") }.count, 1)

        alice.goOffline(); bob.goOffline()
    }

    // MARK: 5 — reinstall shape: heal + key change together, deduped

    func testKeyChangeDuringHealWarnsOnceAndClearsVerified() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        await alice.sendText("establish", to: alice.contacts.first!)
        var ok = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        XCTAssertTrue(ok)

        // bob verified alice, then bob's records go stale
        bob.setVerified(true, for: alice.myHash)
        let wrongKey = IdentityKeyPair.generate().identityKey.serialize()
        bob.store.update {
            $0.knownIdentities[alice.myHash] = wrongKey
            $0.sessions[alice.myHash] = nil
        }

        await alice.sendText("trigger", to: alice.contacts.first!)

        ok = await waitUntil(15) {
            bob.messages[alice.myHash]?.contains {
                $0.kind == .system && $0.text.contains("identity key changed")
            } == true
        }
        XCTAssertTrue(ok, "identity warning row must appear")
        XCTAssertEqual(bob.contacts.first { $0.hash == alice.myHash }?.verified, false,
                       "key change must clear the verified flag")

        _ = await waitUntil(10) {
            bob.messages[alice.myHash]?.contains { $0.kind == .system && $0.text.contains("re-keyed") } == true
        }
        try? await Task.sleep(for: .seconds(2))
        let rows = bob.messages[alice.myHash]!.filter { $0.kind == .system }
        XCTAssertEqual(rows.filter { $0.text.contains("identity key changed") }.count, 1)
        XCTAssertEqual(rows.filter { $0.text.contains("re-keyed") }.count, 1)

        // accept-and-warn: flow continues under the (correct) key
        await alice.sendText("still flowing", to: alice.contacts.first!)
        ok = await waitUntil(10) { bob.messages[alice.myHash]?.contains { $0.text == "still flowing" } == true }
        XCTAssertTrue(ok, "messages must keep flowing after a key change")

        alice.goOffline(); bob.goOffline()
    }
}
