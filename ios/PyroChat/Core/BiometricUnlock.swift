import Foundation
import CryptoKit
import LocalAuthentication

/// docs/BIOMETRIC-UNLOCK.md — opt-in convenience for reopening the app.
///
/// The password remains the ONLY thing that derives the store key; biometry
/// merely unwraps a copy of that key held by the phone's secure hardware.
/// The password itself is never stored, anywhere, in any form.
///
/// Everything this feature knows lives in the Keychain, never in the
/// encrypted blob, so the unlock screen can consult it before anything is
/// decrypted (and the toggle can never drift from the stored key):
///
///   `biometric-store-key`      32 raw key bytes, SecAccessControl
///                              `.biometryCurrentSet` +
///                              `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`
///   `biometric-armed`          breadcrumb: the feature WAS enabled — the only
///                              way to tell "off" from "the OS just destroyed
///                              the key because a face/finger changed".
///                              Its VALUE is the slot tag; see below.
///   `biometric-last-password`  seconds since 1970 of the last PASSWORD unlock
///   `biometric-failures`       consecutive biometric failures (not cancels)
///
/// All four carry the app's keychain service, so `KeychainHelper.wipeAll()`
/// (panic wipe, duress PIN, dead-man) takes them with everything else.
///
/// ## docs/DECOY-ACCOUNT.md §6.1 — device-level and singular
///
/// Anything stored per ACCOUNT outside the blob is a counter an examiner can
/// read: two of a thing that should be one says two accounts exist, without a
/// password being typed. Biometric unlock is the item that would otherwise
/// multiply, and it is resolved by refusing to let it: **the phone can unlock
/// exactly one slot by face.** Enabling it in an account replaces whatever was
/// there before. Four items on every install, whatever the device holds, and
/// no way at all to express "two slots each have a stored key".
///
/// The `[cfg]` row must still show the truth for the account you are IN — on
/// if this slot is the one the phone holds, off otherwise — and it has to
/// answer that without a biometric prompt and without a fifth keychain item.
/// So `biometric-armed`, which already had to exist and already had to be
/// readable without a prompt, carries the answer as its value:
///
///     tag = HMAC-SHA256(key: storeKey, message: "pyrochat-biometric-slot-v1")
///
/// The session compares the tag against its own key. Equal ⇒ this is the slot
/// the phone opens. It is one value, so it cannot say "two"; it is an HMAC, so
/// it says nothing about the key or the account; and a decoy whose phone
/// unlocks the real account simply reads off, which is an ordinary state and
/// not a tell.

// MARK: - §4 policy (pure, injectable, heavily unit-tested)

/// When is the typed password required again? No clock, no Keychain, no UI:
/// every input is handed in.
enum BiometricPolicy {
    /// §4: "Every 7 days."
    static let passwordInterval: TimeInterval = 7 * 86_400
    /// §4: "After three consecutive biometric failures (not cancellations)."
    static let failureLimit = 3

    enum Reason: String, Equatable {
        /// 7 days have passed since the last typed password (or the clock
        /// moved backwards — fail closed, as auto-lock does).
        case interval
        /// Three consecutive non-recognitions.
        case failures
        /// The OS threw the stored key away: enrolment changed.
        case invalidated
        /// No password unlock was ever recorded — nothing to age off, so we
        /// cannot prove the 7 days; fail closed.
        case unknownLastPassword
    }

    enum Decision: Equatable {
        case allowBiometry
        case requirePassword(Reason)

        var allowsBiometry: Bool { self == .allowBiometry }
    }

    static func decide(lastPasswordUnlock: Date?,
                       consecutiveFailures: Int,
                       invalidated: Bool,
                       now: Date) -> Decision {
        // Invalidation wins: the key is gone or unreadable, biometry cannot
        // succeed whatever the other inputs say.
        if invalidated { return .requirePassword(.invalidated) }
        if consecutiveFailures >= failureLimit { return .requirePassword(.failures) }
        guard let last = lastPasswordUnlock else {
            return .requirePassword(.unknownLastPassword)
        }
        let elapsed = now.timeIntervalSince(last)
        if elapsed < 0 { return .requirePassword(.interval) }   // clock rolled back
        if elapsed >= passwordInterval { return .requirePassword(.interval) }
        return .allowBiometry
    }
}

// MARK: - storage seam

/// The three plain (no access control) facts the unlock screen must read
/// BEFORE anything is decrypted, plus the presence of the protected key.
/// A protocol so the unit suite can exercise enable/disable/wipe semantics
/// without a biometric prompt; production is `KeychainBiometricStore`.
protocol BiometricStoreBacking {
    /// Store 32 key bytes behind biometry. False = the OS refused.
    func storeKey(_ key: Data) -> Bool
    /// Presence only — must never raise a biometric prompt.
    func keyExists() -> Bool
    func deleteKey()

    func armed() -> Bool
    func setArmed(_ on: Bool)
    /// The §6.1 slot tag of the key the phone currently holds, or nil.
    func slotTag() -> Data?
    func setSlotTag(_ tag: Data?)

    func lastPasswordUnlock() -> Date?
    func setLastPasswordUnlock(_ date: Date?)

    func failures() -> Int
    func setFailures(_ n: Int)
}

/// Production backing: the real Keychain.
struct KeychainBiometricStore: BiometricStoreBacking {
    func storeKey(_ key: Data) -> Bool {
        KeychainHelper.setBiometric(key, key: BiometricUnlock.keyItem)
    }
    func keyExists() -> Bool { KeychainHelper.exists(BiometricUnlock.keyItem) }
    func deleteKey() { KeychainHelper.delete(BiometricUnlock.keyItem) }

    func armed() -> Bool { KeychainHelper.get(BiometricUnlock.armedItem) != nil }
    func setArmed(_ on: Bool) {
        // The breadcrumb's presence is what `armed` means; a bare 1 is the
        // value when no tag has been set (an older install, or a test).
        if on {
            if KeychainHelper.get(BiometricUnlock.armedItem) == nil {
                KeychainHelper.set(Data([1]), key: BiometricUnlock.armedItem)
            }
        } else {
            KeychainHelper.delete(BiometricUnlock.armedItem)
        }
    }

    /// §6.1: the tag lives in the breadcrumb item rather than beside it, so
    /// the keychain gains nothing. A 1-byte value is the old breadcrumb and
    /// means "armed, slot unknown".
    func slotTag() -> Data? {
        guard let raw = KeychainHelper.get(BiometricUnlock.armedItem),
              raw.count == BiometricUnlock.slotTagBytes else { return nil }
        return raw
    }
    func setSlotTag(_ tag: Data?) {
        guard let tag, tag.count == BiometricUnlock.slotTagBytes else {
            KeychainHelper.delete(BiometricUnlock.armedItem); return
        }
        KeychainHelper.set(tag, key: BiometricUnlock.armedItem)
    }

    func lastPasswordUnlock() -> Date? {
        guard let raw = KeychainHelper.get(BiometricUnlock.lastPasswordItem),
              let text = String(data: raw, encoding: .utf8),
              let secs = Double(text) else { return nil }
        return Date(timeIntervalSince1970: secs)
    }
    func setLastPasswordUnlock(_ date: Date?) {
        guard let date else { KeychainHelper.delete(BiometricUnlock.lastPasswordItem); return }
        // raw seconds since 1970 (§4), as text: the same shape Android keeps
        KeychainHelper.set(Data(String(date.timeIntervalSince1970).utf8),
                           key: BiometricUnlock.lastPasswordItem)
    }

    func failures() -> Int {
        guard let raw = KeychainHelper.get(BiometricUnlock.failuresItem),
              let text = String(data: raw, encoding: .utf8) else { return 0 }
        return Int(text) ?? 0
    }
    func setFailures(_ n: Int) {
        if n <= 0 { KeychainHelper.delete(BiometricUnlock.failuresItem); return }
        KeychainHelper.set(Data(String(n).utf8), key: BiometricUnlock.failuresItem)
    }
}

// MARK: - the feature

enum BiometricUnlock {
    static let keyItem = "biometric-store-key"
    static let armedItem = "biometric-armed"
    static let lastPasswordItem = "biometric-last-password"
    static let failuresItem = "biometric-failures"

    /// §6.1. HMAC-SHA256, so 32 bytes — and distinguishable at a glance from
    /// the 1-byte breadcrumb an older install left in the same item.
    static let slotTagBytes = 32
    /// Pinned, and the same string on Android: the tag is a device-local fact,
    /// but a fixed message keeps the two platforms' behaviour identical.
    static let slotTagMessage = Data("pyrochat-biometric-slot-v1".utf8)

    /// Which slot the phone opens, said in a way that reveals neither the key
    /// nor the account, and that cannot be made to say "two".
    static func slotTag(forStoreKey keyBytes: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(
            for: slotTagMessage, using: SymmetricKey(data: keyBytes)))
    }

    /// Swapped by the unit suite; the app never touches it.
    static var backing: BiometricStoreBacking = KeychainBiometricStore()

    /// What one attempt can come back with. `.passwordRequired` is the §4
    /// policy refusing before any prompt is raised.
    enum Outcome: Equatable {
        case unlocked(Data)          // 32 raw key bytes
        case cancelled
        case notRecognised
        case invalidated
        case passwordRequired
        case unavailable
    }

    // MARK: hardware

    /// nil when the device has no biometric hardware or nothing is enrolled —
    /// §2: "The row is hidden entirely."
    static func biometryType() -> LABiometryType? {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err),
              ctx.biometryType != .none else { return nil }
        return ctx.biometryType
    }

    /// The sensor this phone HAS, whether or not anything is enrolled on it.
    ///
    /// `biometryType()` above answers "can this device authenticate right
    /// now", which is the question the `[cfg]` row asks — §2 hides that row
    /// when nothing is enrolled. docs/STATUS-AND-CFG.md §1 asks a different
    /// one: the status page shows the row on every phone, and to name it
    /// correctly it needs the hardware rather than the enrolment.
    /// `LAContext.biometryType` is populated after a failed `canEvaluatePolicy`
    /// too — `.biometryNotEnrolled` still tells you it is a Face ID phone —
    /// so the answer survives having no faces on file. `.none` (no hardware,
    /// or an OS that will not say) falls through to the generic word.
    static func hardwareName() -> String {
        let ctx = LAContext()
        var err: NSError?
        _ = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
        return biometryName(ctx.biometryType)
    }

    /// "face id" / "touch id" / "optic id" — the words the labels are built from.
    static func biometryName(_ type: LABiometryType) -> String {
        switch type {
        case .faceID: return "face id"
        case .touchID: return "touch id"
        case .opticID: return "optic id"
        default: return "biometrics"
        }
    }

    /// §2, the `[cfg]` row: `unlock with face id`.
    static func rowLabel(_ type: LABiometryType) -> String {
        "unlock with \(biometryName(type))"
    }

    /// §3, the unlock screen: `[ UNLOCK WITH FACE ID ]`.
    static func buttonLabel(_ type: LABiometryType) -> String {
        "[ UNLOCK WITH \(biometryName(type).uppercased()) ]"
    }

    /// §2 footnote — verbatim on both platforms.
    static let footnote = """
        a face or a finger can be taken from you; a password in your head cannot. the app still asks \
        for your password every 7 days, after three failed attempts, and whenever you add or remove a \
        face or fingerprint.
        """

    // MARK: state the unlock screen reads before anything is decrypted

    /// Device-level: the phone holds a stored key for SOME slot.
    ///
    /// This is what the unlock screen asks, and it must stay slot-blind —
    /// §6.3: "the offer on the unlock screen must look identical whether the
    /// stored key belongs to slot 0 or slot 2."
    static var isEnabled: Bool { backing.keyExists() }

    /// §6.1, the `[cfg]` row: is the slot THIS session opened the one the
    /// phone unlocks by face? On for the account that enabled it, off for
    /// every other account on the device — including a decoy whose phone
    /// unlocks the real account, where off is an ordinary state and not a tell.
    ///
    /// Answers without a prompt and without touching the protected item: it
    /// compares tags, not keys.
    static func isEnabled(forStoreKey keyBytes: Data) -> Bool {
        guard backing.keyExists(), let stored = backing.slotTag() else { return false }
        let mine = slotTag(forStoreKey: keyBytes)
        // constant-time; there is no secret here, but the habit is cheap
        return stored.count == mine.count
            && zip(stored, mine).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// The feature was switched on at some point and the key has since
    /// vanished: the OS destroyed it (`.biometryCurrentSet`) because a face or
    /// finger was added or removed.
    static var wasInvalidated: Bool { backing.armed() && !backing.keyExists() }

    static var lastPasswordUnlock: Date? { backing.lastPasswordUnlock() }
    static var consecutiveFailures: Int { backing.failures() }

    static func decision(now: Date = Date()) -> BiometricPolicy.Decision {
        BiometricPolicy.decide(lastPasswordUnlock: backing.lastPasswordUnlock(),
                               consecutiveFailures: backing.failures(),
                               invalidated: wasInvalidated,
                               now: now)
    }

    // MARK: bookkeeping

    /// Every successful PASSWORD unlock: restarts the 7 days and clears the
    /// failure count (§4: "The counter resets on any successful unlock").
    static func notePasswordUnlock(now: Date = Date()) {
        backing.setLastPasswordUnlock(now)
        backing.setFailures(0)
    }

    /// A successful BIOMETRIC unlock: clears the failure count only — the 7
    /// day clock runs on typed passwords alone.
    static func noteBiometricSuccess() { backing.setFailures(0) }

    /// A non-recognition (never a cancellation). Returns the new count.
    @discardableResult
    static func noteFailure() -> Int {
        let n = backing.failures() + 1
        backing.setFailures(n)
        return n
    }

    // MARK: enable / disable / destroy

    /// §2: prompt biometry once to confirm, then store the key. A cancelled
    /// or failed prompt leaves the toggle off and changes nothing.
    static func enable(storeKey: Data) async -> Bool {
        guard storeKey.count == 32 else { return false }        // the key, nothing else
        guard let type = biometryType() else { return false }
        let ctx = LAContext()
        ctx.localizedFallbackTitle = ""                          // no device-credential fallback (§1)
        let reason = "confirm \(biometryName(type)) to unlock pyrochat without your password"
        guard (try? await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                             localizedReason: reason)) == true else { return false }
        guard backing.storeKey(storeKey) else { return false }
        // §6.1: singular. `storeKey` already replaced whatever key was there;
        // this replaces the tag that says whose it is, in the same item, so
        // the two can never disagree and the count never grows.
        backing.setSlotTag(slotTag(forStoreKey: storeKey))
        backing.setFailures(0)
        return true
    }

    /// §5: delete it, unconditionally and before anything else.
    static func destroy() {
        backing.deleteKey()
        backing.setArmed(false)
        backing.setFailures(0)
    }

    /// `destroy`, plus the last-typed-password timestamp — everything this
    /// feature ever wrote.
    ///
    /// The four wipe paths must leave the device in the SAME state, and until
    /// now they did not: the three total ones cleared the timestamp on their
    /// way through the keychain, and the narrowed burn left it behind. It is
    /// inert once the key is gone, so nothing in the app behaves differently —
    /// but a value that survives one kind of wipe and not another is exactly
    /// the sort of difference §5 spends this much effort removing, and it
    /// costs one line to not have it.
    static func shred() {
        destroy()
        backing.setLastPasswordUnlock(nil)
    }

    /// Biometry was removed from the device entirely (§5) — the stored key can
    /// never be read again, so it must not linger.
    static func dropIfBiometryGone() {
        guard biometryType() == nil else { return }
        if backing.keyExists() || backing.armed() { destroy() }
    }

    // MARK: the unlock attempt

    /// One attempt: policy, prompt, read. The caller opens the store with the
    /// bytes it hands back — this type never sees a password.
    static func attempt(now: Date = Date()) async -> Outcome {
        // §5: biometry gone from the device? nothing to attempt.
        dropIfBiometryGone()
        if wasInvalidated {
            destroy()                                            // §4: delete + disable
            return .invalidated
        }
        guard backing.keyExists() else { return .unavailable }
        guard biometryType() != nil else { return .unavailable }
        guard decision(now: now).allowsBiometry else { return .passwordRequired }

        let ctx = LAContext()
        ctx.localizedFallbackTitle = ""                          // §6: no PIN/pattern fallback
        let reason = "unlock pyrochat"
        do {
            _ = try await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                             localizedReason: reason)
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .appCancel, .systemCancel:
                return .cancelled                                // §3: silently fall back
            case .biometryNotEnrolled, .biometryNotAvailable:
                // biometry left the device (§5): the stored key can never be
                // read again — delete it and say so
                destroy()
                return .invalidated
            case .biometryLockout:
                // too many failures at the OS level. The key is still good —
                // only a password unlock (which resets the count) should bring
                // the button back, so park the counter at the limit.
                backing.setFailures(max(BiometricPolicy.failureLimit, backing.failures()))
                return .passwordRequired
            default:
                noteFailure()
                return .notRecognised
            }
        } catch {
            noteFailure()
            return .notRecognised
        }

        // The prompt already authenticated this context: the read reuses it
        // rather than raising a second one.
        switch KeychainHelper.getBiometric(keyItem, context: ctx) {
        case .success(let data):
            guard data.count == 32 else { destroy(); return .invalidated }
            noteBiometricSuccess()
            return .unlocked(data)
        case .failure(let err):
            switch err {
            case .cancelled:
                return .cancelled
            case .notRecognised:
                noteFailure()
                return .notRecognised
            case .missing, .failed:
                // §4: "Detected as an error from the read; the app then
                // deletes the item and disables the feature."
                destroy()
                return .invalidated
            }
        }
    }
}
