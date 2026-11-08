import SwiftUI

/// docs/MESSAGE-DETAILS.md — the `details` sheet. Terminal styling, the same
/// dividers as the rest of the app, and no logic of its own: every word on
/// screen comes from `MessageDetails.lines`, which is pure and tested without
/// any UI. The only thing this file decides is what a line LOOKS like.
struct MessageDetailsView: View {
    @ObservedObject var service: ChatService
    /// The row the long-press opened. Re-read from the log so a receipt that
    /// lands while the sheet is up shows immediately.
    let message: ChatMessage
    /// The thread key: a contact hash for a 1:1 row, a group id for a group.
    let thread: String
    /// Display name of the 1:1 peer; ignored for a group row.
    var peerName: String = "peer"

    @Environment(\.dismiss) private var dismiss

    private var settings: AppSettings { service.store.blob.settings }
    private var group: Group? { service.group(id: thread) }

    /// The live row if it is still there (receipts move under the sheet), else
    /// the one we were opened with.
    private var live: ChatMessage {
        service.messages[thread]?.first { $0.id == message.id } ?? message
    }

    private var input: MessageDetails.Input {
        MessageDetails.Input(
            message: live,
            frame: service.frameCapture.frame(for: message.id),
            group: group,
            peerName: peerName,
            peerHash: group == nil ? thread : "",
            myHash: service.myHash,
            showRead: settings.readReceiptsOn)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                ForEach(Array(MessageDetails.lines(input).enumerated()), id: \.offset) { _, line in
                    row(line)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .terminalScreen(settings)
        .presentationDetents([.large])
    }

    private var header: some View {
        HStack {
            Text("\u{2500}\u{2500} details \u{2500}\u{2500}")
                .font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
            Spacer(minLength: 8)
            Button { dismiss() } label: {
                Text("[X]").font(Theme.mono(13, weight: .bold))
                    .foregroundStyle(Theme.fgDim(settings))
            }
            .accessibilityLabel("Close")
        }
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func row(_ line: MessageDetails.Line) -> some View {
        switch line.kind {
        case .heading:
            Text(line.value)
                .font(Theme.mono(13))
                .foregroundStyle(Theme.fgDim(settings))
                .padding(.top, 14).padding(.bottom, 4)
                .fixedSize(horizontal: false, vertical: true)
        case .row:
            labelled(line.label, line.value, colour: Theme.fg(settings),
                     labelColour: Theme.fgDim(settings))
        case .memberRow:
            let c = line.colour.map { Color(red: $0.r, green: $0.g, blue: $0.b) }
                ?? Theme.fgDim(settings)
            labelled(line.label, line.value, colour: c, labelColour: c)
        case .prose:
            Text(line.value)
                .font(Theme.mono(11))
                .foregroundStyle(Theme.fgDim(settings))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 3)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .frame:
            Text(line.value)
                .font(Theme.mono(10))
                .foregroundStyle(Theme.fg(settings))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8).padding(.vertical, 1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.panel)
        case .footer:
            Text(line.value)
                .font(Theme.mono(10))
                .foregroundStyle(Theme.fgDim(settings))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// The label column: monospace, wide enough for `written on`, with the
    /// value free to wrap under its own edge.
    private func labelled(_ label: String, _ value: String,
                          colour: Color, labelColour: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(Theme.mono(12))
                .foregroundStyle(labelColour)
                .frame(minWidth: 88, alignment: .leading)
                .fixedSize(horizontal: true, vertical: false)
            Text(value)
                .font(Theme.mono(12, weight: .semibold))
                .foregroundStyle(colour)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
