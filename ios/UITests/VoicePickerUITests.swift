import XCTest

/// docs/VOICE-CHANGER.md — the line of voices above the composer while a note is recorded.
/// `--demo-recording` shows that composer without opening the microphone.
///
/// Every capture is written to the runner's Documents directory as well as attached to the
/// result bundle, the same way `ScrubAndWipeUITests` does it.
final class VoicePickerUITests: XCTestCase {

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    private func launch(_ screen: String, textSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=\(screen)", "--demo-noshield", "--demo-recording"]
        if let textSize { app.launchArguments += ["--demo-text-size", textSize] }
        app.launch()
        return app
    }

    private let voices = ["Your own voice", "deep voice", "high voice", "robot voice"]

    /// The four voices are there, your own is the one in use until another is chosen, and a
    /// tap chooses.
    private func assertTheVoicesChoose(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let own = app.buttons["Your own voice"]
        XCTAssertTrue(own.waitForExistence(timeout: 30), "the line of voices is above the composer", file: file, line: line)
        for name in voices {
            XCTAssertTrue(app.buttons[name].exists, name, file: file, line: line)
        }
        XCTAssertTrue(own.isSelected, "nothing is changed until a voice is chosen", file: file, line: line)
        XCTAssertFalse(app.buttons["robot voice"].isSelected, file: file, line: line)
        app.buttons["robot voice"].tap()
        XCTAssertTrue(app.buttons["robot voice"].isSelected, "a tap chooses", file: file, line: line)
        XCTAssertFalse(own.isSelected, "and only one is in use", file: file, line: line)
        XCTAssertFalse(app.buttons["deep voice"].isSelected, file: file, line: line)
        app.buttons["deep voice"].tap()
        XCTAssertTrue(app.buttons["deep voice"].isSelected, file: file, line: line)
        XCTAssertFalse(app.buttons["robot voice"].isSelected, file: file, line: line)
    }

    func testTheVoicesAreAboveTheComposerWhileRecordingAndATapChooses() {
        let app = launch("chat")
        assertTheVoicesChoose(app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH '\u{25CF} REC'")).firstMatch.exists,
                      "over the REC line")
        shot("voice-picker-chat")
        // back to your own voice
        app.buttons["Your own voice"].tap()
        XCTAssertTrue(app.buttons["Your own voice"].isSelected)
        shot("voice-picker-chat-off")
    }

    func testTheSameLineIsInAGroup() {
        let app = launch("group")
        assertTheVoicesChoose(app)
        shot("voice-picker-group")
    }

    /// Between `[SEND]` and the note's row the composer says that the voice is being changed.
    /// A short note is changed before that is seen; `--demo-changing-voice` holds the moment.
    func testTheComposerSaysWhenANoteIsBeingChanged() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat", "--demo-noshield", "--demo-changing-voice", "--demo-text-size", "largest"]
        app.launch()
        let line = app.staticTexts["Changing the voice of your note"]
        XCTAssertTrue(line.waitForExistence(timeout: 30), "the line is above the composer")
        let window = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(line.frame.maxX, window.maxX, "whole, at the largest text size")
        // the composer under it is the ordinary one: another note can be recorded meanwhile
        let record = app.buttons["Record voice note"]
        XCTAssertTrue(record.exists)
        XCTAssertLessThan(line.frame.maxY, record.frame.midY, "over the composer, not in its place")
        XCTAssertFalse(app.buttons["Your own voice"].exists, "the line of voices is for while a note is recorded")
        shot("voice-being-changed")
    }

    /// At the largest text size the four voices still fit on their line, each one whole and
    /// reachable.
    func testTheLineFitsAtTheLargestTextSize() {
        let app = launch("chat", textSize: "largest")
        XCTAssertTrue(app.buttons["Your own voice"].waitForExistence(timeout: 30))
        let window = app.windows.firstMatch.frame
        var lastEdge: CGFloat = 0
        var top: CGFloat?
        for name in voices {
            let button = app.buttons[name]
            XCTAssertTrue(button.isHittable, "\(name) can be tapped")
            XCTAssertGreaterThanOrEqual(button.frame.minX, lastEdge - 0.5, "\(name) does not overlap the one before it")
            XCTAssertLessThanOrEqual(button.frame.maxX, window.maxX, "\(name) is inside the screen")
            if let top { XCTAssertEqual(button.frame.minY, top, accuracy: 1, "\(name) is on the same line") }
            top = button.frame.minY
            lastEdge = button.frame.maxX
        }
        shot("voice-picker-largest")
    }
}
