import Foundation

/// Dead-man switch: if the app isn't OPENED for N days, the next launch
/// wipes everything — same shredder as the duress PIN.
///
/// "Opened" = any presence: unlock, foreground, background. Every one of
/// those re-arms the deadline, so an actively used device can never wipe
/// itself. The wipe only ever fires PRE-AUTH (no live session, no store in
/// RAM that a later flush could resurrect).
///
/// The deadline must be readable BEFORE the store unlocks, so it lives in
/// UserDefaults. That leaks exactly one datum to forensics: a future date.
/// No usage pattern, no history — and it's deleted whenever the switch is
/// off, the wipe fires, or ANY wipe path (duress, burn-everything) runs.
enum DeadmanSwitch {
    static let key = "pyro.deadline"
    /// The interval the deadline was armed with, so PRESENCE can push it
    /// forward without knowing whose setting put it there.
    ///
    /// docs/DECOY-ACCOUNT.md §6.1 keeps the dead-man state device-level — "each
    /// of them acts on every slot" — but every re-arm used to take its days
    /// from whichever account was open, and `nil` days means DISARM. So
    /// unlocking a decoy, which has no dead-man of its own, silently turned
    /// off the real account's: the one switch whose entire purpose is to fire
    /// when the phone is taken, disabled by exactly the session a person is
    /// made to open. This is the second value that makes that impossible.
    ///
    /// UserDefaults, beside the deadline it belongs to, and device-level like
    /// it. It is not per-account and so adds nothing an examiner can count.
    static let daysKey = "pyro.deadline.days"

    /// Pure decision (unit-tested): wipe iff a deadline exists and is past.
    static func shouldWipe(deadline: Date?, now: Date) -> Bool {
        guard let deadline else { return false }
        return now >= deadline
    }

    /// The EXPLICIT setting, from the `[cfg]` row: this account is choosing
    /// what the device does, and `nil` really does mean off.
    static func arm(days: Int?, now: Date = Date(),
                    defaults: UserDefaults = .standard) {
        guard let days, days > 0 else {
            defaults.removeObject(forKey: key)
            defaults.removeObject(forKey: daysKey)
            return
        }
        defaults.set(now.addingTimeInterval(TimeInterval(days) * 86_400)
                        .timeIntervalSince1970, forKey: key)
        defaults.set(days, forKey: daysKey)
    }

    /// PRESENCE: unlock, foreground, background. "Opened = any presence, and
    /// every one of those re-arms the deadline", so an actively used device
    /// can never wipe itself — whichever slot is being used.
    ///
    /// The difference from `arm` is the one that matters (§6.1): a session
    /// whose account has no dead-man of its own must NEVER clear one. It
    /// pushes the existing deadline forward by the interval that set it and
    /// leaves the setting alone.
    static func notePresence(days: Int?, now: Date = Date(),
                             defaults: UserDefaults = .standard) {
        if let days, days > 0 { arm(days: days, now: now, defaults: defaults); return }
        // this account has none. If the DEVICE has one, presence still counts.
        guard defaults.double(forKey: key) > 0 else { return }
        let stored = defaults.integer(forKey: daysKey)
        guard stored > 0 else { return }     // armed by an older build: leave it exactly as it is
        defaults.set(now.addingTimeInterval(TimeInterval(stored) * 86_400)
                        .timeIntervalSince1970, forKey: key)
    }

    static func disarm(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: daysKey)
    }

    /// Call ONLY pre-auth (no live session). Returns true when it wiped.
    static func fireIfExpired(now: Date = Date(),
                              defaults: UserDefaults = .standard) -> Bool {
        let raw = defaults.double(forKey: key)
        let deadline = raw > 0 ? Date(timeIntervalSince1970: raw) : nil
        guard shouldWipe(deadline: deadline, now: now) else { return false }
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: daysKey)
        SecureStore.obliterate()
        return true
    }
}
