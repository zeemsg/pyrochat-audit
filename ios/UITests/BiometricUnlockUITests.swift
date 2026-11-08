import XCTest

/// docs/BIOMETRIC-UNLOCK.md §7, the simulator half: with biometry enrolled,
/// enable the toggle, lock, reopen, authenticate, and land in the app without
/// typing — then no-matches until the password requirement shows.
///
/// A process inside the simulator sandbox cannot post the BiometricKit
/// match/no-match notifications, so the test asks a tiny host-side driver to
/// do it: it writes `match` / `nomatch` / `idle` into `PYRO_BIOMETRY_FILE`
/// and the driver (scratchpad `biometry-driver.sh`) posts them with
/// `xcrun simctl spawn <udid> notifyutil -p com.apple.BiometricKit_Sim.…`.
/// Enrol the device first:
///
///   xcrun simctl spawn <udid> notifyutil \
///       -s com.apple.BiometricKit.enrollmentChanged 1 \
///       -p com.apple.BiometricKit.enrollmentChanged
///
/// Then, with the dev relay on ws://127.0.0.1:7444/ws and the driver running:
///
///   TEST_RUNNER_PYRO_BIOMETRIC_UI=1 \
///   TEST_RUNNER_PYRO_BIOMETRY_FILE=<dir>/biometry.cmd \
///   TEST_RUNNER_PYRO_SHOT_DIR=<dir> xcodebuild test … \
///       -only-testing:PyroChatUITests/BiometricUnlockUITests
final class BiometricUnlockUITests: XCTestCase {

    private let relay = "ws://127.0.0.1:7444/ws"
    private let password = "demo-pass-123"        // DemoAutopilot's fixed password
    private var commandFile: URL?

    override func tearDown() {
        drive("idle")
        super.tearDown()
    }

    func testEnableLockReopenAuthenticateAndNoMatchesRequireThePassword() throws {
        guard ProcessInfo.processInfo.environment["PYRO_BIOMETRIC_UI"] != nil,
              let file = ProcessInfo.processInfo.environment["PYRO_BIOMETRY_FILE"] else {
            throw XCTSkip("needs an enrolled simulator, the dev relay and the biometry driver: set PYRO_BIOMETRIC_UI=1 + PYRO_BIOMETRY_FILE")
        }
        commandFile = URL(fileURLWithPath: file)
        drive("idle")

        let user = "bio_" + UUID().uuidString.lowercased()
            .replacingOccurrences(of: "-", with: "").prefix(8)
        let app = XCUIApplication()

        // ── 1. an identity on this device (the DEBUG autopilot registers) ──
        app.launchArguments = ["--demo-noshield", "--demo-user", String(user),
                               "--demo-relay", relay]
        app.launch()
        let home = app.buttons["Settings"]
        XCTAssertTrue(home.waitForExistence(timeout: 90),
                      "never reached the home screen — is the dev relay up?")

        // ── 2. §2: one row in SAFETY, directly above auto-lock, + footnote ──
        home.tap()
        let row = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH 'unlock with'")).firstMatch
        let footnote = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'a face or a finger can be taken from you'")).firstMatch
        let autoLock = app.staticTexts["auto-lock"]
        XCTAssertTrue(scroll(app, until: { row.exists && footnote.exists
                                           && autoLock.exists && row.isHittable }),
                      "the biometric row never appeared in [cfg] — is biometry enrolled?")
        // nudge (a fifth of the screen at a time) until row, footnote and the
        // auto-lock row beneath them are all on screen together
        for _ in 0..<6 where !(row.isHittable && footnote.isHittable && autoLock.isHittable) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
               .press(forDuration: 0.05,
                      thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)))
        }
        XCTAssertLessThan(row.frame.midY, autoLock.frame.midY,
                          "§2: the row sits in SAFETY, directly above auto-lock")
        XCTAssertLessThan(row.frame.midY, footnote.frame.midY, "the footnote sits under the row")
        XCTAssertEqual(row.value as? String, "0", "OFF by default")

        // ON: one prompt, then the key is stored. Tap the switch itself — the
        // row's centre is the label, which does not flip it.
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        // The switch follows the finger at once — the truth is whether the key
        // was really stored, which is what it reads back as a moment later.
        // (Nothing is queried while the prompt is up: it belongs to another
        // process, and reaching for it there makes XCUITest cancel it.)
        Thread.sleep(forTimeInterval: 1.5)        // the prompt comes up
        drive("match")
        Thread.sleep(forTimeInterval: 2.5)        // matched, key stored
        drive("idle")
        XCTAssertEqual(row.value as? String, "1",
                       "the toggle must stay on: the key is stored")
        save("bio-1-settings-row")

        // ── 3. §7: lock (a cold relaunch is the strictest form) and reopen ──
        app.terminate()
        app.launchArguments = ["--demo-noshield", "--demo-relay", relay]
        app.launch()

        // §3: the screen attempts biometry by itself. Backgrounding cancels
        // that prompt — and a cancel says nothing at all, which is what the
        // clean unlock screen below proves.
        let button = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH '[ UNLOCK WITH'")).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 30),
                      "no biometric button above the password field")
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 1.5)
        app.activate()
        Thread.sleep(forTimeInterval: 1.5)
        let field = app.secureTextFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10),
                      "§3: the password field must stay visible and usable")
        XCTAssertTrue(app.buttons["[ UNLOCK ]"].exists, "§3: [ UNLOCK ] stays too")
        XCTAssertLessThan(button.frame.midY, field.frame.midY, "§3: the button sits ABOVE the field")
        XCTAssertFalse(app.staticTexts["biometricNote"].exists,
                       "§3: a cancelled prompt says nothing")
        save("bio-2-unlock-screen")

        // ── 4. authenticate: into the app without typing a thing ──
        button.tap()
        XCTAssertTrue(drive("match", until: { home.exists }, timeout: 40),
                      "a matching face did not unlock the app")
        _ = app.staticTexts["LINK UP"].waitForExistence(timeout: 20)   // a settled screen
        Thread.sleep(forTimeInterval: 0.8)
        save("bio-3-unlocked")

        // ── 5. §3: a face that does not match never gets in ──
        app.terminate()
        app.launchArguments = ["--demo-noshield", "--demo-relay", relay]
        app.launch()
        XCTAssertTrue(button.waitForExistence(timeout: 30))
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let dismiss = springboard.buttons.matching(
            NSPredicate(format: "identifier CONTAINS 'authentication.button.cancel'")).firstMatch
        drive("nomatch")
        XCTAssertTrue(dismiss.waitForExistence(timeout: 25),
                      "a face that does not match must leave the prompt refusing, never unlock")
        XCTAssertFalse(home.exists, "a no-match must not open the app")
        dismiss.tap()
        drive("idle")
        Thread.sleep(forTimeInterval: 1.0)
        XCTAssertTrue(app.secureTextFields.firstMatch.exists,
                      "still locked, still asking for the password")

        // ── 6. §4: three failures ⇒ the typed password is required ──
        // (the simulator's LocalAuthentication never reports a no-match to
        // the app, so the DEBUG `--demo-bio-failures` records them through
        // the same API real failures use — see AppModel.init)
        app.terminate()
        app.launchArguments = ["--demo-noshield", "--demo-relay", relay,
                               "--demo-bio-failures", "3"]
        app.launch()
        let required = app.staticTexts["biometricPasswordRequired"]
        XCTAssertTrue(required.waitForExistence(timeout: 30),
                      "three failures must show \u{2500}\u{2500} password required \u{2500}\u{2500}")
        XCTAssertFalse(button.exists, "§3: no biometric button that time")
        XCTAssertTrue(app.buttons["[ UNLOCK ]"].exists, "the password field is always there")
        save("bio-4-password-required")

        // ── 7. the typed password always works, and clears the failures ──
        let pwField = app.secureTextFields.firstMatch
        pwField.tap()
        _ = app.keyboards.element.waitForExistence(timeout: 10)
        pwField.typeText(password)
        app.buttons["[ UNLOCK ]"].tap()
        XCTAssertTrue(home.waitForExistence(timeout: 60),
                      "the typed password must always work")
        // and the count is clear again: reopening offers biometry once more
        app.terminate()
        app.launchArguments = ["--demo-noshield", "--demo-relay", relay]
        app.launch()
        XCTAssertTrue(button.waitForExistence(timeout: 30),
                      "a successful password unlock must clear the failures")
    }

    // MARK: helpers

    /// Ask the host driver to post a biometry event (see the class comment).
    @discardableResult
    private func drive(_ command: String, until condition: (() -> Bool)? = nil,
                       timeout: TimeInterval = 20) -> Bool {
        guard let commandFile else { return false }
        try? command.write(to: commandFile, atomically: true, encoding: .utf8)
        Thread.sleep(forTimeInterval: 0.9)      // the driver polls: let it see this
        guard let condition else { return true }
        let deadline = Date().addingTimeInterval(timeout)
        var hit = false
        while Date() < deadline && !hit {
            hit = condition()
            if !hit { Thread.sleep(forTimeInterval: 0.4) }
        }
        try? "idle".write(to: commandFile, atomically: true, encoding: .utf8)
        return hit || condition()
    }

    private func scroll(_ app: XCUIApplication, steps: Int = 12,
                        until condition: () -> Bool) -> Bool {
        for _ in 0..<steps {
            if condition() { return true }
            app.swipeUp()
        }
        return condition()
    }

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir = ProcessInfo.processInfo.environment["PYRO_SHOT_DIR"] else { return }
        try? shot.pngRepresentation.write(
            to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }
}
