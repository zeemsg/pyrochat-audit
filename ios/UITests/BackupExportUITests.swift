import XCTest

/// The gap that let a real user hit "I export and nothing happens".
///
/// Nothing had ever pressed EXPORT through the interface. The encryption was
/// unit-tested, the file format was fixture-tested, and the one step between
/// them — the app actually offering somewhere to put the file — was tested by
/// nobody. Presenting the picker from inside the passphrase alert's action
/// raced the alert's dismissal and SwiftUI dropped it silently, so the status
/// line said the backup was ready and no picker ever appeared.
final class BackupExportUITests: XCTestCase {

    private func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s); a.name = name; a.lifetime = .keepAlways
        add(a)
    }

    private func reveal(_ app: XCUIApplication, _ e: XCUIElement) -> Bool {
        for _ in 0..<25 {
            if e.exists && e.isHittable { return true }
            app.swipeUp()
        }
        return e.exists && e.isHittable
    }

    func testExportOffersSomewhereToPutTheFile() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings", "--demo-noshield"]
        app.launch()
        XCTAssertTrue(app.staticTexts["theme"].waitForExistence(timeout: 90), "reached [cfg]")

        // The row carries an accessibilityLabel, which replaces its visible
        // text for queries — matching on `[ EXPORT ENCRYPTED BACKUP … ]` finds
        // nothing.
        let export = app.buttons["Export encrypted backup"]
        XCTAssertTrue(reveal(app, export), "the export row is reachable")
        export.tap()

        let dialog = app.alerts["BACKUP PASSPHRASE"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 30), "the passphrase dialog opened")
        let fields = dialog.secureTextFields
        XCTAssertEqual(fields.count, 2, "passphrase and confirm")
        fields.element(boundBy: 0).tap()
        fields.element(boundBy: 0).typeText("a-long-enough-passphrase-1")
        fields.element(boundBy: 1).tap()
        fields.element(boundBy: 1).typeText("a-long-enough-passphrase-1")
        dialog.buttons["EXPORT"].tap()

        XCTAssertTrue(dialog.waitForNonExistence(timeout: 20), "the passphrase dialog closed")

        // WHAT THIS CAN AND CANNOT SEE. The file picker is a separate process,
        // so it is not in this app's element tree — our own `[cfg]` nav bar
        // stays queryable underneath it. An earlier version of this test
        // asserted that nav bar disappeared and passed against a deliberately
        // broken build, which is worse than no test. The picker's presence was
        // verified by screenshot instead, on the shipped flow and on a
        // rewritten one, and both showed it.
        //
        // What IS assertable here is the app's half: after encrypting, it must
        // say a destination is still needed and must NOT claim a backup exists.
        // That was the real defect — "backup ready" reads as finished, so a
        // picker dismissed without choosing left the user believing a backup
        // was somewhere they could not find.
        let needsDestination = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'choose where to save it'")).firstMatch
        XCTAssertTrue(needsDestination.waitForExistence(timeout: 30),
                      "after encrypting, the app says a destination is still needed")
    }

    /// …and the status line says a destination is still needed, so a user who
    /// dismisses the picker is not left believing a backup exists.
    func testTheStatusLineDoesNotClaimTheBackupIsSavedYet() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings", "--demo-noshield"]
        app.launch()
        XCTAssertTrue(app.staticTexts["theme"].waitForExistence(timeout: 90))

        // The row carries an accessibilityLabel, which replaces its visible
        // text for queries — matching on `[ EXPORT ENCRYPTED BACKUP … ]` finds
        // nothing.
        let export = app.buttons["Export encrypted backup"]
        XCTAssertTrue(reveal(app, export))
        export.tap()
        let dialog = app.alerts["BACKUP PASSPHRASE"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 30))
        dialog.secureTextFields.element(boundBy: 0).tap()
        dialog.secureTextFields.element(boundBy: 0).typeText("a-long-enough-passphrase-1")
        dialog.secureTextFields.element(boundBy: 1).tap()
        dialog.secureTextFields.element(boundBy: 1).typeText("a-long-enough-passphrase-1")
        dialog.buttons["EXPORT"].tap()

        let claimed = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'backup saved'")).firstMatch
        XCTAssertFalse(claimed.waitForExistence(timeout: 5),
                       "nothing may claim the backup is saved before a destination is chosen")
    }
}
