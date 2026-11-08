import UIKit

/// Terminal-appropriate haptics: subtle tick on send, warning on destructive.
@MainActor
enum Haptics {
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
    static func warn() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}
