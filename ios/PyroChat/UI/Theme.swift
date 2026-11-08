import SwiftUI
import UIKit

/// Terminal aesthetic: monospace everything, phosphor green (or amber) on
/// near-black, optional scanlines.
enum Theme {
    static let bg = Color(red: 0.02, green: 0.03, blue: 0.02)
    static let panel = Color(red: 0.045, green: 0.06, blue: 0.045)

    /// Phosphor themes: a name and one foreground colour; every dim/faint tone is
    /// derived from it. Same ten names and hex values on Android. Unknown names
    /// (a newer build's theme, a hand-edited store) fall back to ember, so two
    /// builds never disagree on how to render a store.
    static let themes: [(name: String, hex: UInt32)] = [
        ("ember",       0x40FF73),   // the classic phosphor green
        ("amber",       0xFFB333),   // 1970s amber monitor
        ("ice",         0x7EE8FA),   // cold cyan
        ("plasma",      0xFF79C6),   // magenta
        ("ultraviolet", 0xBD93F9),   // violet
        ("solar",       0xF1FA8C),   // pale yellow
        ("mint",        0xA8FFC8),   // soft green-white
        ("cobalt",      0x5FA8FF),   // blue
        ("paper",       0xE6E6E6),   // white-on-black teletype
        ("ghost",       0x9AA5B1),   // dim grey-blue
    ]
    static var themeNames: [String] { themes.map(\.name) }
    static let defaultTheme = "ember"

    // MARK: text size (docs/TEXT-SIZE.md)

    /// The five named text sizes and what each multiplies the designed point
    /// size by. Same five names and multipliers on Android; stored as
    /// `settings.textSize`. Unknown names (a newer build, a hand-edited store)
    /// fall back to `normal` — the rule `theme` already uses for colours.
    static let textSizes: [(name: String, scale: CGFloat)] = [
        ("small",   0.85),
        ("normal",  1.00),
        ("large",   1.15),
        ("larger",  1.30),
        ("largest", 1.50),
    ]
    static var textSizeNames: [String] { textSizes.map(\.name) }
    static let defaultTextSize = "normal"

    /// §2: the app multiplier NEVER takes the final size past twice the
    /// designed one, however large the phone's own accessibility setting is.
    static let maxTotalScale: CGFloat = 2.0

    /// §3: the ceiling for fixed-width art (the boot candle, the safety
    /// number) — it scales, but stops at `large`.
    static let artScaleCeiling = multiplier(textSizeNamed: "large")

    /// Stored string → multiplier. An unknown or missing value is `normal`,
    /// so an old store and a newer build can never disagree.
    static func multiplier(textSizeNamed name: String?) -> CGFloat {
        guard let name, let found = textSizes.first(where: { $0.name == name })
        else { return 1.0 }
        return found.scale
    }

    static func multiplier(_ settings: AppSettings) -> CGFloat {
        multiplier(textSizeNamed: settings.textSize)
    }

    /// The live multiplier every `mono` call reads. A one-value cache of
    /// `settings.textSize`, refreshed by `ChatService` when a store is opened
    /// and on every settings write — both of which republish the service, so
    /// the next redraw reads the new value and the change lands immediately
    /// without threading settings through every call site.
    private(set) static var textScale: CGFloat = 1.0

    /// The ONE writer of `textScale`.
    static func applyTextSize(_ settings: AppSettings) {
        textScale = multiplier(settings)
    }

    /// The point size `mono` actually asks for: the designed size, the
    /// phone's own Dynamic Type scaling, this app's multiplier on top, and the
    /// §2 clamp over the combined result. `traits` is for tests only.
    static func scaledSize(_ size: CGFloat, scale: CGFloat = Theme.textScale,
                           traits: UITraitCollection? = nil) -> CGFloat {
        let system = UIFontMetrics(forTextStyle: .body)
            .scaledValue(for: size, compatibleWith: traits)
        return min(system * scale, size * maxTotalScale)
    }

    static func color(hex: UInt32) -> Color {
        Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    static func fg(themeNamed name: String) -> Color {
        color(hex: (themes.first { $0.name == name } ?? themes[0]).hex)
    }

    static func fg(_ settings: AppSettings) -> Color { fg(themeNamed: settings.theme) }

    static func fgDim(_ settings: AppSettings) -> Color {
        fg(settings).opacity(0.55)
    }

    static let alert = Color(red: 1.0, green: 0.35, blue: 0.25)

    /// Candle-flame orange — sender/receiver identity tags.
    static let flame = Color(red: 1.0, green: 0.69, blue: 0.20)

    /// Monospace, scaled with the user's Dynamic Type setting AND the app's
    /// own `text size` setting (read at call time — picks up changes on the
    /// next screen build). The single choke point: nothing else scales type.
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: scaledSize(size), weight: weight, design: .monospaced)
    }

    /// `mono` for the places that assume a physical width (docs/TEXT-SIZE.md
    /// §3: the boot screen, the safety number, and every top bar's bracket
    /// labels, which UIKit folds into a `More` overflow menu once they no
    /// longer fit). It still scales — just never past `large`, so it cannot
    /// overflow the narrowest phone.
    static func monoCapped(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: scaledSize(size, scale: min(textScale, artScaleCeiling)),
                weight: weight, design: .monospaced)
    }
}

/// CRT scanline overlay.
struct Scanlines: View {
    var body: some View {
        GeometryReader { geo in
            let lines = Int(geo.size.height / 3)
            VStack(spacing: 2) {
                ForEach(0..<max(lines, 1), id: \.self) { _ in
                    Rectangle().fill(Color.black.opacity(0.16)).frame(height: 1)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Blinking block cursor.
struct BlockCursor: View {
    let color: Color
    @State private var on = true
    var body: some View {
        Rectangle()
            .fill(color)
            .frame(width: 9, height: 17)
            .opacity(on ? 1 : 0)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.55).repeatForever()) { on.toggle() }
            }
    }
}

/// `── TITLE ──`, the section rule used by `[cfg]` and the status page.
///
/// It carries its own opaque background, and that is the whole reason it is a
/// view rather than a `Text`. A `.listStyle(.plain)` List PINS its section
/// headers, so the rows scroll UNDER the header rather than past it — and a
/// header drawn as bare text over a transparent row leaves both legible at
/// once, one across the other. It was a corner of a row when the headers read
/// `LINK` and `SAFETY`; docs/STATUS-AND-CFG.md §2 renamed them after what the
/// person is trying to do, so `── WHAT LEAVES THIS PHONE ──` now covers a
/// whole row's width and the collision is unmistakable. Caught in a capture,
/// which is the only place it shows.
struct TerminalSectionHeader: View {
    private let title: String
    private let settings: AppSettings

    init(_ title: String, _ settings: AppSettings) {
        self.title = title
        self.settings = settings
    }

    var body: some View {
        Text("\u{2500}\u{2500} \(title) \u{2500}\u{2500}")
            .font(Theme.mono(11))
            .foregroundStyle(Theme.fgDim(settings))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .background(Theme.bg)
            // edge to edge: the List's own row insets would otherwise leave a
            // transparent margin on each side for the row beneath to show
            // through, which is the same defect in a narrower strip.
            .listRowInsets(EdgeInsets())
    }
}

extension View {
    /// Standard PyroChat screen chrome: black bg, green fg, scanlines.
    func terminalScreen(_ settings: AppSettings) -> some View {
        self
            .foregroundStyle(Theme.fg(settings))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.bg)
            .overlay { if settings.scanlines { Scanlines() } }
            .preferredColorScheme(.dark)
    }
}
