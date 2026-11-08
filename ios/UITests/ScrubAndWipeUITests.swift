import XCTest

/// docs/SCRUB-AND-WIPE.md — the two screens the spec describes, captured.
///
/// Every capture is written to the runner's Documents directory as well as
/// attached to the result bundle, the same way `MessageDetailsUITests` does it.
final class ScrubAndWipeUITests: XCTestCase {

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// docs/DECOY-ACCOUNT.md §5: the app opens on the chooser now, on every
    /// device. Routing straight to a password field was never the assertion —
    /// BRANCHING on what the device holds was, and a form shown every time
    /// branches on nothing. The countdown lives where §A2 puts it, above the
    /// password field, which is now the first thing on screen.
    private func reachPasswordField(_ app: XCUIApplication) {
        // The password field IS the root now — the chooser cost a returning
        // user a tap on every launch, and a form shown unconditionally asserts
        // no more than a chooser shown unconditionally. Nothing to tap.
        XCTAssertTrue(app.secureTextFields.firstMatch.waitForExistence(timeout: 30),
                      "the app opens on the password field")
    }

    /// §A2: "From the seventh failure, above the password field, in the alert
    /// colour: 3 attempts left before this device wipes".
    func testUnlockScreenShowsTheCountdown() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=unlock", "--demo-noshield"]
        app.launch()
        reachPasswordField(app)
        let warning = app.staticTexts["3 attempts left before this device wipes"]
        XCTAssertTrue(warning.waitForExistence(timeout: 30),
                      "the countdown line must be above the password field")
        shot("unlock-wipe-warning")
    }

    /// The same screen one failure later — the line counts down.
    func testUnlockScreenCountsDown() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=unlock", "--demo-unlock-failures", "9",
                               "--demo-noshield"]
        app.launch()
        reachPasswordField(app)
        let warning = app.staticTexts["1 attempts left before this device wipes"]
        XCTAssertTrue(warning.waitForExistence(timeout: 30))
        shot("unlock-wipe-warning-last")
    }

    /// §B2: the `── what was removed before sending ──` section, directly
    /// after `── what it is ──`.
    func testDetailsSheetShowsWhatWasRemoved() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat", "--demo-scrub-row"]
        app.launch()
        let row = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS 'report.pdf'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the demo's outgoing file row")
        row.press(forDuration: 1.2)
        let details = app.buttons["details"]
        XCTAssertTrue(details.waitForExistence(timeout: 10))
        details.tap()
        let heading = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'what was removed before sending'")).firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 10),
                      "the receipt section must be on the sheet")
        XCTAssertTrue(app.staticTexts["J. Example"].exists, "…with the values as found")
        shot("details-what-was-removed")
    }
}

extension ScrubAndWipeUITests {
    /// §B3: "A single line under the compose bar as the attachment is staged,
    /// visible before it goes … Tapping it opens the same list."
    func testComposeBarShowsWhatWasRemoved() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat", "--demo-staged-file"]
        app.launch()
        let line = app.buttons["scrubLine"]
        XCTAssertTrue(line.waitForExistence(timeout: 30), "the pre-send line")
        XCTAssertEqual(line.label, "removed author, gps and 1 more from report.pdf")
        shotPublic("compose-scrub-line")
        line.tap()
        let heading = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'what was removed before sending'")).firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 10), "tapping opens the same list")
        shotPublic("compose-scrub-list")
    }

    func shotPublic(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }
}
