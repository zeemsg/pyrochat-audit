import XCTest

/// docs/STATUS-AND-CFG.md §2 — the regrouped `[cfg]`, and in particular its
/// "what must not move" list, which is the set of invariants a regrouping
/// typically breaks. Each one gets an assertion here because each one is
/// exactly the kind of thing that survives a code review and dies in a diff.
///
/// `[cfg]` is now the densest screen in the app, so the layout checks are
/// pixel- and frame-level and the captures are kept: earlier waves on this
/// repo found real defects in a picture that no test caught.
final class CfgLayoutUITests: XCTestCase {

    /// §2's sections, in order. APPEARANCE is SECOND — ordering purely by
    /// consequence put `theme` and `text size` below the fold, and those are
    /// the rows people actually touch. DANGER is last and holds one row.
    static let sections = ["PROTECTION", "APPEARANCE",
                           "WHAT LEAVES THIS PHONE", "ACCOUNT", "DANGER"]
    /// The names this regrouping retired. A stray one means half a move.
    static let retired = ["DISPLAY", "SAFETY", "ACCESS", "LINK"]

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
    }

    private func launch(textSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings"]
        if let textSize { app.launchArguments += ["--demo-text-size", textSize] }
        app.launch()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'PROTECTION'"))
            .firstMatch.waitForExistence(timeout: 90), "never reached [cfg]")
        return app
    }

    /// Where a label was first seen: which scroll page, and the vertical band
    /// it occupied on it. The band matters — a menu picker publishes a row
    /// label, a value and a `label, value` button that all sit on ONE line, so
    /// an ordering test that compares midpoints reports a row as having crept
    /// between itself and its neighbour.
    struct Seen {
        let page: Int
        let top: CGFloat
        let bottom: CGFloat
        /// Earlier page first, then higher on that page.
        var start: Double { Double(page) * 100_000 + Double(top) }
        var end: Double { Double(page) * 100_000 + Double(bottom) }
    }

    /// Scroll the screen end to end, remembering where each interesting label
    /// was seen. A SwiftUI `List` realises lazily, so nothing below the fold
    /// exists until it has been on screen once — an ordering assertion over
    /// live frames can only see a screenful at a time.
    @discardableResult
    private func sweep(_ app: XCUIApplication, shots: String? = nil) -> [String: Seen] {
        var seen: [String: Seen] = [:]
        for page in 0..<14 {
            for e in app.staticTexts.allElementsBoundByIndex
                + app.buttons.allElementsBoundByIndex
                + app.switches.allElementsBoundByIndex where e.exists && !e.label.isEmpty {
                let f = e.frame
                guard f.height > 0 else { continue }
                if seen[e.label] == nil {
                    seen[e.label] = Seen(page: page, top: f.minY, bottom: f.maxY)
                }
            }
            if let shots, page < 6 { shot("\(shots)-\(page)") }
            let before = app.staticTexts.count
            app.swipeUp()
            if page > 2 && app.staticTexts.count == before && seen["[ BURN EVERYTHING ]"] != nil {
                break
            }
        }
        return seen
    }

    private func order(_ seen: [String: Seen], _ label: String) -> Double? {
        seen[label]?.start
    }

    /// Everything whose whole band falls in the gap BETWEEN two rows — so an
    /// element sharing a line with either end is not "between" them.
    private func strictlyBetween(_ seen: [String: Seen],
                                 _ a: Seen, _ b: Seen) -> [String] {
        seen.filter { _, v in v.start > a.end && v.end < b.start }.keys.sorted()
    }

    // MARK: §2 — the five sections, in order, and nothing left of the old ones

    func testSectionsAndTheirOrder() {
        let app = launch()
        let seen = sweep(app, shots: "cfg-normal")
        let headers = Self.sections.map { name -> Double in
            let key = seen.keys.first { $0.contains(name) }
            guard let key, let o = order(seen, key) else {
                XCTFail("no `\(name)` section in [cfg]")
                return -1
            }
            return o
        }
        XCTAssertEqual(headers, headers.sorted(),
                       "§2's five sections are not in the order the spec lists them")
        for gone in Self.retired {
            XCTAssertNil(seen.keys.first { $0.contains("\u{2500}\u{2500} \(gone)") },
                         "`\(gone)` is still a section header — the move is half done")
        }
    }

    // MARK: §1 — the settled row order inside "what leaves this phone"

    /// §1: "Row order in `what leaves this phone` is the same on both
    /// screens, and it is: wake push, proxy, read receipts, typing
    /// indicators, desktop link." The sketch and §2's table disagreed about
    /// where `proxy` sat and BOTH orders shipped, so this is an adjacency
    /// assertion rather than a sort: a future reshuffle fails loudly instead
    /// of quietly moving one row past another.
    func testLeavesSectionRowOrder() {
        let app = launch()
        let seen = sweep(app)
        let order = ["wake push (zero-content)", "socks proxy",
                     "read receipts (send + show)", "typing indicators",
                     "LINK DESKTOP \u{2026}"]
        var previous: Seen?
        var previousName = ""
        for name in order {
            guard let key = seen.keys.first(where: { $0 == name || $0.hasPrefix("\(name), ") }),
                  let here = seen[key] else {
                XCTFail("`\(name)` is missing from WHAT LEAVES THIS PHONE")
                continue
            }
            if let previous {
                XCTAssertLessThan(previous.start, here.start,
                                  "`\(previousName)` must come before `\(name)`")
                // adjacent: only that row's own footnote may sit between them
                let between = strictlyBetween(seen, previous, here).filter {
                    !$0.hasPrefix("banner + sound whenever")
                }
                XCTAssertTrue(between.isEmpty,
                              "a row crept between `\(previousName)` and `\(name)`: \(between)")
            }
            previous = here
            previousName = name
        }
    }

    // MARK: §2 "what must not move" — docs/TEXT-SIZE.md §1

    /// "`text size` stays directly under `theme`." Directly: nothing of the
    /// section's own between them.
    func testTextSizeIsDirectlyUnderTheme() {
        let app = launch()
        let seen = sweep(app)
        guard let theme = seen["theme"], let size = seen["text size"] else {
            return XCTFail("theme / text size missing from [cfg]")
        }
        XCTAssertLessThan(theme.start, size.start, "text size must sit UNDER theme")
        let strays = strictlyBetween(seen, theme, size)
        XCTAssertTrue(strays.isEmpty, "rows crept between theme and text size: \(strays)")
    }

    // MARK: §2 "what must not move" — docs/BIOMETRIC-UNLOCK.md §2

    /// "The biometric row stays directly above `auto-lock`." The row is hidden
    /// on a device with no biometry enrolled, and then there is nothing to
    /// move; the sibling assertion — auto-lock opens PROTECTION — holds either
    /// way, so this test says something on every machine.
    ///
    /// To exercise the real case on a simulator:
    ///     xcrun simctl spawn <udid> notifyutil -s com.apple.BiometricKit.enrollmentChanged 1
    ///     xcrun simctl spawn <udid> notifyutil -p com.apple.BiometricKit.enrollmentChanged
    func testBiometricRowSitsDirectlyAboveAutoLock() {
        let app = launch()
        let seen = sweep(app)
        guard let autoLock = seen["auto-lock"] else {
            return XCTFail("auto-lock missing from [cfg]")
        }
        guard let bioKey = seen.keys.first(where: { $0.hasPrefix("unlock with") }),
              let bio = seen[bioKey] else {
            // nothing enrolled: the row is absent by design, so PROTECTION
            // opens on auto-lock and no row can have got above it.
            guard let pKey = seen.keys.first(where: { $0.contains("PROTECTION") }),
                  let protection = seen[pKey] else {
                return XCTFail("no PROTECTION header")
            }
            let above = strictlyBetween(seen, protection, autoLock)
            XCTAssertTrue(above.isEmpty,
                          "with no biometric row, auto-lock must open PROTECTION; found \(above)")
            return
        }
        XCTAssertLessThan(bio.start, autoLock.start, "§2: the biometric row sits ABOVE auto-lock")
        let between = strictlyBetween(seen, bio, autoLock).filter {
            // §2's own footnote lives between them and always has
            !$0.hasPrefix("a face or a finger can be taken from you")
        }
        XCTAssertTrue(between.isEmpty,
                      "rows crept between the biometric row and auto-lock: \(between)")
    }

    // MARK: §2 "what must not move" — docs/DECOY-ACCOUNT.md §3

    /// "The decoy row keeps its exact label and renders identically whatever
    /// the device holds. It moves section; it does not change."
    ///
    /// The label is asserted verbatim. The rendering is asserted in PIXELS,
    /// because the one change this regrouping could plausibly have made to it
    /// is the one §2 introduced two rows above: the alert colour on rows that
    /// destroy data. A decoy destroys nothing, and a row that took on weight
    /// when it changed section would be a new way to notice it.
    func testDecoyRowIsUnchangedAndUncoloured() {
        let app = launch()
        let decoy = app.buttons["set decoy password \u{2026}"]
        for _ in 0..<10 where !(decoy.exists && decoy.isHittable) { app.swipeUp() }
        XCTAssertTrue(decoy.exists, "the decoy row is missing from PROTECTION")
        XCTAssertEqual(decoy.label, "set decoy password \u{2026}", "§3: the label is the label")
        XCTAssertTrue(decoy.isEnabled, "§3: never a disabled state")

        let duress = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'duress pin'")).firstMatch
        let deadman = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'dead-man wipe'")).firstMatch
        XCTAssertTrue(duress.exists, "the duress row is missing from PROTECTION")
        shot("cfg-consequence-weight")

        let frame = XCUIScreen.main.screenshot()
        let screen = app.frame
        // every row being measured has to be ON the frame being measured
        for e in [duress, deadman, decoy] where e.exists {
            XCTAssertTrue(screen.contains(e.frame),
                          "`\(e.label)` is off screen — the pixels would be someone else's")
        }
        // §2: "Consequence gets weight … the existing alert colour is enough."
        XCTAssertGreaterThan(alertPixels(frame, duress.frame, screenWidth: screen.width), 20,
                             "the duress row carries no alert colour")
        if deadman.exists {
            XCTAssertGreaterThan(alertPixels(frame, deadman.frame, screenWidth: screen.width), 20,
                                 "the dead-man row carries no alert colour")
        }
        // …and the decoy row, two rows down, does not.
        XCTAssertLessThan(alertPixels(frame, decoy.frame, screenWidth: screen.width), 10,
                          "§3: the decoy row took on the alert colour when it changed section")
    }

    // MARK: §2 "what must not move" — burn everything

    /// "`[ BURN EVERYTHING ]` stays last, alone, in DANGER."
    func testBurnEverythingIsLastAndAlone() {
        let app = launch()
        let seen = sweep(app)
        guard let burn = seen["[ BURN EVERYTHING ]"],
              let dangerKey = seen.keys.first(where: { $0.contains("DANGER") }),
              let danger = seen[dangerKey] else {
            return XCTFail("no DANGER section with a burn row")
        }
        XCTAssertLessThan(danger.start, burn.start, "the burn row is inside DANGER")
        // alone: nothing at all between the header and the row…
        XCTAssertTrue(strictlyBetween(seen, danger, burn).isEmpty,
                      "DANGER is not alone — it also holds \(strictlyBetween(seen, danger, burn))")
        // …and last: nothing below it either
        let below = seen.filter { _, v in v.start > burn.end }.keys.sorted()
        XCTAssertTrue(below.isEmpty, "something renders below the burn row: \(below)")
    }

    // MARK: §2 — self-hoster settings collapse

    /// "The relay address and the billing address move behind one `advanced …`
    /// row at the bottom of ACCOUNT."
    ///
    /// Worth knowing what this actually covers: in every SHIPPED build both
    /// rows are compiled out (`#if PRIVATE_SERVER_UI`), so the collapse
    /// changes nothing a user has ever seen, and what this asserts there is
    /// only that neither address leaked into the everyday path. The expander
    /// itself exists for the private-server build, and the last two
    /// assertions run only when compiled with that flag.
    func testServerAddressesAreNotInTheEverydayPath() {
        let app = launch()
        let seen = sweep(app)
        for gone in ["relay", "billing"] {
            let stray = seen.keys.first { $0 == gone || $0.hasPrefix("\(gone), ") }
            XCTAssertNil(stray, "`\(gone)` is still an ordinary ACCOUNT row")
        }
        // the proxy is a user feature, not server config: it stays visible
        XCTAssertNotNil(seen.keys.first { $0.contains("socks proxy") },
                        "the proxy row was collapsed too — §2 keeps it in the everyday path")
        // the row's accessibility label is `Advanced server addresses`, as
        // every other button on this screen replaces its glyph text with a
        // spoken one; match either spelling, either case.
        if let advanced = seen.keys.first(where: { $0.lowercased().contains("advanced") }) {
            // present only in a PRIVATE_SERVER_UI build; when it is, it is last
            guard let a = order(seen, advanced),
                  let support = order(seen, "support") else { return }
            XCTAssertGreaterThan(a, support, "`advanced …` is at the BOTTOM of ACCOUNT")
        }
        // §2: ACCOUNT ends `support, licenses`, and in the self-hoster build
        // one `advanced …` row under them. The licences row is not optional
        // (libsignal is AGPLv3 and the app has to carry its notice), so it is
        // looked for by name: it publishes its row label, its value and the
        // spoken label of the link. Nothing else may sit between `support`
        // and DANGER, whichever build this is.
        let licences: Set<String> = ["licenses", "agplv3, libsignal", "Open source licenses"]
        if let support = seen["support"], let dangerKey = seen.keys.first(where: { $0.contains("DANGER") }),
           let danger = seen[dangerKey] {
            let tail = strictlyBetween(seen, support, danger)
            XCTAssertTrue(tail.contains("Open source licenses"),
                          "the licences row is not between `support` and the end of ACCOUNT: \(tail)")
            let strays = tail.filter { !licences.contains($0) && !$0.lowercased().contains("advanced") }
            XCTAssertTrue(strays.isEmpty,
                          "ACCOUNT ends on something other than `licenses` and `advanced …`: \(strays)")
            if let advanced = seen.keys.first(where: { $0.lowercased().contains("advanced") }),
               let a = order(seen, advanced), let l = order(seen, "Open source licenses") {
                XCTAssertGreaterThan(a, l, "`advanced …` is under `licenses`, last in ACCOUNT")
            }
        }
    }

    // MARK: docs/TEXT-SIZE.md §3 — the densest screen, at the widest size

    func testEverySectionRendersAtLargestOnTheNarrowestPhone() {
        let app = launch(textSize: "largest")
        var seen: [String: (page: Int, y: CGFloat)] = [:]
        for page in 0..<20 {
            for e in app.staticTexts.allElementsBoundByIndex
                + app.buttons.allElementsBoundByIndex
                + app.switches.allElementsBoundByIndex where e.exists && !e.label.isEmpty {
                if seen[e.label] == nil { seen[e.label] = (page, e.frame.midY) }
                let f = e.frame
                guard f.width > 0, f.height > 0 else { continue }
                XCTAssertLessThanOrEqual(f.maxX, app.frame.maxX + 0.5,
                                         "`\(e.label.prefix(40))` runs off the right edge at largest")
                XCTAssertGreaterThanOrEqual(f.minX, -0.5,
                                            "`\(e.label.prefix(40))` runs off the left edge at largest")
            }
            if page < 10 { shot("cfg-largest-\(page)") }
            app.swipeUp()
            if seen["[ BURN EVERYTHING ]"] != nil { break }
        }
        for name in Self.sections {
            XCTAssertNotNil(seen.keys.first { $0.contains(name) },
                            "`\(name)` never rendered at largest on the narrowest phone")
        }
        XCTAssertNotNil(seen["[ BURN EVERYTHING ]"], "the screen never reached its end")
    }

    // MARK: pixels

    /// Pixels in `rect` (screen POINTS) that are clearly `Theme.alert`
    /// (255, 89, 64) rather than any phosphor foreground.
    ///
    /// The scale comes from the CGImage's pixel width against the screen's
    /// point width. `UIImage.size` is in points too, so deriving it from
    /// there gives 1 on every Retina device and samples the top-left corner
    /// of the screen for every row — which reads as "no alert colour
    /// anywhere", i.e. as the bug this test exists to catch.
    private func alertPixels(_ shot: XCUIScreenshot, _ rect: CGRect,
                             screenWidth: CGFloat) -> Int {
        guard let cg = shot.image.cgImage,
              let data = cg.dataProvider?.data, let p = CFDataGetBytePtr(data) else { return 0 }
        let scale = CGFloat(cg.width) / max(screenWidth, 1)
        let bpr = cg.bytesPerRow, bpp = cg.bitsPerPixel / 8
        let alphaFirst = cg.alphaInfo == .premultipliedFirst || cg.alphaInfo == .first
            || cg.alphaInfo == .noneSkipFirst
        let little = cg.bitmapInfo.contains(.byteOrder32Little)
        let x0 = max(0, Int(rect.minX * scale)), x1 = min(cg.width, Int(rect.maxX * scale))
        let y0 = max(0, Int(rect.minY * scale)), y1 = min(cg.height, Int(rect.maxY * scale))
        var hits = 0
        var y = y0
        while y < y1 {
            var x = x0
            while x < x1 {
                let o = y * bpr + x * bpp
                let b0 = Int(p[o]), b1 = Int(p[o + 1]), b2 = Int(p[o + 2]), b3 = Int(p[o + 3])
                let r: Int, g: Int, b: Int
                if little { (r, g, b) = alphaFirst ? (b2, b1, b0) : (b3, b2, b1) }
                else      { (r, g, b) = alphaFirst ? (b1, b2, b3) : (b0, b1, b2) }
                if r > 150 && g < 140 && b < 140 && r > g + 60 { hits += 1 }
                x += 1
            }
            y += 1
        }
        return hits
    }
}
