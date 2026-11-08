import SwiftUI
import UIKit

/// Screenshot protection: iOS never lets an app alter a captured screenshot,
/// but content hosted inside a secure text field's canvas layer is EXCLUDED
/// from screenshots, screen recordings, AirPlay mirroring, and the app
/// switcher snapshot — captures show black where the app was. This reparents
/// the whole window's layer into such a canvas, so everything (sheets and
/// keyboards' text included) is covered at once.
///
/// Toggling OFF just flips `isSecureTextEntry` back — the layer tree stays
/// put, which avoids the fragile un-reparenting dance.
///
/// The field must not outlive its window. It is added to the window as a
/// subview and then its layer is taken out from under the window's, so UIKit
/// never tells it when the window goes; what the field's text interactions
/// remember of that window is then left dangling, and UIKit crashes on it: in
/// the field's dealloc, or the next time a text view anywhere finishes a scroll
/// and tells every text interaction so. Nothing repairs such a field afterwards.
/// So the shield is taken off (`remove`) while the window is still there: when
/// its scene is disconnected, and when another window takes the shield. Not
/// when the probe leaves the window: a screen presented full screen can take
/// the app's root view out of the window, and the shield has to hold then.
enum ScreenshotShield {
    private static var field: UITextField?
    private static weak var canvas: CALayer?
    private static weak var shielded: UIWindow?
    /// Fields whose window went with the shield still on it, which nothing here
    /// should let happen. Held, because letting go of one crashes at once.
    private static var lost: [UITextField] = []

    /// Flip the already-installed shield (from the settings toggle).
    @MainActor
    static func set(_ on: Bool) { field?.isSecureTextEntry = on }

    /// Whether captures come out black right now.
    @MainActor
    static var isOn: Bool { field?.isSecureTextEntry ?? false }

    @MainActor
    static func apply(_ on: Bool, to window: UIWindow?) {
        guard let window else { return }
        if let field, shielded === window {
            field.isSecureTextEntry = on
            follow(window)
            return
        }
        guard on else { return }   // never installed and not wanted: nothing to do
        // one window at a time: off the one it was on, then onto this one
        if let before = shielded {
            remove(from: before)
        } else if let field {
            lost.append(field)
            Self.field = nil
        }
        install(on: window)
    }

    /// Keeps an installed shield around its window. A phone that folds hands
    /// the app from one display to the other while it runs; if the system
    /// gives the window's layer a new place in the tree on the way, it is no
    /// longer under the canvas and captures would show the app again. On or
    /// off is not touched.
    @MainActor
    static func follow(_ window: UIWindow) {
        guard let field, let canvas, shielded === window else { return }
        guard let host = window.layer.superlayer else { return }   // on no display right now
        // anywhere under the canvas is out of every capture, with or without a
        // layer of the system's in between; and hanging the field under something
        // that is itself under the field would tie the tree in a knot
        var above: CALayer? = host
        while let layer = above {
            if layer === canvas || layer === field.layer { return }
            above = layer.superlayer
        }
        // the same field and the same canvas, hung where the window now is: nothing
        // is built or torn down, so there is nothing new to go wrong
        host.addSublayer(field.layer)
        canvas.addSublayer(window.layer)
    }

    /// Takes the shield off a window, for a window that is going away. The
    /// layers go back the way UIKit keeps them and the field leaves the window
    /// as any subview does, so UIKit clears what the field knew of it and the
    /// field can be let go of.
    @MainActor
    static func remove(from window: UIWindow) {
        guard let field, shielded === window else { return }
        if let host = field.layer.superlayer, host !== window.layer {
            host.addSublayer(window.layer)          // the window's layer, back where the system keeps it
            window.layer.addSublayer(field.layer)   // and the field's, back inside its superview's
        }
        field.removeFromSuperview()
        Self.field = nil
        canvas = nil
        shielded = nil
    }

    /// A scene is gone, and its windows go with it: the shield comes off the
    /// window first if it was that scene's, or is already in none.
    @MainActor
    static func sceneWentAway(_ scene: UIScene) {
        guard let window = shielded, window.windowScene == nil || window.windowScene === scene else { return }
        remove(from: window)
    }

    @MainActor
    private static func install(on window: UIWindow) {
        let f = UITextField()
        f.isSecureTextEntry = true
        f.isUserInteractionEnabled = false
        // the secure canvas is the field's private subview; reparent the
        // window's layer beneath it. Defensive: if the hierarchy ever
        // changes shape in an iOS update, fail open (app keeps working,
        // just without the shield).
        guard let canvas = f.subviews.first else { return }
        f.frame = window.bounds
        canvas.frame = window.bounds
        window.addSubview(f)
        window.layer.superlayer?.addSublayer(f.layer)
        f.layer.sublayers?.last?.addSublayer(window.layer)
        field = f
        Self.canvas = window.layer.superlayer
        shielded = window
    }
}

/// Grabs the hosting UIWindow from inside the SwiftUI tree and keeps the
/// shield in sync with the setting.
struct ShieldInstaller: UIViewRepresentable {
    let enabled: Bool

    func makeUIView(context: Context) -> ShieldProbeView {
        let v = ShieldProbeView()
        v.isHidden = true
        v.wanted = enabled
        return v
    }

    func updateUIView(_ v: ShieldProbeView, context: Context) {
        v.wanted = enabled
        v.sync()
    }
}

final class ShieldProbeView: UIView {
    var wanted = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        watch()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        watch()
    }

    private func watch() {
        // the window resized, or on another display (a fold): the shield goes with it
        registerForTraitChanges([UITraitHorizontalSizeClass.self, UITraitVerticalSizeClass.self,
                                 UITraitDisplayScale.self]) { (probe: ShieldProbeView, _: UITraitCollection) in
            probe.follow()
        }
        NotificationCenter.default.addObserver(self, selector: #selector(follow),
                                               name: UIScene.didActivateNotification, object: nil)
        // the window is about to go with its scene: the shield comes off it first
        NotificationCenter.default.addObserver(self, selector: #selector(sceneWentAway(_:)),
                                               name: UIScene.didDisconnectNotification, object: nil)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        sync()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        follow()
    }

    func sync() {
        guard let window else { return }
        ScreenshotShield.apply(wanted, to: window)
    }

    @objc private func follow() {
        guard let window else { return }
        ScreenshotShield.follow(window)
    }

    @objc func sceneWentAway(_ note: Notification) {
        guard let scene = note.object as? UIScene else { return }
        ScreenshotShield.sceneWentAway(scene)
    }
}
