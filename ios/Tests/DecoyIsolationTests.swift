import XCTest
import CryptoKit
@testable import PyroChat

/// docs/DECOY-ACCOUNT.md §6 — "What must never leak". Each numbered channel
/// there is a real channel, not a hypothetical, and §9 requires the assertion
/// to be on "the session's reachable surface, not on the UI".
final class DecoyIsolationTests: XCTestCase {

    private var work: URL!
    private var prevSalt: Data?
    private var prevBacking: BiometricStoreBacking!

    override func setUpWithError() throws {
        work = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-iso-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        prevSalt = KeychainHelper.get("kdf-salt")
        KeychainHelper.set(Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 11) }), key: "kdf-salt")
        prevBacking = BiometricUnlock.backing
    }

    override func tearDownWithError() throws {
        BiometricUnlock.backing = prevBacking
        SlotVault.chooseSlot = { $0.randomElement()! }
        SlotVault.onHalfAttempt = nil
        if let prevSalt { KeychainHelper.set(prevSalt, key: "kdf-salt") }
        else { KeychainHelper.delete("kdf-salt") }
        try? FileManager.default.removeItem(at: work)
    }

    private func url(_ name: String = "pyro.store") -> URL { work.appendingPathComponent(name) }

    /// A container holding a real account in slot 0 and a decoy in slot 2,
    /// with slot 1 left as the noise it was authored with.
    private func twoAccounts(at u: URL) throws -> (real: SecureStore, decoy: SecureStore) {
        SlotVault.chooseSlot = { _ in 0 }
        let real = SecureStore(creatingWithPassword: "real-password-11", at: u)
        real.update { $0.usernameDisplay = "realuser" }
        real.flush()
        SlotVault.chooseSlot = { $0.randomElement()! }
        let decoy = try SecureStore(creatingDecoyWithPassword: "decoy-password-11",
                                    inSlot: 2, at: u)
        decoy.update { $0.usernameDisplay = "decoyuser" }
        decoy.flush()
        real.noteAllocated(slot: 2)
        return (real, decoy)
    }

    // MARK: - §6.1, the keychain count that must never grow

    /// §6.1: "the device holds a fixed set of keychain items whose number is
    /// identical on every install, whatever it contains." The thing that would
    /// otherwise multiply is the biometric key, and this asserts that neither
    /// a second ACCOUNT nor a second biometric enable adds an item.
    func testNoAccountAddsAKeychainItem() throws {
        let u = url()
        let roster = Self.roster
        let before = DecoyAllocationTests.keychainAccounts()
        XCTAssertTrue(before.isSubset(of: roster),
                      "an item outside the pinned roster appeared: \(before.subtracting(roster))")

        let pair = try twoAccounts(at: u)
        let afterTwoAccounts = DecoyAllocationTests.keychainAccounts()
        XCTAssertEqual(afterTwoAccounts, before,
                       "a second account on the device must add nothing to the keychain")

        let fake = FakeBiometrics()
        BiometricUnlock.backing = fake
        _ = fake.storeKey(pair.real.rawKey)
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.real.rawKey))
        XCTAssertEqual(fake.itemCount, 2, "one stored key and one tag, and there is nowhere for a third")

        // enabling it in the OTHER account replaces, never adds
        _ = fake.storeKey(pair.decoy.rawKey)
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.decoy.rawKey))
        XCTAssertEqual(fake.itemCount, 2, "§6.1: one keychain item, one stored key, no count that grows")
    }

    /// §6.1: "the phone can unlock exactly one slot by face… The `[cfg]` row
    /// then shows the truth for the account you are in: on if this slot is the
    /// one the phone holds, off otherwise."
    ///
    /// Asserted through `isEnabled(forStoreKey:)`, which is what the row reads
    /// — not through the row itself, which is interface.
    func testBiometricUnlockIsSingularAndTheRowTellsTheTruthPerSlot() throws {
        let pair = try twoAccounts(at: url())
        let fake = FakeBiometrics()
        BiometricUnlock.backing = fake

        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: pair.real.rawKey))
        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: pair.decoy.rawKey))

        // enabled in the real account
        XCTAssertTrue(fake.storeKey(pair.real.rawKey))
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.real.rawKey))
        XCTAssertTrue(BiometricUnlock.isEnabled(forStoreKey: pair.real.rawKey),
                      "on for the account that enabled it")
        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: pair.decoy.rawKey),
                       "off inside the decoy, which is an ordinary state and not a tell")
        XCTAssertTrue(BiometricUnlock.isEnabled,
                      "§6.3: the device-level question the UNLOCK screen asks stays slot-blind")

        // enabling it in the decoy REPLACES whatever was there
        XCTAssertTrue(fake.storeKey(pair.decoy.rawKey))
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.decoy.rawKey))
        XCTAssertTrue(BiometricUnlock.isEnabled(forStoreKey: pair.decoy.rawKey))
        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: pair.real.rawKey),
                       "two slots each claiming a stored key is the leak this removes")
        XCTAssertEqual(fake.itemCount, 2)
    }

    /// §6.1: the tag says WHICH slot without saying anything else. It must not
    /// be the key, must not be derivable back to it, and must be stable.
    func testTheSlotTagRevealsNothingAboutTheKey() throws {
        let pair = try twoAccounts(at: url())
        let tag = BiometricUnlock.slotTag(forStoreKey: pair.real.rawKey)
        XCTAssertEqual(tag.count, BiometricUnlock.slotTagBytes)
        XCTAssertNotEqual(tag, pair.real.rawKey)
        XCTAssertFalse(tag.contains(pair.real.rawKey.prefix(8)))
        XCTAssertEqual(tag, BiometricUnlock.slotTag(forStoreKey: pair.real.rawKey), "stable")
        XCTAssertNotEqual(tag, BiometricUnlock.slotTag(forStoreKey: pair.decoy.rawKey))
    }

    /// §5: a decoy's burn takes the real account's stored key too — the
    /// OPPOSITE of what this test asserted when it was written.
    ///
    /// It used to protect a kindness: the phone unlocks one slot by face, so
    /// why should burning a different slot disable it? Because a device that
    /// still raises a face prompt at launch has something left, and a device
    /// with nothing does not. Someone made to wipe, who then reopens the app,
    /// reads the answer off the launch screen. The kindness WAS the aftermath
    /// leak, arriving through the one piece of state §5 had exempted.
    ///
    /// The cost is re-enabling face unlock after burning a decoy. Kept as a
    /// test rather than deleted, because the old behaviour is the intuitive
    /// one and will look like a bug fix to whoever meets it next.
    func testBurningTheDecoyTakesTheBiometricKeyEvenThoughItIsNotItsOwn() throws {
        let u = url()
        let pair = try twoAccounts(at: u)
        let fake = FakeBiometrics()
        BiometricUnlock.backing = fake
        XCTAssertTrue(fake.storeKey(pair.real.rawKey))
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.real.rawKey))
        XCTAssertTrue(BiometricUnlock.isEnabled(forStoreKey: pair.real.rawKey),
                      "precondition: the phone holds the REAL account's key")

        _ = pair.decoy.burnCurrentSlot()

        XCTAssertNil(fake.key, "the burn took a key belonging to a slot it never touched")
        XCTAssertFalse(BiometricUnlock.isEnabled(forStoreKey: pair.real.rawKey),
                       "so the launch screen after a burn looks the same as after a wipe")
    }

    /// …and the opposite: burning the slot the phone DOES hold takes the key
    /// with it, or the store would stay openable by a face after it was wiped.
    func testBurningTheSlotThePhoneHoldsTakesTheStoredKey() throws {
        let u = url()
        let pair = try twoAccounts(at: u)
        let fake = FakeBiometrics()
        BiometricUnlock.backing = fake
        XCTAssertTrue(fake.storeKey(pair.decoy.rawKey))
        fake.setSlotTag(BiometricUnlock.slotTag(forStoreKey: pair.decoy.rawKey))

        _ = pair.decoy.burnCurrentSlot()

        XCTAssertNil(fake.key, "a wipe that left it behind would leave the store openable by a face")
        XCTAssertFalse(BiometricUnlock.isEnabled)
    }

    // MARK: - §6, the session's reachable surface

    /// §9: "A decoy session cannot read, name, count or wipe another slot."
    /// Every question the session object can be asked, asked.
    func testADecoySessionCannotReadNameCountOrWipeAnotherSlot() throws {
        let u = url()
        let pair = try twoAccounts(at: u)
        let decoy = try SecureStore(unlockingWithPassword: "decoy-password-11", at: u)

        // read: its blob is its own, entirely
        XCTAssertEqual(decoy.blob.usernameDisplay, "decoyuser")
        XCTAssertTrue(decoy.blob.contacts.isEmpty)
        XCTAssertNil(decoy.blob.messages["realuser"])

        // name: it knows one index, and that index is its own
        XCTAssertEqual(decoy.blob.knownSlots, [2])
        XCTAssertEqual(decoy.slotIndex, 2)

        // count: nothing it can be asked reports how many slots are real
        XCTAssertEqual(decoy.freeSlots, [])
        XCTAssertFalse(decoy.hasAllocatedDecoy)

        // and the other account's key is not derivable from anything it holds
        XCTAssertNotEqual(decoy.rawKey, pair.real.rawKey)

        // wipe: its burn narrows, and the real slot opens afterwards
        XCTAssertFalse(decoy.burnCurrentSlot())
        XCTAssertNoThrow(try SecureStore(unlockingWithPassword: "real-password-11", at: u))
    }

    /// §4: "Nothing shared is writable across slots. Settings, themes, text
    /// size… are all per-slot. A theme chosen in the real account and visible
    /// in the decoy is a leak." §9 names this one directly.
    func testNoSettingWrittenInOneSlotIsVisibleFromAnother() throws {
        let u = url()
        let pair = try twoAccounts(at: u)
        pair.real.update {
            $0.settings.theme = "amber"
            $0.settings.textSize = "largest"
            $0.settings.scanlines = false
            $0.settings.wipeAfterDays = 3
            $0.blockedHashes = ["deadbeef"]
        }
        pair.real.flush()

        let decoy = try SecureStore(unlockingWithPassword: "decoy-password-11", at: u)
        XCTAssertEqual(decoy.blob.settings.theme, AppSettings().theme)
        XCTAssertNil(decoy.blob.settings.textSize)
        XCTAssertTrue(decoy.blob.settings.scanlines)
        XCTAssertNil(decoy.blob.settings.wipeAfterDays)
        XCTAssertNil(decoy.blob.blockedHashes)

        // and back the other way
        decoy.update { $0.settings.theme = "ember"; $0.notes = [] }
        decoy.flush()
        let real = try SecureStore(unlockingWithPassword: "real-password-11", at: u)
        XCTAssertEqual(real.blob.settings.theme, "amber", "the real account kept its own")
        XCTAssertEqual(real.blob.settings.textSize, "largest")
    }

    /// §2: "Try that key against every slot, always all of them, in order."
    /// A container with two real accounts must not short-circuit on the first
    /// match — §9 asks this be asserted through the attempt log rather than by
    /// timing, so a loaded machine cannot make it flaky.
    func testUnlockingEitherAccountStillAttemptsAllSixHalves() throws {
        let u = url()
        _ = try twoAccounts(at: u)
        for password in ["real-password-11", "decoy-password-11", "not-a-password"] {
            var attempts: [String] = []
            SlotVault.onHalfAttempt = { attempts.append("\($0).\($1)") }
            _ = try? SecureStore(unlockingWithPassword: password, at: u)
            SlotVault.onHalfAttempt = nil
            XCTAssertEqual(attempts,
                           (0..<SlotVault.slotCount).flatMap { s in (0..<2).map { "\(s).\($0)" } },
                           "\(password): all six, always, in order")
        }
    }

    /// §6.4: "An encrypted backup covers the slot that made it and says
    /// nothing about the others." Exporting from the decoy must produce a file
    /// that is unremarkable on its own.
    func testABackupFromOneSlotCarriesNothingOfAnother() throws {
        let u = url()
        let pair = try twoAccounts(at: u)
        pair.real.update { $0.contacts = [Contact(hash: String(repeating: "a", count: 64),
                                                  name: "someone-real", addedAt: Date())] }
        pair.real.flush()

        let decoy = try SecureStore(unlockingWithPassword: "decoy-password-11", at: u)
        let bytes = try BackupService.export(from: decoy, passphrase: "backup-passphrase-1")
        XCTAssertNil(bytes.range(of: Data("someone-real".utf8)),
                     "an exported decoy backup must carry nothing of the other slot")
        let restored = try BackupService.decode(bytes, passphrase: "backup-passphrase-1")
        XCTAssertEqual(restored.blob.usernameDisplay, "decoyuser")
        XCTAssertTrue(restored.blob.contacts.isEmpty)

        // §1: a RESTORE authors a fresh container of its own, so the record it
        // ends up with is the author's — whole — not the decoy's one index
        // carried over. A restored decoy is simply an account, on a new device.
        let elsewhere = work.appendingPathComponent("restored.store")
        let landed = SecureStore(restoring: restored.blob, attachments: restored.attachments,
                                 password: "restored-password-1", at: elsewhere)
        landed.flush()
        XCTAssertEqual(landed.blob.knownSlots, Array(0..<SlotVault.slotCount),
                       "§1: an author writes the record out explicitly, because absent fails closed")
        XCTAssertEqual(landed.blob.allocatedSlots, [try XCTUnwrap(landed.slotIndex)])
    }

    // MARK: helpers

    /// §6.1's pinned roster. The point of the test is that this list never
    /// gains an entry per account — add to it only for something genuinely
    /// device-level, and say so in `docs/DECOY-ACCOUNT.md`.
    static let roster: Set<String> = [
        "kdf-salt",                  // shared; readable before any unlock
        "duress-verifier",           // device-level; acts on every slot
        "unlock-failures",           // device-level; a wrong password names no account
        "biometric-store-key",       // ONE stored key, for ONE slot
        "biometric-armed",           // the breadcrumb, and §6.1's slot tag
        "biometric-last-password",
        "biometric-failures",
    ]

    /// In-memory stand-in that can COUNT what it holds — the one question
    /// §6.1 is actually about.
    final class FakeBiometrics: BiometricStoreBacking {
        var key: Data?
        var tag: Data?
        var isArmed = false
        var last: Date?
        var failureCount = 0
        /// What an examiner would count.
        var itemCount: Int { [key, tag].compactMap { $0 }.count }

        func storeKey(_ k: Data) -> Bool { guard k.count == 32 else { return false }; key = k; return true }
        func keyExists() -> Bool { key != nil }
        func deleteKey() { key = nil }
        func armed() -> Bool { isArmed || tag != nil }
        func setArmed(_ on: Bool) { isArmed = on; if !on { tag = nil } }
        func slotTag() -> Data? { tag }
        func setSlotTag(_ t: Data?) { tag = t; if t != nil { isArmed = true } }
        func lastPasswordUnlock() -> Date? { last }
        func setLastPasswordUnlock(_ d: Date?) { last = d }
        func failures() -> Int { failureCount }
        func setFailures(_ n: Int) { failureCount = max(0, n) }
    }
}

// MARK: - §5 + §6.1, the dead-man switch across slots

/// docs/DECOY-ACCOUNT.md §5: "dead-man switch — unchanged: wipes **every**
/// slot", and §6.1: it is device-level and stays that way.
///
/// Device-level cuts both ways, and the second way is the dangerous one: a
/// session that has no dead-man of its own must not be able to turn off one
/// the device is carrying. Otherwise the switch whose entire purpose is to
/// fire when the phone is taken is disabled by exactly the session a person is
/// made to open.
final class DeadmanAcrossSlotsTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suite: String!

    override func setUpWithError() throws {
        suite = "pyro-deadman-\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suite)
    }

    private var deadline: Date? {
        let raw = defaults.double(forKey: DeadmanSwitch.key)
        return raw > 0 ? Date(timeIntervalSince1970: raw) : nil
    }

    /// The bug, named: opening an account with no dead-man of its own used to
    /// clear the one the device was carrying.
    func testASessionWithNoDeadmanCannotDisarmTheDevicesOne() throws {
        let armed = Date(timeIntervalSince1970: 1_800_000_000)
        DeadmanSwitch.arm(days: 7, now: armed, defaults: defaults)
        let original = try XCTUnwrap(deadline)

        // the decoy opens: its own setting is nil
        let later = armed.addingTimeInterval(86_400)
        DeadmanSwitch.notePresence(days: nil, now: later, defaults: defaults)

        let after = try XCTUnwrap(deadline, "the deadline must still exist")
        XCTAssertGreaterThan(after, original,
                             "presence in ANY slot pushes it forward — an actively used device never wipes itself")
        XCTAssertEqual(after.timeIntervalSince(later), 7 * 86_400, accuracy: 1,
                       "…by the interval that armed it, not by the open account's (absent) one")
    }

    /// And a device with no dead-man at all stays that way: presence must not
    /// invent one.
    func testPresenceOnADeviceWithNoDeadmanArmsNothing() {
        DeadmanSwitch.notePresence(days: nil, defaults: defaults)
        XCTAssertNil(deadline)
    }

    /// The `[cfg]` row is still an explicit choice, and off still means off —
    /// device-level and singular, the same shape §6.1 gives biometric unlock.
    func testTheSettingRowStillTurnsItOff() throws {
        DeadmanSwitch.arm(days: 30, defaults: defaults)
        XCTAssertNotNil(deadline)
        DeadmanSwitch.arm(days: nil, defaults: defaults)
        XCTAssertNil(deadline)
        XCTAssertEqual(defaults.integer(forKey: DeadmanSwitch.daysKey), 0,
                       "and the interval goes with it")
    }

    /// A deadline left by an older build has no interval beside it. Presence
    /// must leave it exactly as it is rather than guess — the switch fires
    /// when it was always going to.
    func testADeadlineFromAnOlderBuildIsLeftAlone() throws {
        let when = Date().addingTimeInterval(5 * 86_400)
        defaults.set(when.timeIntervalSince1970, forKey: DeadmanSwitch.key)
        DeadmanSwitch.notePresence(days: nil, defaults: defaults)
        XCTAssertEqual(try XCTUnwrap(deadline).timeIntervalSince1970,
                       when.timeIntervalSince1970, accuracy: 1)
    }

    /// §5: when it does fire it still takes everything, and leaves no
    /// future-dated artifact behind.
    func testFiringClearsBothValues() {
        DeadmanSwitch.arm(days: 1, now: Date(timeIntervalSince1970: 1_000_000), defaults: defaults)
        XCTAssertTrue(DeadmanSwitch.fireIfExpired(now: Date(), defaults: defaults))
        XCTAssertNil(deadline)
        XCTAssertEqual(defaults.integer(forKey: DeadmanSwitch.daysKey), 0)
    }
}
