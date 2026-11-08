import XCTest

/// The screenshot shield, seen from where a capture is made. Every other UI test
/// launches with the shield off (`--demo-noshield`, or the screenshot demo, which
/// leaves it out) so that its own screenshots show something; this is the one
/// that looks at what the shield does to a capture: black with it, the app without.
final class ShieldUITests: XCTestCase {

    /// The share of the app's window that is lit in a capture of the canned chat.
    private func litShare(with arguments: [String]) -> Double {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat"] + arguments
        app.launch()
        XCTAssertTrue(app.textViews["Message"].waitForExistence(timeout: 90), "the chat never opened")
        Thread.sleep(forTimeInterval: 1.5)      // the boot art has handed off
        let lit = Capture.litShare(in: app.windows.firstMatch.frame)
        app.terminate()
        return lit
    }

    /// A picture opened full screen takes the app's own root view out of the
    /// window for as long as it is open. The shield is on the window, not on that
    /// view, and has to hold: this is the screen most worth a screenshot.
    func testACaptureStaysBlackWhileAPictureIsOpenFullScreen() {
        func open(_ arguments: [String]) -> XCUIApplication {
            let app = XCUIApplication()
            app.launchArguments = ["--screenshot-demo=chat"] + arguments
            app.launch()
            let row = app.staticTexts.matching(NSPredicate(format: "label CONTAINS '[IMG'")).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 90), "the demo chat has a picture")
            // the picture itself, under its caption: that is what opens it
            let picture = app.images.allElementsBoundByIndex.first { $0.frame.height > 80 }
            XCTAssertNotNil(picture, "the picture is not on screen")
            picture?.tap()
            XCTAssertTrue(app.buttons["[CLOSE]"].waitForExistence(timeout: 10), "the viewer did not open")
            XCTAssertTrue(app.images["imageViewerImage"].waitForExistence(timeout: 5))
            Thread.sleep(forTimeInterval: 1.5)      // the presentation has finished
            return app
        }
        let plain = open([])
        XCTAssertGreaterThan(Capture.litShare(in: plain.windows.firstMatch.frame), 0.01,
                             "without the shield a capture shows the picture")
        plain.terminate()

        let app = open(["--demo-shield"])
        XCTAssertLessThan(Capture.litShare(in: app.windows.firstMatch.frame), 0.0005,
                          "with the shield a capture of the open picture is black")
        app.buttons["[CLOSE]"].tap()
        XCTAssertTrue(app.textViews["Message"].waitForExistence(timeout: 10), "back in the chat")
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertLessThan(Capture.litShare(in: app.windows.firstMatch.frame), 0.0005,
                          "and of the chat again once the picture is closed")
        app.terminate()
    }

    /// The `[cfg]` switch, from where a capture is made: off, a capture shows the
    /// app; on again, it is black again.
    func testTheSettingSwitchesTheShieldOffAndOnAgain() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings", "--demo-shield"]
        app.launch()
        let toggle = app.switches["block screenshots (capture = black)"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 90), "never reached [cfg]")
        for _ in 0..<6 where !toggle.isHittable { app.swipeUp() }
        Thread.sleep(forTimeInterval: 1.5)
        let window = app.windows.firstMatch.frame
        let knob = toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5))
        XCTAssertEqual(toggle.value as? String, "1", "the shield is on to begin with")
        XCTAssertLessThan(Capture.litShare(in: window), 0.0005)

        knob.tap()
        XCTAssertEqual(toggle.value as? String, "0")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertGreaterThan(Capture.litShare(in: window), 0.01, "switched off, a capture shows the app")

        knob.tap()
        XCTAssertEqual(toggle.value as? String, "1")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertLessThan(Capture.litShare(in: window), 0.0005, "switched on again, a capture is black again")
        app.terminate()
    }

    func testACaptureOfTheShieldedAppIsBlackAndOneOfTheUnshieldedAppIsNot() {
        let open = litShare(with: [])
        XCTAssertGreaterThan(open, 0.01, "without the shield a capture shows the conversation")
        let shielded = litShare(with: ["--demo-shield"])
        XCTAssertLessThan(shielded, 0.0005, "with the shield a capture of the app is black")
    }
}
