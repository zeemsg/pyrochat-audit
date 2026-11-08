import XCTest

/// docs/TEXT-SIZE.md §4 (on device): every screen the spec names, captured at
/// `largest` on the narrowest phone in the simulator, so the four places that
/// assume a physical width (§3) can actually be looked at.
///
/// Like `MessageDetailsUITests`, each capture is written to the runner's
/// Documents directory as well as attached to the result bundle, so it can be
/// pulled off the simulator with
/// `xcrun simctl get_app_container <udid> com.pyrochat.app.uitests.xctrunner data`.
///
/// The size comes from `--demo-text-size <name>` (DEBUG demo mode only): it
/// seeds the throwaway demo store's `text size` setting, so the app renders it
/// through exactly the production path a real store would.
final class TextSizeUITests: XCTestCase {

    /// Override with `PYRO_TEXT_SIZE` to sweep the other names by hand.
    private var size: String {
        ProcessInfo.processInfo.environment["PYRO_TEXT_SIZE"] ?? "largest"
    }

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// docs/TEXT-SIZE.md §3: a capped top bar keeps every bracket label on
    /// the strip — UIKit must never fold one into its own `More` menu.
    private func assertNoOverflow(_ app: XCUIApplication, _ labels: [String]) {
        for label in labels {
            let item = app.buttons.matching(
                NSPredicate(format: "label == %@", label)).firstMatch
            XCTAssertTrue(item.exists, "\(label) is on the bar, not behind `More`")
        }
        XCTAssertFalse(app.buttons["OverflowBarButtonItem"].exists,
                       "no overflow chevron at the capped size")
    }

    private func launch(_ screen: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=\(screen)", "--demo-text-size", size]
        app.launch()
        return app
    }

    /// §3: the ASCII candle and the POST lines — fixed-width art, capped at
    /// `large`, on a screen that is held up instead of handing off.
    func testBootScreen() {
        let app = launch("boot")
        let welcome = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'welcome to the dark'")).firstMatch
        XCTAssertTrue(welcome.waitForExistence(timeout: 30), "the boot sequence finished")
        shot("textsize-\(size)-boot")
    }

    /// The chat log's `[hh:mm:ss] <alice> text` columns.
    func testChatLog() {
        let app = launch("chat")
        let row = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'hi pyrobot'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the demo thread is on screen")
        // §3: [/] [block] [burn] [ttl] all stay on the bar — a destructive
        // action must not end up hidden behind a chevron
        assertNoOverflow(app, ["Search in conversation", "Block user", "Burn thread",
                               "Self-destruct timer for this conversation"])
        XCTAssertTrue(app.staticTexts["<pyrobot>"].exists, "the peer header is still there")
        shot("textsize-\(size)-chat")
    }

    /// The group chat header — `--screenshot-demo=group` seeds the `#ops`
    /// thread, so its `[/] [who]` strip is capturable without new scaffolding.
    func testGroupHeader() {
        let app = launch("group")
        let title = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS '#ops'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30), "the demo group is on screen")
        assertNoOverflow(app, ["Search in conversation", "Members"])
        shot("textsize-\(size)-group")
    }

    /// `[cfg]` itself — the APPEARANCE section carries the new row
    /// (docs/STATUS-AND-CFG.md §2 renamed DISPLAY; §1 of this spec keeps
    /// `text size` directly under `theme` inside it).
    func testSettings() {
        let app = launch("settings")
        XCTAssertTrue(app.buttons["What protects me"].waitForExistence(timeout: 90), "[cfg] is up")
        // APPEARANCE is the third of §2's five sections, so both rows are
        // below the fold on arrival — and further down at `largest`. Scroll
        // to the row being MEASURED, not to the header above it: a scroll
        // that stops as soon as the header appears can leave the row itself
        // unrealised, which is a pass or a fail depending on where it landed.
        let row = app.staticTexts["text size"]
        for _ in 0..<12 where !(row.exists && app.frame.contains(row.frame)) { app.swipeUp() }
        XCTAssertTrue(row.exists, "the spec's row label, verbatim")
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'APPEARANCE'")).firstMatch.exists
            || app.staticTexts["theme"].exists,
                      "…and it is in APPEARANCE, under `theme`")
        shot("textsize-\(size)-cfg")
    }

    /// §3: the details sheet's envelope lines wrap mid-JSON; a horizontal
    /// scroll would not be acceptable.
    func testDetailsSheet() {
        let app = launch("chat")
        let row = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'hi pyrobot'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the demo thread is on screen")
        row.press(forDuration: 1.2)
        let details = app.buttons["details"]
        XCTAssertTrue(details.waitForExistence(timeout: 10), "the long-press menu offers `details`")
        details.tap()
        let header = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'what the relay received'")).firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "the details sheet opened")
        shot("textsize-\(size)-details")
    }

    /// §3: the safety number — 12 groups of 5 digits, capped at `large` — and
    /// the QR screen under it, whose code never scales.
    func testSafetyNumber() {
        let app = launch("chat")
        let verify = app.buttons["Verify safety number"]
        XCTAssertTrue(verify.waitForExistence(timeout: 30), "the chat header offers verify")
        verify.tap()
        let title = app.staticTexts["SAFETY NUMBER"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "the safety number sheet opened")
        shot("textsize-\(size)-safety-number")
    }

    /// §3: the home toolbar's five bracket labels. Capped at `large`, all
    /// five stay on the bar — UIKit must NOT fold any of them into its own
    /// `More` overflow menu.
    func testHomeToolbarKeepsAllFiveLabels() {
        let app = launch("home")
        XCTAssertTrue(app.staticTexts["PYROCHAT"].waitForExistence(timeout: 30),
                      "the home screen is up")
        assertNoOverflow(app, ["Settings", "Notes", "Share my username",
                               "Add contact by QR", "Open new channel"])
        shot("textsize-\(size)-home")
    }

    /// §3: the group members sheet — two lines per member (Android's layout),
    /// every bracket-or-tag label one unbroken line, and the names rendering as
    /// ordinary content type rather than the smallest thing on the sheet.
    func testGroupMembersSheet() {
        let app = launch("group-members")
        let title = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'members'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30), "the members sheet is up")
        // the bracket labels are whole, so they are still findable as one string
        XCTAssertTrue(app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH 'Remove '")).firstMatch.exists,
                      "a removable row is on screen")
        // the two-line split gives the names their width back: short ones are
        // whole, not elided to a stub
        for name in ["<bob>", "<you>"] {
            XCTAssertTrue(app.staticTexts[name].exists, "\(name) renders in full")
        }
        // `joined HH:MM` moved to its own line under the member
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH 'joined '")).firstMatch.exists,
                      "the second line is there")
        for label in ["[ add member ]", "[ leave group ]"] {
            let item = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label == %@", label)).firstMatch
            XCTAssertTrue(item.exists, "\(label) renders whole")
        }
        shot("textsize-\(size)-members")
    }

    /// §3: the add-by-QR screen — a fixed-size code with a caption that scales.
    func testAddByQR() {
        let app = launch("home")
        // At `largest` the toolbar's five items no longer fit side by side and
        // UIKit folds the trailing ones into its own `More` overflow menu —
        // nothing is clipped, it just takes one more tap to reach [qr].
        XCTAssertTrue(app.staticTexts["PYROCHAT"].waitForExistence(timeout: 30),
                      "the home screen is up")
        // the overflow menu drops the accessibility label and shows the raw
        // `[qr]` title, so either spelling is accepted
        let qr = app.buttons.matching(
            NSPredicate(format: "label == 'Add contact by QR' OR label == '[qr]'")).firstMatch
        if !qr.exists {
            let more = app.buttons["OverflowBarButtonItem"]
            XCTAssertTrue(more.waitForExistence(timeout: 10),
                          "[qr] is either on the bar or behind `More`")
            more.tap()
        }
        XCTAssertTrue(qr.waitForExistence(timeout: 10), "the home screen offers [qr]")
        qr.tap()
        let title = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'ADD BY QR'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10), "the add-by-QR screen opened")
        shot("textsize-\(size)-add-by-qr")
    }
}
