import SwiftUI

/// Boot sequence: ASCII candle + fake POST lines, then hands off.
struct BootView: View {
    let settings: AppSettings
    let done: () -> Void

    @State private var lines: [String] = []
    @State private var showCandle = false

    // docs/TEXT-SIZE.md §3: the candle is fixed-width art and the POST lines
    // are a fixed-width column of dot leaders — both are drawn with
    // `Theme.monoCapped`, which scales but stops at `large` so nothing
    // overflows the narrowest phone.
    private static let candle = #"""
        )
       (
      .-.
      | |
      | |
    __|_|__
    '-----'
    """#

    private static let boot: [String] = [
        "PYROCHAT BIOS v0.1.0",
        "init secure enclave .......... OK",
        "mount encrypted store ........ OK",
        "load signal protocol ......... OK",
        "  x3dh + double ratchet",
        "  pqxdh kyber1024",
        "zero plaintext policy ........ ENFORCED",
        "relay trust level ............ NONE (by design)",
        "",
        "> welcome to the dark",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            if showCandle {
                Text(Self.candle)
                    .font(Theme.monoCapped(13))
                    .foregroundStyle(Theme.fg(settings))
            }
            ForEach(lines.indices, id: \.self) { i in
                Text(lines[i]).font(Theme.monoCapped(13))
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .terminalScreen(settings)
        .task {
            showCandle = true
            for line in Self.boot {
                try? await Task.sleep(for: .milliseconds(110))
                lines.append(line)
            }
            try? await Task.sleep(for: .milliseconds(450))
            done()
        }
    }
}
