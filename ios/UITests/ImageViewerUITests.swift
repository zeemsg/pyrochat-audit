import XCTest

/// Opening a picture in a chat and pulling it down must close the viewer (both platforms).
/// Drags start on the picture itself: the gesture lives on the image, not on the black ground
/// around it (the demo picture sits in the upper third of the screen).
final class ImageViewerUITests: XCTestCase {
    func testSwipeDownClosesTheImageViewer() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat"]
        app.launch()
        let imgRow = app.staticTexts.matching(NSPredicate(format: "label CONTAINS '[IMG'")).firstMatch
        XCTAssertTrue(imgRow.waitForExistence(timeout: 30), "demo chat has an image row")
        imgRow.tap()
        let close = app.buttons["[CLOSE]"]
        XCTAssertTrue(close.waitForExistence(timeout: 10), "viewer opens with [CLOSE]")
        let img = app.images["imageViewerImage"]
        XCTAssertTrue(img.waitForExistence(timeout: 5), "the picture is on screen")

        // a short pull must snap back and keep the viewer open
        let mid = img.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        mid.press(forDuration: 0.05, thenDragTo: mid.withOffset(CGVector(dx: 0, dy: 50)),
                  withVelocity: .slow, thenHoldForDuration: 0.1)
        sleep(1)
        XCTAssertTrue(close.exists, "a small pull keeps the viewer open")

        // a real swipe down closes it
        img.swipeDown(velocity: .fast)
        let gone = NSPredicate(format: "exists == false")
        wait(for: [expectation(for: gone, evaluatedWith: close)], timeout: 5)
        XCTAssertTrue(imgRow.waitForExistence(timeout: 5), "back in the chat")
    }
}
