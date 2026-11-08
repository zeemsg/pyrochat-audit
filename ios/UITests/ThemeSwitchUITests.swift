import XCTest

/// Theme regression: picking a theme in `[cfg]` must recolour the screen at
/// once. The store is not observable, so a bare `store.update` used to leave
/// every view on the old colour until some unrelated publish — "on iOS the
/// theme doesn't change, on Android it's instant". Drives the DEBUG screenshot
/// demo (`--screenshot-demo=settings`) and measures the pixels: ember is green
/// (G high, R low), plasma is magenta (R and B high, G low).
final class ThemeSwitchUITests: XCTestCase {
    func testPickingATheme_RecoloursImmediately() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-demo=settings"]
        app.launch()

        // docs/STATUS-AND-CFG.md §2 moved the theme row: it used to open
        // `[cfg]` as the first row of DISPLAY and now sits in APPEARANCE, the
        // third of five sections, below the fold. A SwiftUI List has not
        // realised it on arrival, so this scrolls to it rather than waiting
        // for something that is not coming.
        let label = app.staticTexts["theme"]
        XCTAssertTrue(app.buttons["What protects me"].waitForExistence(timeout: 90),
                      "never reached [cfg]")
        // `isHittable` is the wrong stopping point here: the row is a menu
        // picker, whose label text is not itself a hit target. Scroll until
        // the row is fully ON SCREEN instead.
        for _ in 0..<12 where !(label.exists && app.frame.contains(label.frame)) {
            app.swipeUp()
        }
        XCTAssertTrue(label.exists && app.frame.contains(label.frame),
                      "theme row never appeared")
        let before = hues(XCUIScreen.main.screenshot())
        print("THEME: before green=\(before.green) magenta=\(before.magenta)")
        XCTAssertGreaterThan(before.green, before.magenta * 4, "demo store should open in ember")

        // A SwiftUI menu picker in a List exposes a button carrying the label and
        // the current value; fall back to the value text on the row's right edge.
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS 'theme'")).firstMatch
        if button.waitForExistence(timeout: 3) && button.isHittable {
            button.tap()
        } else {
            let f = label.frame
            app.coordinate(withNormalizedOffset: .zero)
               .withOffset(CGVector(dx: app.frame.width - 40, dy: f.midY)).tap()
        }
        let plasma = app.buttons["plasma"]
        XCTAssertTrue(plasma.waitForExistence(timeout: 5), "menu did not list plasma")
        plasma.tap()
        Thread.sleep(forTimeInterval: 0.4)          // one frame + the menu's dismissal

        let after = hues(XCUIScreen.main.screenshot())
        print("THEME: after  green=\(after.green) magenta=\(after.magenta)")
        XCTAssertGreaterThan(after.magenta, after.green * 4,
                             "screen still green after choosing plasma — settings change not published")
        XCTAssertGreaterThan(after.magenta, before.magenta * 10)
    }

    /// Counts of clearly green vs clearly magenta pixels over the whole frame.
    private func hues(_ shot: XCUIScreenshot) -> (green: Int, magenta: Int) {
        guard let cg = shot.image.cgImage,
              let data = cg.dataProvider?.data, let p = CFDataGetBytePtr(data) else { return (0, 0) }
        let bpr = cg.bytesPerRow, bpp = cg.bitsPerPixel / 8
        let alphaFirst = cg.alphaInfo == .premultipliedFirst || cg.alphaInfo == .first || cg.alphaInfo == .noneSkipFirst
        let little = cg.bitmapInfo.contains(.byteOrder32Little)
        var green = 0, magenta = 0
        var y = 0
        while y < cg.height {
            var x = 0
            while x < cg.width {
                let o = y * bpr + x * bpp
                let b0 = Int(p[o]), b1 = Int(p[o + 1]), b2 = Int(p[o + 2]), b3 = Int(p[o + 3])
                let r: Int, g: Int, b: Int
                if little { (r, g, b) = alphaFirst ? (b2, b1, b0) : (b3, b2, b1) }   // BGRA / ABGR
                else      { (r, g, b) = alphaFirst ? (b1, b2, b3) : (b0, b1, b2) }   // ARGB / RGBA
                if g > 140 && r < 120 && b < 160 { green += 1 }
                else if r > 150 && b > 120 && g < 150 { magenta += 1 }
                x += 2
            }
            y += 2
        }
        return (green, magenta)
    }
}
