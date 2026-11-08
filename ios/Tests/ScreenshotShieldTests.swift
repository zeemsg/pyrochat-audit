import XCTest
import UIKit
@testable import PyroChat

@MainActor
final class ScreenshotShieldTests: XCTestCase {

    private var windows: [UIWindow] = []

    /// Each test lets the screen catch up before its windows go and again after,
    /// so that what it did to the layer tree takes effect here: a crash at the
    /// next commit has to be this test's, not the one that happens to run after
    /// it. And the shield comes off before the windows go, as the app does it: a
    /// shield field that outlives its window crashes UIKit later, in whichever
    /// test is running by then.
    override func tearDown() {
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        windows.forEach { ScreenshotShield.remove(from: $0); $0.isHidden = true }
        windows = []
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
    }

    /// The shield reparents the window's layer under a secure canvas. The
    /// app must still RENDER normally — only OS capture paths go black.
    func testShieldKeepsWindowRendering() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        windows.append(window)
        let red = UIView(frame: window.bounds)
        red.backgroundColor = .red
        window.addSubview(red)
        window.makeKeyAndVisible()

        ScreenshotShield.apply(true, to: window)

        // structure: window layer now lives under a secure field's canvas
        XCTAssertNotNil(window.layer.superlayer,
                        "window layer must stay attached after reparenting")
        // pixels: drawing the layer tree still shows the content
        let img = UIGraphicsImageRenderer(bounds: window.bounds).image { ctx in
            window.layer.render(in: ctx.cgContext)
        }
        var pixel = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let cg = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8,
                           bytesPerRow: 4, space: space,
                           bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        cg.draw(img.cgImage!, in: CGRect(x: -50, y: -50, width: 100, height: 100))
        XCTAssertGreaterThan(pixel[0], 200, "content must still render (red)")
        XCTAssertLessThan(pixel[1], 60)

        // toggling off/on flips the secure flag without touching the tree
        ScreenshotShield.set(false)
        ScreenshotShield.set(true)
        XCTAssertNotNil(window.layer.superlayer)
    }

    func testApplyIsIdempotentAndSafeOnNilWindow() {
        ScreenshotShield.apply(true, to: nil)   // must not crash
        let w = UIWindow(frame: CGRect(x: 0, y: 0, width: 50, height: 50))
        windows.append(w)
        ScreenshotShield.apply(true, to: w)
        ScreenshotShield.apply(true, to: w)     // second install: no-op
        ScreenshotShield.apply(false, to: w)    // disable via flag
        ScreenshotShield.apply(true, to: w)
        XCTAssertNotNil(w.layer.superlayer)
    }

    // MARK: a window the system moves (a phone that folds hands the app to its other display)

    /// A window with something red in it, kept until the test is over.
    private func redWindow() -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        let red = UIView(frame: window.bounds)
        red.backgroundColor = .red
        window.addSubview(red)
        window.makeKeyAndVisible()
        windows.append(window)
        return window
    }

    /// The window sits under a secure field's canvas: window → canvas → field.
    private func isShielded(_ window: UIWindow) -> Bool {
        window.layer.superlayer?.superlayer?.delegate is UITextField
    }

    /// What such a move may do: the system hangs the window's layer back where it
    /// keeps it, out from under the canvas. Returns that place.
    private func moveAsTheSystemMight(_ window: UIWindow) throws -> CALayer {
        let home = try XCTUnwrap(window.layer.superlayer?.superlayer?.superlayer,
                                 "the shield is not installed the way this test expects")
        home.addSublayer(window.layer)
        XCTAssertTrue(window.layer.superlayer === home)
        XCTAssertFalse(isShielded(window), "the move took the window out from under the canvas")
        return home
    }

    private func redness(of window: UIWindow) -> UInt8 {
        let img = UIGraphicsImageRenderer(bounds: window.bounds).image { ctx in
            window.layer.render(in: ctx.cgContext)
        }
        var pixel = [UInt8](repeating: 0, count: 4)
        let cg = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                           space: CGColorSpaceCreateDeviceRGB(),
                           bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        cg.draw(img.cgImage!, in: CGRect(x: -50, y: -50, width: 100, height: 100))
        return pixel[0]
    }

    func testTheShieldIsPutBackAroundAWindowTheSystemMoved() throws {
        let window = redWindow()
        ScreenshotShield.apply(true, to: window)
        XCTAssertTrue(isShielded(window))
        let home = try moveAsTheSystemMight(window)

        ScreenshotShield.follow(window)

        XCTAssertTrue(isShielded(window), "the window is under a secure canvas again")
        XCTAssertTrue(window.layer.superlayer?.superlayer?.superlayer === home, "which is where the window was put")
        XCTAssertTrue(ScreenshotShield.isOn)
        XCTAssertEqual(home.sublayers?.filter { $0.delegate is UITextField }.count, 1,
                       "by the one field there was: none is left behind and none is added")
        XCTAssertGreaterThan(redness(of: window), 200, "and the app still draws")
    }

    func testAShieldTheUserSwitchedOffIsStillOffAfterItIsPutBack() throws {
        let window = redWindow()
        ScreenshotShield.apply(true, to: window)
        ScreenshotShield.set(false)
        _ = try moveAsTheSystemMight(window)

        ScreenshotShield.follow(window)

        XCTAssertTrue(isShielded(window))
        XCTAssertFalse(ScreenshotShield.isOn, "following the window is not a reason to switch the shield back on")
        ScreenshotShield.set(true)
        XCTAssertTrue(ScreenshotShield.isOn, "and the toggle still reaches it")
    }

    func testAShieldThatIsInPlaceIsLeftAlone() {
        let window = redWindow()
        ScreenshotShield.apply(true, to: window)
        let canvas = window.layer.superlayer
        ScreenshotShield.follow(window)
        ScreenshotShield.apply(true, to: window)
        XCTAssertTrue(window.layer.superlayer === canvas, "nothing was rebuilt")
        let other = redWindow()
        ScreenshotShield.follow(other)
        XCTAssertFalse(isShielded(other), "a window that was never shielded is not given one by following it")
    }

    /// The system may also put a layer of its own between the canvas and the
    /// window. The window is then still inside the canvas, so still out of every
    /// capture, and the shield must not be hung under that layer: it would be
    /// under itself.
    func testAWindowStillInsideTheCanvasIsNotTouched() throws {
        let window = redWindow()
        ScreenshotShield.apply(true, to: window)
        let canvas = try XCTUnwrap(window.layer.superlayer)
        let between = CALayer()
        canvas.addSublayer(between)
        between.addSublayer(window.layer)

        ScreenshotShield.follow(window)

        XCTAssertTrue(window.layer.superlayer === between, "the window is where the system put it")
        XCTAssertTrue(between.superlayer === canvas, "and that is still inside the canvas")
        XCTAssertTrue(canvas.superlayer?.delegate is UITextField)
        XCTAssertFalse(between.sublayers?.contains { $0.delegate is UITextField } ?? false,
                       "the field was not hung under its own canvas")
        XCTAssertGreaterThan(redness(of: window), 200)
    }

    /// The installer's probe is what notices: it is laid out again when the window
    /// changes size, which a move from one display to the other does.
    func testTheProbeFollowsTheWindowWhenItIsLaidOutAgain() throws {
        let window = redWindow()
        let probe = ShieldProbeView(frame: window.bounds)
        probe.isHidden = true                       // as `ShieldInstaller` makes it
        window.addSubview(probe)                    // in a window: the shield goes on
        XCTAssertTrue(isShielded(window))
        _ = try moveAsTheSystemMight(window)

        window.frame = CGRect(x: 0, y: 0, width: 180, height: 140)
        probe.frame = window.bounds
        window.layoutIfNeeded()

        XCTAssertTrue(isShielded(window), "the probe did not put the shield back")
        XCTAssertTrue(ScreenshotShield.isOn)
    }

    // MARK: a window that goes away (the shield has to come off it first)

    /// What crashes if a shield field has outlived its window: a text view
    /// somewhere finishes a scroll and tells every text interaction so.
    private func aTextViewScrollsSomewhere() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 200))
        let text = UITextView(frame: CGRect(x: 0, y: 0, width: 320, height: 60))
        window.addSubview(text)
        window.makeKeyAndVisible()
        text.becomeFirstResponder()
        text.text = Array(repeating: "line", count: 40).joined(separator: "\n")
        text.setContentOffset(CGPoint(x: 0, y: 200), animated: true)
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        text.setContentOffset(.zero, animated: true)
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        text.resignFirstResponder()
        window.isHidden = true
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
    }

    func testAWindowCanGoOnceTheShieldIsOffItAndTheNextOneIsShielded() {
        autoreleasepool {
            let first = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
            first.makeKeyAndVisible()
            ScreenshotShield.apply(true, to: first)
            ScreenshotShield.set(false); ScreenshotShield.set(true)
            XCTAssertTrue(isShielded(first))
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))

            ScreenshotShield.remove(from: first)

            XCTAssertFalse(isShielded(first), "the window's layer is back where the system keeps it")
            XCTAssertFalse(first.subviews.contains { $0 is UITextField }, "and the field has left the window")
            XCTAssertFalse(ScreenshotShield.isOn)
            first.isHidden = true
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))     // the first window is gone

        let next = redWindow()
        ScreenshotShield.apply(true, to: next)
        XCTAssertTrue(isShielded(next))
        XCTAssertTrue(ScreenshotShield.isOn)
        XCTAssertGreaterThan(redness(of: next), 200)
        aTextViewScrollsSomewhere()     // nothing of the first window's shield is left to trip over
    }

    /// A screen presented full screen can take the app's root view, and the probe
    /// with it, out of the window for as long as it is up. The shield is on the
    /// window and stays there.
    func testTheShieldStaysOnAWindowItsProbeLeaves() {
        let window = redWindow()
        let probe = ShieldProbeView(frame: window.bounds)
        window.addSubview(probe)
        XCTAssertTrue(isShielded(window))
        let canvas = window.layer.superlayer

        probe.removeFromSuperview()

        XCTAssertTrue(isShielded(window))
        XCTAssertTrue(ScreenshotShield.isOn)
        window.addSubview(probe)
        XCTAssertTrue(window.layer.superlayer === canvas, "and nothing is rebuilt when the probe comes back")
    }

    func testTheShieldComesOffWhenTheSceneOfItsWindowIsDisconnected() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
                                  "the test host has no window scene")
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 100, height: 100)
        window.makeKeyAndVisible()
        windows.append(window)
        let probe = ShieldProbeView(frame: window.bounds)
        window.addSubview(probe)
        XCTAssertTrue(isShielded(window))

        // not posted: the app itself listens for the real thing
        probe.sceneWentAway(Notification(name: UIScene.didDisconnectNotification, object: NSObject()))
        XCTAssertTrue(isShielded(window), "another scene going away is none of this window's business")
        probe.sceneWentAway(Notification(name: UIScene.didDisconnectNotification, object: scene))
        XCTAssertFalse(isShielded(window))
    }

    func testTheShieldMovesFromOneWindowToTheNext() {
        let first = redWindow(), second = redWindow()
        ScreenshotShield.apply(true, to: first)
        ScreenshotShield.apply(true, to: second)
        XCTAssertFalse(isShielded(first), "one window at a time: it came off the first")
        XCTAssertFalse(first.subviews.contains { $0 is UITextField })
        XCTAssertTrue(isShielded(second))
        XCTAssertGreaterThan(redness(of: first), 200, "which still draws")
        XCTAssertGreaterThan(redness(of: second), 200)
    }

    func testSettingDefaultsOnAndDecodesSafely() throws {
        XCTAssertTrue(AppSettings().screenshotBlockOn, "secure default: ON")
        // old persisted blob without the key must decode with the default
        let old = try JSONDecoder().decode(AppSettings.self,
            from: Data(#"{"theme":"ember"}"#.utf8))
        XCTAssertTrue(old.screenshotBlockOn)
        let off = try JSONDecoder().decode(AppSettings.self,
            from: Data(#"{"blockScreenshots":false}"#.utf8))
        XCTAssertFalse(off.screenshotBlockOn)
    }
}
