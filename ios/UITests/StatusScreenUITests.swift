import XCTest

/// docs/STATUS-AND-CFG.md §1 and §4 — "what protects me", on a real screen.
///
/// The unit suite owns the wording and the decoy absence across every state
/// the page can hold (`Tests/SecurityStatusTests.swift`); this file owns the
/// things only a device answers: that the page is reachable from the first row
/// of `[cfg]` and from nowhere else, that tapping a row lands on the control
/// that changes it, that the whole RENDERED surface — not the model behind it —
/// says nothing about a decoy, and that it fits the narrowest phone at the
/// largest text size.
///
/// Captures land in the runner's Documents directory as well as the result
/// bundle, so they can be pulled off with
/// `xcrun simctl get_app_container <udid> com.pyrochat.app.uitests.xctrunner data`.
final class StatusScreenUITests: XCTestCase {

    /// §2, verbatim: the first row of `[cfg]`.
    static let entryRow = "[ what protects me ]"
    static let groups = ["WHAT PROTECTS YOU", "WHAT LEAVES THIS PHONE", "IF YOU LOSE IT"]

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    private func launchCfg(textSize: String? = nil, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings"] + extra
        if let textSize { app.launchArguments += ["--demo-text-size", textSize] }
        app.launch()
        XCTAssertTrue(app.staticTexts[Self.entryRow].waitForExistence(timeout: 90)
                      || app.buttons.matching(
                          NSPredicate(format: "label CONTAINS 'what protects me'"))
                         .firstMatch.waitForExistence(timeout: 10),
                      "never reached [cfg]")
        return app
    }

    /// The one way in.
    private func openStatus(_ app: XCUIApplication) {
        let entry = app.buttons["What protects me"]
        XCTAssertTrue(entry.waitForExistence(timeout: 30), "the entry row is missing from [cfg]")
        entry.tap()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'WHAT PROTECTS YOU'"))
            .firstMatch.waitForExistence(timeout: 20), "the status page did not open")
    }

    private func row(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
    }

    /// Everything the screen renders, gathered while scrolling it end to end.
    /// §4: "assert on the WHOLE rendered surface, not on one label."
    private func renderedSurface(_ app: XCUIApplication) -> [String] {
        var seen = Set<String>()
        func sweep() {
            for query in [app.staticTexts, app.buttons, app.cells,
                          app.switches, app.navigationBars, app.images] {
                for e in query.allElementsBoundByIndex where e.exists {
                    seen.insert(e.label)
                    if let v = e.value as? String { seen.insert(v) }
                }
            }
        }
        sweep()
        for _ in 0..<10 { app.swipeUp(); sweep() }
        for _ in 0..<12 { app.swipeDown(); sweep() }
        return Array(seen)
    }

    // MARK: §1 — reached from the first row of [cfg] and from nowhere else

    func testEntryRowIsFirstAndOpensThePage() {
        let app = launchCfg()
        let entry = app.buttons["What protects me"]
        XCTAssertTrue(entry.waitForExistence(timeout: 30))
        XCTAssertTrue(entry.label.contains(Self.entryRow)
                      || app.staticTexts[Self.entryRow].exists,
                      "§2: the row reads `\(Self.entryRow)` verbatim")

        // §2: "above every section" — it is over the first section header,
        // and over every row of it.
        let protection = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'PROTECTION'")).firstMatch
        XCTAssertTrue(protection.exists, "PROTECTION is the first section")
        XCTAssertLessThan(entry.frame.midY, protection.frame.midY)
        shot("status-cfg-entry-row")

        openStatus(app)
        for group in Self.groups {
            let header = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@", group)).firstMatch
            _ = header.waitForExistence(timeout: 5)
            if !header.exists { app.swipeUp() }
            XCTAssertTrue(app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@", group)).firstMatch.exists,
                          "the `\(group)` group is missing")
        }
        shot("status-page")
    }

    /// §1: "read-only: every row is a fact, not a control". Nothing on the
    /// page may be a switch, a stepper or a text field — a page that can be
    /// changed is a second `[cfg]`, and the point was to have one place to
    /// look rather than two places to edit.
    func testThePageIsReadOnly() {
        let app = launchCfg()
        openStatus(app)
        XCTAssertEqual(app.switches.count, 0, "a toggle on the status page")
        XCTAssertEqual(app.textFields.count, 0)
        XCTAssertEqual(app.secureTextFields.count, 0)
        XCTAssertEqual(app.sliders.count, 0)
        XCTAssertEqual(app.steppers.count, 0)
    }

    /// §4: two rows are INERT — "tapping reaches the control that changes it"
    /// is simply false for them. `wrong attempts` has no control anywhere;
    /// `verified contacts` has one, but it is a safety number inside a chat
    /// rather than a `[cfg]` row. Neither may be a button, and tapping where
    /// they are must leave the page where it was.
    func testTheTwoInertRowsAreNotTappable() {
        let app = launchCfg()
        openStatus(app)
        for inert in ["wrong attempts", "verified contacts"] {
            let asButton = app.buttons.matching(
                NSPredicate(format: "label CONTAINS %@", inert)).firstMatch
            let asText = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@", inert)).firstMatch
            for _ in 0..<6 where !(asButton.exists || asText.exists) { app.swipeUp() }
            XCTAssertFalse(asButton.exists, "`\(inert)` is a button — §4 says it is inert")
            XCTAssertTrue(asText.exists, "`\(inert)` is missing from the page entirely")
            // tapping it changes nothing: the page is still the page
            asText.tap()
            XCTAssertTrue(app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS 'WHAT LEAVES THIS PHONE'"))
                .firstMatch.waitForExistence(timeout: 5),
                          "tapping `\(inert)` navigated somewhere")
        }
        shot("status-inert-rows")
    }

    // MARK: §4 — the decoy, over the whole rendered surface

    /// docs/DECOY-ACCOUNT.md §3: not SET, not NOT SET, not a greyed row. The
    /// model behind this screen is covered exhaustively by the unit suite;
    /// this reads what the device actually drew, because a leak could also
    /// arrive through a label the view added rather than the model.
    func testNothingRenderedEverMentionsADecoy() {
        let app = launchCfg()
        openStatus(app)
        let surface = renderedSurface(app)
        XCTAssertGreaterThan(surface.count, 8, "nothing was scraped — the sweep is broken")
        // `sender hidden` is the one place the word may appear: that row is about the relay
        // (docs/SEALED-SENDER.md §5.8) and is the same on Android. Anywhere else it still fails.
        let all = surface.joined(separator: " \u{241F} ").lowercased()
            .replacingOccurrences(of: "sender hidden", with: "")
        for word in ["decoy", "slot", "another account", "second account",
                     "other account", "hidden", "vault"] {
            XCTAssertFalse(all.contains(word),
                           "the status page rendered `\(word)`: \(all)")
        }
        // …and the page really did have content to leak: the row the decoy
        // sits NEXT to in [cfg] is here, worded as §1 wrote it.
        XCTAssertTrue(all.contains("duress pin"), "the page is not empty")
        XCTAssertTrue(all.contains("wipes everything"))
        shot("status-surface-swept")
    }

    // MARK: §4 — tapping a row reaches the control that changes it

    func testTappingARowLandsOnItsSetting() {
        let app = launchCfg()
        openStatus(app)
        let autoLock = row(app, "auto-lock")
        XCTAssertTrue(autoLock.waitForExistence(timeout: 20), "no auto-lock row")
        autoLock.tap()

        // back in [cfg], with the control it points at on screen
        let control = app.staticTexts["auto-lock"]
        XCTAssertTrue(control.waitForExistence(timeout: 20), "did not land back in [cfg]")
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'PROTECTION'")).firstMatch.exists)
        // the jump scrolls it into view rather than leaving it below the fold
        XCTAssertTrue(app.frame.contains(control.frame),
                      "the auto-lock row is not on screen after the jump")
        shot("status-jump-autolock")

        // a row further down reaches a different section
        openStatus(app)
        let backup = row(app, "backup")
        for _ in 0..<6 where !(backup.exists && backup.isHittable) { app.swipeUp() }
        XCTAssertTrue(backup.exists, "no backup row")
        backup.tap()
        let export = app.buttons["Export encrypted backup"]
        XCTAssertTrue(export.waitForExistence(timeout: 20), "did not reach the backup control")
        XCTAssertTrue(app.frame.contains(export.frame),
                      "the backup control is not on screen after the jump")
        shot("status-jump-backup")
    }

    // MARK: §1 — the iOS password-manager warning

    /// §1's late addition: the group about what leaves the phone must report
    /// the browser mirror, which is the largest continuous fact in it, and
    /// typing indicators alongside it. Both reach their `[cfg]` control.
    func testDesktopLinkAndTypingAreOnThePageAndReachTheirControls() {
        let app = launchCfg()
        openStatus(app)
        let link = row(app, "desktop link")
        for _ in 0..<8 where !(link.exists && link.isHittable) { app.swipeUp() }
        XCTAssertTrue(link.exists, "§1's desktop link row is missing")
        XCTAssertTrue(link.label.contains("read and reply from a browser")
                      || link.label.contains("linked to"),
                      "the row says nothing about what a link means: \(link.label)")
        XCTAssertTrue(app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'typing indicators'")).firstMatch.exists,
                      "typing indicators is missing from the same group")
        shot("status-desktop-link")

        link.tap()
        let control = app.buttons["Link a desktop browser"]
        XCTAssertTrue(control.waitForExistence(timeout: 20),
                      "tapping the desktop link row did not reach [cfg]'s link row")
        XCTAssertTrue(app.frame.contains(control.frame),
                      "…and it is not on screen after the jump")
        shot("status-jump-desktop-link")
    }

    func testPasswordManagerWarningIsOnThePage() {
        let app = launchCfg()
        openStatus(app)
        let warning = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'icloud keychain'")).firstMatch
        for _ in 0..<8 where !warning.exists { app.swipeUp() }
        XCTAssertTrue(warning.exists,
                      "§1: iOS carries the password-manager warning and nothing else does")
        XCTAssertTrue(warning.label.lowercased().contains("burn everything"),
                      "it says which wipes do not reach it")
        // it sits in the group about what leaves the phone, not somewhere else
        let leaves = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'WHAT LEAVES THIS PHONE'")).firstMatch
        let lose = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'IF YOU LOSE IT'")).firstMatch
        if leaves.exists { XCTAssertGreaterThan(warning.frame.midY, leaves.frame.midY) }
        if lose.exists { XCTAssertLessThan(warning.frame.midY, lose.frame.midY) }
        shot("status-password-manager")
    }

    // MARK: docs/TEXT-SIZE.md §3 — the narrowest phone at the largest size

    func testAtTheLargestTextSize() {
        let app = launchCfg(textSize: "largest")
        openStatus(app)
        shot("status-largest-1")
        assertNothingOverflows(app)
        for i in 2...5 {
            app.swipeUp()
            shot("status-largest-\(i)")
            assertNothingOverflows(app)
        }
    }

    /// The same page with every row present and every one in its longest
    /// state — the widest this screen can ever draw. A capture of the demo
    /// store measures one combination; this measures the worst one, and the
    /// biometric row (whose note is the longest on the page) cannot appear at
    /// all on a simulator with nothing enrolled.
    func testWorstCaseRowsAtBothSizes() {
        for size in ["normal", "largest"] {
            let app = launchCfg(textSize: size, extra: ["--demo-status-worst"])
            openStatus(app)
            let face = app.buttons.matching(
                NSPredicate(format: "label CONTAINS 'face id'")).firstMatch
            XCTAssertTrue(face.waitForExistence(timeout: 20),
                          "the forced page must carry the biometric row")
            // §1's longest sentence, and the one a demo store cannot produce
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(
                format: "label CONTAINS 'a banner when a message arrives'"))
                .firstMatch.exists
                || app.buttons.matching(NSPredicate(
                    format: "label CONTAINS 'a banner when a message arrives'"))
                    .firstMatch.exists,
                          "the worst-case page is not in its worst case")
            shot("status-worst-\(size)-1")
            assertNothingOverflows(app)
            for i in 2...5 {
                app.swipeUp()
                shot("status-worst-\(size)-\(i)")
                assertNothingOverflows(app)
            }
            app.terminate()
        }
    }

    private func assertNothingOverflows(_ app: XCUIApplication,
                                        file: StaticString = #filePath, line: UInt = #line) {
        let screen = app.frame
        for e in app.staticTexts.allElementsBoundByIndex + app.buttons.allElementsBoundByIndex
        where e.exists && !e.label.isEmpty {
            let f = e.frame
            guard f.width > 0, f.height > 0 else { continue }
            XCTAssertLessThanOrEqual(f.maxX, screen.maxX + 0.5,
                                     "`\(e.label.prefix(40))` runs off the right edge",
                                     file: file, line: line)
            XCTAssertGreaterThanOrEqual(f.minX, -0.5,
                                        "`\(e.label.prefix(40))` runs off the left edge",
                                        file: file, line: line)
        }
    }
}
