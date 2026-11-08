import XCTest

/// docs/FIRST-RUN.md §4 "Gate", the iOS half, against the LOCAL harness:
/// relay ws://127.0.0.1:7444/ws with a pyrobot on it, billing :8088 in
/// devMode. Skipped unless `PYRO_GATE_CODE` carries a key freshly minted
/// from that billing (`POST /api/checkout/dev`) — run it through the
/// scratchpad `gate.sh`, which mints one, wipes the app for a genuine CREATE
/// and passes the code in. Leaves a throwaway `gate_…` identity on the DEV
/// relay only.
final class FirstRunGateUITests: XCTestCase {
    func testCreateIdentity_PyrobotTour_RedeemByLink_SecondDeliveryRefused() throws {
        guard let code = ProcessInfo.processInfo.environment["PYRO_GATE_CODE"], !code.isEmpty else {
            throw XCTSkip("set PYRO_GATE_CODE to a key minted from the local billing to run the gate")
        }
        let user = "gate_" + UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(8)
        let app = XCUIApplication()
        // the DEBUG autopilot creates the identity (the CREATE path, which
        // adds pyrobot by itself); `--demo-say2` sends "hi" 6 s later so the
        // demo subtitle is on screen first
        app.launchArguments = ["--demo-noshield", "--demo-user", String(user),
                               "--demo-peer", "pyrobot", "--demo-say2", "hi", "--demo-say2-delay", "6",
                               "--demo-relay", "ws://127.0.0.1:7444/ws",
                               "--demo-billing", "http://127.0.0.1:8088"]
        app.launch()

        // §1: the pyrobot row appears by itself, with the demo subtitle
        XCTAssertTrue(app.staticTexts["<pyrobot>"].waitForExistence(timeout: 60),
                      "pyrobot was not added after CREATE IDENTITY")
        XCTAssertTrue(app.staticTexts["demo peer \u{2014} say hi for a 60-second tour"].waitForExistence(timeout: 5),
                      "demo subtitle missing on the fresh pyrobot row")
        XCTAssertEqual(app.staticTexts.matching(identifier: "<pyrobot>").count, 1, "exactly one pyrobot row")
        save(XCUIScreen.main.screenshot(), as: "gate-pyrobot-row")

        // "hi" goes out → the tour arrives; the row previews its last line
        // (one line, so the label is the visible head of it — the tail with
        // "bring a friend: [share]…" is truncated away)
        let tourEnd = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "send me anything and i'll echo it back")).firstMatch
        XCTAssertTrue(tourEnd.waitForExistence(timeout: 90), "the tour did not arrive")
        save(XCUIScreen.main.screenshot(), as: "gate-tour")

        // §3: the key by deep link → ✓ access granted: 31 days
        XCTAssertEqual(redeemByLink(app, code: code), "\u{2713} access granted: 31 days")
        save(XCUIScreen.main.screenshot(), as: "gate-redeemed")
        // a second delivery of the same code → the invalid/used error
        XCTAssertEqual(redeemByLink(app, code: code), "\u{2717} invalid or already-used access key")
        save(XCUIScreen.main.screenshot(), as: "gate-reused")
    }

    /// Opens the link, answers the springboard "Open in PyroChat?" prompt if
    /// it shows, waits for the pre-filled dialog to open and close, scrolls
    /// [cfg] to ACCESS and returns the verdict text.
    private func redeemByLink(_ app: XCUIApplication, code: String) -> String {
        XCUIDevice.shared.system.open(URL(string: "pyrochat://redeem/\(code)")!)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.buttons["Open"]
        let allow = springboard.buttons["Allow"]      // a stray notifications prompt
        let field = app.textFields.matching(NSPredicate(format: "value == %@", code)).firstMatch
        var deadline = Date().addingTimeInterval(15)
        var seen = false
        while Date() < deadline {
            if allow.exists { allow.tap() }
            if open.exists { open.tap() }
            if field.exists { seen = true; break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertTrue(seen, "ACCESS KEY dialog did not open pre-filled")
        deadline = Date().addingTimeInterval(30)     // a real redeem: 31 blind signatures
        while field.exists && Date() < deadline { Thread.sleep(forTimeInterval: 0.2) }
        XCTAssertFalse(field.exists, "dialog did not close with the verdict")
        let verdict = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH '\u{2717}' OR label BEGINSWITH '\u{2713}'")).firstMatch
        for _ in 0..<5 where !verdict.exists { app.swipeUp() }
        return verdict.exists ? verdict.label : "(no verdict)"
    }

    private func save(_ shot: XCUIScreenshot, as name: String) {
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir = ProcessInfo.processInfo.environment["PYRO_SHOT_DIR"] else { return }
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }
}
