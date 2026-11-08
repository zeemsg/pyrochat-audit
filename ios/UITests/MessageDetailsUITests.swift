import XCTest

/// docs/MESSAGE-DETAILS.md §4 (device): long-press a message in the demo chat,
/// open `details`, screenshot it; repeat for a group message showing the
/// per-member rows and the envelope-count line; repeat for a message with no
/// capture.
///
/// Every capture is written to the runner's Documents directory as well as
/// attached to the result bundle, so it can be pulled off the simulator with
/// `xcrun simctl get_app_container <udid> com.pyrochat.app.uitests.xctrunner data`
/// and actually looked at.
final class MessageDetailsUITests: XCTestCase {

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// Long-press a row, screenshot the menu, tap `details`, screenshot the sheet.
    private func openDetails(_ app: XCUIApplication, on rowText: String, named name: String) {
        let row = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", rowText)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "demo row \(rowText) is on screen")
        row.press(forDuration: 1.2)
        let details = app.buttons["details"]
        XCTAssertTrue(details.waitForExistence(timeout: 10),
                      "the long-press menu offers `details`")
        shot("\(name)-menu")
        details.tap()
        let header = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'what the relay received'")).firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "the details sheet opened")
        shot(name)
    }

    private func closeSheet(_ app: XCUIApplication) {
        let close = app.buttons["Close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() }
        sleep(1)
    }

    func testDetailsSheetOnAOneToOneMessage() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat"]
        app.launch()
        // the demo seeds a captured envelope for this row
        openDetails(app, on: "hi pyrobot", named: "details-1to1-text")
        closeSheet(app)
        // …and deliberately none for this one
        openDetails(app, on: "did this get through?", named: "details-no-capture")
    }

    func testDetailsSheetOnAGroupMessage() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=group"]
        app.launch()
        openDetails(app, on: "welcome. dashboard below", named: "details-group")
    }
}
