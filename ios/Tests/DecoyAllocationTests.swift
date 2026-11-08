import XCTest
import CryptoKit
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §1 (allocation), §3 (creating the decoy) and §5
/// (wipe semantics), asserted on bytes and on the session's reachable
/// surface — never on the interface, which §9 is explicit about.
final class DecoyAllocationTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-decoy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 7) }), key: "kdf-salt")
    }

    override func tearDownWithError() throws {
        SlotVault.chooseSlot = { $0.randomElement()! }
        SlotVault.randomBytes = SlotVault.systemRandomBytes
        SecureStore.onStoreFull = nil
        SecureStore.onWrite = nil
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    private func url(_ name: String = "pyro.store") -> URL { work.appendingPathComponent(name) }

    private func slotBytes(of url: URL, _ index: Int) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(SlotVault.offset(ofSlot: index)))
        return try XCTUnwrap(handle.read(upToCount: SlotVault.slotBytes))
    }

    /// Pin the container so a test can name slots rather than hunt for them.
    private func makeFirstAccount(in slot: Int, password: String = "first-password-1",
                                  at u: URL) -> SecureStore {
        SlotVault.chooseSlot = { free in
            precondition(free.contains(slot)); return slot
        }
        let store = SecureStore(creatingWithPassword: password, at: u)
        store.flush()
        SlotVault.chooseSlot = { $0.randomElement()! }
        return store
    }

    // MARK: - §1, the allocation record

    /// §1: "Each account records, inside its own encrypted plaintext, the slot
    /// indices it has allocated." Inside, and NOWHERE else — not the header,
    /// not the keychain, not a sidecar. This asserts both halves.
    func testTheRecordIsInTheEncryptedPlaintextAndNowhereElse() throws {
        let u = url()
        let store = makeFirstAccount(in: 1, at: u)
        let keychainBefore = Self.keychainAccounts()
        let dirBefore = Self.siblings(of: u)

        store.noteAllocated(slot: 2)

        // it IS in the plaintext: a reopen under the same password sees it
        let reopened = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        XCTAssertEqual(reopened.blob.allocatedSlots, [1, 2])
        // §1: the AUTHOR writes it out explicitly. Absent fails closed, so
        // "I authored this container" is never left to be inferred.
        XCTAssertEqual(reopened.blob.knownSlots, [0, 1, 2])

        // and it is not anywhere an examiner can reach without the password
        let raw = try Data(contentsOf: u)
        XCTAssertEqual(raw.prefix(SlotVault.headerBytes), SlotVault.header(),
                       "the header is the same 13 bytes on every container ever written")
        XCTAssertEqual(raw.count, SlotVault.fileBytes,
                       "and the file length never moves, whatever has been allocated")
        XCTAssertEqual(Self.keychainAccounts(), keychainBefore,
                       "§6.1: allocating a slot adds no keychain item")
        XCTAssertEqual(Self.siblings(of: u), dirBefore,
                       "and no sidecar appears beside the store")
    }

    /// §1: the author knows every slot, so it can allocate; a decoy knows one,
    /// so it cannot. The whole of "a decoy cannot create another" is this, and
    /// nothing is bolted on beside it.
    func testTheAuthorCanAllocateAndADecoyCannot() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        XCTAssertEqual(first.freeSlots, [1, 2])
        XCTAssertFalse(first.hasAllocatedDecoy)

        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.flush()
        first.noteAllocated(slot: 2)

        XCTAssertEqual(decoy.blob.knownSlots, [2], "a decoy knows its own index and no other")
        XCTAssertEqual(decoy.freeSlots, [],
                       "so there is nothing it could allocate, at any point, ever")

        // A DECOY's record is stamped explicitly, so it is never absent and the
        // fail-closed rule protects it exactly as §1 describes.
        XCTAssertNotNil(decoy.blob.knownSlots, "a decoy's record is written, not inferred")

        // What follows is the iOS-only repair for builds 32-34, and it is a
        // REAL weakening of §1, stated plainly rather than hidden.
        //
        // Those builds' migration authored a container and forgot to stamp the
        // record, so every account that arrived by upgrade — which is all of
        // them — read as knowing only its own slot and could never set a
        // decoy. The repair stamps an absent record on an existing container.
        //
        // The cost is exactly this test's original scenario: a container that
        // DOES hold a decoy, whose record was then lost, now comes back
        // believing it may allocate over the slot the decoy is in. That state
        // is not reachable from any shipped build — both platforms preserve
        // both keys, and no third writer exists — but it is no longer refused,
        // and if one ever appears this repair is the first thing to delete.
        // Android does NOT carry it: its migration never had the gap, so an
        // absent record there is unexplained and stays refused.
        let stripped = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        stripped.update { $0.knownSlots = nil; $0.allocatedSlots = nil }
        stripped.flush()
        let reopened = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        XCTAssertEqual(Set(reopened.blob.knownSlots ?? []), Set(0..<SlotVault.slotCount),
                       "the 32-34 repair stamps it, which is what lets an upgraded account "
                       + "set a decoy at all")
        XCTAssertFalse(reopened.freeSlots.isEmpty)
        XCTAssertTrue(first.hasAllocatedDecoy)
    }

    /// §1: "A freshly authored slot writes one real half" and the container is
    /// otherwise untouched. Writing a decoy into a free slot must leave the
    /// account that asked for it byte-identical.
    func testCreatingADecoyLeavesEveryOtherSlotByteIdentical() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        first.update { $0.usernameDisplay = "real" }
        first.flush()
        let mine = try slotBytes(of: u, 0)
        let untouched = try slotBytes(of: u, 1)

        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.update { $0.usernameDisplay = "decoy" }
        decoy.flush()

        XCTAssertEqual(try slotBytes(of: u, 0), mine, "the real account is exactly as it was")
        XCTAssertEqual(try slotBytes(of: u, 1), untouched, "and so is the noise beside it")
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes)

        // both passwords open their own account and only their own
        let asFirst = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        XCTAssertEqual(asFirst.blob.usernameDisplay, "real")
        let asDecoy = try SecureStore(unlockingWithPassword: "decoy-password-1", at: u)
        XCTAssertEqual(asDecoy.blob.usernameDisplay, "decoy")
        XCTAssertNotEqual(asFirst.slotIndex, asDecoy.slotIndex)
    }

    /// §3's row replaces, and a replacement must take the WHOLE slot.
    ///
    /// A slot is two halves that a save alternates between, so writing only
    /// half 0 leaves the previous decoy in half 1 — still authenticating under
    /// its own password, still opening, until the new one's SECOND save lands
    /// on it. Whether the replaced decoy survived would depend on the parity of
    /// how many times it had saved, which is not a property anything should
    /// depend on.
    func testReplacingADecoyTakesBothHalvesOfItsSlot() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        let old = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                  inSlot: 2, at: u)
        // the init's own write lands in half 0; one more save alternates to
        // half 1, which is the case this test is about
        old.update { $0.usernameDisplay = "old-decoy" }
        old.flush()
        XCTAssertEqual(old.liveHalf, 1, "the fixture needs a live copy in half 1")
        first.noteAllocated(slot: 2)

        let replacement = try SecureStore(creatingDecoyWithPassword: "decoy-password-2",
                                          inSlot: 2, at: u)

        // Asserted HERE, before the replacement's second save. That save
        // alternates onto half 1 and would clean up after the bug — which is
        // exactly why this window is easy to miss and worth pinning: a crash
        // inside it would leave two accounts in one slot, and whether the
        // replaced decoy was still openable would depend on the parity of how
        // many times it had saved.
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "decoy-password-1", at: u),
                             "the replaced decoy is gone at once, not one save later")

        replacement.update { $0.usernameDisplay = "new-decoy" }
        replacement.flush()
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "decoy-password-1", at: u),
                             "…and it stays gone")
        XCTAssertEqual(try SecureStore(unlockingWithPassword: "decoy-password-2", at: u)
                        .blob.usernameDisplay, "new-decoy")
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: "first-password-1", at: u),
                        "and the account that owns it is untouched")
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes)
    }

    /// §3: "a password, which must not be the real one (rejected, with the
    /// ordinary wrong-value wording)". The store never keeps a password, so
    /// the question it can actually answer is "does this derive my key" — and
    /// with one shared salt that is the same question.
    func testAPasswordEqualToTheCurrentOneIsRecognised() throws {
        let store = makeFirstAccount(in: 0, at: url())
        XCTAssertTrue(store.derivesSameKey(as: "first-password-1"))
        XCTAssertFalse(store.derivesSameKey(as: "first-password-2"))
        XCTAssertFalse(store.derivesSameKey(as: ""))
    }

    /// §1: a decoy's store must NEVER author a container, because authoring
    /// replaces the whole file — the account that created it included. If the
    /// file stops being a seekable container the write is dropped, not taken.
    func testADecoyStoreRefusesToAuthorAContainer() throws {
        let u = url()
        _ = makeFirstAccount(in: 0, at: u)
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.flush()

        // the file becomes something this build cannot seek into
        try Data(repeating: 0xAB, count: 64).write(to: u)
        decoy.update { $0.usernameDisplay = "should not land" }
        decoy.flush()

        XCTAssertEqual(try Data(contentsOf: u).count, 64,
                       "the decoy must not have replaced the file with a container of its own")
    }

    /// §1: a slot can only be taken by an account that knows it is free. The
    /// injectable seam must not be able to talk the app into taking one it
    /// does not know about — writing over an account we cannot open is the one
    /// thing §1 forbids absolutely, so the seam is checked, not trusted.
    @MainActor
    func testAllocationRefusesASlotOutsideTheKnownFreeSet() async throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        XCTAssertEqual(first.freeSlots, [1, 2])
        let before = try Data(contentsOf: u)

        let service = ChatService(store: first)
        // the account's OWN slot: known, but taken, so never free
        SlotVault.chooseSlot = { _ in 0 }
        let failure = await service.createDecoy(username: "someone", password: "another-pass-1")

        XCTAssertEqual(failure, DecoyCopy.failed)
        XCTAssertEqual(try Data(contentsOf: u), before, "and not one byte moved")
        XCTAssertEqual(first.blob.allocatedSlots, [0], "nor was anything recorded")
        service.shutdown()
    }

    // MARK: - §5, wipe semantics

    /// §5: "`[ BURN EVERYTHING ]` wipes the current slot only." From the
    /// decoy, so the account that matters survives it intact.
    func testBurnFromTheDecoyLeavesTheOtherSlotsByteIdentical() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        first.update { $0.usernameDisplay = "real" }
        first.flush()
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.update { $0.usernameDisplay = "decoy" }
        decoy.flush()
        first.noteAllocated(slot: 2)

        let realSlot = try slotBytes(of: u, 0)
        let noiseSlot = try slotBytes(of: u, 1)

        XCTAssertFalse(decoy.burnCurrentSlot(), "a decoy may never take the whole file")

        XCTAssertEqual(try slotBytes(of: u, 0), realSlot, "the real account is untouched")
        XCTAssertEqual(try slotBytes(of: u, 1), noiseSlot, "the noise beside it is untouched")
        XCTAssertNotEqual(try slotBytes(of: u, 2), Data(repeating: 0, count: SlotVault.slotBytes))
        XCTAssertEqual(try Data(contentsOf: u).count, SlotVault.fileBytes,
                       "and the file's length never moves")

        // the decoy's password no longer opens anything; the real one still does
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "decoy-password-1", at: u))
        let survivor = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        XCTAssertEqual(survivor.blob.usernameDisplay, "real")
    }

    /// §5, the other half: an account that can PROVE the rest of its container
    /// is noise takes the whole file, exactly as every wipe did before this
    /// feature existed. That is the overwhelmingly common device.
    /// §5: "a burn must NOT delete the file even when the account can prove it
    /// is alone, because behaving differently in the common case is precisely
    /// what makes the uncommon case legible." An earlier version of this took
    /// the whole file and was exactly the wrong optimisation.
    func testBurnFromALoneAccountLeavesAFreshAllNoiseContainer() throws {
        let u = url()
        let lone = makeFirstAccount(in: 1, at: u)
        lone.update { $0.usernameDisplay = "alone" }
        lone.flush()
        let occupied = try slotBytes(of: u, 1)

        XCTAssertTrue(lone.burnCurrentSlot(), "total: the device-level state goes too")

        XCTAssertTrue(FileManager.default.fileExists(atPath: u.path), "§5: never deleted")
        let raw = try Data(contentsOf: u)
        XCTAssertEqual(raw.count, SlotVault.fileBytes,
                       "wiped, never used, and holding two accounts are the same bytes")
        XCTAssertEqual(raw.prefix(SlotVault.headerBytes), SlotVault.header())
        XCTAssertNotEqual(try slotBytes(of: u, 1), occupied, "and the account really is gone")
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "first-password-1", at: u))
    }

    /// The two burns are indistinguishable from outside: same file, same
    /// length, same header. That is the whole of §5's fix.
    func testBothBurnsLeaveTheSameShapeOnDisk() throws {
        let lonelyURL = url("lone.store")
        let lone = makeFirstAccount(in: 1, at: lonelyURL)
        lone.flush()
        _ = lone.burnCurrentSlot()

        let pairURL = url("pair.store")
        let first = makeFirstAccount(in: 0, at: pairURL)
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: pairURL)
        decoy.flush()
        first.noteAllocated(slot: 2)
        _ = decoy.burnCurrentSlot()

        let a = try Data(contentsOf: lonelyURL)
        let b = try Data(contentsOf: pairURL)
        XCTAssertEqual(a.count, b.count)
        XCTAssertEqual(a.count, SlotVault.fileBytes)
        XCTAssertEqual(a.prefix(SlotVault.headerBytes), b.prefix(SlotVault.headerBytes))
        XCTAssertEqual(a.prefix(SlotVault.headerBytes), SlotVault.header())
    }

    /// §5: a container exists from first run, before any account.
    func testTheContainerIsAuthoredAtFirstRunAndNeverDeletedAfter() throws {
        let u = url("firstrun.store")
        XCTAssertFalse(FileManager.default.fileExists(atPath: u.path))
        XCTAssertTrue(SecureStore.ensureContainerExists(at: u))

        let raw = try Data(contentsOf: u)
        XCTAssertEqual(raw.count, SlotVault.fileBytes)
        XCTAssertEqual(raw.prefix(SlotVault.headerBytes), SlotVault.header())
        XCTAssertNil(SlotVault.open(try SlotVault.parse(raw),
                                    key: SecureStore.deriveKey(
                                        password: "anything-at-all",
                                        salt: try XCTUnwrap(KeychainHelper.get("kdf-salt")))),
                     "three slots of noise: nothing opens it, which is also true of a used device")

        // idempotent, and it never touches what is already there
        XCTAssertFalse(SecureStore.ensureContainerExists(at: u))
        XCTAssertEqual(try Data(contentsOf: u), raw)
    }

    /// …and it must never overwrite a pre-container store, which §7 has to be
    /// able to migrate.
    func testFirstRunLeavesALegacyStoreAlone() throws {
        let u = url("legacy.store")
        let legacy = Data("not a container, but somebody's whole account".utf8)
        try legacy.write(to: u)
        XCTAssertFalse(SecureStore.ensureContainerExists(at: u))
        XCTAssertEqual(try Data(contentsOf: u), legacy)
    }

    /// §5: a real account that has handed a slot out cannot prove the rest is
    /// noise — it knows the opposite — so its own burn must narrow too, or it
    /// would destroy the decoy it created.
    func testBurnFromAnAccountThatHasADecoyKeepsTheFile() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.update { $0.usernameDisplay = "decoy" }
        decoy.flush()
        first.noteAllocated(slot: 2)

        XCTAssertFalse(first.burnCurrentSlot())
        XCTAssertTrue(FileManager.default.fileExists(atPath: u.path))
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "first-password-1", at: u))
        let survivor = try SecureStore(unlockingWithPassword: "decoy-password-1", at: u)
        XCTAssertEqual(survivor.blob.usernameDisplay, "decoy")
    }

    /// §6.2: each attachment is sealed with its own slot's key, so attribution
    /// is exactly "does it open under my key". A narrowed burn must take its
    /// own files and leave every other slot's alone.
    func testBurnDeletesOnlyItsOwnAttachments() throws {
        let u = url()
        let first = makeFirstAccount(in: 0, at: u)
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-1",
                                    inSlot: 2, at: u)
        decoy.flush()
        first.noteAllocated(slot: 2)

        let mine = UUID().uuidString.lowercased()
        let theirs = UUID().uuidString.lowercased()
        try first.writeAttachment(id: theirs, data: Data("real audio".utf8))
        try decoy.writeAttachment(id: mine, data: Data("decoy audio".utf8))

        _ = decoy.burnCurrentSlot()

        let dir = SecureStore.attachmentsDir(for: u)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent(mine).path),
                       "the decoy's own attachment goes with its slot")
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent(theirs).path),
                      "and a file it cannot open is not its to delete")
        let survivor = try SecureStore(unlockingWithPassword: "first-password-1", at: u)
        XCTAssertEqual(survivor.readAttachment(id: theirs), Data("real audio".utf8))
    }

    // MARK: - §1a, the condition that must be surfaced and never acted on

    /// §1a: "Do not migrate it, and do not delete anything." The file stays
    /// legacy, the app keeps working, and the condition is REPORTED — with the
    /// reason that says which of the two conditions it is.
    func testTooLargeToMigrateIsReportedAndNothingIsDestroyed() throws {
        let u = url()
        let password = "heavy-password-1"
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "heavy"
        // pure text, over the ceiling even with every byte of audio elsewhere
        blob.drafts = ["t": String(repeating: "x", count: SlotVault.maxPayloadBytes)]
        let plain = try JSONEncoder().encode(blob)
        XCTAssertGreaterThan(plain.count, SlotVault.maxPayloadBytes)
        let key = SecureStore.deriveKey(password: password,
                                        salt: try XCTUnwrap(KeychainHelper.get("kdf-salt")))
        try XCTUnwrap(try AES.GCM.seal(plain, using: key).combined).write(to: u)
        let legacyBytes = try Data(contentsOf: u)

        var reported: [SecureStore.FullReason] = []
        SecureStore.onStoreFull = { _, reason in reported.append(reason) }

        let store = try SecureStore(unlockingWithPassword: password, at: u)
        store.flush()

        XCTAssertTrue(store.isLegacyFile, "§1a: it stays in its legacy shape")
        XCTAssertEqual(store.blob.usernameDisplay, "heavy", "and it opened, and it works")
        XCTAssertEqual(reported.first, .cannotMigrate, "and the condition is not swallowed")
        XCTAssertEqual(try Data(contentsOf: u).count, legacyBytes.count,
                       "nothing was trimmed to make it fit")
        XCTAssertNotNil(try? SecureStore(unlockingWithPassword: password, at: u),
                        "and it keeps opening, launch after launch")
        // §1a: "the condition is reported and nothing is destroyed" — the line
        // says so in as many words, and asks the user for nothing.
        XCTAssertEqual(StoreFullCopy.line(for: .cannotMigrate), StoreFullCopy.cannotMigrate)
        XCTAssertTrue(StoreFullCopy.cannotMigrate.contains("nothing has been changed or deleted"))
        XCTAssertFalse(StoreFullCopy.cannotMigrate.lowercased().contains("trim"),
                       "trimming to fit is exactly what §1a refuses to offer unasked")
    }

    // MARK: helpers

    /// Every keychain account this app owns, by name. §6.1's counter.
    static func keychainAccounts() -> Set<String> {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "ca.pyrochat.app",
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let items = out as? [[String: Any]] else { return [] }
        return Set(items.compactMap { $0[kSecAttrAccount as String] as? String })
    }

    static func siblings(of storeURL: URL) -> Set<String> {
        let dir = storeURL.deletingLastPathComponent()
        let all = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return Set(all)
    }
}

// MARK: - §3 end to end, through the live dev relay (127.0.0.1:7444)

/// docs/DECOY-ACCOUNT.md §3: "The decoy is then a genuine account: real
/// identity keys, real registration, real conversations." Nothing about that
/// can be proved against a stub, so this one talks to the relay the rest of
/// the integration suite already uses.
@MainActor
final class DecoyCreationTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?
    private var services: [ChatService] = []

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-decoy-live-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 13) }), key: "kdf-salt")
    }

    override func tearDown() async throws {
        for s in services { s.shutdown() }
        services = []
        SlotVault.chooseSlot = { $0.randomElement()! }
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    private func name(_ prefix: String) -> String {
        prefix + UUID().uuidString.prefix(8).lowercased()
    }

    /// A registered first account, in slot 0, at an isolated path.
    private func registeredFirstAccount(password: String) async throws
        -> (store: SecureStore, service: ChatService, url: URL) {
        let u = work.appendingPathComponent("pyro.store")
        SlotVault.chooseSlot = { _ in 0 }
        let store = SecureStore(creatingWithPassword: password, at: u)
        SlotVault.chooseSlot = { $0.randomElement()! }
        let service = ChatService(store: store)
        services.append(service)
        try await service.register(username: name("iosreal"))
        store.flush()
        return (store, service, u)
    }

    /// §3, the whole row: it asks for a username and a password, rejects a
    /// password equal to the current account's, and creates a genuine account
    /// in a slot this account knows is free.
    func testSettingADecoyCreatesAGenuineRegisteredAccount() async throws {
        let password = "first-live-password-1"
        let (store, service, u) = try await registeredFirstAccount(password: password)
        XCTAssertTrue(service.canCreateDecoy)

        // §3's one-message rule protects the DECOY, not the owner. Inside an
        // account that demonstrably owns the container, every failure says
        // plainly what went wrong — a real user hit the vague string with a
        // seven-character password and had no way to discover that. The
        // indistinguishable case is "no room", and it is asserted where it
        // belongs, in the decoy test below.
        let sameAsReal = await service.createDecoy(username: name("iosdecoy"), password: password)
        XCTAssertEqual(sameAsReal, DecoyCopy.samePassword)
        let badName = await service.createDecoy(username: "no", password: "decoy-live-password-1")
        XCTAssertEqual(badName, DecoyCopy.badUsername)
        let shortPass = await service.createDecoy(username: name("iosdecoy"), password: "short1")
        XCTAssertEqual(shortPass, DecoyCopy.shortPassword)
        XCTAssertEqual(Set([sameAsReal, badName, shortPass]).count, 3,
                       "the owner is told which of the three it was")

        let decoyName = name("iosdecoy")
        let failure = await service.createDecoy(username: decoyName,
                                                password: "decoy-live-password-1")
        XCTAssertNil(failure)

        // the record moved, inside the encrypted plaintext and nowhere else
        XCTAssertEqual(store.blob.allocatedSlots?.count, 2)
        XCTAssertTrue(store.hasAllocatedDecoy)
        XCTAssertEqual(store.freeSlots.count, 1)

        // and the slot really holds a registered identity
        let decoy = try SecureStore(unlockingWithPassword: "decoy-live-password-1", at: u)
        XCTAssertEqual(decoy.blob.usernameDisplay, decoyName)
        XCTAssertFalse(decoy.blob.identityKeyPair.isEmpty, "real identity keys")
        XCTAssertNotEqual(decoy.blob.registrationId, 0)
        XCTAssertFalse(decoy.blob.authPrivateKey.isEmpty)
        XCTAssertFalse(decoy.blob.signedPreKeys.isEmpty)
        XCTAssertFalse(decoy.blob.kyberPreKeys.isEmpty)
        XCTAssertNotEqual(decoy.blob.identityKeyPair, store.blob.identityKeyPair,
                          "its own keys, not a copy of the account that made it")
        XCTAssertNotEqual(decoy.slotIndex, store.slotIndex)

        // §6: the real account is untouched and still opens
        let real = try SecureStore(unlockingWithPassword: password, at: u)
        XCTAssertEqual(real.blob.usernameDisplay, store.blob.usernameDisplay)

        // it reaches the same relay by the same route as the account that made
        // it — anything else would be a far louder tell than a shared setting
        XCTAssertEqual(decoy.blob.settings.relayURL, store.blob.settings.relayURL)
        // …and nothing else came across (§4)
        XCTAssertEqual(decoy.blob.settings.theme, AppSettings().theme)
        XCTAssertNil(decoy.blob.settings.textSize)

        // §3: the row CREATES OR REPLACES and is never a one-shot. Used again
        // from the account that owns the decoy it rebuilds the decoy in the
        // slot it already allocated — without which a mistyped decoy password
        // is only recoverable by wiping the device, and a setting-shaped row
        // that is really an irreversible one-time action is a trap.
        XCTAssertTrue(service.canCreateDecoy)
        XCTAssertTrue(service.decoyDialogIsReplacement,
                      "and only the DIALOG may say so — never the row")
        let replacementName = name("iosagain")
        let replaced = await service.createDecoy(username: replacementName,
                                                 password: "decoy-live-password-1b")
        XCTAssertNil(replaced)

        // §1: still ONE decoy, in the SAME slot. The third slot is never used.
        XCTAssertEqual(store.blob.allocatedSlots?.count, 2)
        XCTAssertEqual(store.freeSlots.count, 1)
        let rebuilt = try SecureStore(unlockingWithPassword: "decoy-live-password-1b", at: u)
        XCTAssertEqual(rebuilt.blob.usernameDisplay, replacementName)
        XCTAssertEqual(rebuilt.slotIndex, decoy.slotIndex, "the slot it already allocated")
        XCTAssertThrowsError(try SecureStore(unlockingWithPassword: "decoy-live-password-1", at: u),
                             "the old decoy is gone and cannot be recovered")

        // and the real account is untouched by any of it
        let stillThere = try SecureStore(unlockingWithPassword: password, at: u)
        XCTAssertEqual(stillThere.blob.usernameDisplay, store.blob.usernameDisplay)
    }

    /// §1: "A decoy cannot create another." Asserted on the decoy's own live
    /// session, not on the interface.
    func testADecoySessionCannotCreateADecoy() async throws {
        let password = "first-live-password-2"
        let (_, service, u) = try await registeredFirstAccount(password: password)
        let created = await service.createDecoy(username: name("iosdecoy"),
                                                password: "decoy-live-password-2")
        XCTAssertNil(created)

        let decoyStore = try SecureStore(unlockingWithPassword: "decoy-live-password-2", at: u)
        let decoyService = ChatService(store: decoyStore)
        services.append(decoyService)

        XCTAssertFalse(decoyService.canCreateDecoy)
        XCTAssertEqual(decoyStore.freeSlots, [], "it knows one index, and it is already its own")
        let failure = await decoyService.createDecoy(username: name("iosnested"),
                                                     password: "nested-live-password-1")
        XCTAssertEqual(failure, DecoyCopy.failed)
        XCTAssertEqual(decoyStore.blob.allocatedSlots, [try XCTUnwrap(decoyStore.slotIndex)],
                       "and nothing was recorded")

        // THE property, asserted where it actually matters: from inside a
        // decoy every input fails with the SAME words, so the person holding
        // the phone cannot learn from the failure that they are in one. The
        // owner's specific messages above are unreachable from here, because
        // "no room" is decided before anything is validated.
        let decoyBadName = await decoyService.createDecoy(username: "no",
                                                          password: "nested-live-password-1")
        let decoyShortPass = await decoyService.createDecoy(username: name("iosnested"),
                                                            password: "short1")
        XCTAssertEqual(decoyBadName, DecoyCopy.failed)
        XCTAssertEqual(decoyShortPass, DecoyCopy.failed)
        XCTAssertEqual(Set([failure, decoyBadName, decoyShortPass]).count, 1,
                       "one message from a decoy, whatever is typed")
    }

    // MARK: docs/DECOY-ACCOUNT.md §5 — the burn takes the biometric key

    /// The property I introduced and did not test, which is the combination
    /// most likely to be quietly regressed.
    ///
    /// A device that still raises a face prompt at launch has something left;
    /// one with nothing does not. So a narrowed burn must destroy the stored
    /// key EVEN WHEN it belongs to a slot the burn is not touching — the case
    /// the old code deliberately spared, to avoid a decoy's burn disabling the
    /// real account's face unlock. That kindness was the leak.
    func testBurningOneSlotTakesTheBiometricKeyEvenWhenItIsAnotherSlots() throws {
        let fake = BiometricUnlockTests.FakeBacking()
        BiometricUnlock.backing = fake
        defer { BiometricUnlock.backing = KeychainBiometricStore() }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("burn-bio-\(UUID().uuidString)")
        let owner = SecureStore(creatingWithPassword: "owner-password-1", at: url)

        // a key that belongs to SOMETHING ELSE: 32 bytes that are not this
        // store's, exactly as a decoy's burn would find the real account's.
        let foreign = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        XCTAssertTrue(fake.storeKey(foreign))
        fake.setArmed(true)
        fake.setLastPasswordUnlock(Date())
        XCTAssertTrue(fake.keyExists())
        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: owner.rawKey),
                       "precondition: the key is not this store's")

        // …and the OTHER direction, which Android proved is the real trap.
        // SCRUB-AND-WIPE §A1's wrong-password counter acts on EVERY slot, so
        // a decoy's burn clearing it would silently reset the real account's
        // wipe countdown — a leak running the opposite way. On Android these
        // live in one preferences file and clearing it wholesale takes the
        // counter too; on iOS they are separate Keychain items, so the mistake
        // is unreachable here. Assert it anyway: the platforms must agree, and
        // "unreachable" is a property of today's storage layout, not a rule.
        let counter = BurnCounterSpy()
        counter.setFailures(4)
        let previousCounter = UnlockFailures.backing
        UnlockFailures.backing = counter
        defer { UnlockFailures.backing = previousCounter }

        owner.burnCurrentSlot()

        XCTAssertFalse(fake.keyExists(), "the burn took a key that was not its own")
        XCTAssertFalse(fake.armed(), "and the breadcrumb with it")
        XCTAssertNil(fake.lastPasswordUnlock(),
                     "and the timestamp, so all four wipe paths leave the same state")
        XCTAssertEqual(counter.failures(), 4,
                       "but NOT §A1's counter — it acts on every slot, and a decoy "
                       + "must not be able to reset the real account's countdown")
    }

    /// The bug a real user hit on build 33, and the reason it was invisible:
    /// it reported as "could not set decoy password", the one vague string
    /// reserved for a decoy, on an account that owned its container.
    ///
    /// Migration authors the container — it writes all three slots and fills
    /// two with noise — but it did not stamp `knownSlots`. So `freeSlots` fell
    /// back to "only my own slot", subtracted the slot migration had just
    /// taken, and came out empty. EVERY account that arrived by upgrade, which
    /// is every existing account, could never set a decoy.
    func testAMigratedAccountCanStillSetADecoy() throws {
        let u = FileManager.default.temporaryDirectory
            .appendingPathComponent("migrated-\(UUID().uuidString)")
        // a legacy store as every shipped build wrote one: a single sealed
        // blob, no container magic
        let salt = try XCTUnwrap(KeychainHelper.get("kdf-salt"))
        let key = SecureStore.deriveKey(password: "legacy-password-1", salt: salt)
        var blob = SecureStore.Blob()
        blob.usernameDisplay = "upgraded"
        blob.settings.theme = "amber"
        let legacy = try XCTUnwrap(AES.GCM.seal(try JSONEncoder().encode(blob), using: key).combined)
        try legacy.write(to: u)

        let migrated = try SecureStore(unlockingWithPassword: "legacy-password-1", at: u)
        XCTAssertFalse(migrated.isLegacyFile, "it migrated")
        XCTAssertEqual(migrated.blob.usernameDisplay, "upgraded", "and kept the account")
        XCTAssertEqual(migrated.blob.settings.theme, "amber")
        XCTAssertEqual(Set(migrated.blob.knownSlots ?? []), Set(0..<SlotVault.slotCount),
                       "migration authored the container, so it knows every slot")
        XCTAssertEqual(migrated.blob.allocatedSlots, [try XCTUnwrap(migrated.slotIndex)])
        XCTAssertFalse(migrated.freeSlots.isEmpty,
                       "so there is room for a decoy — this is what was empty")
    }
}

/// Minimal stand-in for the wrong-password counter, so the burn test can prove
/// §A1's count survives without reaching into another test case's fixtures.

final class BurnCounterSpy: UnlockFailureStoring {
    private var n = 0
    func failures() -> Int { n }
    func setFailures(_ value: Int) { n = value }
}
