import Foundation

/// docs/STATUS-AND-CFG.md §1 — the read-only page that answers "what is
/// protecting me right now".
///
/// Every sentence the screen renders comes from §1's tables, verbatim. §1:
/// "The sketch above is illustrative. The tables below are normative. Where
/// they disagree, the tables win." §3 requires both apps to say the same
/// thing, and the tables exist because the first draft showed each row in one
/// state and left the other fourteen sentences to be invented twice.
///
/// Two values on this page are chosen rather than quoted, and both are named
/// where they are written: the status screen's navigation title (`StatusView`)
/// and the `<name>` fallback for a browser that does not identify itself.
/// Everything else is a quotation.
///
/// Everything is produced HERE, as a pure function of a value type, for two
/// reasons:
///
///  * §4's first test — "reports the true state of every row it shows, driven
///    from a store rather than a fixture: flip each setting, assert the row
///    changes" — is then a table test over `groups(_:)`, not a UI drive.
///  * §4's second — "it never mentions a decoy, in ANY state, under ANY
///    password — assert on the whole rendered surface, not on one label" — is
///    only honest if the whole surface can be enumerated. With the copy in a
///    pure function the unit suite can walk the cartesian product of every
///    input and read every string the screen can ever produce. A UI test
///    scrapes one device in one state; this covers all of them.
///
/// **The decoy is absent. Entirely.** Not `SET`, not `NOT SET`, not a greyed
/// row. There is no input here that could carry it and no branch that could
/// render it — `DECOY-ACCOUNT.md` §3. Every other screen is careful never to
/// answer that question, and a page that lists everything is exactly where it
/// would slip out, so the absence is enforced by a test that reads the whole
/// surface rather than by remembering.
///
/// **No scores, no grades, no green ticks.** §1: "this is a product for people
/// with real adversaries, and a screen that says 'you are 80% secure' is a
/// lie." There is no aggregate anywhere in this file, on purpose.
enum SecurityStatus {

    // MARK: - what a row can point at

    /// The `[cfg]` control that changes a row (§1: "tapping one goes to the
    /// setting that changes it"). `nil` on the rows that have no control:
    ///
    ///   * `wrong attempts` — nothing anywhere sets the counter.
    ///   * `verified contacts` — the control is a safety number inside a chat,
    ///     not a settings row.
    ///   * the biometric row on a phone with nothing enrolled — `[cfg]` hides
    ///     that row entirely (`BIOMETRIC-UNLOCK.md` §2), so there is nothing
    ///     to land on.
    ///
    /// Those rows are inert, not disabled-looking: a row that jumped somewhere
    /// unrelated teaches the wrong thing about the rest of the page.
    enum CfgAnchor: String, Hashable, CaseIterable {
        case biometric, autoLock, duress, deadman, screenshotShield
        case wakePush, proxy, readReceipts, typing, desktopLink
        case backup, accessKey
    }

    struct Row: Equatable {
        /// Left column, always lowercase, always the same string for a given
        /// row whatever its state.
        let label: String
        /// The state, in the app's terminal shorthand. First line, right.
        let value: String
        /// §1: "**The right-hand column is the feature.**" What being in THIS
        /// state costs, from §1's table. Second line.
        ///
        /// Empty on exactly one row in one state — `access key` when funded,
        /// whose note cell in §1 reads "(no note — the value says it)". That
        /// is "deliberate and is the single exception to 'every row has a
        /// note'".
        let note: String
        let anchor: CfgAnchor?
    }

    struct Group: Equatable {
        let title: String
        let rows: [Row]
        /// §1: "On iOS the password-manager warning is a FOOTNOTE under the
        /// group, not a row" — it has no state and no control behind it, and
        /// a footnote wearing a row's clothes dilutes the column that makes
        /// the group work. Android has none: it closed the exposure
        /// structurally, and a row there reading "nothing to report" would be
        /// a tick by another name.
        let footer: String?
    }

    // MARK: - everything the page needs to know

    /// Assembled by the view from the live store, the Keychain and the
    /// device; kept as a plain value so the suite can drive every combination
    /// without a simulator.
    struct Input: Equatable {
        /// The row's label: the sensor this phone has, in the words the app
        /// already uses — `face id`, `touch id`, `optic id`, or `biometrics`
        /// when there is no hardware to name. §1: "Labels are per-platform
        /// where the hardware is."
        var biometryLabel = "biometrics"
        /// Is anything enrolled? When nothing is, the row is STILL SHOWN and
        /// says so: "a page whose job is 'what protects you' must not go quiet
        /// about a protection."
        var biometryEnrolled = false
        /// DECOY-ACCOUNT.md §6.1: true only when the stored key is THIS slot's,
        /// never the device-level "some slot has one".
        var biometricOn = false
        /// nil = off; otherwise the timeout in minutes.
        var autoLockMinutes: Int?
        var duressSet = false
        /// nil = off; otherwise 7, 30 or 90.
        var deadmanDays: Int?
        var screenshotShield = true
        var unlockFailures = 0
        var wakePush = false
        var proxyOn = false
        var readReceipts = false
        var typingIndicators = true
        /// nil = no browser is linked. Otherwise the browser's name as the
        /// mirror reported it, and `desktopLinkSince` the already-formatted
        /// clock time the link began — the view owns the locale, this stays
        /// a value the suite can drive.
        var desktopLinkBrowser: String?
        var desktopLinkSince = ""
        var contactsTotal = 0
        var contactsVerified = 0
        /// Whether the relay is being told who sends (docs/SEALED-SENDER.md §5.8).
        var senderHidden: SenderHidden = .notOffered
        /// §1's `lastBackupAt`: seconds since 1970, nil when never.
        var lastBackup: Double?
        var accessDays = 0
        var now = Date()
    }

    /// The three states of the `sender hidden` row. It is a fact about this login, not a
    /// switch: there is no control behind it.
    enum SenderHidden: Equatable, CaseIterable {
        /// Messages go out sealed wherever the other person's app can take them.
        case on
        /// The relay did not offer sealed sender, or its certificate did not pass.
        case notOffered
        /// It was offered, and the second connection it needs could not be opened.
        case noPrivateConnection
    }

    // MARK: - group titles (§1, verbatim)

    static let protectsTitle = "WHAT PROTECTS YOU"
    static let leavesTitle = "WHAT LEAVES THIS PHONE"
    static let loseTitle = "IF YOU LOSE IT"

    /// §2: the one row that reaches this screen, and the only way in.
    static let entryRow = "[ what protects me ]"

    /// §1's `<carrier>`: `apple` on iOS, `google` on Android.
    ///
    /// The table first wrote `google` for both. On iOS the message is handed
    /// to Apple's APNs and Google is told nothing whatsoever, so rendering it
    /// would have printed a false statement about where a user's metadata
    /// goes — on the one page in the app whose entire job is to say where it
    /// goes. §1 now resolves it per platform: "accuracy outranks screenshot
    /// parity here, and it is one word."
    static let pushCarrier = "apple"

    /// §1: "The one real hole in 'the password never leaves your device' is
    /// that iOS may offer to save it, and the user has no other way to
    /// discover that."
    ///
    /// `AuthCopy.passwordManagerWarning` says the same thing at the moment of
    /// typing ("if iphone offers to save this password, decline") and cannot
    /// be reused verbatim here: this page is read long afterwards, by someone
    /// who may already have said yes, and telling them to decline a sheet that
    /// is not on screen would answer the wrong question. This states the fact
    /// instead, and states it in full — an app whose whole claim is that a
    /// wipe is total owes the one exception a plain sentence.
    static let passwordManagerWarning =
        "if you ever let iphone save your password, that copy is in icloud keychain. "
        + "it is not in this app, and nothing here reaches it — not the duress pin, "
        + "not ten wrong passwords, not burn everything."

    /// The biometric row when the phone has nothing enrolled (§1, verbatim).
    static let noBiometryEnrolled = "this phone has no fingerprint or face enrolled"

    /// §1's value for a protection the phone cannot give at all. An em dash,
    /// not a word: `OFF` would say you could turn it on.
    static let unusable = "\u{2014}"

    /// §1: "`desktop link`'s `since <time>` is `HH:mm` in the phone's own
    /// zone." Fixed 24-hour, never `.shortened`, which renders `2:32 PM` in
    /// one locale and `14:32` in another.
    ///
    /// **Both screens call this one function**, and that is the point of it
    /// living here rather than in either view. `[cfg]`'s linked row used to
    /// format the same instant its own way, so a 12-hour phone showed
    /// `2:32 PM` in `[cfg]` and `14:32` on the status page — the same defect
    /// as a UTC date beside an age counted in local hours, one line in the
    /// app disagreeing with another about a fact neither of them computes.
    /// A time format is not wording, so §2's "every row keeps its wording"
    /// does not protect it.
    ///
    /// `zone` is for the suite; production is always the device's.
    static func clock(_ date: Date, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "HH:mm"
        return f.string(from: date)
    }

    // MARK: - the page

    static func groups(_ i: Input) -> [Group] {
        [
            Group(title: protectsTitle, rows: protects(i), footer: nil),
            Group(title: leavesTitle, rows: leaves(i), footer: passwordManagerWarning),
            Group(title: loseTitle, rows: lose(i), footer: nil),
        ]
    }

    /// Every string the page can render, flattened. The decoy test walks this.
    static func surface(_ i: Input) -> [String] {
        groups(i).flatMap { g -> [String] in
            [g.title] + g.rows.flatMap { [$0.label, $0.value, $0.note] } + [g.footer].compactMap { $0 }
        }
    }

    // MARK: - WHAT PROTECTS YOU

    private static func protects(_ i: Input) -> [Row] {
        [
            biometricRow(i),
            Row(label: "auto-lock",
                value: i.autoLockMinutes == nil ? "OFF" : "ON",
                note: i.autoLockMinutes.map { "relocks \($0) \(minuteWord($0)) after you leave" }
                    ?? "on: relocks after you leave the app",
                anchor: .autoLock),
            Row(label: "duress pin",
                value: i.duressSet ? "SET" : "NOT SET",
                note: i.duressSet
                    ? "a second password that wipes everything"
                    : "on: a second password that wipes everything",
                anchor: .duress),
            Row(label: "dead-man switch",
                value: i.deadmanDays == nil ? "OFF" : "ON",
                note: i.deadmanDays.map { "wipes if unopened for \($0) days" }
                    ?? "on: wipes if unopened for 7, 30 or 90 days",
                anchor: .deadman),
            Row(label: "screenshot shield",
                value: i.screenshotShield ? "ON" : "OFF",
                note: i.screenshotShield
                    ? "captures come out black"
                    : "on: captures come out black",
                anchor: .screenshotShield),
            // §1's table writes this cell as `<n>/10 — at ten, this device
            // wipes itself`; the count before the dash IS the value column in
            // §1's own sketch, so it is not repeated in the sentence.
            Row(label: "wrong attempts",
                value: "\(max(0, i.unlockFailures))/\(WipePolicy.limit)",
                note: "at ten, this device wipes itself",
                anchor: nil),
        ]
    }

    /// §1: the row is shown on every phone, including one with no biometric
    /// hardware at all. `[cfg]`'s row is hidden in that case
    /// (`BIOMETRIC-UNLOCK.md` §2), so the status row carries no anchor then —
    /// there is no control to land on.
    ///
    /// §1: the value is `\u{2014}`, "not `OFF`, because `OFF` implies you
    /// could turn it on". One branch covers both shapes of unusable — no
    /// sensor at all, and a sensor with nothing enrolled — because the
    /// pinned sentence is true of both and nothing on this page turns on
    /// which it is.
    private static func biometricRow(_ i: Input) -> Row {
        guard i.biometryEnrolled else {
            return Row(label: i.biometryLabel, value: unusable,
                       note: noBiometryEnrolled, anchor: nil)
        }
        return Row(label: i.biometryLabel,
                   value: i.biometricOn ? "ON" : "OFF",
                   note: i.biometricOn
                       ? "opens without typing, for 7 days at a time"
                       : "on: opens without typing, for 7 days at a time",
                   anchor: .biometric)
    }

    // MARK: - WHAT LEAVES THIS PHONE

    private static func leaves(_ i: Input) -> [Row] {
        [
            Row(label: "wake push",
                value: i.wakePush ? "ON" : "OFF",
                note: i.wakePush
                    ? "\(pushCarrier) is told a message waits. never who, never what"
                    : "on: a banner when a message arrives while the app is closed",
                anchor: .wakePush),
            Row(label: "proxy",
                value: i.proxyOn ? "ON" : "OFF",
                note: i.proxyOn
                    ? "the relay never learns your address"
                    : "on: the relay never learns your address",
                anchor: .proxy),
            Row(label: "read receipts",
                value: i.readReceipts ? "ON" : "OFF",
                note: i.readReceipts
                    ? "contacts learn when you opened a message"
                    : "contacts are not told when you read",
                anchor: .readReceipts),
            Row(label: "typing indicators",
                value: i.typingIndicators ? "ON" : "OFF",
                note: i.typingIndicators
                    ? "contacts see when you are typing"
                    : "contacts are not told when you type",
                anchor: .typing),
            // §1: "A linked browser is a live mirror of your messages on
            // another machine, and it is the largest continuous fact that
            // group exists to report." Its value is `ON`/`OFF` like every
            // other switch-shaped row here — `LINKED` was the obvious word
            // and the wrong one, because the value column is pinned and a
            // different word on one platform is a visible screenshot
            // difference. The browser's name lives in the note.
            Row(label: "desktop link",
                value: i.desktopLinkBrowser == nil ? "OFF" : "ON",
                note: i.desktopLinkBrowser.map { "linked to \($0), since \(i.desktopLinkSince)" }
                    ?? "on: read and reply from a browser",
                anchor: .desktopLink),
            // docs/SEALED-SENDER.md §5.8. Like `verified contacts` it has no
            // `[cfg]` control: it reports what this login got from the relay.
            // ON carries its limit in the same breath, because a row that only
            // said "hidden" would overstate it for every contact on an older build.
            senderHiddenRow(i.senderHidden),
            // §1: "`verified contacts` last on the status page only." It is
            // not a switch and it has no `[cfg]` control, so it reads as the
            // odd one out wherever it sits; last is where it stops
            // interrupting the run of rows that are.
            Row(label: "verified contacts",
                value: verifiedValue(verified: i.contactsVerified, total: i.contactsTotal),
                note: verifiedNote(verified: i.contactsVerified, total: i.contactsTotal),
                anchor: nil),
        ]
    }

    static func senderHiddenRow(_ state: SenderHidden) -> Row {
        switch state {
        case .on:
            return Row(label: "sender hidden", value: "ON",
                       note: "the relay is not told who sends, when the other person's app supports it",
                       anchor: nil)
        case .notOffered:
            return Row(label: "sender hidden", value: "OFF",
                       note: "this relay does not offer it. it is told who sends each message",
                       anchor: nil)
        case .noPrivateConnection:
            return Row(label: "sender hidden", value: "OFF",
                       note: "the private connection could not be opened. the relay is told who sends",
                       anchor: nil)
        }
    }

    /// §1's value column, `<k> of <n>` — except on a phone with no contacts,
    /// where it is `none`, "because `0 of 0 — none verified` is nonsense
    /// about nobody".
    static func verifiedValue(verified: Int, total: Int) -> String {
        let total = max(0, total)
        guard total > 0 else { return "none" }
        return "\(min(max(0, verified), total)) of \(total)"
    }

    /// §1's table: `all <n> verified` when every one is checked,
    /// `the others could be anyone` when some are, `none verified — any of
    /// them could be anyone` when none is, and `no contacts on this phone
    /// yet` when there is nobody to be unsure about.
    static func verifiedNote(verified: Int, total: Int) -> String {
        let total = max(0, total)
        let verified = min(max(0, verified), total)
        if total == 0 { return "no contacts on this phone yet" }
        if verified == total { return "all \(total) verified" }
        if verified == 0 { return "none verified \u{2014} any of them could be anyone" }
        // §1's plural rules: `the other` for exactly one, `the others` beyond.
        return total - verified == 1
            ? "the other could be anyone"
            : "the others could be anyone"
    }

    // MARK: - IF YOU LOSE IT

    private static func lose(_ i: Input) -> [Row] {
        var rows = [
            Row(label: "backup",
                value: backupValue(i.lastBackup, now: i.now),
                note: i.lastBackup.map { "last made \(backupDate($0))" }
                    ?? "there is nothing to restore from",
                anchor: .backup),
        ]
        // ACCESS_KEY_UI (ios/project.yml): the free app's relay enforces no
        // token, so "EXPIRED — messaging is off" would be false. Debug only.
        #if ACCESS_KEY_UI
        // §1's value table: `<n> days` / `EXPIRED`. Not `<n> days left`
        // — the sketch's phrasing was the value and the note run
        // together, and this is "the one row whose note is empty when
        // funded; that is deliberate and is the single exception to
        // 'every row has a note'". Expired splits at the table's own
        // dash: `EXPIRED` is the state, `messaging is off` the cost.
        rows.append(
            Row(label: "access key",
                value: i.accessDays > 0
                    ? "\(i.accessDays) \(i.accessDays == 1 ? "day" : "days")"
                    : "EXPIRED",
                note: i.accessDays > 0 ? "" : "messaging is off",
                anchor: .accessKey))
        #endif
        return rows
    }

    /// §1's value column for `backup`: `NEVER`, `today`, `1 day ago`,
    /// `12 days ago` — all four pinned, including the plural and the
    /// same-day case.
    ///
    /// Spelled out rather than `12d ago`: the value table pins the words, and
    /// this is the one row on the page that prints two renderings of the same
    /// instant — the age here, the absolute day in the note — so they must
    /// not also differ in style.
    static func backupValue(_ last: Double?, now: Date) -> String {
        guard let last else { return "NEVER" }
        let days = max(0, Int((now.timeIntervalSince1970 - last) / 86_400))
        if days == 0 { return "today" }
        return "\(days) \(days == 1 ? "day" : "days") ago"
    }

    /// The day inside `last made <date>`: ISO, and in the phone's OWN ZONE.
    ///
    /// §1: "every clock and date on this page is local, never UTC". UTC was
    /// the tempting answer and it is wrong here for a concrete reason — the
    /// age in the value column is counted in local hours, so a UTC date beside
    /// it makes an evening backup west of Greenwich read `today` next to
    /// tomorrow's date. One line on the page disagreeing with the rest of it
    /// is worse than two phones in different zones naming different days for
    /// the same file, which is a comparison nobody makes.
    ///
    /// ISO, because it is the one written form that reads identically in
    /// every locale and §3 compares these two screens in a screenshot.
    /// `zone` is for the suite; production is always the device's.
    static func backupDate(_ epochSeconds: Double, zone: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let d = cal.dateComponents([.year, .month, .day],
                                   from: Date(timeIntervalSince1970: epochSeconds))
        return String(format: "%04d-%02d-%02d", d.year ?? 0, d.month ?? 0, d.day ?? 0)
    }

    /// `relocks 1 minute after you leave`, not `1 minutes`. §1's table writes
    /// `<n> minutes`; `auto-lock` offers 1m as a real choice, and a grammar
    /// error on a page this short is louder than the one-word divergence.
    private static func minuteWord(_ n: Int) -> String { n == 1 ? "minute" : "minutes" }
}
