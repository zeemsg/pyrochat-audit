import XCTest
@testable import PyroChat

/// docs/STATUS-AND-CFG.md §4 — the status screen.
///
/// The screen's whole content is `SecurityStatus.groups(_:)`, a pure function,
/// so "assert on the whole rendered surface" can be taken literally here: the
/// decoy test below walks every combination of every input and reads every
/// string the page can ever draw, which no single UI drive can do.
@MainActor
final class SecurityStatusTests: XCTestCase {

    // MARK: helpers

    private func row(_ i: SecurityStatus.Input, _ label: String) -> SecurityStatus.Row? {
        SecurityStatus.groups(i).flatMap(\.rows).first { $0.label == label }
    }

    private func labels(_ i: SecurityStatus.Input) -> [String] {
        SecurityStatus.groups(i).flatMap(\.rows).map(\.label)
    }

    // MARK: §4.1 — every row reports the true state, and changes when it does

    /// "flip each setting, assert the row changes". Value AND note: a page
    /// whose right-hand column froze while the state moved would be worse
    /// than one with no right-hand column at all.
    func testEveryRowFollowsItsInput() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true

        func assertFlips(_ label: String, _ mutate: (inout SecurityStatus.Input) -> Void,
                         file: StaticString = #filePath, line: UInt = #line) {
            guard let before = row(i, label) else {
                return XCTFail("no `\(label)` row", file: file, line: line)
            }
            mutate(&i)
            guard let after = row(i, label) else {
                return XCTFail("`\(label)` row vanished", file: file, line: line)
            }
            XCTAssertNotEqual(before.value, after.value,
                              "`\(label)` state did not move", file: file, line: line)
            XCTAssertNotEqual(before.note, after.note,
                              "`\(label)` right-hand column did not move", file: file, line: line)
        }

        assertFlips("face id") { $0.biometricOn = true }
        assertFlips("auto-lock") { $0.autoLockMinutes = 15 }
        assertFlips("duress pin") { $0.duressSet = true }
        assertFlips("dead-man switch") { $0.deadmanDays = 30 }
        assertFlips("screenshot shield") { $0.screenshotShield = false }
        assertFlips("wake push") { $0.wakePush = true }
        assertFlips("proxy") { $0.proxyOn = true }
        assertFlips("read receipts") { $0.readReceipts = true }
        assertFlips("typing indicators") { $0.typingIndicators = false }
        assertFlips("desktop link") {
            $0.desktopLinkBrowser = "safari"; $0.desktopLinkSince = "14:32"
        }
        assertFlips("backup") { $0.lastBackup = $0.now.timeIntervalSince1970 - 3 * 86_400 }
        assertFlips("access key") { $0.accessDays = 142 }

        // the counter has one note in every state, so only the value moves
        let before = row(i, "wrong attempts")!
        i.unlockFailures = 7
        XCTAssertEqual(before.value, "0/10")
        XCTAssertEqual(row(i, "wrong attempts")!.value, "7/10")

        // …and so does the contact count, which is a different shape again
        i.contactsTotal = 4
        i.contactsVerified = 1
        XCTAssertEqual(row(i, "verified contacts")!.value, "1 of 4")
        XCTAssertEqual(row(i, "verified contacts")!.note, "the others could be anyone")
    }

    /// §1's sample page, rendered from the state it describes.
    ///
    /// §1: "The sketch above is illustrative. The tables below are
    /// normative. Where they disagree, the tables win." So this asserts the
    /// TABLES' answers for that state — which is also what the sketch now
    /// says, since it was corrected to match. Three cells were stale in it
    /// and reading around them is how two shipped platforms ended up
    /// disagreeing: `access key` funded is `142 days` with an empty note, and
    /// `auto-lock` / `dead-man switch` carry `ON`, never their interval.
    func testTheSpecsOwnExamplePage() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true
        i.autoLockMinutes = nil
        i.duressSet = true
        i.deadmanDays = nil
        i.screenshotShield = true
        i.unlockFailures = 0
        i.wakePush = true
        i.proxyOn = false
        i.readReceipts = true
        i.contactsTotal = 4
        i.contactsVerified = 1
        i.senderHidden = .on
        i.lastBackup = nil
        i.accessDays = 142

        let g = SecurityStatus.groups(i)
        XCTAssertEqual(g.map(\.title),
                       ["WHAT PROTECTS YOU", "WHAT LEAVES THIS PHONE", "IF YOU LOSE IT"])

        XCTAssertEqual(g[0].rows.map(\.label),
                       ["face id", "auto-lock", "duress pin", "dead-man switch",
                        "screenshot shield", "wrong attempts"])
        XCTAssertEqual(g[0].rows.map(\.value), ["OFF", "OFF", "SET", "OFF", "ON", "0/10"])
        XCTAssertEqual(g[0].rows.map(\.note), [
            "on: opens without typing, for 7 days at a time",
            "on: relocks after you leave the app",
            "a second password that wipes everything",
            "on: wipes if unopened for 7, 30 or 90 days",
            "captures come out black",
            "at ten, this device wipes itself",
        ])

        // §1: "Row order in `what leaves this phone` is the same on both
        // screens, and it is: wake push, proxy, read receipts, typing
        // indicators, desktop link, then on the status page only `sender
        // hidden` and, last, `verified contacts`."
        XCTAssertEqual(g[1].rows.map(\.label),
                       ["wake push", "proxy", "read receipts", "typing indicators",
                        "desktop link", "sender hidden", "verified contacts"])
        XCTAssertEqual(g[1].rows.map(\.value),
                       ["ON", "OFF", "ON", "ON", "OFF", "ON", "1 of 4"])
        XCTAssertEqual(g[1].rows.map(\.note), [
            // §1: `<carrier>` is `apple` on iOS and `google` on Android —
            // the push goes to APNs here and Google is told nothing at all.
            "apple is told a message waits. never who, never what",
            "on: the relay never learns your address",
            "contacts learn when you opened a message",
            "contacts see when you are typing",
            "on: read and reply from a browser",
            // SEALED-SENDER.md §5.8: ON carries its limit in the same breath
            "the relay is not told who sends, when the other person's app supports it",
            "the others could be anyone",
        ])

        XCTAssertEqual(g[2].rows.map(\.label), ["backup", "access key"])
        XCTAssertEqual(g[2].rows[0].value, "NEVER")
        XCTAssertEqual(g[2].rows[0].note, "there is nothing to restore from")
        // §1: "the one row whose note is empty when funded; that is
        // deliberate and is the single exception to `every row has a note`."
        XCTAssertEqual(g[2].rows[1].value, "142 days")
        XCTAssertEqual(g[2].rows[1].note, "")
    }

    /// §1: "A phone with no biometric hardware still shows the row, reading
    /// `this phone has no fingerprint or face enrolled` — a page whose job is
    /// 'what protects you' must not go quiet about a protection." It is inert
    /// there, because `[cfg]` has no row to land on
    /// (docs/BIOMETRIC-UNLOCK.md §2 hides it).
    func testTheBiometricRowIsShownEvenWithNothingEnrolled() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = false
        guard let bare = SecurityStatus.groups(i)[0].rows.first else {
            return XCTFail("the biometric row vanished with nothing enrolled")
        }
        XCTAssertEqual(bare.label, "face id")
        // §1: "A phone with no biometric sensor renders the value as `—`,
        // not `OFF`, because `OFF` implies you could turn it on."
        XCTAssertEqual(bare.value, "\u{2014}")
        XCTAssertEqual(bare.note, "this phone has no fingerprint or face enrolled")
        XCTAssertNil(bare.anchor, "there is no [cfg] row to jump to")
        // it never claims a protection the phone is not giving
        XCTAssertNotEqual(bare.value, "ON")
        XCTAssertNotEqual(bare.value, "OFF")

        // §1: "Labels are per-platform where the hardware is."
        i.biometryLabel = "touch id"
        XCTAssertEqual(SecurityStatus.groups(i)[0].rows.first?.label, "touch id")
        i.biometryLabel = "biometrics"
        XCTAssertEqual(SecurityStatus.groups(i)[0].rows.first?.label, "biometrics")
    }

    /// §1's two-state table, every cell, both columns. It exists because half
    /// these sentences were otherwise invented twice — once per platform — so
    /// they are pinned here rather than smoke-tested.
    func testEveryCellOfTheTwoStateTable() {
        func note(_ label: String, _ mutate: (inout SecurityStatus.Input) -> Void) -> String {
            var i = SecurityStatus.Input()
            i.biometryLabel = "face id"
            i.biometryEnrolled = true
            mutate(&i)
            return row(i, label)?.note ?? "<missing row: \(label)>"
        }
        XCTAssertEqual(note("face id") { $0.biometricOn = true },
                       "opens without typing, for 7 days at a time")
        XCTAssertEqual(note("face id") { $0.biometricOn = false },
                       "on: opens without typing, for 7 days at a time")
        XCTAssertEqual(note("auto-lock") { $0.autoLockMinutes = 5 },
                       "relocks 5 minutes after you leave")
        XCTAssertEqual(note("auto-lock") { $0.autoLockMinutes = nil },
                       "on: relocks after you leave the app")
        XCTAssertEqual(note("duress pin") { $0.duressSet = true },
                       "a second password that wipes everything")
        XCTAssertEqual(note("duress pin") { $0.duressSet = false },
                       "on: a second password that wipes everything")
        XCTAssertEqual(note("dead-man switch") { $0.deadmanDays = 30 },
                       "wipes if unopened for 30 days")
        XCTAssertEqual(note("dead-man switch") { $0.deadmanDays = nil },
                       "on: wipes if unopened for 7, 30 or 90 days")
        XCTAssertEqual(note("screenshot shield") { $0.screenshotShield = true },
                       "captures come out black")
        XCTAssertEqual(note("screenshot shield") { $0.screenshotShield = false },
                       "on: captures come out black")
        XCTAssertEqual(note("wrong attempts") { $0.unlockFailures = 3 },
                       "at ten, this device wipes itself")
        XCTAssertEqual(note("wrong attempts") { $0.unlockFailures = 0 },
                       "at ten, this device wipes itself")
        XCTAssertEqual(note("wake push") { $0.wakePush = true },
                       "apple is told a message waits. never who, never what")
        XCTAssertEqual(note("wake push") { $0.wakePush = false },
                       "on: a banner when a message arrives while the app is closed")
        XCTAssertEqual(note("proxy") { $0.proxyOn = true },
                       "the relay never learns your address")
        XCTAssertEqual(note("proxy") { $0.proxyOn = false },
                       "on: the relay never learns your address")
        XCTAssertEqual(note("read receipts") { $0.readReceipts = true },
                       "contacts learn when you opened a message")
        XCTAssertEqual(note("read receipts") { $0.readReceipts = false },
                       "contacts are not told when you read")
        XCTAssertEqual(note("verified contacts") { $0.contactsTotal = 4; $0.contactsVerified = 4 },
                       "all 4 verified")
        XCTAssertEqual(note("verified contacts") { $0.contactsTotal = 4; $0.contactsVerified = 1 },
                       "the others could be anyone")
        XCTAssertEqual(note("verified contacts") { $0.contactsTotal = 4; $0.contactsVerified = 3 },
                       "the other could be anyone")
        XCTAssertEqual(note("verified contacts") { $0.contactsTotal = 4; $0.contactsVerified = 0 },
                       "none verified \u{2014} any of them could be anyone")
        XCTAssertEqual(note("backup") { $0.lastBackup = nil },
                       "there is nothing to restore from")
        XCTAssertEqual(note("access key") { $0.accessDays = 0 }, "messaging is off")
        XCTAssertEqual(note("access key") { $0.accessDays = 9 }, "")
        XCTAssertEqual(note("typing indicators") { $0.typingIndicators = true },
                       "contacts see when you are typing")
        XCTAssertEqual(note("typing indicators") { $0.typingIndicators = false },
                       "contacts are not told when you type")
        XCTAssertEqual(note("desktop link") { $0.desktopLinkBrowser = nil },
                       "on: read and reply from a browser")
        XCTAssertEqual(note("desktop link") {
            $0.desktopLinkBrowser = "safari"; $0.desktopLinkSince = "14:32"
        }, "linked to safari, since 14:32")
    }

    /// §1: "A linked browser is a live mirror of your messages on another
    /// machine, and it is the largest continuous fact that group exists to
    /// report." It is in the group about what leaves the phone, it names the
    /// browser, and it says so whichever shape `[cfg]`'s own row is in.
    func testTheDesktopLinkRowIsInTheRightGroupAndNamesTheBrowser() {
        var i = SecurityStatus.Input()
        i.desktopLinkBrowser = "Firefox Developer Edition"
        i.desktopLinkSince = "23:54"
        let leaves = SecurityStatus.groups(i)[1]
        XCTAssertEqual(leaves.title, "WHAT LEAVES THIS PHONE")
        guard let link = leaves.rows.first(where: { $0.label == "desktop link" }) else {
            return XCTFail("no desktop link row")
        }
        // §1's value table: `ON`, not `LINKED`. Android shipped `ON` and a
        // different word here is a visible screenshot difference.
        XCTAssertEqual(link.value, "ON")
        XCTAssertTrue(link.note.contains("Firefox Developer Edition"))
        XCTAssertTrue(link.note.contains("23:54"))
        XCTAssertEqual(link.anchor, .desktopLink, "it reaches [cfg]'s own link row")
        // it is not in either of the other two groups
        XCTAssertFalse(SecurityStatus.groups(i)[0].rows.contains { $0.label == "desktop link" })
        XCTAssertFalse(SecurityStatus.groups(i)[2].rows.contains { $0.label == "desktop link" })
        // and with nothing linked the page still carries the row, saying so
        i.desktopLinkBrowser = nil
        let off = SecurityStatus.groups(i)[1].rows.first { $0.label == "desktop link" }
        XCTAssertEqual(off?.value, "OFF")
        XCTAssertEqual(off?.note, "on: read and reply from a browser")
    }

    /// §1's SECOND table: which column each cell is. Two of the note cells
    /// read like values (`last made <date>`, `all <n> verified`) and the row
    /// already has one, so the value column is pinned separately — and the
    /// obvious readings are wrong for four rows. `auto-lock` and `dead-man`
    /// are `ON`/`OFF` and NOT their interval; `desktop link` is `ON` and not
    /// `LINKED`; `access key` is `<n> days` and not `<n> days left`.
    /// §1's three values that are not `ON`/`OFF`, and the one that is not a
    /// word at all.
    /// §1: "`desktop link`'s `since <time>` is `HH:mm` in the phone's own
    /// zone", and BOTH screens render it through this one function — `[cfg]`'s
    /// linked row used to format the same instant its own way, so a 12-hour
    /// phone showed `2:32 PM` there and `14:32` on the status page.
    func testTheClockIsTwentyFourHourLocalAndSharedByBothScreens() {
        // 2025-09-11 01:00 UTC — 21:00 the previous evening in New York
        let t = Date(timeIntervalSince1970: 1_757_552_400)
        XCTAssertEqual(SecurityStatus.clock(t, zone: TimeZone(secondsFromGMT: 0)!), "01:00")
        XCTAssertEqual(SecurityStatus.clock(t, zone: TimeZone(identifier: "America/New_York")!),
                       "21:00")
        // never a 12-hour clock, whatever the device's locale prefers
        for hour in 0..<24 {
            let stamp = Date(timeIntervalSince1970: 1_757_548_800 + Double(hour) * 3_600)
            let text = SecurityStatus.clock(stamp, zone: TimeZone(secondsFromGMT: 0)!)
            XCTAssertEqual(text.count, 5, "`\(text)` is not HH:mm")
            XCTAssertFalse(text.uppercased().contains("AM"))
            XCTAssertFalse(text.uppercased().contains("PM"))
            XCTAssertEqual(text, String(format: "%02d:00", hour))
        }
        // and it is what the row prints
        var i = SecurityStatus.Input()
        i.desktopLinkBrowser = "safari"
        i.desktopLinkSince = SecurityStatus.clock(t, zone: TimeZone(secondsFromGMT: 0)!)
        XCTAssertEqual(row(i, "desktop link")!.note, "linked to safari, since 01:00")
    }

    func testTheThreeValuesThatAreNotOnOrOff() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = false
        XCTAssertEqual(row(i, "face id")!.value, "\u{2014}",
                       "§1: not OFF, because OFF implies you could turn it on")
        XCTAssertEqual(SecurityStatus.unusable, "\u{2014}")
        i.contactsTotal = 0
        XCTAssertEqual(row(i, "verified contacts")!.value, "none")
        i.lastBackup = nil
        XCTAssertEqual(row(i, "backup")!.value, "NEVER")
    }

    func testTheValueColumnTable() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true
        func value(_ label: String) -> String? {
            SecurityStatus.groups(i).flatMap(\.rows).first { $0.label == label }?.value
        }
        // every switch-shaped row is ON/OFF, whatever it is switched to
        i.autoLockMinutes = 15
        i.deadmanDays = 90
        i.biometricOn = true
        i.screenshotShield = true
        i.wakePush = true
        i.proxyOn = true
        i.readReceipts = true
        i.typingIndicators = true
        i.desktopLinkBrowser = "safari"
        i.desktopLinkSince = "14:32"
        for label in ["face id", "auto-lock", "dead-man switch", "screenshot shield",
                      "wake push", "proxy", "read receipts", "typing indicators",
                      "desktop link"] {
            XCTAssertEqual(value(label), "ON", "`\(label)` value is not the pinned ON")
        }
        i.autoLockMinutes = nil
        i.deadmanDays = nil
        i.biometricOn = false
        i.screenshotShield = false
        i.wakePush = false
        i.proxyOn = false
        i.readReceipts = false
        i.typingIndicators = false
        i.desktopLinkBrowser = nil
        for label in ["face id", "auto-lock", "dead-man switch", "screenshot shield",
                      "wake push", "proxy", "read receipts", "typing indicators",
                      "desktop link"] {
            XCTAssertEqual(value(label), "OFF", "`\(label)` value is not the pinned OFF")
        }
        // duress is the one that says SET / NOT SET
        i.duressSet = true
        XCTAssertEqual(value("duress pin"), "SET")
        i.duressSet = false
        XCTAssertEqual(value("duress pin"), "NOT SET")
        // and the four that carry a count
        i.unlockFailures = 3
        XCTAssertEqual(value("wrong attempts"), "3/10")
        i.contactsTotal = 9; i.contactsVerified = 2
        XCTAssertEqual(value("verified contacts"), "2 of 9")
        i.contactsTotal = 0; i.contactsVerified = 0
        XCTAssertEqual(value("verified contacts"), "none", "§1's empty-phone case")
        i.contactsTotal = 9; i.contactsVerified = 2
        i.lastBackup = nil
        XCTAssertEqual(value("backup"), "NEVER")
        i.lastBackup = i.now.timeIntervalSince1970 - 12 * 86_400
        XCTAssertEqual(value("backup"), "12 days ago")
        i.accessDays = 142
        XCTAssertEqual(value("access key"), "142 days")
        i.accessDays = 0
        XCTAssertEqual(value("access key"), "EXPIRED")
    }

    /// §1's plural rules, both of them, applied in the same pass as Android.
    /// Fixing grammar on one side alone recreates the divergence §3 exists to
    /// prevent, so these are pinned rather than left to taste.
    func testPluralRules() {
        var i = SecurityStatus.Input()
        i.autoLockMinutes = 1
        XCTAssertEqual(row(i, "auto-lock")!.note, "relocks 1 minute after you leave")
        i.autoLockMinutes = 5
        XCTAssertEqual(row(i, "auto-lock")!.note, "relocks 5 minutes after you leave")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 3, total: 4),
                       "the other could be anyone")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 2, total: 4),
                       "the others could be anyone")
        // the same rule reaches the two counted values
        XCTAssertEqual(SecurityStatus.backupValue(i.now.timeIntervalSince1970,
                                                  now: i.now), "today")
        XCTAssertEqual(SecurityStatus.backupValue(i.now.timeIntervalSince1970 - 86_400,
                                                  now: i.now), "1 day ago")
        XCTAssertEqual(SecurityStatus.backupValue(i.now.timeIntervalSince1970 - 2 * 86_400,
                                                  now: i.now), "2 days ago")
        // §1: "A phone with no contacts reads `none` / `no contacts on this
        // phone yet`, because `0 of 0 — none verified` is nonsense about
        // nobody."
        var empty = SecurityStatus.Input()
        XCTAssertEqual(row(empty, "verified contacts")!.value, "none")
        XCTAssertEqual(row(empty, "verified contacts")!.note, "no contacts on this phone yet")
        empty.contactsTotal = 1
        XCTAssertEqual(row(empty, "verified contacts")!.value, "0 of 1")
        i.accessDays = 1
        XCTAssertEqual(row(i, "access key")!.value, "1 day")
        i.accessDays = 2
        XCTAssertEqual(row(i, "access key")!.value, "2 days")
    }

    /// §3 asks both platforms to say the same thing; the table says `google is
    /// told a message waits`, which is Android's carrier. On iOS the push goes
    /// to Apple and Google is told nothing at all, so the table's sentence
    /// would be a false statement on the page whose whole job is to say where
    /// a user's metadata goes. One word, isolated in `pushCarrier`, pinned
    /// here so the divergence is deliberate rather than drifted into.
    func testTheWakePushRowNamesTheCarrierThatActuallyGetsTold() {
        XCTAssertEqual(SecurityStatus.pushCarrier, "apple")
        var i = SecurityStatus.Input()
        i.wakePush = true
        let all = SecurityStatus.surface(i).joined(separator: " ").lowercased()
        XCTAssertFalse(all.contains("google"),
                       "iOS does not hand wake pushes to Google and must not say it does")
        XCTAssertTrue(all.contains("apple is told a message waits"))
    }

    // MARK: §4.2 — the decoy is absent, in any state, under any password

    /// docs/DECOY-ACCOUNT.md §3. Not `SET`, not `NOT SET`, not a greyed row.
    ///
    /// The whole rendered surface, over the cartesian product of every input
    /// the page has — 2·2·4·2·4·2·11·2·2·2 states of the switches alone,
    /// times the contact counts and the two "if you lose it" rows. A test that
    /// asserted on one label would pass on a page that leaked in one state.
    func testNothingOnThisPageEverMentionsADecoy() {
        // Words that would disclose it, plus the ones a future edit is most
        // likely to reach for. "second password" is deliberately NOT here:
        // §1's own duress copy is "a second password that wipes everything",
        // and a test that banned it would ban the spec.
        let forbidden = ["decoy", "slot", "another account", "second account",
                         "other account", "hidden account", "vault", "container"]
        var seen = 0
        for enrolled in [false, true] {
            for biometricOn in [false, true] {
                for autoLock in [nil, 1, 5, 15] as [Int?] {
                    for duress in [false, true] {
                        for deadman in [nil, 7, 30, 90] as [Int?] {
                            for shield in [false, true] {
                                for failures in 0...WipePolicy.limit {
                                    for push in [false, true] {
                                        for proxy in [false, true] {
                                            for receipts in [false, true] {
                                                var i = SecurityStatus.Input()
                                                i.biometryEnrolled = enrolled
                                                i.biometricOn = biometricOn
                                                i.autoLockMinutes = autoLock
                                                i.duressSet = duress
                                                i.deadmanDays = deadman
                                                i.screenshotShield = shield
                                                i.unlockFailures = failures
                                                i.wakePush = push
                                                i.proxyOn = proxy
                                                i.readReceipts = receipts
                                                // the two shapes that vary
                                                // independently of the switches
                                                i.typingIndicators = receipts
                                                i.desktopLinkBrowser = proxy
                                                    ? "Firefox Developer Edition" : nil
                                                i.desktopLinkSince = "23:54"
                                                i.contactsTotal = failures % 5
                                                i.contactsVerified = failures % 3
                                                i.accessDays = failures * 20
                                                i.lastBackup = push
                                                    ? i.now.timeIntervalSince1970 - 86_400 : nil
                                                assertClean(SecurityStatus.surface(i), forbidden)
                                                seen += 1
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        XCTAssertGreaterThan(seen, 5_000, "the product really was walked")
    }

    /// The contact-count line is generated rather than picked from a list, so
    /// it gets the same treatment across every count it can ever render.
    func testTheContactLineNeverLeaksEither() {
        for total in 0...40 {
            for verified in 0...total {
                var i = SecurityStatus.Input()
                i.contactsTotal = total
                i.contactsVerified = verified
                assertClean(SecurityStatus.surface(i), ["decoy", "slot", "account"])
            }
        }
    }

    private func assertClean(_ surface: [String], _ forbidden: [String],
                             file: StaticString = #filePath, line: UInt = #line) {
        let all = surface.joined(separator: " \u{241F} ").lowercased()
        for word in forbidden {
            if all.contains(word) {
                XCTFail("the status page said `\(word)`: \(all)", file: file, line: line)
            }
        }
    }

    // MARK: §1 — no scores, no grades, no green ticks

    /// "this is a product for people with real adversaries, and a screen that
    /// says 'you are 80% secure' is a lie." There is no aggregate in the
    /// model, and this is the test that keeps one from being added.
    func testThereIsNoScoreAnywhere() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true
        i.biometricOn = true
        i.duressSet = true
        i.deadmanDays = 7
        i.contactsTotal = 4
        i.contactsVerified = 4
        i.accessDays = 200
        i.lastBackup = i.now.timeIntervalSince1970
        let all = SecurityStatus.surface(i).joined(separator: " ").lowercased()
        for banned in ["%", "score", "grade", "rating", "protected", "\u{2713}",
                       "\u{2717}", "good", "excellent", "weak", "strong"] {
            XCTAssertFalse(all.contains(banned), "the page graded the user: `\(banned)`")
        }
        // …and the fully-hardened page still names the one thing it cannot
        // close: the password iOS may have copied into iCloud Keychain.
        XCTAssertTrue(all.contains("icloud keychain"))
    }

    /// The right-hand column exists on every row in every state — it IS the
    /// feature, and a blank one is the failure mode §1 was written against.
    func testEveryRowHasSomethingInTheRightHandColumn() {
        for enrolled in [false, true] {
            for on in [false, true] {
                for total in 0...3 {
                    var i = SecurityStatus.Input()
                    i.biometryEnrolled = enrolled
                    i.biometricOn = on
                    i.duressSet = on
                    i.screenshotShield = on
                    i.wakePush = on
                    i.proxyOn = on
                    i.readReceipts = on
                    i.typingIndicators = on
                    i.desktopLinkBrowser = on ? "safari" : nil
                    i.desktopLinkSince = "14:32"
                    i.autoLockMinutes = on ? 5 : nil
                    i.deadmanDays = on ? 7 : nil
                    i.contactsTotal = total
                    i.contactsVerified = on ? total : 0
                    i.accessDays = on ? 30 : 0
                    i.lastBackup = on ? i.now.timeIntervalSince1970 : nil
                    for r in SecurityStatus.groups(i).flatMap(\.rows) {
                        XCTAssertFalse(r.label.isEmpty)
                        XCTAssertFalse(r.value.isEmpty, "`\(r.label)` had no state")
                        // one documented exception, and exactly one: §1's table
                        // gives `access key` with days left no second sentence,
                        // and inventing a thirteenth line the other platform
                        // does not have would be worse than a blank one.
                        if r.label == "access key" && i.accessDays > 0 { continue }
                        XCTAssertFalse(r.note.isEmpty, "`\(r.label)` had no cost")
                        XCTAssertEqual(r.label, r.label.lowercased(),
                                       "row labels are lowercase like every other row in the app")
                    }
                }
            }
        }
    }

    // MARK: §4.3 — tapping a row reaches the control that changes it

    /// Every row that a `[cfg]` control can change carries that control's
    /// anchor, and the two that no control changes carry none — named here so
    /// that adding a setting for one of them is a test failure rather than a
    /// silent omission.
    func testAnchors() {
        var i = SecurityStatus.Input()
        i.biometryLabel = "face id"
        i.biometryEnrolled = true
        let rows = SecurityStatus.groups(i).flatMap(\.rows)
        let byLabel = Dictionary(uniqueKeysWithValues: rows.map { ($0.label, $0.anchor) })
        XCTAssertEqual(byLabel["face id"], .biometric)
        XCTAssertEqual(byLabel["auto-lock"], .autoLock)
        XCTAssertEqual(byLabel["duress pin"], .duress)
        XCTAssertEqual(byLabel["dead-man switch"], .deadman)
        XCTAssertEqual(byLabel["screenshot shield"], .screenshotShield)
        XCTAssertEqual(byLabel["wake push"], .wakePush)
        XCTAssertEqual(byLabel["proxy"], .proxy)
        XCTAssertEqual(byLabel["read receipts"], .readReceipts)
        XCTAssertEqual(byLabel["typing indicators"], .typing)
        XCTAssertEqual(byLabel["desktop link"], .desktopLink)
        XCTAssertEqual(byLabel["backup"], .backup)
        XCTAssertEqual(byLabel["access key"], .accessKey)
        // §4: these two are INERT. Nothing in [cfg] sets the wrong-attempt
        // counter at all, and the control for a contact's safety number is
        // inside that chat rather than in settings.
        XCTAssertNil(byLabel["wrong attempts"] ?? nil)
        XCTAssertNil(byLabel["verified contacts"] ?? nil)
        // …and so is the biometric row when nothing is enrolled, because
        // [cfg] does not render a row for it to land on.
        var bare = SecurityStatus.Input()
        bare.biometryLabel = "face id"
        bare.biometryEnrolled = false
        XCTAssertNil(SecurityStatus.groups(bare)[0].rows.first?.anchor ?? nil)
        // every anchor the enum declares is actually used by some row
        let used = Set(rows.compactMap(\.anchor))
        XCTAssertEqual(used, Set(SecurityStatus.CfgAnchor.allCases))
    }

    // MARK: the small pure pieces

    func testVerifiedContactsLine() {
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 0, total: 0),
                       "no contacts on this phone yet")
        XCTAssertEqual(SecurityStatus.verifiedValue(verified: 0, total: 0), "none")
        XCTAssertEqual(SecurityStatus.verifiedValue(verified: 1, total: 4), "1 of 4")
        XCTAssertEqual(SecurityStatus.verifiedValue(verified: 9, total: 2), "2 of 2")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 1, total: 4),
                       "the others could be anyone")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 3, total: 4),
                       "the other could be anyone")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 4, total: 4), "all 4 verified")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 0, total: 1),
                       "none verified \u{2014} any of them could be anyone")
        // nonsense in, nothing broken out
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: 9, total: 2), "all 2 verified")
        XCTAssertEqual(SecurityStatus.verifiedNote(verified: -3, total: -1),
                       "no contacts on this phone yet")
    }

    func testBackupValueAndDate() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let t = now.timeIntervalSince1970
        XCTAssertEqual(SecurityStatus.backupValue(nil, now: now), "NEVER")
        XCTAssertEqual(SecurityStatus.backupValue(t, now: now), "today")
        XCTAssertEqual(SecurityStatus.backupValue(t - 3_600, now: now), "today")
        XCTAssertEqual(SecurityStatus.backupValue(t - 86_400, now: now), "1 day ago")
        XCTAssertEqual(SecurityStatus.backupValue(t - 40 * 86_400, now: now), "40 days ago")
        // a clock that went backwards reads as today, never as a negative age
        XCTAssertEqual(SecurityStatus.backupValue(t + 86_400, now: now), "today")

        // §1: "every clock and date on this page is local, never UTC." The
        // age in the value column is counted in local hours, so a UTC date
        // beside it makes an evening backup west of Greenwich read `today`
        // next to tomorrow's date — one line on the page disagreeing with the
        // rest of it. This is the bug that test pins.
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let expected = cal.dateComponents([.year, .month, .day], from: now)
        XCTAssertEqual(SecurityStatus.backupDate(t),
                       String(format: "%04d-%02d-%02d",
                              expected.year!, expected.month!, expected.day!))
        // 2025-09-10, 21:00 in New York is already 2025-09-11 in UTC. The row
        // must say the tenth — the day the person was on when they made the
        // file — and this is the exact instant where UTC gets it wrong.
        let evening = 1_757_552_400.0                      // 2025-09-11 01:00 UTC
        XCTAssertEqual(SecurityStatus.backupDate(evening,
                                                 zone: TimeZone(identifier: "America/New_York")!),
                       "2025-09-10")
        XCTAssertEqual(SecurityStatus.backupDate(evening, zone: TimeZone(secondsFromGMT: 0)!),
                       "2025-09-11", "…and that is exactly what UTC would have printed")
        // …and it is UNIX seconds. Reading these as Swift reference-date
        // seconds would land in 2032 and nobody would notice until a user
        // asked why their backup is in the future.
        XCTAssertEqual(SecurityStatus.backupDate(1_757_462_400,
                                                 zone: TimeZone(secondsFromGMT: 0)!),
                       "2025-09-10")
        var i = SecurityStatus.Input()
        i.now = now
        i.lastBackup = t
        XCTAssertEqual(row(i, "backup")!.note, "last made \(SecurityStatus.backupDate(t))")
    }

    func testAccessKeyRow() {
        var i = SecurityStatus.Input()
        i.accessDays = 0
        XCTAssertEqual(row(i, "access key")!.value, "EXPIRED")
        XCTAssertEqual(row(i, "access key")!.note, "messaging is off")
        i.accessDays = 1
        XCTAssertEqual(row(i, "access key")!.value, "1 day")
        XCTAssertEqual(row(i, "access key")!.note, "")
    }

    /// §1: `backup` "never prints a date twice" — the age is the value, the
    /// absolute day is the note, and they are two renderings of one instant.
    func testBackupPrintsTheAgeAndTheDayButNotTheSameOneTwice() {
        var i = SecurityStatus.Input()
        i.now = Date(timeIntervalSince1970: 1_758_499_200)
        i.lastBackup = i.now.timeIntervalSince1970 - 12 * 86_400
        let r = row(i, "backup")!
        XCTAssertEqual(r.value, "12 days ago")
        XCTAssertEqual(r.note,
                       "last made \(SecurityStatus.backupDate(i.lastBackup!))")
        XCTAssertFalse(r.note.contains(r.value), "the row printed its age twice")
        XCTAssertFalse(r.value.contains("-"), "the row printed the date twice")
    }

    // MARK: §1 — the iOS password-manager warning

    /// "The one real hole in 'the password never leaves your device' is that
    /// iOS may offer to save it, and the user has no other way to discover
    /// that." It belongs in the group about what leaves the phone, and it says
    /// that no wipe in the app reaches it.
    func testPasswordManagerWarningIsInTheRightGroupAndSaysTheHardPart() {
        let g = SecurityStatus.groups(SecurityStatus.Input())
        XCTAssertNil(g[0].footer)
        XCTAssertNil(g[2].footer)
        XCTAssertEqual(g[1].footer, SecurityStatus.passwordManagerWarning)
        let w = SecurityStatus.passwordManagerWarning.lowercased()
        XCTAssertTrue(w.contains("icloud keychain"), "it names where the copy goes")
        XCTAssertTrue(w.contains("duress pin"), "…and that the duress pin does not reach it")
        XCTAssertTrue(w.contains("burn everything"), "…nor does burn everything")
        XCTAssertEqual(w, w.lowercased(), "the app's ordinary voice")
    }

    // MARK: §4.1 — "driven from a store rather than a fixture"

    /// The wiring, not the mapping: a real `SecureStore` with real settings,
    /// read through the same call the screen makes. A pure-function suite over
    /// a hand-built `Input` cannot catch a row plumbed to the wrong field.
    func testInputIsReadFromARealStore() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-status-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = SecureStore(creatingWithPassword: "status-test-pass-1",
                                at: dir.appendingPathComponent("pyro.store"))
        store.update { b in
            b.settings.autoLockMinutes = 15
            b.settings.wipeAfterDays = 30
            b.settings.blockScreenshots = false
            b.settings.pushEnabled = true
            b.settings.socksProxy = "127.0.0.1:9050"
            b.settings.sendReadReceipts = true
            b.settings.sendTyping = false
            b.settings.lastBackupAt = Date().timeIntervalSince1970 - 2 * 86_400
            b.contacts = [
                Contact(hash: "a", name: "alice", addedAt: Date(), verified: true),
                Contact(hash: "b", name: "bob", addedAt: Date(), verified: false),
                Contact(hash: "c", name: "carol", addedAt: Date(), verified: false),
            ]
        }
        let service = ChatService(store: store)
        let i = StatusView.live(service)

        XCTAssertEqual(i.autoLockMinutes, 15)
        XCTAssertEqual(i.deadmanDays, 30)
        XCTAssertFalse(i.screenshotShield)
        XCTAssertTrue(i.wakePush)
        XCTAssertTrue(i.proxyOn)
        XCTAssertTrue(i.readReceipts)
        XCTAssertFalse(i.typingIndicators)
        // no browser paired in a bare service, and the row says so rather
        // than vanishing
        XCTAssertNil(i.desktopLinkBrowser)
        XCTAssertNil(StatusView.desktopLink(service)?.browser)
        XCTAssertEqual(row(i, "desktop link")!.value, "OFF")
        XCTAssertEqual(i.contactsTotal, 3)
        XCTAssertEqual(i.contactsVerified, 1)
        XCTAssertEqual(i.lastBackup, store.blob.settings.lastBackupAt)
        XCTAssertEqual(i.accessDays, 0)
        // and the page built from it says the true things
        // §1's value table: ON/OFF, never the interval — the interval is in
        // the note, where the table puts it.
        XCTAssertEqual(row(i, "auto-lock")!.value, "ON")
        XCTAssertEqual(row(i, "auto-lock")!.note, "relocks 15 minutes after you leave")
        XCTAssertEqual(row(i, "dead-man switch")!.value, "ON")
        XCTAssertEqual(row(i, "dead-man switch")!.note, "wipes if unopened for 30 days")
        XCTAssertEqual(row(i, "screenshot shield")!.value, "OFF")
        XCTAssertEqual(row(i, "verified contacts")!.value, "1 of 3")
        XCTAssertEqual(row(i, "backup")!.value, "2 days ago")

        // an empty proxy string is off, not "on with no address"
        store.update { $0.settings.socksProxy = "" }
        XCTAssertFalse(StatusView.live(service).proxyOn)

        // `probeBiometry: false` is the seam the view uses to refresh cheaply
        // on every service publish (the desktop-link row has to stay live).
        // It must change ONLY the three biometric fields.
        var full = StatusView.live(service)
        var cheap = StatusView.live(service, probeBiometry: false)
        full.biometryLabel = ""; cheap.biometryLabel = ""
        full.biometryEnrolled = false; cheap.biometryEnrolled = false
        full.biometricOn = false; cheap.biometricOn = false
        full.now = cheap.now
        XCTAssertEqual(full, cheap)
    }

    /// The `backup` row's field: optional, omitted while unset, and carried
    /// across a save/load. Omitted-when-unset is what keeps a store written by
    /// this build byte-identical to one written before the field existed, so
    /// no checked-in fixture has to move.
    func testLastBackupAtIsOptionalOmittedAndRoundTrips() throws {
        let plain = AppSettings()
        XCTAssertNil(plain.lastBackupAt)
        let encoded = try JSONEncoder().encode(plain)
        XCTAssertFalse(String(decoding: encoded, as: UTF8.self).contains("lastBackupAt"),
                       "unset must not appear in the store at all")
        // a store written before this field existed still decodes
        let old = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertNil(old.lastBackupAt)
        // and a set one survives the trip
        var set = AppSettings()
        set.lastBackupAt = 1_700_000_000
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(set))
        XCTAssertEqual(try XCTUnwrap(back.lastBackupAt), 1_700_000_000, accuracy: 0.001)
    }

    /// §1: "Both platforms spell it `lastBackupAt`, seconds since 1970."
    ///
    /// The spelling and the EPOCH both. Every other date in this blob is a
    /// Swift `Date`, which `JSONEncoder` writes as seconds since 2001-01-01;
    /// writing this one the same way would hand Android a number 978 307 200
    /// seconds small, and the row would read a date 31 years early on one
    /// platform and be silently dropped on the next save from the other.
    func testLastBackupAtIsUnixSecondsUnderTheSpecsExactKey() throws {
        var set = AppSettings()
        let stamp = Date(timeIntervalSince1970: 1_700_000_000)
        set.lastBackupAt = stamp.timeIntervalSince1970
        let json = String(decoding: try JSONEncoder().encode(set), as: UTF8.self)
        XCTAssertTrue(json.contains("\"lastBackupAt\":1700000000"),
                      "the key or the epoch drifted: \(json)")
        // the trap this guards: a Swift Date would encode 31 years smaller
        let asSwiftDate = try JSONEncoder().encode(["x": stamp])
        XCTAssertFalse(String(decoding: asSwiftDate, as: UTF8.self).contains("1700000000"))
    }

    /// docs/STATUS-AND-CFG.md §1's `duress pin SET`, and the `[cfg]` row that
    /// has to agree with it. Both read the Keychain; neither reads a flag that
    /// only the dialog sets.
    func testDuressPinIsSetReadsTheKeychain() {
        let previous = KeychainHelper.get("duress-verifier")
        defer {
            if let previous { KeychainHelper.set(previous, key: "duress-verifier") }
            else { KeychainHelper.delete("duress-verifier") }
        }
        KeychainHelper.delete("duress-verifier")
        XCTAssertFalse(SecureStore.duressPinIsSet)
        SecureStore.setDuressPin("2580")
        XCTAssertTrue(SecureStore.duressPinIsSet)
        KeychainHelper.delete("duress-verifier")
        XCTAssertFalse(SecureStore.duressPinIsSet)
    }
}
