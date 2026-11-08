import XCTest

/// docs/DECOY-ACCOUNT.md §5 — "The first screen stops claiming to know."
///
/// This is now the first thing every user of the app sees, so it is captured
/// and looked at rather than merely asserted. The property being checked is a
/// negative one, which is unusual and is the whole point: NOTHING on this
/// screen may say whether this device holds an identity. `> an identity lives
/// on this device.` and `> no identity on this device yet.` were both true
/// once and are both false now, because a container exists from first run and
/// no wipe deletes it.
final class AuthScreenUITests: XCTestCase {

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    private func launch(textSize: String? = nil, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=unlock", "--demo-noshield"] + extra
        if let textSize { app.launchArguments += ["--demo-text-size", textSize] }
        app.launch()
        return app
    }

    /// The app now opens on the PASSWORD FORM, not the chooser — a returning
    /// user should not pay a tap on every launch to reach a field they use
    /// daily, and a form shown unconditionally asserts no more than a chooser
    /// shown unconditionally. Creating and restoring moved one tap in, so the
    /// tests that exercise them come through here.
    private func reachChooser(_ app: XCUIApplication) {
        let other = app.buttons["Create an identity or restore a backup"]
        XCTAssertTrue(other.waitForExistence(timeout: 60), "reached the auth screen")
        other.tap()
        XCTAssertTrue(app.buttons["Create identity"].waitForExistence(timeout: 10),
                      "and the other options are one tap away")
    }

    /// Nothing on the root screen asserts, and both actions are offered on
    /// equal footing.
    func testTheFirstScreenAssertsNothing() {
        let app = launch()
        let submit = app.buttons["[ UNLOCK ]"]
        XCTAssertTrue(submit.waitForExistence(timeout: 60), "reached the auth screen")
        shot("auth-root")

        // The root is the password form, reached with no taps. It is the same
        // screen on a phone that has never held an account, on one holding
        // two, and on one wiped ten minutes ago — which is the property; the
        // chooser satisfied it too, and charged a tap for the privilege.
        XCTAssertTrue(app.secureTextFields.firstMatch.exists, "the password field, at zero taps")
        XCTAssertTrue(app.buttons["Create an identity or restore a backup"].exists,
                      "…and the way to the other options")
        XCTAssertFalse(app.buttons["Create identity"].exists, "which are NOT on the root")

        // the two sentences this screen is no longer entitled to say
        for claim in ["an identity lives on this device",
                      "no identity on this device",
                      "nothing to sign in to",
                      "this device already has an identity",
                      "one identity per device"] {
            let found = app.descendants(matching: .any).matching(
                NSPredicate(format: "label CONTAINS[c] %@", claim)).firstMatch
            XCTAssertFalse(found.exists, "the screen must not claim: \(claim)")
        }

        XCTAssertLessThanOrEqual(submit.frame.maxX, app.frame.maxX)

        // and the same claims are still absent one tap in, where they used to live
        reachChooser(app)
        for claim in ["an identity lives on this device", "no identity on this device"] {
            let found = app.descendants(matching: .any).matching(
                NSPredicate(format: "label CONTAINS[c] %@", claim)).firstMatch
            XCTAssertFalse(found.exists, "the chooser must not claim: \(claim)")
        }
    }

    /// Unlocking is one tap in, and the form it reaches does not assert either.
    func testUnlockFormDoesNotAssert() {
        let app = launch()
        XCTAssertTrue(app.secureTextFields.firstMatch.waitForExistence(timeout: 60))
        shot("auth-unlock")
        XCTAssertTrue(app.buttons["[ UNLOCK ]"].exists, "the submit button")
        XCTAssertTrue(app.buttons["Create an identity or restore a backup"].exists,
                      "…and the way out, which is now the header's job on this screen")
        let claim = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS[c] 'this device'"
                        + " AND label CONTAINS[c] 'identity'")).firstMatch
        XCTAssertFalse(claim.exists)
    }

    /// docs/DECOY-ACCOUNT.md §1's plain advice, which sits on the create form
    /// itself: "if you already have an identity on this device, sign in first.
    /// From inside an account the app knows which slots are free and nothing
    /// is risked." Unconditional like everything else on this screen — it
    /// points at the safe path without implying this device is on it.
    func testCreateFormCarriesThePlainAdvice() {
        let app = launch()
        reachChooser(app)
        let create = app.buttons["Create identity"]
        XCTAssertTrue(create.waitForExistence(timeout: 60))
        create.tap()

        // §1's plain advice sits on the form itself
        let advice = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'if you already have an identity'")).firstMatch
        XCTAssertTrue(advice.waitForExistence(timeout: 10))
        shot("auth-create")
        XCTAssertLessThanOrEqual(advice.frame.maxX, app.frame.maxX)
        XCTAssertGreaterThanOrEqual(advice.frame.minX, 0)

        XCTAssertTrue(app.buttons["[ IGNITE ]"].exists)
    }

    /// iOS offers to put the store password in the phone's password manager,
    /// and the app cannot stop it. This pins both halves of that.
    ///
    /// The offer is real and measured, not inferred: tapping the password
    /// field on the create form raises a sheet headed "Use Strong Password?"
    /// whose own body says the password "will be saved in Passwords". Three
    /// honest ways of declining were tried — `textContentType(nil)`, a
    /// `UITextField` subclass with a hard-nil content type and late
    /// `isSecureTextEntry`, and a secure `UITextView` — and the sheet appears
    /// for all three. What suppresses it is claiming the field is a one-time
    /// code, which is a lie and hands the user a numeric keypad for the most
    /// important secret in the app.
    ///
    /// So the app warns instead. If a future iOS lets this be declined
    /// honestly, the first assertion here starts failing and someone should
    /// remove the warning.
    func testTheAppWarnsAboutThePasswordManagerItCannotDecline() {
        let app = launch()
        reachChooser(app)
        let create = app.buttons["Create identity"]
        XCTAssertTrue(create.waitForExistence(timeout: 60))
        create.tap()

        let warning = app.staticTexts["passwordManagerWarning"]
        XCTAssertTrue(warning.waitForExistence(timeout: 10),
                      "the warning is on the form before anything is typed")
        XCTAssertTrue(warning.label.contains("survives every wipe"))
        XCTAssertLessThanOrEqual(warning.frame.maxX, app.frame.maxX)
        XCTAssertGreaterThanOrEqual(warning.frame.minX, 0)
        shot("auth-create-password-warning")

        let username = app.textFields["username:"]
        XCTAssertTrue(username.exists)
        username.tap()
        username.typeText("someone_new")
        let password = app.secureTextFields["password:"]
        XCTAssertTrue(password.exists, "still a secure field")
        password.tap()

        // The condition the warning exists for. If this stops being true,
        // the warning can go.
        let offer = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS[c] 'Strong Password'")).firstMatch
        XCTAssertTrue(offer.waitForExistence(timeout: 10),
                      "iOS still offers to generate and save this password")
        shot("auth-create-ios-offer")
        XCTAssertTrue(app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS[c] 'saved in'")).firstMatch.exists,
                      "…and says out loud that it will be saved")
    }

    /// The same exposure on the restore form, which also chooses a password.
    func testRestoreFormCarriesTheSameWarning() {
        let app = launch()
        reachChooser(app)
        let restore = app.buttons["Restore from backup"]
        XCTAssertTrue(restore.waitForExistence(timeout: 10))
        restore.tap()
        XCTAssertTrue(app.staticTexts["passwordManagerWarning"].waitForExistence(timeout: 10))
    }

    /// §1's confirmation itself. Raised through `--demo-confirm-create`
    /// because iOS AutoFill claims this screen's password fields and a UI test
    /// cannot type past it — the alert is the real one either way.
    func testTheColdCreateConfirmation() {
        let app = launch(extra: ["--demo-confirm-create"])
        let confirm = app.alerts["ERASE AND CREATE"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 60),
                      "§1: creating from cold asks first, every time")
        shot("auth-create-confirm")
        // §1: a STATEMENT, not a probability — a cold create rewrites the
        // whole container, so there is nothing to hedge about
        let warning = confirm.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'erases anything else stored on this device'"))
            .firstMatch
        XCTAssertTrue(warning.exists, "and says exactly what it costs")
        // it must not say whether anything IS stored here
        XCTAssertFalse(confirm.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[c] 'already has'")).firstMatch.exists)
        XCTAssertTrue(confirm.buttons["CONTINUE"].exists)
        confirm.buttons["ABORT"].tap()
        XCTAssertTrue(app.buttons["[ IGNITE ]"].waitForExistence(timeout: 10),
                      "aborting leaves the form exactly as it was")
    }

    /// The narrowest phone at the largest type: this screen is the first thing
    /// every user sees and the warning is the longest line on it.
    func testFirstScreenAtTheLargestTextSize() {
        let app = launch(textSize: "largest")
        reachChooser(app)
        let create = app.buttons["Create identity"]
        XCTAssertTrue(create.waitForExistence(timeout: 60))
        shot("auth-root-largest")
        XCTAssertLessThanOrEqual(create.frame.maxX, app.frame.maxX)
        create.tap()
        let advice = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'if you already have an identity'")).firstMatch
        XCTAssertTrue(advice.waitForExistence(timeout: 10))
        shot("auth-create-largest")
        XCTAssertLessThanOrEqual(advice.frame.maxX, app.frame.maxX)
        XCTAssertGreaterThanOrEqual(advice.frame.minX, 0)
    }
}
