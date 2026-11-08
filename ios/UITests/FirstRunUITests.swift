import XCTest

/// docs/FIRST-RUN.md through the real UI, on the DEBUG screenshot demo
/// (`--screenshot-demo=first-run` / `first-run-bot`: a brand-new identity,
/// no relay, billing pointed at loopback port 1 so an auto-submitted key
/// never reaches a server).
///
/// Set `PYRO_SHOT_DIR` (xcodebuild: `TEST_RUNNER_PYRO_SHOT_DIR=…`) to also
/// save each capture there as a PNG.
final class FirstRunUITests: XCTestCase {
    /// §3: `pyrochat://redeem/<CODE>` handed to the running app opens the
    /// ACCESS KEY dialog in [cfg] with the code filled in and submits it at
    /// once; the verdict then lands under ACCESS as it always did.
    func testDeepLinkOpensAccessKeyDialogPrefilled() throws {
        let code = "PYRO-TEST-CODE-0000-0000"
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=first-run-bot"]
        app.launch()
        XCTAssertTrue(app.staticTexts["<pyrobot>"].waitForExistence(timeout: 30), "home never appeared")

        XCUIDevice.shared.system.open(URL(string: "pyrochat://redeem/\(code)")!)
        // the first external open of the scheme asks "Open in PyroChat?" on
        // the springboard (later ones are remembered): answer it if it shows,
        // while watching for the dialog — it stays up only a few seconds.
        // The field is matched by its pre-filled value (the grep bar on the
        // home screen is also a text field, but an empty one).
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.buttons["Open"]
        let field = app.textFields.matching(NSPredicate(format: "value == %@", code)).firstMatch
        var deadline = Date().addingTimeInterval(12)
        var dialogSeen = false
        while Date() < deadline {
            if open.exists { open.tap() }
            if field.exists { dialogSeen = true; break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertTrue(dialogSeen, "ACCESS KEY dialog did not open pre-filled with the code")
        save(XCUIScreen.main.screenshot(), as: "access-key-dialog")
        XCTAssertTrue(app.staticTexts["ACCESS KEY"].exists, "dialog title missing")

        // the verdict closes the dialog and lands under ACCESS, as it always did
        deadline = Date().addingTimeInterval(8)
        while field.exists && Date() < deadline { Thread.sleep(forTimeInterval: 0.2) }
        XCTAssertFalse(field.exists, "dialog did not close with the verdict")
        let verdict = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH '\u{2717}' OR label BEGINSWITH '\u{2713}'")).firstMatch
        for _ in 0..<5 where !verdict.exists { app.swipeUp() }   // ACCESS sits below the fold
        XCTAssertTrue(verdict.exists, "no redeem verdict under ACCESS")
    }

    /// §1: the three-line empty state, then the [share] sheet from the toolbar.
    func testEmptyStateAndShareSheet() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=first-run"]
        app.launch()
        let empty = app.descendants(matching: .any).matching(identifier: "emptyState").firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30), "empty state never appeared")
        let label = empty.label
        XCTAssertTrue(label.contains("no channels yet."), label)
        XCTAssertTrue(label.contains("press [+] and add pyrobot for a 60-second tour,"), label)
        XCTAssertTrue(label.contains("or [share] your username to bring a friend."), label)
        save(XCUIScreen.main.screenshot(), as: "empty-state")

        let share = app.buttons["Share my username"]
        XCTAssertTrue(share.waitForExistence(timeout: 5), "[share] missing from the toolbar")
        share.tap()
        // the share sheet is a system remote view outside the app's
        // accessibility tree — give it time to come up, then capture
        Thread.sleep(forTimeInterval: 3)
        XCTAssertEqual(app.state, .runningForeground, "app must survive presenting the share sheet")
        save(XCUIScreen.main.screenshot(), as: "share-sheet")
    }

    private func save(_ shot: XCUIScreenshot, as name: String) {
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir = ProcessInfo.processInfo.environment["PYRO_SHOT_DIR"] else { return }
        let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")
        try? shot.pngRepresentation.write(to: url)
    }
}
