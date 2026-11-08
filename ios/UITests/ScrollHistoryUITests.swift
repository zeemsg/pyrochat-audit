import XCTest

/// Scroll-history regression for `TerminalLogView`: paging older rows into
/// the window must never leave the conversation area blank (the "log goes
/// black until you swipe again" report), and must not move the reader.
/// Drives the DEBUG screenshot demo (`--screenshot-demo=chat --demo-rows N`)
/// — canned store, no relay.
///
/// After every swipe toward older rows a screenshot is taken and the log
/// band (top 15 % = header and bottom 12 % = composer excluded) is measured
/// two ways:
///   * `lines`: share of horizontal scan lines holding at least one bright
///     pixel (any channel > 80). Text on black sits well above 0.25; a
///     blank viewport is ~0. This is the assertion.
///   * `black`: share of near-black pixels (all channels < 40). Printed for
///     the record only — it tracks content density (short rows push a
///     perfectly rendered frame past 0.9) so it cannot gate.
/// The top visible row's `#index` is also traced: a page landing must not
/// carry the reader further than one swipe's worth of rows.
final class ScrollHistoryUITests: XCTestCase {
    private let swipes = 12
    private let minLineFraction = 0.08
    private let maxRowJump = 40

    override func setUpWithError() throws {
        continueAfterFailure = true      // print every swipe before failing
    }

    func testPagingHistoryNeverBlanksTheLog() throws {
        try runScrollHistory(rows: 400, extraArgs: [])
    }

    // MARK: driver

    private func runScrollHistory(rows: Int, extraArgs: [String]) throws {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=chat", "--demo-rows", "\(rows)"] + extraArgs
        app.launch()

        XCTAssertTrue(app.buttons["Send"].waitForExistence(timeout: 30),
                      "chat composer never appeared")
        XCTAssertTrue(app.staticTexts.matching(rowPredicate).firstMatch
                        .waitForExistence(timeout: 10), "no chat row rendered")
        Thread.sleep(forTimeInterval: 1.0)          // open-at-bottom re-pins settle

        let base = measure(XCUIScreen.main.screenshot())
        var rowsNow = try visibleRows(app)
        print("BLANK: baseline black=\(fmt(base.black)) lines=\(fmt(base.lines)) rows=\(rowsNow.count) top=\(short(rowsNow.first))")
        XCTAssertGreaterThan(base.lines, 0.25, "baseline frame should be full of text")
        var lastTop = index(of: rowsNow.first)

        // swipe by screen coordinates inside the log band: finger moves down
        // (viewport up = older rows), released with momentum like a real flick
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.32))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.78))

        var lines: [Double] = []
        for i in 1...swipes {
            from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .default,
                       thenHoldForDuration: 0)
            Thread.sleep(forTimeInterval: 0.7)
            let shot = XCUIScreen.main.screenshot()
            let m = measure(shot)
            lines.append(m.lines)
            rowsNow = try visibleRows(app)
            let top = index(of: rowsNow.first)
            print("BLANK: swipe \(i) black=\(fmt(m.black)) lines=\(fmt(m.lines)) rows=\(rowsNow.count) top=\(short(rowsNow.first))")
            let att = XCTAttachment(screenshot: shot)
            att.name = "swipe-\(i)"
            att.lifetime = .keepAlways
            add(att)

            XCTAssertGreaterThanOrEqual(m.lines, minLineFraction,
                "log went blank after swipe \(i): bright-line fraction \(fmt(m.lines))")
            if let top, let last = lastTop {
                XCTAssertLessThanOrEqual(top, last,
                    "swipe \(i) moved toward NEWER rows (#\(last) -> #\(top)): viewport lost its anchor")
                XCTAssertLessThanOrEqual(last - top, maxRowJump,
                    "swipe \(i) jumped \(last - top) rows (#\(last) -> #\(top)): page landing moved the reader")
            }
            if top != nil { lastTop = top }
        }
        print("BLANK: lines=[\(lines.map(fmt).joined(separator: ", "))] min=\(fmt(lines.min() ?? 0))")

        // at least one row must still be a real, tappable view after paging
        let after = try visibleRows(app)
        XCTAssertFalse(after.isEmpty, "no chat row inside the log band after paging")
        let hittable = after.contains { label in
            // labels run past XCUITest's 128-char subscript limit: match by predicate
            app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch.isHittable
        }
        XCTAssertTrue(hittable, "no chat row is hittable after paging (visible: \(after.prefix(3).map(short)))")
    }

    // MARK: helpers

    /// Generated rows are "<pyrobot> #i …" / "<you> #i …".
    private var rowPredicate: NSPredicate {
        NSPredicate(format: "label CONTAINS '#'")
    }

    /// Labels of chat rows whose frame intersects the log band, top first.
    /// One accessibility snapshot per call (attribute access on 100+ elements
    /// would re-query each).
    private func visibleRows(_ app: XCUIApplication) throws -> [String] {
        let screen = XCUIScreen.main.screenshot().image.size    // points
        let band = CGRect(x: 0, y: screen.height * 0.15, width: screen.width,
                          height: screen.height * (0.88 - 0.15))
        let snap = try app.snapshot()
        var found: [(CGFloat, String)] = []
        func walk(_ s: XCUIElementSnapshot) {
            if s.elementType == .staticText, s.label.contains("#"),
               s.frame.intersects(band) {
                found.append((s.frame.minY, s.label))
            }
            s.children.forEach(walk)
        }
        walk(snap)
        return found.sorted { $0.0 < $1.0 }.map(\.1)
    }

    /// "#123" -> 123 from a row label.
    private func index(of label: String?) -> Int? {
        guard let label, let hash = label.firstIndex(of: "#") else { return nil }
        let digits = label[label.index(after: hash)...].prefix { $0.isNumber }
        return Int(digits)
    }

    private func short(_ label: String?) -> String {
        guard let label else { return "-" }
        return String(label.prefix(28))
    }

    /// `black`: share of sampled pixels in the log band with all channels
    /// < 40. `lines`: share of sampled scan lines in the band holding at
    /// least one pixel with any channel > 80.
    private func measure(_ shot: XCUIScreenshot) -> (black: Double, lines: Double) {
        guard let cg = shot.image.cgImage,
              let data = cg.dataProvider?.data,
              let p = CFDataGetBytePtr(data) else {
            XCTFail("screenshot has no pixel data"); return (1, 0)
        }
        let w = cg.width, h = cg.height
        let bpr = cg.bytesPerRow, bpp = cg.bitsPerPixel / 8
        // colour bytes: skip a leading alpha byte when the alpha sits first
        let colourOffset: Int
        switch cg.alphaInfo {
        case .premultipliedFirst, .first, .noneSkipFirst: colourOffset = 1
        default: colourOffset = 0
        }
        let y0 = Int(Double(h) * 0.15), y1 = Int(Double(h) * 0.88)
        var dark = 0, total = 0, brightLines = 0, lineCount = 0
        var y = y0
        while y < y1 {
            var bright = false
            var x = 0
            while x < w {
                let i = y * bpr + x * bpp + colourOffset
                let r = p[i], g = p[i + 1], b = p[i + 2]
                if r < 40 && g < 40 && b < 40 { dark += 1 }
                if r > 80 || g > 80 || b > 80 { bright = true }
                total += 1
                x += 3
            }
            if bright { brightLines += 1 }
            lineCount += 1
            y += 2
        }
        guard total > 0, lineCount > 0 else { return (1, 0) }
        return (Double(dark) / Double(total), Double(brightLines) / Double(lineCount))
    }

    private func fmt(_ v: Double) -> String { String(format: "%.3f", v) }
}
