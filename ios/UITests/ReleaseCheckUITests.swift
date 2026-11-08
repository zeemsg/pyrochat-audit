import XCTest

/// The RELEASE build, end to end, before an upload. Every other test here and every unit test
/// runs a Debug build; this is the one look at the binary as it ships (optimised, no Debug code,
/// the production trust root only).
///
/// `deploy/ios-release-check.sh` runs it: a Debug build makes an identity with the autopilot's
/// password (iOS offers to generate a password on the create form, and a UI test cannot type past
/// that offer), the Release build is installed over it, and this unlocks it and goes on from
/// there against a relay on this machine: first contact with pyrobot, a voice note in a changed
/// voice, the status page, and a capture of the screen at the start and at the end, which has to
/// be black. Skipped unless the script runs it.
final class ReleaseCheckUITests: XCTestCase {
    /// `DemoAutopilot`'s password: the Debug build created the identity with it.
    private static let password = "demo-pass-123"

    func testTheReleaseBuildUnlocksMeetsTheBotSendsAChangedVoiceNoteAndHidesTheSender() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["PYRO_RELEASE_CHECK"] == "1",
                          "run by deploy/ios-release-check.sh, against an installed Release build")
        continueAfterFailure = false
        let app = XCUIApplication()
        // the one switch a Release build has: a process that looks like a test host talks to
        // the relay on this machine (`AppSettings.underXCTest`), never to the real one
        app.launchEnvironment["XCTestBundlePath"] = "/dev/null"
        app.launch()

        // unlock: one password field, which iOS leaves alone
        let field = app.secureTextFields["password:"]
        XCTAssertTrue(field.waitForExistence(timeout: 60), "the unlock screen never appeared")
        field.tap()
        field.typeText(Self.password)
        app.buttons["[ UNLOCK ]"].tap()
        let add = app.buttons["Open new channel"]
        XCTAssertTrue(add.waitForExistence(timeout: 60), "the identity the Debug build made did not unlock")
        // a Release build has no argument that drops the screenshot shield
        assertACaptureIsBlack(of: app, "after the unlock")

        // first contact, made by this build
        add.tap()
        let dialog = app.alerts["OPEN CHANNEL"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 10))
        let name = dialog.textFields.firstMatch
        name.tap()
        name.typeText("pyrobot")
        dialog.buttons["CONNECT"].tap()
        let row = app.staticTexts["<pyrobot>"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "pyrobot was not found on the relay")
        row.tap()

        say("hello", in: app)
        XCTAssertTrue(text(containing: "send me anything", in: app).waitForExistence(timeout: 90),
                      "pyrobot's tour did not arrive")
        XCTAssertTrue(text(containing: "DELIVERED", in: app).exists, "the first message never showed DELIVERED")

        // a voice note, in the deep voice: where this machine can record at all. A Mac with no
        // microphone gives the simulator no audio input, the recorder stops at once and the line
        // of voices never shows. The script then says the note was NOT TRIED, which is not a pass.
        app.buttons["Record voice note"].tap()
        let deep = app.buttons["deep voice"]
        if deep.waitForExistence(timeout: 8) {
            deep.tap()
            XCTAssertTrue(app.buttons["deep voice, selected"].waitForExistence(timeout: 5)
                          || deep.isSelected, "the deep voice was not chosen")
            Thread.sleep(forTimeInterval: 2.5)
            app.buttons["[SEND]"].tap()
            let play = app.buttons["Play voice note"]
            XCTAssertTrue(play.waitForExistence(timeout: 90), "the changed note never appeared in the conversation")
            XCTAssertTrue(text(containing: "an attachment arrived", in: app).waitForExistence(timeout: 60),
                          "the bot never received the note")
            play.tap()
            let stop = app.buttons["Stop voice note"]
            XCTAssertTrue(stop.waitForExistence(timeout: 10), "the changed note does not play")
            stop.tap()
            print("RELEASE-CHECK voice note: sent in the deep voice and played")
        } else {
            print("RELEASE-CHECK voice note: NOT TRIED, this machine gives the simulator no audio input")
        }

        say("again", in: app)
        XCTAssertTrue(text(containing: "you said: again", in: app).waitForExistence(timeout: 30))

        // the status page
        app.navigationBars.buttons.firstMatch.tap()
        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        let entry = app.buttons["What protects me"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10))
        entry.tap()
        let sender = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH 'sender hidden'")).firstMatch
        for _ in 0..<6 where !sender.exists { app.swipeUp() }
        XCTAssertTrue(sender.exists, "the status page has no `sender hidden` row")
        XCTAssertTrue(sender.label.contains("ON") && !sender.label.contains("OFF"),
                      "the status page says: \(sender.label)")
        assertACaptureIsBlack(of: app, "on the status page, after everything above")
    }

    /// The screenshot shield, from where a capture is made. The keyboard is the
    /// system's own window and is not under it, so this waits for it to go.
    private func assertACaptureIsBlack(of app: XCUIApplication, _ when: String) {
        for _ in 0..<20 where app.keyboards.count > 0 { Thread.sleep(forTimeInterval: 0.25) }
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThan(Capture.litShare(in: app.windows.firstMatch.frame), 0.0005,
                          "a capture of the app is not black \(when): the screenshot shield is not on")
    }

    private func say(_ words: String, in app: XCUIApplication) {
        let field = app.textViews["Message"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "no composer")
        field.tap()
        field.typeText(words)
        app.buttons["Send"].tap()
    }

    private func text(containing words: String, in app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }
}
