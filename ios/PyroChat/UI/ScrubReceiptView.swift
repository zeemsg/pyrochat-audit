import SwiftUI

/// docs/SCRUB-AND-WIPE.md §B3 — "Tapping it opens the same list."
///
/// The same list, literally: the heading, the rows and the footnote all come
/// from `MessageDetails`, so the sheet a sender sees before the file goes and
/// the section the details sheet shows afterwards can never drift apart.
struct ScrubReceiptView: View {
    let settings: AppSettings
    let filename: String
    let removed: [ScrubField]
    var supported: Bool = true

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(filename)
                        .font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                        .lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 8)
                    Button { dismiss() } label: {
                        Text("[X]").font(Theme.mono(13, weight: .bold))
                            .foregroundStyle(Theme.fgDim(settings))
                    }
                    .accessibilityLabel("Close")
                }
                .padding(.bottom, 6)

                Text(MessageDetails.headingWhatWasRemoved)
                    .font(Theme.mono(13)).foregroundStyle(Theme.fgDim(settings))
                    .padding(.top, 8).padding(.bottom, 4)
                    .fixedSize(horizontal: false, vertical: true)

                if removed.isEmpty {
                    Text(MessageDetails.nothingRemovable)
                        .font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, 3)
                } else {
                    ForEach(Array(removed.enumerated()), id: \.offset) { _, field in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(field.label)
                                .font(Theme.mono(12))
                                .foregroundStyle(Theme.fgDim(settings))
                                .frame(minWidth: 88, alignment: .leading)
                                .fixedSize(horizontal: true, vertical: false)
                            Text(field.value)
                                .font(Theme.mono(12, weight: .semibold))
                                .foregroundStyle(Theme.fg(settings))
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                Text(MessageDetails.scrubFootnote)
                    .font(Theme.mono(10)).foregroundStyle(Theme.fgDim(settings))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 18)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .terminalScreen(settings)
        .presentationDetents([.medium, .large])
    }
}
