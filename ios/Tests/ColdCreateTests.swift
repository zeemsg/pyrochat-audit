import XCTest
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold, which the rules
/// above would otherwise forbid".
///
/// Free-slot knowledge lives in an account's own plaintext, so a cold start
/// has none and every slot is noise it cannot tell from ciphertext. Taken
/// literally the rules say refuse — and refusing is not survivable, because
/// the container is never deleted, so a device wiped by the duress PIN, ten
/// wrong passwords or the dead-man switch still has one on the next launch.
/// Refusing there means a wiped phone can never hold an account again: the
/// feature would brick exactly the people it is for, at exactly the moment it
/// fired for them.
final class ColdCreateTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-cold-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 5) }), key: "kdf-salt")
    }

    override func tearDownWithError() throws {
        SlotVault.chooseSlot = { $0.randomElement()! }
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    private func url(_ n: String = "pyro.store") -> URL { work.appendingPathComponent(n) }

    private func slotBytes(of u: URL, _ i: Int) throws -> Data {
        let h = try FileHandle(forReadingFrom: u)
        defer { try? h.close() }
        try h.seek(toOffset: UInt64(SlotVault.offset(ofSlot: i)))
        return try XCTUnwrap(h.read(upToCount: SlotVault.slotBytes))
    }

    /// **The sequence that matters most.** Wipe the device the way a duress PIN
    /// does, relaunch cold, create an identity. If this cannot be done the
    /// feature has bricked the phone of the person it exists to protect.
    func testAWipedDeviceCanHoldAnAccountAgain() throws {
        let u = url()
        SlotVault.chooseSlot = { _ in 1 }
        let victim = SecureStore(creatingWithPassword: "under-duress-pw-1", at: u)
        victim.update { $0.usernameDisplay = "the-real-one" }
        victim.flush()
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "under-duress-pw-1", at: u))

        // the wipe: every slot gone, the file NOT (§5)
        try SecureStore.writeNoiseContainer(at: u)
        XCTAssertTrue(FileManager.default.fileExists(atPath: u.path))
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "under-duress-pw-1", at: u))

        // relaunch cold: the app authors nothing, because a container is there
        XCTAssertFalse(SecureStore.ensureContainerExists(at: u))

        // …and a new identity can still be created
        SlotVault.chooseSlot = { $0.randomElement()! }
        let rebuilt = SecureStore(creatingWithPassword: "after-the-wipe-pw-1", at: u)
        rebuilt.update { $0.usernameDisplay = "starting-over" }
        rebuilt.flush()

        let reopened = try SecureStore(unlockingWithPassword: "after-the-wipe-pw-1", at: u)
        XCTAssertEqual(reopened.blob.usernameDisplay, "starting-over")
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes)
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "under-duress-pw-1", at: u),
                             "and the wiped account stays wiped")

        // §1: and a DECOY works immediately afterwards. This is the whole
        // reason a cold create rewrites the container rather than gambling on
        // one slot — the take-one-at-random version could create an account on
        // a wiped phone but never a decoy, which is a real loss for exactly
        // the people this feature is for.
        XCTAssertEqual(reopened.blob.knownSlots, [0, 1, 2])
        XCTAssertEqual(reopened.freeSlots.count, 2)
        let spare = try SecureStore(creatingDecoyWithPassword: "decoy-after-wipe-1",
                                    inSlot: try XCTUnwrap(reopened.freeSlots.first), at: u)
        spare.update { $0.usernameDisplay = "the-decoy" }
        spare.flush()
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "decoy-after-wipe-1", at: u)
                        .blob.usernameDisplay, "the-decoy")
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "after-the-wipe-pw-1", at: u)
                        .blob.usernameDisplay, "starting-over")
    }

    /// §1: it rewrites the WHOLE container. Every slot is claimed, so the
    /// device is left in a state it can reason about — which is what makes a
    /// decoy work afterwards on a wiped phone.
    func testColdCreateRewritesTheWholeContainer() throws {
        let u = url()
        SecureStore.ensureContainerExists(at: u)
        let before = (0..<SlotVault.slotCount).map { try! slotBytes(of: u, $0) }

        SlotVault.chooseSlot = { _ in 2 }
        let store = SecureStore(creatingWithPassword: "cold-create-pw-1", at: u)
        store.flush()

        XCTAssertEqual(store.slotIndex, 2)
        for i in 0..<SlotVault.slotCount {
            XCTAssertNotEqual(try slotBytes(of: u, i), before[i],
                              "every slot is rewritten — the account's, and fresh noise in the rest")
        }
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes,
                       "and the file is the same size it always is")
        XCTAssertEqual(store.blob.knownSlots, [0, 1, 2])
        XCTAssertEqual(store.freeSlots, [0, 1].filter { $0 != 2 } + [1].filter { _ in false })
    }

    /// §1: "a cold create on a phone that still holds a reachable account
    /// destroys it." Certainty, not a lottery — and the outcome is identical
    /// whatever the device held, which is what leaves nothing to compare.
    ///
    /// Every slot, both halves, whichever slot the new account lands in.
    func testAColdCreateDestroysEveryAccountOnTheDevice() throws {
        let u = url()
        SlotVault.chooseSlot = { _ in 0 }
        let first = SecureStore(creatingWithPassword: "already-here-pw-1", at: u)
        first.update { $0.usernameDisplay = "already-here" }
        first.flush()
        first.update { $0.usernameDisplay = "already-here-again" }
        first.flush()                       // live copy in the OTHER half
        XCTAssertEqual(first.liveHalf, 1, "the fixture needs a live copy in half 1")
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-here-pw-1",
                                    inSlot: 2, at: u)
        decoy.flush()
        first.noteAllocated(slot: 2)

        SlotVault.chooseSlot = { _ in 1 }   // and it lands on NEITHER of them
        let intruder = SecureStore(creatingWithPassword: "did-not-know-pw-1", at: u)
        intruder.flush()

        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "already-here-pw-1", at: u),
                             "the account it never touched is gone too")
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "decoy-here-pw-1", at: u))
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "did-not-know-pw-1", at: u))
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes,
                       "and the container is exactly the size it always is")
    }

    /// §1: the user has just asserted this device is theirs to take, so the
    /// record follows the assertion — otherwise the ordinary path to owning an
    /// account could never set a decoy, and §3 would be dead for everyone.
    func testAColdCreatedAccountCanStillSetADecoy() throws {
        let u = url()
        SecureStore.ensureContainerExists(at: u)
        SlotVault.chooseSlot = { _ in 0 }
        let store = SecureStore(creatingWithPassword: "cold-create-pw-1", at: u)
        store.flush()
        XCTAssertEqual(store.blob.knownSlots, [0, 1, 2])
        XCTAssertEqual(store.freeSlots, [1, 2])
    }

    /// §1: "an unlock attempt must not mint a salt." A wiped phone somebody
    /// typed at must be indistinguishable from one nobody touched.
    func testAnUnlockAttemptNeverMintsASalt() throws {
        let u = url("nosalt.store")
        try SecureStore.writeNoiseContainer(at: u)
        let before = try Data(contentsOf: u)
        KeychainHelper.delete("kdf-salt")
        XCTAssertNil(KeychainHelper.get("kdf-salt"))

        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "anything-at-all-1", at: u)) {
            XCTAssertEqual($0 as? SecureStore.StoreError, .badPassword)
        }
        XCTAssertNil(KeychainHelper.get("kdf-salt"),
                     "a wrong password at a wiped phone leaves nothing behind")
        XCTAssertFalse(SecureStore.matchesDuressPin("2580"))
        XCTAssertNil(KeychainHelper.get("kdf-salt"), "nor does a duress-PIN check")
        XCTAssertEqual(try Data(contentsOf: u), before, "and the file did not move")

        // …while CREATING one does mint it, because that is what it is for
        _ = SecureStore(creatingWithPassword: "a-real-password-1", at: u)
        XCTAssertNotNil(KeychainHelper.get("kdf-salt"))
    }

    /// A path with nothing readable at it still authors — a fresh install
    /// before `ensureContainerExists` has run, or a file this build cannot
    /// parse. Otherwise a corrupt store would be unrecoverable.
    func testColdCreateAuthorsWhenThereIsNothingToSeekInto() throws {
        let u = url("nothing.store")
        let store = SecureStore(creatingWithPassword: "cold-create-pw-1", at: u)
        store.flush()
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes)
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "cold-create-pw-1", at: u))

        let junk = url("junk.store")
        try Data(repeating: 0x33, count: 900).write(to: junk)
        let over = SecureStore(creatingWithPassword: "cold-create-pw-1", at: junk)
        over.flush()
        XCTAssertEqual(try Data(contentsOf: junk).count, SlotVault.fileBytes)
    }

    /// §1's confirmation is unconditional, and that is the point — so the copy
    /// must not be parameterised by anything.
    func testTheConfirmationSaysTheSameThingAlways() {
        XCTAssertEqual(AuthCopy.coldCreateWarning,
                       "creating an identity here erases anything else stored on this device.")
        XCTAssertFalse(AuthCopy.root.contains("identity lives"))
        XCTAssertFalse(AuthCopy.root.contains("no identity"))
    }

    /// Android's finding: a restored backup was inheriting the EXPORTING
    /// device's slot record. `[own, decoy]` from another phone describes
    /// indices that are noise here, so trusting it would hand this device
    /// permission to allocate over a slot nobody has ever looked at.
    func testARestoredBackupDiscardsTheExportingDevicesRecord() throws {
        let u = url("restored.store")
        var incoming = SecureStore.Blob()
        incoming.usernameDisplay = "from-another-phone"
        incoming.knownSlots = [2]            // it was a DECOY over there
        incoming.allocatedSlots = [2, 0]     // and it claimed a slot that is noise here

        SlotVault.chooseSlot = { _ in 1 }
        let landed = SecureStore(restoring: incoming, attachments: [:],
                                 password: "restored-pw-1", at: u)
        landed.flush()

        XCTAssertEqual(landed.slotIndex, 1)
        XCTAssertEqual(landed.blob.knownSlots, [0, 1, 2], "re-stamped, never adopted")
        XCTAssertEqual(landed.blob.allocatedSlots, [1])
        XCTAssertEqual(landed.freeSlots, [0, 2],
                       "and it does not believe it owns slot 0 on THIS device")
        XCTAssertEqual(landed.blob.usernameDisplay, "from-another-phone", "the data came across")
    }
}

// MARK: - the wiped-device path, end to end, through the live dev relay

/// docs/DECOY-ACCOUNT.md §1: "a device wiped by the duress PIN, by ten wrong
/// passwords or by the dead-man switch still has [a container] on the next
/// launch — so refusing means a wiped phone can never hold an account again.
/// The feature would brick exactly the people it is for, at exactly the moment
/// it fired for them."
///
/// So this drives the whole sequence through the real `AppModel` at the real
/// store path: arm a duress PIN, enter it, relaunch cold, create a new
/// identity, and send a message that the other side actually receives. Nothing
/// short of the last step proves the account is real.
@MainActor
final class WipedDeviceRecoveryTests: XCTestCase {
    private var model: AppModel?
    private var peer: ChatService?
    private var peerDir: URL?

    override func setUp() async throws {
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    override func tearDown() async throws {
        model?.service?.shutdown(); model = nil
        peer?.shutdown(); peer = nil
        if let peerDir { try? FileManager.default.removeItem(at: peerDir) }
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    private func name(_ p: String) -> String { p + UUID().uuidString.prefix(8).lowercased() }

    /// A second, registered account in its own file — somebody to message.
    private func makePeer() async throws -> ChatService {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-peer-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        peerDir = dir
        let store = SecureStore(creatingWithPassword: "peer-password-1",
                                at: dir.appendingPathComponent("store"))
        let svc = ChatService(store: store)
        peer = svc
        try await svc.register(username: name("iospeer"))
        return svc
    }

    /// **duress wipe → kill → relaunch → create → send.**
    func testDuressWipeThenColdCreateThenSendAMessage() async throws {
        let peerService = try await makePeer()
        let peerName = peerService.store.blob.usernameDisplay

        // --- an ordinary device, with an identity and a duress PIN armed ---
        do {
            let m = AppModel(); model = m
            m.finishBoot()
            let created = await m.submitAuth(.create, username: name("iosdoomed"),
                                             password: "the-real-password-1")
            XCTAssertNil(created, "create failed: \(created ?? "")")
            m.service?.store.flush()
            SecureStore.setDuressPin("2580")
            XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "the-real-password-1"))
            m.lock()

            // --- the duress PIN, typed at the unlock prompt ---
            let cover = await m.submitAuth(.unlock, username: "", password: "2580")
            XCTAssertEqual(cover, AppModel.wrongPasswordError,
                           "the cover story, unchanged")
            XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "the-real-password-1"),
                                 "every slot is gone")
            // §5: and the file is NOT
            XCTAssertTrue(SecureStore.storeExists)
            XCTAssertEqual(try Data(contentsOf: SecureStore.storeURL).count, SlotVault.fileBytes)
            m.service?.shutdown()
        }

        // --- kill the app and relaunch it, exactly as `PyroChatApp.init` does ---
        model = nil
        SecureStore.sweepPlaintextTmp()
        XCTAssertFalse(SecureStore.ensureContainerExists(),
                       "the container survived the wipe, so launch authors nothing")

        // --- create a new identity on the wiped device ---
        let m = AppModel(); model = m
        m.finishBoot()
        let myName = name("iosrisen")
        let failure = await m.submitAuth(.create, username: myName,
                                         password: "the-new-password-1")
        XCTAssertNil(failure, "a wiped phone must be able to hold an account again: \(failure ?? "")")
        let service = try XCTUnwrap(m.service)
        service.store.flush()
        XCTAssertEqual(m.stage, .main)
        XCTAssertEqual(service.store.blob.usernameDisplay, myName)

        // --- and it is a REAL account: it can send, and the other side gets it ---
        try await service.addContact(username: peerName)
        let contact = try XCTUnwrap(service.contacts.first { $0.name == peerName })
        let body = "sent from a phone that was wiped \(UUID().uuidString.prefix(6))"
        await service.sendText(body, to: contact)

        var delivered = false
        for _ in 0..<80 {
            try? await Task.sleep(for: .milliseconds(250))
            if peerService.messages.values.flatMap({ $0 }).contains(where: { $0.text == body }) {
                delivered = true; break
            }
        }
        XCTAssertTrue(delivered, "the message never reached the other side")

        // the new identity opens by its password, and the wiped one never will
        service.store.flush()
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "the-new-password-1"))
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "the-real-password-1"))
        XCTAssertEqual(try Data(contentsOf: SecureStore.storeURL).count, SlotVault.fileBytes)
    }

    /// §1's whole reason for rewriting the container rather than gambling on
    /// one slot: **a decoy must work immediately after a cold create on a
    /// duress-wiped phone.** The take-one-at-random version could create an
    /// account there and never a decoy, which is a real loss for exactly the
    /// people this feature is for. Driven through the live relay, so the decoy
    /// is a genuine registered account and not a store trick.
    func testADecoyCanBeSetRightAfterAColdCreateOnAWipedPhone() async throws {
        // arm and fire the duress wipe
        do {
            let m = AppModel(); model = m
            m.finishBoot()
            let created = await m.submitAuth(.create, username: name("iosdoomed"),
                                             password: "the-real-password-3")
            XCTAssertNil(created, "create failed: \(created ?? "")")
            m.service?.store.flush()
            SecureStore.setDuressPin("2580")
            m.lock()
            _ = await m.submitAuth(.unlock, username: "", password: "2580")
            m.service?.shutdown()
        }
        model = nil
        XCTAssertFalse(SecureStore.ensureContainerExists())

        // cold create on the wiped phone
        let m = AppModel(); model = m
        m.finishBoot()
        let failure = await m.submitAuth(.create, username: name("iosrisen"),
                                         password: "the-new-password-3")
        XCTAssertNil(failure, "create failed: \(failure ?? "")")
        let service = try XCTUnwrap(m.service)
        service.store.flush()

        // …and a decoy, straight away
        XCTAssertTrue(service.canCreateDecoy,
                      "a wiped phone that has just created an account must be able to hold a decoy")
        let decoyName = name("iosdecoy")
        let decoyFailure = await service.createDecoy(username: decoyName,
                                                     password: "the-decoy-password-3")
        XCTAssertNil(decoyFailure, "setting a decoy after a cold create must work")

        let decoy = try SecureStore(unlockingWithPassword: "the-decoy-password-3")
        XCTAssertEqual(decoy.blob.usernameDisplay, decoyName)
        XCTAssertFalse(decoy.blob.identityKeyPair.isEmpty, "a genuine registered account")
        XCTAssertNotEqual(decoy.slotIndex, service.store.slotIndex)
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "the-new-password-3"),
                        "and the real account is untouched by it")
        XCTAssertEqual(try Data(contentsOf: SecureStore.storeURL).count, SlotVault.fileBytes)
    }

    /// The same for ten wrong passwords, which runs the identical wipe — the
    /// path a coerced person is most likely to see.
    func testTenWrongPasswordsThenColdCreate() async throws {
        do {
            let m = AppModel(); model = m
            m.finishBoot()
            let created = await m.submitAuth(.create, username: name("iosdoomed"),
                                             password: "the-real-password-2")
            XCTAssertNil(created, "create failed: \(created ?? "")")
            m.service?.store.flush()
            m.lock()
            for _ in 0..<WipePolicy.limit {
                _ = await m.submitAuth(.unlock, username: "", password: "not-it-at-all-1")
            }
            XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "the-real-password-2"))
            XCTAssertTrue(SecureStore.storeExists, "§5: never deleted")
            m.service?.shutdown()
        }
        model = nil
        XCTAssertFalse(SecureStore.ensureContainerExists())

        let m = AppModel(); model = m
        m.finishBoot()
        let failure = await m.submitAuth(.create, username: name("iosrisen"),
                                         password: "the-new-password-2")
        XCTAssertNil(failure, "a wiped phone must be able to hold an account again: \(failure ?? "")")
        XCTAssertEqual(m.stage, .main)
        m.service?.store.flush()
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "the-new-password-2"))
    }
}
