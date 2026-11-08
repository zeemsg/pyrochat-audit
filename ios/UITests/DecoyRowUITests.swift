import XCTest

/// docs/DECOY-ACCOUNT.md §3, on a real screen at the narrowest phone in the
/// simulator. Earlier waves on this repo found genuine layout defects this way
/// that no unit test caught — a `[cfg]` row is a long lowercase string next to
/// a long lowercase footnote, and both of those are exactly the shape that
/// clips or folds when the width runs out.
///
/// Captures land in the runner's Documents directory as well as the result
/// bundle, so they can be pulled off with
/// `xcrun simctl get_app_container <udid> com.pyrochat.app.uitests.xctrunner data`.
final class DecoyRowUITests: XCTestCase {

    /// §3's row, verbatim — the same string in the app and here, because the
    /// whole property is that it never changes.
    static let rowLabel = "set decoy password \u{2026}"

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    private func launchSettings(textSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings"]
        if let textSize { app.launchArguments += ["--demo-text-size", textSize] }
        app.launch()
        return app
    }

    /// Scroll a list until `element` is on screen, or give up.
    @discardableResult
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    /// Scroll until a piece of text is not just realised but fully ON SCREEN.
    ///
    /// `reveal` stops as soon as its element is hittable, which is the wrong
    /// stopping point for anything measured rather than tapped: the guidance
    /// sits BELOW the row, so a scroll that lands the row at the bottom edge
    /// leaves the line it is being asserted about off-screen or unrealised.
    /// That made this test pass or fail on where the scroll happened to stop.
    /// Assert on the element you are measuring, and scroll until you can.
    private func revealText(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        for _ in 0..<8 {
            if element.exists && app.frame.contains(element.frame) { return true }
            app.swipeUp()
        }
        return element.exists && app.frame.contains(element.frame)
    }

    /// §3: the row is in PROTECTION, reads as an ordinary lowercase `[cfg]`
    /// row, and carries the one line of guidance §3 and §6.2 between them
    /// require.
    ///
    /// docs/STATUS-AND-CFG.md §2 moved it out of SAFETY, which no longer
    /// exists; §2's "it moves section; it does not change" is why everything
    /// else asserted below is unchanged from before that move.
    func testSafetySectionWithTheDecoyRow() {
        let app = launchSettings()
        // the top of [cfg] first — the decoy row is far enough down that a
        // SwiftUI List has not realised it yet, so waiting on it would just
        // time out. `[ what protects me ]` is now the screen's first row.
        XCTAssertTrue(app.staticTexts["[ what protects me ]"].waitForExistence(timeout: 90)
                      || app.buttons["What protects me"].waitForExistence(timeout: 10),
                      "reached [cfg]")

        let row = app.buttons[Self.rowLabel]
        XCTAssertTrue(reveal(app, row), "the decoy row is reachable in PROTECTION")
        shot("decoy-cfg-safety")

        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'PROTECTION'")).firstMatch.exists,
                      "and it is in PROTECTION, not somewhere else")
        // §3: no checkmark, no "configured", no colour change. The label is the
        // SAME string whatever the device holds, so assert it verbatim.
        XCTAssertEqual(row.label, Self.rowLabel)

        // the footnote wraps rather than clipping: §3's one line of guidance
        let note = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'a second password that opens'")).firstMatch
        XCTAssertTrue(revealText(app, note), "the guidance line is on screen")
        XCTAssertGreaterThan(note.frame.height, 30,
                             "it wrapped to several lines instead of being truncated to one")
        XCTAssertLessThanOrEqual(note.frame.maxX, app.frame.maxX,
                                 "and it stays inside the screen at the narrowest width")
        XCTAssertGreaterThanOrEqual(note.frame.minX, 0)

        // §2's biometric row must still sit directly above auto-lock. It and
        // the decoy row are now in the same section; the decoy row goes BELOW
        // the dead-man rows, never between these two.
        let bio = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH 'unlock with'")).firstMatch
        let autoLock = app.staticTexts["auto-lock"]
        if bio.exists && autoLock.exists {
            XCTAssertLessThan(bio.frame.midY, autoLock.frame.midY)
        }
    }

    /// §3's dialog: a username, a password, and nothing else. No "seed it with
    /// fake messages" checkbox, because a fabricated history that reads like
    /// fabrication is worse than a thin real one.
    func testDecoyCreationDialog() {
        let app = launchSettings()
        XCTAssertTrue(app.buttons["What protects me"].waitForExistence(timeout: 90), "reached [cfg]")
        let row = app.buttons[Self.rowLabel]
        XCTAssertTrue(reveal(app, row))
        row.tap()

        let dialog = app.alerts["DECOY PASSWORD"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 30), "the dialog opened")
        XCTAssertTrue(dialog.textFields["username"].waitForExistence(timeout: 10))
        shot("decoy-create-dialog")

        XCTAssertTrue(dialog.textFields["username"].exists)
        XCTAssertTrue(dialog.secureTextFields["password"].exists)
        XCTAssertTrue(dialog.buttons["SET"].exists)
        XCTAssertTrue(dialog.buttons["ABORT"].exists)
        XCTAssertEqual(dialog.checkBoxes.count, 0, "§3: nothing else")
        XCTAssertEqual(dialog.textFields.count, 1)
        XCTAssertEqual(dialog.secureTextFields.count, 1)

        // typing into it, captured as the user would see it
        dialog.textFields["username"].tap()
        dialog.textFields["username"].typeText("spare_line")
        dialog.secureTextFields["password"].tap()
        dialog.secureTextFields["password"].typeText("a-second-password-1")
        shot("decoy-create-dialog-filled")

        // The field must actually be holding what was typed. A password field
        // that silently swallows input would make §3's "the password must not
        // be the real one" unfalsifiable from the user's side: they would be
        // setting a decoy to the empty string without being told.
        XCTAssertEqual(dialog.textFields["username"].value as? String, "spare_line")
        let masked = dialog.secureTextFields["password"].value as? String ?? ""
        XCTAssertFalse(masked.isEmpty,
                       "the secure field is holding the password (rendered masked)")
        XCTAssertFalse(masked.contains("a-second-password-1"),
                       "…and not showing it in the clear")

        dialog.buttons["ABORT"].tap()
        XCTAssertTrue(app.buttons[Self.rowLabel].waitForExistence(timeout: 10))
        XCTAssertFalse(dialog.exists)
        // §3: aborting leaves the row exactly as it was, and says nothing
        XCTAssertFalse(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'configured'")).firstMatch.exists)
        shot("decoy-cfg-after-abort")
    }

    /// docs/TEXT-SIZE.md §3 on the new row: the widest the app ever renders,
    /// on the narrowest phone. A lowercase row label plus a five-line footnote
    /// is exactly the shape that clips when both go up a size.
    func testSafetySectionAtTheLargestTextSize() {
        let app = launchSettings(textSize: "largest")
        XCTAssertTrue(app.buttons["What protects me"].waitForExistence(timeout: 90), "reached [cfg]")
        let row = app.buttons[Self.rowLabel]
        XCTAssertTrue(reveal(app, row), "the row is still reachable at largest")
        shot("decoy-cfg-safety-largest")

        XCTAssertEqual(row.label, Self.rowLabel, "and not truncated to fit")
        XCTAssertLessThanOrEqual(row.frame.maxX, app.frame.maxX)
        let note = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'a second password that opens'")).firstMatch
        XCTAssertTrue(note.exists)
        XCTAssertLessThanOrEqual(note.frame.maxX, app.frame.maxX,
                                 "the guidance line stays inside the screen at largest")
        XCTAssertGreaterThanOrEqual(note.frame.minX, 0)
    }

    /// The gap this file had: every other test types into the dialog and then
    /// presses ABORT, so nothing here has ever pressed SET. `createDecoy` is
    /// covered by the unit suite, but the wiring between the button, the work
    /// it starts and the line the user is supposed to read was not covered by
    /// anything — which is exactly where "I set a username and password and
    /// nothing happened" lives.
    private static let resultTitle = "DECOY PASSWORD"

    func testPressingSetActuallyReportsAnOutcome() {
        let app = launchSettings()
        XCTAssertTrue(app.buttons["What protects me"].waitForExistence(timeout: 90), "reached [cfg]")
        let row = app.buttons[Self.rowLabel]
        XCTAssertTrue(reveal(app, row))
        row.tap()

        let dialog = app.alerts["DECOY PASSWORD"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 30), "the dialog opened")
        XCTAssertTrue(dialog.textFields["username"].waitForExistence(timeout: 10))

        // A FRESH name every run. The decoy registers for real on the relay,
        // which remembers it — so a fixed name passes once and then reports
        // "that username is already taken" forever after, which is a correct
        // answer to a badly written test.
        let unique = "spare_\(UUID().uuidString.prefix(8).lowercased())"
        dialog.textFields["username"].tap()
        dialog.textFields["username"].typeText(unique)
        dialog.secureTextFields["password"].tap()
        dialog.secureTextFields["password"].typeText("a-second-password-1")
        dialog.buttons["SET"].tap()

        // Whatever the outcome, the user must be TOLD one, in a dialog they
        // cannot scroll past and the system's "Save Password?" sheet cannot
        // cover. Silence is the bug this test exists for.
        //
        // Both dialogs carry the same title on purpose — the result must not
        // announce itself as success or failure before it is read — so the
        // result is identified by its dismiss button, not by its title.
        let ok = app.alerts.buttons["OK"]
        XCTAssertTrue(ok.waitForExistence(timeout: 60),
                      "SET said nothing at all — neither success nor failure")
        let said = app.alerts.firstMatch.staticTexts.allElementsBoundByIndex
            .map(\.label).joined(separator: " ")
        shot("decoy-after-set")
        XCTAssertTrue(said.contains("decoy password set") || said.contains("could not set decoy password"),
                      "the dialog reports an outcome, not an empty box: \(said)")
        ok.tap()
        XCTAssertTrue(app.buttons[Self.rowLabel].waitForExistence(timeout: 20),
                      "and dismisses back to [cfg]")
    }
}
