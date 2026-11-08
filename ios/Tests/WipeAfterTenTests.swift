import XCTest
@testable import PyroChat

/// docs/SCRUB-AND-WIPE.md §A4 — the policy, the counter, and the wipe.
final class WipePolicyTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    /// §A4: "covering 0, 6, 7, 8, 9, 10 and above."
    func testDecisionAcrossTheWholeRange() {
        XCTAssertEqual(WipePolicy.limit, 10)
        XCTAssertEqual(WipePolicy.warnFrom, 7)
        for n in 0...6 {
            XCTAssertEqual(WipePolicy.decide(failures: n, now: t0), .allow,
                           "\(n) failures must say nothing at all")
        }
        XCTAssertEqual(WipePolicy.decide(failures: 7, now: t0), .warn(3))
        XCTAssertEqual(WipePolicy.decide(failures: 8, now: t0), .warn(2))
        XCTAssertEqual(WipePolicy.decide(failures: 9, now: t0), .warn(1))
        for n in [10, 11, 12, 99, Int.max] {
            XCTAssertEqual(WipePolicy.decide(failures: n, now: t0), .wipe,
                           "\(n) failures must wipe")
        }
        // a counter that somehow went negative is not an invitation
        XCTAssertEqual(WipePolicy.decide(failures: -1, now: t0), .allow)
    }

    /// The rule is consecutive failures, not failures per hour: waiting buys
    /// an attacker nothing back.
    func testTimeNeverChangesTheDecision() {
        for offset: TimeInterval in [-86_400, 0, 1, 3_600, 365 * 86_400] {
            let now = t0.addingTimeInterval(offset)
            XCTAssertEqual(WipePolicy.decide(failures: 6, now: now), .allow)
            XCTAssertEqual(WipePolicy.decide(failures: 7, now: now), .warn(3))
            XCTAssertEqual(WipePolicy.decide(failures: 10, now: now), .wipe)
        }
    }

    /// §A2, verbatim, counting down 3, 2, 1.
    func testWarningLineIsTheSpecString() {
        XCTAssertEqual(WipePolicy.warning(failures: 7), "3 attempts left before this device wipes")
        XCTAssertEqual(WipePolicy.warning(failures: 8), "2 attempts left before this device wipes")
        XCTAssertEqual(WipePolicy.warning(failures: 9), "1 attempts left before this device wipes")
    }

    /// §A4: "The warning appears at seven and not at six." And never at ten:
    /// the wipe has already happened and the screen carries no trace of it.
    func testNoWarningBelowSevenOrAtTheWipe() {
        for n in 0...6 { XCTAssertNil(WipePolicy.warning(failures: n), "\(n) must be silent") }
        for n in [10, 11, 40] { XCTAssertNil(WipePolicy.warning(failures: n), "\(n): no trace") }
    }
}

// MARK: - the counter

final class UnlockFailureCounterTests: XCTestCase {

    final class FakeCounter: UnlockFailureStoring {
        var n = 0
        func failures() -> Int { n }
        func setFailures(_ value: Int) { n = value }
    }

    private func withCounter(_ backing: UnlockFailureStoring, _ body: () -> Void) {
        let previous = UnlockFailures.backing
        UnlockFailures.backing = backing
        defer { UnlockFailures.backing = previous }
        body()
    }

    func testNoteAndReset() {
        let fake = FakeCounter()
        withCounter(fake) {
            XCTAssertEqual(UnlockFailures.count, 0)
            XCTAssertEqual(UnlockFailures.note(), 1)
            XCTAssertEqual(UnlockFailures.note(), 2)
            XCTAssertEqual(UnlockFailures.count, 2)
            UnlockFailures.reset()
            XCTAssertEqual(UnlockFailures.count, 0)
            for _ in 0..<7 { UnlockFailures.note() }
            XCTAssertEqual(UnlockFailures.warning, "3 attempts left before this device wipes")
            UnlockFailures.note()
            XCTAssertEqual(UnlockFailures.warning, "2 attempts left before this device wipes")
            for _ in 0..<2 { UnlockFailures.note() }
            XCTAssertEqual(UnlockFailures.decision(), .wipe)
        }
    }

    /// §A1: it lives OUTSIDE the encrypted store, in the Keychain item the
    /// spec names, and it survives a restart — which for a Keychain item means
    /// a fresh reader sees what the last writer wrote.
    func testTheRealKeychainItemSurvivesARestart() {
        XCTAssertEqual(UnlockFailures.item, "unlock-failures")
        let real = KeychainUnlockFailures()
        defer { real.setFailures(0) }
        real.setFailures(0)
        XCTAssertNil(KeychainHelper.get(UnlockFailures.item), "zero is stored as absence")
        real.setFailures(4)
        // a brand-new reader = the next launch of the app
        XCTAssertEqual(KeychainUnlockFailures().failures(), 4)
        XCTAssertEqual(String(data: KeychainHelper.get(UnlockFailures.item) ?? Data(),
                              encoding: .utf8), "4")
        real.setFailures(0)
        XCTAssertEqual(KeychainUnlockFailures().failures(), 0)
    }

    /// §A1: "A failed biometric attempt never touches it; that has its own
    /// rule … and the two counters are separate."
    func testBiometricFailuresAndWipeFailuresAreSeparateCounters() {
        let counter = FakeCounter()
        let bio = BiometricUnlockTests.FakeBacking()
        let previousBio = BiometricUnlock.backing
        BiometricUnlock.backing = bio
        defer { BiometricUnlock.backing = previousBio }
        withCounter(counter) {
            for _ in 0..<3 { BiometricUnlock.noteFailure() }
            XCTAssertEqual(BiometricUnlock.consecutiveFailures, 3)
            XCTAssertEqual(UnlockFailures.count, 0,
                           "three biometric failures must not move the wipe counter")
            XCTAssertEqual(UnlockFailures.decision(), .allow)

            for _ in 0..<9 { UnlockFailures.note() }
            XCTAssertEqual(BiometricUnlock.consecutiveFailures, 3,
                           "nine wrong passwords must not move the biometric counter")
            // §A1: a biometric SUCCESS resets the wipe counter (the app calls
            // both on that path) while the biometric counter has its own reset
            BiometricUnlock.noteBiometricSuccess()
            UnlockFailures.reset()
            XCTAssertEqual(UnlockFailures.count, 0)
            XCTAssertEqual(BiometricUnlock.consecutiveFailures, 0)
        }
    }
}

// MARK: - the whole thing, through the real AppModel and the real store

/// Uses the app's real store path, wiped before and after — the same shape the
/// other wipe tests use (FirstRunAppModelKeyTests, FeatureV04Tests).
@MainActor
final class WipeAfterTenAppModelTests: XCTestCase {
    private let password = "wipe-test-pass-123"
    private var model: AppModel?

    override func setUp() async throws {
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    override func tearDown() async throws {
        model?.service?.shutdown()
        model = nil
        SecureStore.obliterate()
        UnlockFailures.reset()
    }

    /// Everything a wipe is supposed to leave behind — nothing, except the
    /// container itself.
    ///
    /// docs/DECOY-ACCOUNT.md §5: **no path ever deletes it.** Wiped, never
    /// used, and holding two accounts are the same 12,582,925 bytes, which is
    /// what stops the screen after a narrowed burn from saying which happened.
    /// So a wipe is not "the file is gone" any more; it is "the file is three
    /// slots of noise and nothing in it opens".
    private struct Aftermath: Equatable, CustomStringConvertible {
        var storeExists: Bool
        var storeOpens: Bool
        var storeIsWholeContainer: Bool
        var attachmentsExist: Bool
        var kdfSaltExists: Bool
        var duressVerifierExists: Bool
        var deadmanArmed: Bool
        var biometricKeyExists: Bool
        var description: String {
            "store=\(storeExists) opens=\(storeOpens) container=\(storeIsWholeContainer) "
            + "attachments=\(attachmentsExist) salt=\(kdfSaltExists) "
            + "duress=\(duressVerifierExists) deadman=\(deadmanArmed) "
            + "biokey=\(biometricKeyExists)"
        }
    }

    private func aftermath(_ model: AppModel,
                           _ bio: BiometricUnlockTests.FakeBacking) -> Aftermath {
        _ = model
        // Read the salt FIRST, and open with it by hand. `SecureStore`'s own
        // unlock path derives through `salt()`, which CREATES one when it is
        // missing — so measuring "does it still open" after a wipe would put
        // the very item the next line checks for back in the keychain.
        let salt = KeychainHelper.get("kdf-salt")
        let raw = try? Data(contentsOf: SecureStore.storeURL)
        let opens: Bool = {
            guard let salt, let raw, SlotVault.isContainer(raw),
                  let container = try? SlotVault.parse(raw) else { return false }
            return SlotVault.open(container,
                                  key: SecureStore.deriveKey(password: password,
                                                             salt: salt)) != nil
        }()
        return Aftermath(
            storeExists: SecureStore.storeExists,
            storeOpens: opens,
            storeIsWholeContainer: raw?.count == SlotVault.fileBytes,
            attachmentsExist: FileManager.default.fileExists(
                atPath: SecureStore.attachmentsDir(for: SecureStore.storeURL).path),
            kdfSaltExists: salt != nil,
            duressVerifierExists: KeychainHelper.get("duress-verifier") != nil,
            deadmanArmed: UserDefaults.standard.double(forKey: DeadmanSwitch.key) > 0,
            biometricKeyExists: bio.keyExists())
    }

    /// A store on disk, an attachment beside it, a duress PIN, a dead-man
    /// deadline, and a biometric key — the state a wipe has to destroy.
    private func seedIdentity(_ bio: BiometricUnlockTests.FakeBacking) {
        let store = SecureStore(creatingWithPassword: password)
        try? store.writeAttachment(id: UUID().uuidString.lowercased(),
                                   data: Data("attachment".utf8))
        store.flush()
        SecureStore.setDuressPin("2580")
        DeadmanSwitch.arm(days: 7)
        _ = bio.storeKey(Data(repeating: 7, count: 32))
        bio.setArmed(true)
        XCTAssertTrue(SecureStore.storeExists)
    }

    private func withBio(_ bio: BiometricUnlockTests.FakeBacking,
                         _ body: () async -> Void) async {
        let previous = BiometricUnlock.backing
        BiometricUnlock.backing = bio
        await body()
        BiometricUnlock.backing = previous
    }

    /// §A4: the warning appears at seven and not at six, driven by real wrong
    /// passwords through the real unlock path.
    func testWrongPasswordsCountAndTheWarningStartsAtSeven() async {
        let bio = BiometricUnlockTests.FakeBacking()
        await withBio(bio) {
            seedIdentity(bio)
            let m = AppModel(); model = m
            m.finishBoot()
            XCTAssertNil(m.wipeWarning(), "a fresh store says nothing")

            // five earlier failures, then two real ones over the top
            UnlockFailures.backing.setFailures(5)
            var error = await m.submitAuth(.unlock, username: "", password: "nope-nope-1")
            XCTAssertEqual(error, "decrypt failed — wrong password")
            XCTAssertEqual(UnlockFailures.count, 6)
            XCTAssertNil(m.wipeWarning(), "six failures: still nothing above the field")

            error = await m.submitAuth(.unlock, username: "", password: "nope-nope-2")
            XCTAssertEqual(error, "decrypt failed — wrong password")
            XCTAssertEqual(UnlockFailures.count, 7)
            XCTAssertEqual(m.wipeWarning(), "3 attempts left before this device wipes")
            XCTAssertTrue(aftermath(m, bio).storeOpens, "seven is a warning, not a wipe")

            // …and the right password clears it
            error = await m.submitAuth(.unlock, username: "", password: password)
            XCTAssertNil(error, "unlock failed: \(error ?? "")")
            XCTAssertEqual(UnlockFailures.count, 0, "a typed success resets the counter")
            XCTAssertNil(m.wipeWarning())
            m.service?.shutdown()
        }
    }

    /// §A1: the duress PIN never touches the counter — it is not a wrong
    /// password, it is the wipe.
    func testDuressPinDoesNotIncrementTheCounter() async {
        let bio = BiometricUnlockTests.FakeBacking()
        let counter = UnlockFailureCounterTests.FakeCounter()
        counter.n = 4
        let previousCounter = UnlockFailures.backing
        UnlockFailures.backing = counter
        defer { UnlockFailures.backing = previousCounter }
        await withBio(bio) {
            seedIdentity(bio)
            let m = AppModel(); model = m
            m.finishBoot()
            let error = await m.submitAuth(.unlock, username: "", password: "2580")
            XCTAssertEqual(error, AppModel.wrongPasswordError)
            XCTAssertEqual(counter.n, 4, "the duress PIN must not move the counter")
            XCTAssertFalse(aftermath(m, bio).storeOpens)
            // §5: the file survives every wipe, as three slots of noise
            XCTAssertTrue(SecureStore.storeExists)
            XCTAssertEqual((try? Data(contentsOf: SecureStore.storeURL))?.count,
                           SlotVault.fileBytes)
        }
    }

    /// §A4: "The tenth failure calls exactly the same wipe path as the duress
    /// PIN, asserted by the same post-conditions the duress tests already use."
    func testTenthFailureWipesExactlyAsTheDuressPinDoes() async {
        // 1. the duress PIN
        let duressBio = BiometricUnlockTests.FakeBacking()
        var duressAftermath: Aftermath?
        await withBio(duressBio) {
            seedIdentity(duressBio)
            let m = AppModel(); model = m
            m.finishBoot()
            let error = await m.submitAuth(.unlock, username: "", password: "2580")
            XCTAssertEqual(error, AppModel.wrongPasswordError)
            duressAftermath = aftermath(m, duressBio)
            model = nil
        }

        // 2. the tenth wrong password, from the same starting state
        SecureStore.obliterate()
        UnlockFailures.reset()
        let tenthBio = BiometricUnlockTests.FakeBacking()
        var tenthAftermath: Aftermath?
        await withBio(tenthBio) {
            seedIdentity(tenthBio)
            let m = AppModel(); model = m
            m.finishBoot()
            UnlockFailures.backing.setFailures(WipePolicy.limit - 1)   // nine already spent
            XCTAssertEqual(m.wipeWarning(), "1 attempts left before this device wipes")
            let error = await m.submitAuth(.unlock, username: "", password: "still-wrong-123")
            // §A2: no message, no explanation and no trace
            XCTAssertNil(error, "the tenth failure says nothing at all")
            XCTAssertNil(m.wipeWarning(), "and leaves no countdown behind")
            tenthAftermath = aftermath(m, tenthBio)
        }

        XCTAssertEqual(tenthAftermath, duressAftermath,
                       "the tenth failure must leave the device in exactly the state the "
                       + "duress PIN leaves it in")
        XCTAssertEqual(tenthAftermath?.storeExists, true, "§5: never deleted")
        XCTAssertEqual(tenthAftermath?.storeOpens, false, "…and nothing in it opens")
        XCTAssertEqual(tenthAftermath?.storeIsWholeContainer, true)
        XCTAssertEqual(tenthAftermath?.attachmentsExist, false)
        XCTAssertEqual(tenthAftermath?.kdfSaltExists, false)
        XCTAssertEqual(tenthAftermath?.duressVerifierExists, false)
        XCTAssertEqual(tenthAftermath?.deadmanArmed, false)
        XCTAssertEqual(tenthAftermath?.biometricKeyExists, false)
    }
}
