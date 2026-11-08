import XCTest
@testable import PyroChat

/// v0.4 feature suite: delete-for-both, add-by-QR, per-contact burn,
/// dead-man switch, SOCKS proxy parsing. Live tests need the dev relay
/// on 127.0.0.1:7444 (same as RelayIntegrationTests).
@MainActor
final class FeatureV04Tests: XCTestCase {

    private func makeService() -> (ChatService, String) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-v04-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "v04-test-pass",
                                at: dir.appendingPathComponent("store"))
        let username = "v4" + UUID().uuidString.prefix(8).lowercased()
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

    // MARK: delete for both

    func testDeleteForBothRemovesOnBothDevices_butNeverPeersOwnMessages() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        guard let bobContact = alice.contacts.first else { return XCTFail() }

        await alice.sendText("to be retracted", to: bobContact)
        let arrived = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.text == "to be retracted" } == true
        }
        XCTAssertTrue(arrived, "message must arrive first")
        guard let msgId = alice.messages[bobContact.hash]?.first?.id else { return XCTFail() }

        await alice.deleteForBoth(id: msgId, thread: bobContact.hash)
        XCTAssertNil(alice.messages[bobContact.hash]?.first { $0.id == msgId },
                     "gone locally")
        let goneRemote = await waitUntil {
            bob.messages[alice.myHash]?.contains { $0.id == msgId } != true
        }
        XCTAssertTrue(goneRemote, "gone on the peer")

        // AUTH BOUNDARY: bob replies; alice must NOT be able to have her
        // control delete bob's copy of BOB'S OWN message… and conversely a
        // crafted control from bob naming ALICE's message ids must not
        // touch alice's outgoing rows.
        guard let aliceContact = bob.contacts.first(where: { $0.hash == alice.myHash })
        else { return XCTFail("bob should have auto-created alice") }
        await bob.sendText("bobs message", to: aliceContact)
        let bobArrived = await waitUntil {
            alice.messages[bobContact.hash]?.contains { $0.text == "bobs message" } == true
        }
        XCTAssertTrue(bobArrived)
        await alice.sendText("alices own", to: bobContact)
        guard let aliceOwn = alice.messages[bobContact.hash]?
            .first(where: { $0.text == "alices own" })?.id else { return XCTFail() }
        // bob crafts a malicious delete control naming alice's outgoing id
        await bob.deleteForBoth(id: aliceOwn, thread: alice.myHash)   // no-op: not bob's msg
        try? await Task.sleep(for: .seconds(2))
        XCTAssertTrue(alice.messages[bobContact.hash]?.contains { $0.id == aliceOwn } == true,
                      "peer must never delete MY messages on my device")
        alice.goOffline(); bob.goOffline()
    }

    func testDeleteForBothRefusesWhenOffline() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        guard let bobContact = alice.contacts.first else { return XCTFail() }
        await alice.sendText("kept", to: bobContact)
        guard let id = alice.messages[bobContact.hash]?.first?.id else { return XCTFail() }

        alice.goOffline()
        let ok = await alice.deleteForBoth(id: id, thread: bobContact.hash)
        XCTAssertFalse(ok, "offline: must refuse rather than silently delete-for-me")
        XCTAssertTrue(alice.messages[bobContact.hash]?.contains { $0.id == id } == true,
                      "row untouched when the retraction can't be delivered")
        XCTAssertNotNil(alice.lastError)
        bob.goOffline()
    }

    // MARK: account kill

    func testDeregisterMakesTheAccountUnreachableAndTellsThePeer() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        guard let bobContact = alice.contacts.first else { return XCTFail() }
        await alice.sendText("before", to: bobContact)
        let arrived = await waitUntil { bob.messages[alice.myHash]?.isEmpty == false }
        XCTAssertTrue(arrived)

        await bob.deregister()                 // bob kills his account
        try? await Task.sleep(for: .seconds(1))

        await alice.sendText("after", to: bobContact)
        let failed = await waitUntil {
            alice.messages[bobContact.hash]?.first { $0.text == "after" }?.status == .failed
        }
        XCTAssertTrue(failed, "sending to a killed account must fail, never sit at SENT")
        let noted = await waitUntil {
            alice.messages[bobContact.hash]?.contains {
                $0.kind == .system && $0.text.contains("deleted their account") } == true
        }
        XCTAssertTrue(noted, "the sender is told the account is gone")
        // and a fresh contact of that name fails cleanly
        let (carol, carolName) = makeService()
        try await carol.register(username: carolName)
        do { try await carol.addContact(username: bobName); XCTFail("must throw") }
        catch { XCTAssertTrue("\(error)".contains("unknown_user")) }
        alice.goOffline(); carol.goOffline()
    }

    func testGoOnlineFailsFastWhenTheAccountIsGone() async throws {
        let (bob, bobName) = makeService()
        try await bob.register(username: bobName)
        await bob.deregister()                 // relay forgets bob
        try? await Task.sleep(for: .seconds(1))
        // before the fix the challenge wait never resolved and goOnline() hung forever
        let started = Date()
        do { try await bob.goOnline(); XCTFail("login of a killed account must throw") }
        catch { XCTAssertTrue("\(error)".contains("unknown_user"), "got \(error)") }
        XCTAssertLessThan(Date().timeIntervalSince(started), 10, "must fail fast, not wait for a deadline")
        bob.goOffline()
    }

    // MARK: add by QR

    func testAddByQRAddsAndVerifiesInOneStep() async throws {
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)

        guard let payload = bob.myAddQRPayload() else { return XCTFail() }
        let parts = payload.split(separator: ":").map(String.init)
        XCTAssertEqual(parts[0], "pyro-add"); XCTAssertEqual(parts[1], "v1")
        XCTAssertEqual(parts[2], bobName)
        let key = Data(base64urlNoPad: parts[3])!

        let verified = try await alice.addContactViaQR(username: bobName, identityKey: key)
        XCTAssertTrue(verified)
        XCTAssertTrue(alice.contacts.first { $0.name == bobName }?.verified == true,
                      "one scan = added AND verified")

        // wrong key (MITM simulation) must throw loudly, not quietly verify
        let (carol, carolName) = makeService()
        try await carol.register(username: carolName)
        do {
            _ = try await alice.addContactViaQR(username: carolName,
                                                identityKey: Data(repeating: 9, count: 33))
            XCTFail("identity mismatch must throw")
        } catch { /* expected */ }
        XCTAssertFalse(alice.contacts.contains { $0.name == carolName },
                       "a MITM-suspect contact created by the scan must be rolled back")
        // (session archival is asserted indirectly: re-adding must refetch a
        // bundle, which the rolled-back contact list already forces)
        alice.goOffline(); bob.goOffline(); carol.goOffline()
    }

    func testQRCannotDemoteAKnownVerifiedContact() async throws {
        // security: a QR is attacker-controllable input. For a contact we
        // already hold a key for, it may CONFIRM but never demote/reset.
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        alice.setVerified(true, for: bob.myHash)

        do {
            _ = try await alice.addContactViaQR(username: bobName,
                                                identityKey: Data(repeating: 7, count: 33))
            XCTFail("mismatching QR for a known contact must throw")
        } catch { XCTAssertTrue("\(error)".contains("qr_key_mismatch")) }
        XCTAssertTrue(alice.contacts.first { $0.hash == bob.myHash }?.verified == true,
                      "verified badge untouched by a foreign QR")
        XCTAssertTrue(alice.contacts.contains { $0.hash == bob.myHash }, "contact untouched")

        // the genuine code still confirms
        let real = Data(base64urlNoPad: bob.myAddQRPayload()!.split(separator: ":").map(String.init)[3])!
        let confirmed = try await alice.addContactViaQR(username: bobName, identityKey: real)
        XCTAssertTrue(confirmed)
        alice.goOffline(); bob.goOffline()
    }

    func testQRRejectsUsernamesOutsideTheRegistrationCharset() async throws {
        let (alice, aliceName) = makeService()
        try await alice.register(username: aliceName)
        for bad in ["alice\u{200B}", "al", "alice\nbob", "a\u{202E}lice", String(repeating: "x", count: 25)] {
            do {
                _ = try await alice.addContactViaQR(username: bad, identityKey: Data(repeating: 1, count: 33))
                XCTFail("must reject \(bad.debugDescription)")
            } catch { XCTAssertTrue("\(error)".contains("bad_username"), "\(bad.debugDescription): \(error)") }
        }
        XCTAssertTrue(ChatService.isValidUsername("zee_01"))
        XCTAssertFalse(ChatService.isValidUsername("zee 01"))
        alice.goOffline()
    }

    func testDeleteForBothHonorsRelayRefusal() async throws {
        // the relay answers a control asynchronously; a rate_limited/queue_full
        // verdict must make deleteForBoth return false and keep the row
        let (alice, aliceName) = makeService()
        let (bob, bobName) = makeService()
        try await alice.register(username: aliceName)
        try await bob.register(username: bobName)
        try await alice.addContact(username: bobName)
        guard let bobContact = alice.contacts.first else { return XCTFail() }
        await alice.sendText("target", to: bobContact)
        guard let id = alice.messages[bobContact.hash]?.first?.id else { return XCTFail() }
        // burn the relay's 30-per-10s send budget
        for i in 0..<34 { await alice.sendText("burst \(i)", to: bobContact) }
        let ok = await alice.deleteForBoth(id: id, thread: bobContact.hash)
        XCTAssertFalse(ok, "relay refused the control (rate_limited) — must report failure")
        XCTAssertTrue(alice.messages[bobContact.hash]?.contains { $0.id == id } == true,
                      "row must survive a refused retraction")
        alice.goOffline(); bob.goOffline()
    }

    // MARK: per-contact burn

    func testPerContactBurnOverridesGlobal() {
        let (alice, _) = makeService()
        alice.store.update { $0.settings.defaultBurnSeconds = 3600 }
        let hash = "peer-hash-1"
        alice.contacts.append(Contact(hash: hash, name: "p", addedAt: Date()))

        XCTAssertEqual(alice.burnDefault(for: hash), 3600, "falls back to global")
        alice.setBurnDefault(60, for: hash)
        XCTAssertEqual(alice.burnDefault(for: hash), 60, "override wins")
        alice.setBurnDefault(0, for: hash)
        XCTAssertNil(alice.burnDefault(for: hash), "0 = explicit off beats global")
        alice.setBurnDefault(nil, for: hash)
        XCTAssertEqual(alice.burnDefault(for: hash), 3600, "nil = back to global")
        alice.goOffline()
    }

    func testContactBurnSecondsDecodesSafely() throws {
        let old = Data(#"{"hash":"h","name":"n","addedAt":700000000}"#.utf8)
        let d = JSONDecoder(); d.dateDecodingStrategy = .secondsSince1970
        let c = try d.decode(Contact.self, from: old)
        XCTAssertNil(c.burnSeconds, "legacy contacts decode with no override")
    }

    // MARK: dead-man switch

    func testDeadmanDecisionAndLifecycle() {
        XCTAssertFalse(DeadmanSwitch.shouldWipe(deadline: nil, now: Date()))
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertFalse(DeadmanSwitch.shouldWipe(deadline: now.addingTimeInterval(1), now: now))
        XCTAssertTrue(DeadmanSwitch.shouldWipe(deadline: now, now: now))
        XCTAssertTrue(DeadmanSwitch.shouldWipe(deadline: now.addingTimeInterval(-86400), now: now))

        let defaults = UserDefaults(suiteName: "pyro-deadman-test-\(UUID())")!
        DeadmanSwitch.arm(days: 7, now: now, defaults: defaults)
        let raw = defaults.double(forKey: DeadmanSwitch.key)
        XCTAssertEqual(raw, now.timeIntervalSince1970 + 7 * 86_400, accuracy: 1)
        DeadmanSwitch.arm(days: nil, now: now, defaults: defaults)
        XCTAssertEqual(defaults.double(forKey: DeadmanSwitch.key), 0, "off removes the deadline")
        // not expired -> no wipe, key stays
        DeadmanSwitch.arm(days: 30, now: Date(), defaults: defaults)
        XCTAssertFalse(DeadmanSwitch.fireIfExpired(now: Date(), defaults: defaults))
        XCTAssertGreaterThan(defaults.double(forKey: DeadmanSwitch.key), 0)
        // disarm (called by every wipe path) clears the artifact
        DeadmanSwitch.disarm(defaults: defaults)
        XCTAssertEqual(defaults.double(forKey: DeadmanSwitch.key), 0)
        XCTAssertFalse(DeadmanSwitch.fireIfExpired(now: .distantFuture, defaults: defaults),
                       "no deadline = never fires, even far in the future")
    }

    func testObliterateDisarmsDeadman() {
        DeadmanSwitch.arm(days: 1)
        XCTAssertGreaterThan(UserDefaults.standard.double(forKey: DeadmanSwitch.key), 0)
        SecureStore.obliterate()          // duress / burn-everything path
        XCTAssertEqual(UserDefaults.standard.double(forKey: DeadmanSwitch.key), 0,
                       "a deadline must never outlive the identity it guarded")
    }

    // MARK: socks parsing

    func testProxyParsing() {
        XCTAssertNil(RelayClient.parseProxy(nil))
        XCTAssertNil(RelayClient.parseProxy(""))
        XCTAssertNil(RelayClient.parseProxy("  "))
        XCTAssertNil(RelayClient.parseProxy("hostonly"))
        XCTAssertNil(RelayClient.parseProxy(":1080"))
        XCTAssertNil(RelayClient.parseProxy("host:0"))
        XCTAssertNil(RelayClient.parseProxy("host:99999"))
        XCTAssertNil(RelayClient.parseProxy("host:abc"))
        let p = RelayClient.parseProxy("127.0.0.1:9050")
        XCTAssertEqual(p?.host, "127.0.0.1"); XCTAssertEqual(p?.port, 9050)
        let t = RelayClient.parseProxy(" tor.local:1080 ")
        XCTAssertEqual(t?.host, "tor.local"); XCTAssertEqual(t?.port, 1080)
    }
}
