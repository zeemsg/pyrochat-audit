import XCTest
import Security
import CryptoKit
import LocalAuthentication
@testable import PyroChat

/// docs/BIOMETRIC-UNLOCK.md §7.
///
/// The §4 policy is a pure function and is hammered here: both sides of the
/// 7-day boundary, the third failure, reset on success, invalidation, the
/// clock moving backwards. Everything else is exercised through an in-memory
/// backing, except the one thing the spec insists must be measured against
/// the real Keychain: that `KeychainHelper.wipeAll()` reaches an item
/// carrying an access control.
final class BiometricUnlockTests: XCTestCase {

    // MARK: - §4 policy (pure)

    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    func testPolicyAllowsBiometryInsideTheWindow() {
        for elapsed in [0, 1, 60, 86_400, 6 * 86_400, 7 * 86_400 - 1] {
            XCTAssertEqual(
                BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                       invalidated: false,
                                       now: t0.addingTimeInterval(Double(elapsed))),
                .allowBiometry, "elapsed \(elapsed)s must still allow biometry")
        }
    }

    func testPolicySevenDayBoundaryBothSides() {
        let week = BiometricPolicy.passwordInterval
        XCTAssertEqual(week, 7 * 86_400)
        // one second short: still fine
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: false, now: t0.addingTimeInterval(week - 1)),
            .allowBiometry)
        // exactly 7 days: the password is due
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: false, now: t0.addingTimeInterval(week)),
            .requirePassword(.interval))
        // and beyond
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: false, now: t0.addingTimeInterval(week + 1)),
            .requirePassword(.interval))
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: false, now: t0.addingTimeInterval(365 * 86_400)),
            .requirePassword(.interval))
    }

    func testPolicyThirdFailureRequiresPasswordAndTwoDoNot() {
        for n in 0..<BiometricPolicy.failureLimit {
            XCTAssertEqual(
                BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: n,
                                       invalidated: false, now: t0),
                .allowBiometry, "\(n) failures must not lock biometry out")
        }
        for n in [3, 4, 99] {
            XCTAssertEqual(
                BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: n,
                                       invalidated: false, now: t0),
                .requirePassword(.failures), "\(n) failures must require the password")
        }
    }

    func testPolicyInvalidationBeatsEverything() {
        // fresh password, zero failures — invalidation still wins
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: true, now: t0),
            .requirePassword(.invalidated))
        // and it is reported as invalidation, not as "too many failures"
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 9,
                                   invalidated: true, now: t0.addingTimeInterval(1e7)),
            .requirePassword(.invalidated))
    }

    func testPolicyFailsClosedWithNoRecordAndOnABackwardsClock() {
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: nil, consecutiveFailures: 0,
                                   invalidated: false, now: t0),
            .requirePassword(.unknownLastPassword))
        // the wall clock moved backwards while we were away: fail CLOSED,
        // exactly as auto-lock does
        XCTAssertEqual(
            BiometricPolicy.decide(lastPasswordUnlock: t0, consecutiveFailures: 0,
                                   invalidated: false, now: t0.addingTimeInterval(-1)),
            .requirePassword(.interval))
    }

    /// The counter resets on any successful unlock of either kind (§4).
    func testFailureCounterResetsOnEitherKindOfSuccess() {
        let fake = FakeBacking()
        withBacking(fake) {
            BiometricUnlock.notePasswordUnlock(now: t0)
            XCTAssertEqual(BiometricUnlock.noteFailure(), 1)
            XCTAssertEqual(BiometricUnlock.noteFailure(), 2)
            XCTAssertEqual(BiometricUnlock.decision(now: t0), .allowBiometry)
            XCTAssertEqual(BiometricUnlock.noteFailure(), 3)
            XCTAssertEqual(BiometricUnlock.decision(now: t0), .requirePassword(.failures))

            // a biometric success clears the count but NOT the 7-day clock
            BiometricUnlock.noteBiometricSuccess()
            XCTAssertEqual(BiometricUnlock.consecutiveFailures, 0)
            XCTAssertEqual(BiometricUnlock.lastPasswordUnlock, t0)
            XCTAssertEqual(BiometricUnlock.decision(now: t0), .allowBiometry)

            // …so a biometric success cannot extend the week
            let week = BiometricPolicy.passwordInterval
            XCTAssertEqual(BiometricUnlock.decision(now: t0.addingTimeInterval(week)),
                           .requirePassword(.interval))

            // a typed password restarts it and clears failures
            _ = BiometricUnlock.noteFailure()
            BiometricUnlock.notePasswordUnlock(now: t0.addingTimeInterval(week))
            XCTAssertEqual(BiometricUnlock.consecutiveFailures, 0)
            XCTAssertEqual(BiometricUnlock.decision(now: t0.addingTimeInterval(week)),
                           .allowBiometry)
        }
    }

    // MARK: - §7 enable / disable / destroy semantics

    func testEnableStoresExactlyThirtyTwoBytesAndNoPasswordMaterial() {
        let fake = FakeBacking()
        withBacking(fake) {
            let password = "correct horse battery staple"
            let salt = Data(repeating: 0xAB, count: 32)
            let key = SecureStore.deriveKey(password: password, salt: salt)
            let raw = key.withUnsafeBytes { Data($0) }

            XCTAssertTrue(fake.storeKey(raw), "the backing must accept the key")
            XCTAssertEqual(fake.key?.count, 32, "exactly the 32-byte store key")
            XCTAssertEqual(fake.key, raw)
            // nothing derived from, or containing, the password
            let stored = fake.everything
            XCTAssertFalse(stored.contains(Data(password.utf8)))
            XCTAssertFalse(stored.contains(salt))
            XCTAssertEqual(stored.count, 1, "the key is the only protected item")
        }
    }

    func testEnableRefusesAnythingThatIsNotTheStoreKey() async {
        let fake = FakeBacking()
        BiometricUnlock.backing = fake
        defer { BiometricUnlock.backing = KeychainBiometricStore() }
        // 31 bytes, 33 bytes, a password: never stored, never prompted
        for junk in [Data(repeating: 1, count: 31), Data(repeating: 1, count: 33),
                     Data("hunter2".utf8)] {
            let ok = await BiometricUnlock.enable(storeKey: junk)
            XCTAssertFalse(ok)
            XCTAssertNil(fake.key)
        }
    }

    func testDisableDeletesTheKeyAndTheToggleFollowsIt() {
        let fake = FakeBacking()
        withBacking(fake) {
            _ = fake.storeKey(Data(repeating: 7, count: 32))
            fake.setArmed(true)
            XCTAssertTrue(BiometricUnlock.isEnabled)

            BiometricUnlock.destroy()
            XCTAssertFalse(BiometricUnlock.isEnabled)
            XCTAssertNil(fake.key)
            XCTAssertFalse(fake.armed(), "the breadcrumb goes with it — off is off")
            XCTAssertFalse(BiometricUnlock.wasInvalidated)
        }
    }

    /// §2/§3: the key vanishing under us (the OS destroying a
    /// `.biometryCurrentSet` item) must read as "biometrics changed", not as
    /// "the user turned it off".
    func testKeyDestroyedByTheOSReadsAsInvalidated() {
        let fake = FakeBacking()
        withBacking(fake) {
            _ = fake.storeKey(Data(repeating: 7, count: 32))
            fake.setArmed(true)
            fake.key = nil                       // the OS threw it away
            XCTAssertFalse(BiometricUnlock.isEnabled)
            XCTAssertTrue(BiometricUnlock.wasInvalidated)
            XCTAssertEqual(BiometricUnlock.decision(now: t0),
                           .requirePassword(.invalidated))
            BiometricUnlock.destroy()
            XCTAssertFalse(BiometricUnlock.wasInvalidated)
        }
    }

    // MARK: - §5 destruction paths, against the REAL Keychain

    /// The spec's explicit demand: prove `wipeAll()` reaches an item that
    /// carries an access control — do not assume it.
    func testWipeAllRemovesAnAccessControlledItem() throws {
        try XCTSkipUnless(Self.canWriteBiometricItems,
                          "no biometric enrolment on this device: the OS refuses to create the item at all")
        let key = Data(repeating: 0x5A, count: 32)
        XCTAssertTrue(KeychainHelper.setBiometric(key, key: BiometricUnlock.keyItem))
        XCTAssertTrue(KeychainHelper.exists(BiometricUnlock.keyItem),
                      "presence must be readable without a prompt")
        // an ordinary item alongside it, so we also see the sweep working
        KeychainHelper.set(Data([1]), key: "wipe-canary")

        KeychainHelper.wipeAll()

        XCTAssertFalse(KeychainHelper.exists(BiometricUnlock.keyItem),
                       "wipeAll() left the biometric key behind — a duress wipe would not be a wipe")
        XCTAssertNil(KeychainHelper.get("wipe-canary"))

        // …and the measurement behind wipeAll's comment: a bare class+service
        // delete DOES reach an access-controlled item (deletion never
        // authenticates), which is why no prompt can appear during a wipe.
        XCTAssertTrue(KeychainHelper.setBiometric(key, key: BiometricUnlock.keyItem))
        let bare: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "ca.pyrochat.app",
        ]
        XCTAssertEqual(SecItemDelete(bare as CFDictionary), errSecSuccess)
        XCTAssertFalse(KeychainHelper.exists(BiometricUnlock.keyItem))
    }

    /// obliterate() (TOTAL WIPE, the duress PIN and the dead-man switch all
    /// run through it) must take the biometric key with everything else.
    func testObliterateTakesTheBiometricKey() throws {
        let fake = FakeBacking()
        withBacking(fake) {
            _ = fake.storeKey(Data(repeating: 3, count: 32))
            fake.setArmed(true)
            fake.setLastPasswordUnlock(t0)
            SecureStore.obliterate()
            XCTAssertNil(fake.key, "obliterate must destroy the stored key first")
            XCTAssertFalse(fake.armed())
        }
        // and for real: the item is gone from the actual Keychain too
        if Self.canWriteBiometricItems {
            XCTAssertTrue(KeychainHelper.setBiometric(Data(repeating: 9, count: 32),
                                                      key: BiometricUnlock.keyItem))
            SecureStore.obliterate()
            XCTAssertFalse(KeychainHelper.exists(BiometricUnlock.keyItem))
        }
    }

    /// The plain items must be readable BEFORE anything is decrypted, and
    /// must survive nothing but a wipe.
    func testPolicyItemsRoundTripThroughTheRealKeychain() {
        let real = KeychainBiometricStore()
        defer { real.setLastPasswordUnlock(nil); real.setFailures(0); real.setArmed(false) }
        let when = Date(timeIntervalSince1970: 1_712_345_678)
        real.setLastPasswordUnlock(when)
        XCTAssertEqual(real.lastPasswordUnlock()?.timeIntervalSince1970 ?? 0,
                       when.timeIntervalSince1970, accuracy: 0.001)
        real.setFailures(2)
        XCTAssertEqual(real.failures(), 2)
        real.setFailures(0)
        XCTAssertEqual(real.failures(), 0, "zero is stored as absence, not as \"0\"")
        real.setArmed(true)
        XCTAssertTrue(real.armed())

        KeychainHelper.wipeAll()
        XCTAssertNil(real.lastPasswordUnlock())
        XCTAssertEqual(real.failures(), 0)
        XCTAssertFalse(real.armed())
    }

    // MARK: - §1 the key really is the key

    /// A password change replaces the stored key: the OLD key must no longer
    /// open the store, and the new one must.
    func testStoreOpensFromTheRawKeyAndARekeyInvalidatesTheOldOne() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-bio-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("store")

        let store = SecureStore(creatingWithPassword: "biometric-pass-123", at: url)
        store.update { $0.usernameDisplay = "zeebest" }
        store.flush()
        let raw = store.rawKey
        XCTAssertEqual(raw.count, 32)

        let reopened = try SecureStore(unlockingWithKey: raw, at: url)
        XCTAssertEqual(reopened.blob.usernameDisplay, "zeebest")
        XCTAssertEqual(reopened.rawKey, raw)

        // a key of the wrong size is refused outright
        XCTAssertThrowsError(try SecureStore(unlockingWithKey: Data(repeating: 0, count: 16),
                                             at: url))
        // the wrong 32 bytes fail GCM authentication, same as a wrong password
        XCTAssertThrowsError(try SecureStore(unlockingWithKey: Data(repeating: 0, count: 32),
                                             at: url))

        // "a password change replaces it": the store resealed under a new
        // password no longer opens with the key biometry was holding.
        //
        // docs/DECOY-ACCOUNT.md §1 made a restore take ONE slot of the
        // container rather than replace the whole file, so the slot is pinned
        // here — otherwise this would be asserting a two-in-three coin flip
        // rather than the resealing it is about.
        let occupied = try XCTUnwrap(store.slotIndex)
        SlotVault.chooseSlot = { _ in occupied }
        defer { SlotVault.chooseSlot = { $0.randomElement()! } }
        let rekeyed = SecureStore(restoring: store.blob, attachments: [:],
                                  password: "a-different-password-456", at: url)
        rekeyed.flush()
        XCTAssertNotEqual(rekeyed.rawKey, raw)
        XCTAssertThrowsError(try SecureStore(unlockingWithKey: raw, at: url)) { error in
            XCTAssertEqual(error as? SecureStore.StoreError, .badPassword)
        }
        XCTAssertNoThrow(try SecureStore(unlockingWithKey: rekeyed.rawKey, at: url))
    }

    // MARK: - §2/§3 labels, verbatim

    func testLabelsAndFootnoteAreTheSpecsWords() {
        XCTAssertEqual(BiometricUnlock.rowLabel(.faceID), "unlock with face id")
        XCTAssertEqual(BiometricUnlock.rowLabel(.touchID), "unlock with touch id")
        XCTAssertEqual(BiometricUnlock.buttonLabel(.faceID), "[ UNLOCK WITH FACE ID ]")
        XCTAssertEqual(BiometricUnlock.buttonLabel(.touchID), "[ UNLOCK WITH TOUCH ID ]")
        XCTAssertEqual(BiometricUnlock.footnote,
            "a face or a finger can be taken from you; a password in your head cannot. "
            + "the app still asks for your password every 7 days, after three failed attempts, "
            + "and whenever you add or remove a face or fingerprint.")
    }

    // MARK: - helpers

    /// Can this device create a `.biometryCurrentSet` item at all? (A
    /// simulator with nothing enrolled, or a phone with no passcode, cannot.)
    static let canWriteBiometricItems: Bool = {
        let probe = "biometric-probe-\(UUID().uuidString)"
        defer { KeychainHelper.delete(probe) }
        return KeychainHelper.setBiometric(Data(repeating: 0, count: 32), key: probe)
    }()

    private func withBacking(_ backing: BiometricStoreBacking, _ body: () -> Void) {
        let previous = BiometricUnlock.backing
        BiometricUnlock.backing = backing
        defer { BiometricUnlock.backing = previous }
        body()
    }

    /// In-memory stand-in for the Keychain: lets the suite exercise every
    /// enable/disable/wipe path without a biometric prompt.
    final class FakeBacking: BiometricStoreBacking {
        var key: Data?
        var isArmed = false
        var last: Date?
        var failureCount = 0
        /// docs/DECOY-ACCOUNT.md §6.1 — the one value that says which slot the
        /// phone opens. Singular by construction: there is nowhere to put a
        /// second one.
        var tag: Data?

        /// Everything this backing is protecting — used to prove no password
        /// material is anywhere near it.
        var everything: [Data] { [key].compactMap { $0 } }

        func storeKey(_ key: Data) -> Bool {
            guard key.count == 32 else { return false }
            self.key = key
            return true
        }
        func keyExists() -> Bool { key != nil }
        func deleteKey() { key = nil }
        func armed() -> Bool { isArmed || tag != nil }
        func setArmed(_ on: Bool) { isArmed = on; if !on { tag = nil } }
        func slotTag() -> Data? { tag }
        func setSlotTag(_ t: Data?) { tag = t; if t != nil { isArmed = true } }
        func lastPasswordUnlock() -> Date? { last }
        func setLastPasswordUnlock(_ date: Date?) { last = date }
        func failures() -> Int { failureCount }
        func setFailures(_ n: Int) { failureCount = max(0, n) }
    }

    // MARK: the prompt storm (docs/BIOMETRIC-UNLOCK.md §0)

    /// Presence must be answerable from metadata alone. `exists` asks with the
    /// prompt suppressed, so the one item that matters comes back as
    /// `errSecInteractionNotAllowed` ("there, but not without a face") rather
    /// than as a match. Reading that as "missing" is what would be dangerous:
    /// `wasInvalidated` would go true and the app would destroy a good key.
    func testPresenceTreatsInteractionNotAllowedAsPresent() {
        XCTAssertTrue(KeychainHelper.presenceMeansExists(errSecSuccess))
        XCTAssertTrue(KeychainHelper.presenceMeansExists(errSecInteractionNotAllowed),
                      "an item that exists but needs a face still exists")
        XCTAssertFalse(KeychainHelper.presenceMeansExists(errSecItemNotFound))
        // errSecItemNotFound is also what `kSecUseAuthenticationUISkip` would
        // return for our item, which is exactly why the query must not use it.
    }
}
