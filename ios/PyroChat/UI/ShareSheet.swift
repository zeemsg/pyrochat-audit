import SwiftUI
import UIKit

/// The system share sheet (`UIActivityViewController`) presented over
/// whatever is on screen — used by the home toolbar's `[share]`. Presented
/// from UIKit rather than `ShareLink` so the DEBUG screenshot demo can open
/// it without a tap (`--demo-share`).
enum ShareSheet {
    @MainActor
    static func present(text: String) {
        guard let host = topViewController() else { return }
        let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        // phone-only app, but a popover anchor keeps it safe on any idiom
        if let pop = vc.popoverPresentationController {
            pop.sourceView = host.view
            pop.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.safeAreaInsets.top,
                                    width: 1, height: 1)
        }
        host.present(vc, animated: true)
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        let window = windows.first { $0.isKeyWindow } ?? windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
