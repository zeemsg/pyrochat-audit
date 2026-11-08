import XCTest
import Combine
import UIKit
@testable import PyroChat

final class ThemeTests: XCTestCase {
    func testTenNamedThemesDefaultingToEmber() {
        XCTAssertEqual(Theme.themes.count, 10)
        XCTAssertEqual(Set(Theme.themeNames).count, 10, "names are unique")
        XCTAssertEqual(Theme.themeNames.first, "ember")
        XCTAssertTrue(Theme.themeNames.contains("amber"), "the original two survive")
        // an unknown theme (newer build, hand-edited store) renders as ember, never crashes
        var s = AppSettings(); s.theme = "hologram"
        XCTAssertEqual(Theme.fg(s), Theme.fg(themeNamed: "ember"))
        s.theme = "paper"
        XCTAssertNotEqual(Theme.fg(s), Theme.fg(themeNamed: "ember"))
    }
}

/// docs/TEXT-SIZE.md §4.
final class TextSizeTests: XCTestCase {

    func testEveryNamedSizeMapsToItsMultiplier() {
        XCTAssertEqual(Theme.textSizeNames, ["small", "normal", "large", "larger", "largest"])
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "small"), 0.85, accuracy: 0.0001)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "normal"), 1.00, accuracy: 0.0001)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "large"), 1.15, accuracy: 0.0001)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "larger"), 1.30, accuracy: 0.0001)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "largest"), 1.50, accuracy: 0.0001)
        XCTAssertEqual(Theme.defaultTextSize, "normal")
    }

    /// An unknown name and a missing value both render as `normal` — the rule
    /// `theme` already uses, so an old store and a newer build never disagree.
    func testUnknownAndMissingBothRenderAsNormal() {
        let normal = Theme.multiplier(textSizeNamed: "normal")
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "gigantic"), normal)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: "Largest"), normal, "exact spelling only")
        XCTAssertEqual(Theme.multiplier(textSizeNamed: ""), normal)
        XCTAssertEqual(Theme.multiplier(textSizeNamed: nil), normal)

        var s = AppSettings()
        XCTAssertNil(s.textSize, "never changed = nothing stored")
        XCTAssertEqual(Theme.multiplier(s), normal)
        s.textSize = "hologram"
        XCTAssertEqual(Theme.multiplier(s), normal)
        s.textSize = "largest"
        XCTAssertEqual(Theme.multiplier(s), 1.50, accuracy: 0.0001)
    }

    /// §2: the app multiplier rides ON TOP of the phone's own accessibility
    /// scaling, and the combined result is clamped to twice the designed size.
    func testClampHoldsAtALargeAccessibilitySettingPlusLargest() {
        let huge = UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge)
        let largest = Theme.multiplier(textSizeNamed: "largest")
        for designed in [CGFloat(10), 11, 12, 13, 14, 16, 18] {
            let system = UIFontMetrics(forTextStyle: .body)
                .scaledValue(for: designed, compatibleWith: huge)
            XCTAssertGreaterThan(system, designed, "the simulated setting really is large")
            let combined = Theme.scaledSize(designed, scale: largest, traits: huge)
            XCTAssertLessThanOrEqual(combined, designed * 2 + 0.0001,
                                     "never more than twice the designed size")
            XCTAssertEqual(combined, min(system * largest, designed * 2), accuracy: 0.0001)
            // and at the default content size it is exactly the multiplier
            let plain = UITraitCollection(preferredContentSizeCategory: .large)
            XCTAssertEqual(Theme.scaledSize(designed, scale: largest, traits: plain),
                           min(designed * largest, designed * 2), accuracy: 0.0001)
        }
    }

    /// §3: the fixed-width art (boot screen, safety number) scales but stops
    /// at `large`, whatever the setting says.
    func testFixedWidthArtStopsAtLarge() {
        let ceiling = Theme.artScaleCeiling
        XCTAssertEqual(ceiling, Theme.multiplier(textSizeNamed: "large"), accuracy: 0.0001)
        for name in Theme.textSizeNames {
            let art = min(Theme.multiplier(textSizeNamed: name), ceiling)
            XCTAssertLessThanOrEqual(art, ceiling)
        }
        XCTAssertEqual(min(Theme.multiplier(textSizeNamed: "small"), ceiling), 0.85,
                       accuracy: 0.0001, "the cap is a ceiling, not a floor")
    }

    /// Store round trip: omitted until changed, then survives save and load.
    func testStoreRoundTripOmitsTheFieldUntilItIsChanged() throws {
        let encoder = JSONEncoder()
        let bare = try encoder.encode(AppSettings())
        XCTAssertFalse(String(decoding: bare, as: UTF8.self).contains("textSize"),
                       "an unset textSize is not written at all")

        var s = AppSettings()
        s.textSize = "larger"
        let json = String(decoding: try encoder.encode(s), as: UTF8.self)
        XCTAssertTrue(json.contains("\"textSize\":\"larger\""), "exact key and spelling")
        let back = try JSONDecoder().decode(AppSettings.self, from: try encoder.encode(s))
        XCTAssertEqual(back.textSize, "larger")

        // an old store (no key at all) decodes to nil, i.e. `normal`
        let old = Data(#"{"theme":"amber","scanlines":false}"#.utf8)
        let decoded = try JSONDecoder().decode(AppSettings.self, from: old)
        XCTAssertNil(decoded.textSize)
        XCTAssertEqual(Theme.multiplier(decoded), 1.0, accuracy: 0.0001)

        // a value this build has never heard of decodes, and renders as normal
        let future = Data(#"{"theme":"ember","textSize":"colossal"}"#.utf8)
        let f = try JSONDecoder().decode(AppSettings.self, from: future)
        XCTAssertEqual(f.textSize, "colossal", "kept verbatim — never rewritten")
        XCTAssertEqual(Theme.multiplier(f), 1.0, accuracy: 0.0001)
    }
}

@MainActor
final class TextSizeLiveTests: XCTestCase {
    /// §2: changing the setting must redraw immediately. `Theme.mono` reads
    /// `Theme.textScale`, and `updateSettings` refreshes it before the publish
    /// that re-renders every screen — so the new size is already in place.
    func testChangingTheSettingUpdatesTheMultiplierThePublishWillRedrawWith() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-textsize-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("store")
        let store = SecureStore(creatingWithPassword: "text-size-pass-123", at: url)
        let service = ChatService(store: store)
        defer { service.shutdown(); Theme.applyTextSize(AppSettings()) }

        XCTAssertEqual(Theme.textScale, 1.0, accuracy: 0.0001, "a fresh store is normal")

        var published = 0
        let sub = service.objectWillChange.sink { _ in published += 1 }
        defer { sub.cancel() }

        service.updateSettings { $0.textSize = "largest" }
        XCTAssertEqual(published, 1, "one synchronous publish per settings change")
        XCTAssertEqual(Theme.textScale, 1.5, accuracy: 0.0001,
                       "the multiplier is live before the redraw")
        XCTAssertEqual(service.store.blob.settings.textSize, "largest")

        service.updateSettings { $0.textSize = "small" }
        XCTAssertEqual(Theme.textScale, 0.85, accuracy: 0.0001)

        // reopening the store hands the stored value straight back to Theme
        store.flush()
        Theme.applyTextSize(AppSettings())
        XCTAssertEqual(Theme.textScale, 1.0, accuracy: 0.0001)
        let reopened = try SecureStore(unlockingWithPassword: "text-size-pass-123", at: url)
        let service2 = ChatService(store: reopened)
        defer { service2.shutdown() }
        XCTAssertEqual(reopened.blob.settings.textSize, "small")
        XCTAssertEqual(Theme.textScale, 0.85, accuracy: 0.0001,
                       "opening a store applies its text size")
    }
}

@MainActor
final class SettingsPublishTests: XCTestCase {
    /// Changing a setting from the UI must republish the service in the same call:
    /// `SecureStore` is not observable, so a bare `store.update` left the theme
    /// picker (and every other screen) on the old colour until some unrelated
    /// event happened to re-render. Android was instant; iOS was not.
    func testUpdateSettingsPublishesImmediately() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-theme-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = SecureStore(creatingWithPassword: "theme-pass-123",
                                at: dir.appendingPathComponent("store"))
        let service = ChatService(store: store)
        defer { service.shutdown() }

        var published = 0
        let sub = service.objectWillChange.sink { _ in published += 1 }
        defer { sub.cancel() }

        service.updateSettings { $0.theme = "plasma" }
        XCTAssertEqual(published, 1, "one synchronous publish per settings change")
        XCTAssertEqual(service.store.blob.settings.theme, "plasma")
        XCTAssertEqual(Theme.fg(service.store.blob.settings), Theme.fg(themeNamed: "plasma"))

        service.updateSettings { $0.scanlines = false }
        XCTAssertEqual(published, 2)
        XCTAssertFalse(service.store.blob.settings.scanlines)
    }
}
