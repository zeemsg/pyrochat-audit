import SwiftUI

/// The `<pyrochat>` channel: operator notices rendered as a terminal log,
/// the same look as a chat. Read-only by construction — the relay speaks,
/// nobody answers — so there is no composer and no peer; the strip where
/// the prompt would sit says so.
struct NoticesView: View {
    @ObservedObject var service: ChatService
    @Environment(\.openURL) private var openURL

    private var settings: AppSettings { service.store.blob.settings }

    var body: some View {
        VStack(spacing: 0) {
            log
            footer
        }
        .terminalScreen(settings)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("<pyrochat>")
                    .font(Theme.monoCapped(14, weight: .semibold))
                    .foregroundStyle(Theme.flame)
            }
        }
        .onAppear { service.markNoticesSeen() }
        // one that arrives while the channel is open is seen as it renders
        .onChange(of: service.notices) { _, _ in service.markNoticesSeen() }
    }

    private var log: some View {
        GeometryReader { outer in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    Text("── system notices ──")
                        .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    ForEach(service.notices) { notice in
                        row(notice)
                    }
                }
                .padding(.horizontal, 12)
                // terminal semantics, same as a chat: a short log starts at
                // the top; once it overflows, it pins to the bottom
                .frame(minHeight: outer.size.height, alignment: .top)
            }
            .defaultScrollAnchor(.bottom)
        }
    }

    /// `[HH:mm:ss] <pyrochat> text`, with the link (if any) on a dim second
    /// line under the text.
    private func row(_ n: Notice) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("[\(Self.time.string(from: n.ts))]")
                .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                .fixedSize()
            VStack(alignment: .leading, spacing: 2) {
                (Text("<pyrochat> ").fontWeight(.semibold).foregroundColor(Theme.flame)
                 + Text(n.text))
                    .font(Theme.mono(13))
                    .textSelection(.enabled)
                if let url = n.url {
                    Button {
                        if let u = URL(string: url) { openURL(u) }
                    } label: {
                        Text(url)
                            .font(Theme.mono(11))
                            .foregroundStyle(Theme.fgDim(settings))
                            .underline()
                            .multilineTextAlignment(.leading)
                    }
                    .accessibilityLabel("Open link")
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var footer: some View {
        Text("notices come from the relay. nobody can reply here.")
            .font(Theme.mono(11))
            .foregroundStyle(Theme.fgDim(settings))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Theme.panel)
    }

    private static let time: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; return f
    }()
}
