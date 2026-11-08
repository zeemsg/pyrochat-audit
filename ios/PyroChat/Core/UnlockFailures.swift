import Foundation

/// docs/SCRUB-AND-WIPE.md §A — ten consecutive wrong passwords destroy the
/// store and return the app to the create-identity screen, indistinguishable
/// from a fresh install.
///
/// Two pieces, deliberately separate:
///
///   * `WipePolicy` — the pure decision, from (failures, now) alone. No
///     Keychain, no UI, no clock of its own: the unit suite drives it directly.
///   * `UnlockFailures` — where the count lives. OUTSIDE the encrypted store
///     (§A1), because it must be readable when the store cannot be opened:
///     the Keychain item `unlock-failures`, which also survives a reinstall.
///     Ten guesses is ten guesses, and deleting the app must not hand an
///     attacker a fresh ten.
///
/// The biometric three-failure rule (docs/BIOMETRIC-UNLOCK.md §4) is a
/// DIFFERENT counter with a different item, a different limit and a different
/// consequence. The two never touch each other.

// MARK: - the policy (pure, injectable, heavily unit-tested)

enum WipePolicy {
    /// §A: "Ten consecutive wrong passwords destroy the store."
    static let limit = 10
    /// §A2: "From the seventh failure, above the password field."
    static let warnFrom = 7

    enum Decision: Equatable {
        /// Nothing to say: type the password.
        case allow
        /// Show the countdown line; the payload is the attempts LEFT (3, 2, 1).
        case warn(Int)
        /// This failure was the tenth: wipe, now, with no message.
        case wipe
    }

    /// `now` is part of the signature the spec names. There is deliberately no
    /// time term in the rule: the count is consecutive failures, not failures
    /// per hour, so a wait never buys an attacker anything back.
    static func decide(failures: Int, now: Date = Date()) -> Decision {
        if failures >= limit { return .wipe }
        if failures >= warnFrom { return .warn(limit - failures) }
        return .allow
    }

    /// §A2, verbatim and identical on both platforms.
    static func warningLine(attemptsLeft n: Int) -> String {
        "\(n) attempts left before this device wipes"
    }

    /// The line above the password field, or nil when there is nothing to say
    /// (below seven failures — and at ten, where the wipe has already happened
    /// and the screen must carry no trace of it).
    static func warning(failures: Int, now: Date = Date()) -> String? {
        guard case .warn(let n) = decide(failures: failures, now: now) else { return nil }
        return warningLine(attemptsLeft: n)
    }
}

// MARK: - storage seam

/// A protocol so the unit suite (and the screenshot demo) can drive the
/// counter without the real Keychain; production is `KeychainUnlockFailures`.
protocol UnlockFailureStoring {
    func failures() -> Int
    func setFailures(_ n: Int)
}

struct KeychainUnlockFailures: UnlockFailureStoring {
    func failures() -> Int {
        guard let raw = KeychainHelper.get(UnlockFailures.item),
              let text = String(data: raw, encoding: .utf8) else { return 0 }
        return Int(text) ?? 0
    }

    func setFailures(_ n: Int) {
        // zero is stored as absence, the same shape the biometric counter uses
        if n <= 0 { KeychainHelper.delete(UnlockFailures.item); return }
        KeychainHelper.set(Data(String(n).utf8), key: UnlockFailures.item)
    }
}

// MARK: - the counter

enum UnlockFailures {
    /// §A1: the item name is part of the spec.
    static let item = "unlock-failures"

    /// Swapped by the unit suite and by the screenshot demo; the app never
    /// assigns to it.
    static var backing: UnlockFailureStoring = KeychainUnlockFailures()

    static var count: Int { backing.failures() }

    /// §A1: "Incremented ONLY by a wrong typed password on the unlock screen."
    /// Returns the new count.
    @discardableResult
    static func note() -> Int {
        let n = max(0, backing.failures()) + 1
        backing.setFailures(n)
        return n
    }

    /// §A1: "Reset to zero by any successful unlock, typed or biometric, and
    /// by creating or restoring an identity."
    static func reset() { backing.setFailures(0) }

    static func decision(now: Date = Date()) -> WipePolicy.Decision {
        WipePolicy.decide(failures: backing.failures(), now: now)
    }

    /// §A2 — nil until the seventh consecutive failure.
    static var warning: String? { WipePolicy.warning(failures: backing.failures()) }
}
