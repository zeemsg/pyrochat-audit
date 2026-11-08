import XCTest

/// The app in a window that changes size while it runs. An iPad shows an iPhone
/// app in a window the user can resize, which is the nearest thing there is today
/// to a phone that folds: the system resizes the window under the app, and the
/// screenshot shield has to stay around it. Skipped on a phone, where the window
/// is the screen.
final class WindowResizeUITests: XCTestCase {

    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat"] + arguments
        app.launch()
        XCTAssertTrue(app.textViews["Message"].waitForExistence(timeout: 90), "the chat never opened")
        Thread.sleep(forTimeInterval: 1.5)      // the boot art has handed off
        return app
    }

    /// Drags the window's resize corner by `by` and returns the window's new frame.
    private func resize(_ app: XCUIApplication, by: CGVector) -> CGRect {
        let frame = app.windows.firstMatch.frame
        let corner = springboard.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: frame.maxX - 5, dy: frame.maxY - 5))
        corner.press(forDuration: 0.4, thenDragTo: corner.withOffset(by))
        Thread.sleep(forTimeInterval: 2)
        return app.windows.firstMatch.frame
    }

    func testTheAppAndItsShieldSurviveTheWindowBeingResized() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad,
                          "an iPad simulator shows the app in a window; on a phone there is nothing to resize")
        let app = launch(["--demo-shield"])
        let before = app.windows.firstMatch.frame
        let composerBefore = app.textViews["Message"].frame
        XCTAssertLessThan(Capture.litShare(in: before), 0.0005, "with the shield a capture of the window is black")

        let smaller = resize(app, by: CGVector(dx: -120, dy: -160))
        print("RESIZE window \(before) -> \(smaller); composer \(composerBefore) -> \(app.textViews["Message"].frame)")
        XCTAssertNotEqual(smaller.size, before.size, "the window did not change size: nothing was tried")
        XCTAssertLessThan(Capture.litShare(in: smaller), 0.0005, "a capture is still black after the window was made smaller")

        let larger = resize(app, by: CGVector(dx: 200, dy: 260))
        print("RESIZE window \(smaller) -> \(larger); composer \(app.textViews["Message"].frame)")
        XCTAssertNotEqual(larger.size, smaller.size)
        XCTAssertLessThan(Capture.litShare(in: larger), 0.0005, "and after it was made larger")
        XCTAssertTrue(app.textViews["Message"].isHittable, "the message field is still there to type in")
        XCTAssertTrue(larger.contains(app.textViews["Message"].frame), "and inside the window")

        // the same window without the shield: what was black above was the app, not an empty window
        app.terminate()
        let open = launch([])
        let lit = Capture.litShare(in: open.windows.firstMatch.frame)
        print("RESIZE unshielded lit share \(lit) in \(open.windows.firstMatch.frame)")
        XCTAssertGreaterThan(lit, 0.01, "without the shield the same capture shows the conversation")
    }
}
