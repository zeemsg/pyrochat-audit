import UIKit
import UniformTypeIdentifiers

/// Message text goes to the system pasteboard only on the user's explicit
/// `copy`, and then as little as possible: this device only (no Universal
/// Clipboard / Handoff to a Mac that has none of PyroChat's protections) and
/// gone after a minute, instead of sitting there for every later app to read.
enum Pasteboard {
    static let lifetime: TimeInterval = 60

    static func copySensitive(_ text: String, now: Date = Date()) {
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: text]],
            options: [.localOnly: true, .expirationDate: now.addingTimeInterval(lifetime)])
    }
}
