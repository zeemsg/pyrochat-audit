import SwiftUI
import UIKit

/// Runs `report` once each time a recording or a mirroring of this view's scene
/// starts (`ChatView` tells the peer), and once on appearance when one is already
/// running.
///
/// Two signals, because each alone has let a recording pass:
/// - the scene's own capture state (`isSceneCaptured`), the right one on a phone
///   with two displays, where `UIScreen.main` is not always the screen the app is
///   on. Build 40 relied on it alone, and on a real iPhone it did not change when
///   a recording was started from Control Center (2026-11-08);
/// - `UIScreen.capturedDidChangeNotification`, read from the screen the
///   notification names, never from `UIScreen.main`. Builds up to 39 used it and
///   it did fire on real phones.
/// A recording reported through one signal is not reported again through the
/// other: `reported` stays set until either signal says the capture has ended.
private struct SceneCaptureAlert: ViewModifier {
    @Environment(\.isSceneCaptured) private var captured
    let report: () -> Void
    @State private var reported = false

    func body(content: Content) -> some View {
        content
            .onAppear { if captured { fire() } }
            .onChange(of: captured) { _, now in
                if now { fire() } else { ended() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { note in
                // the notification's object is the screen whose capture state changed
                guard let screen = note.object as? UIScreen else { return }
                if screen.isCaptured { fire() } else { ended() }
            }
    }

    private func fire() {
        guard !reported else { return }
        reported = true
        report()
    }

    private func ended() { reported = false }
}

extension View {
    func onSceneCaptureStart(_ report: @escaping () -> Void) -> some View {
        modifier(SceneCaptureAlert(report: report))
    }
}
