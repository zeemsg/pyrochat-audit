import SwiftUI

/// The voice a note will go out in, shown above the composer while one is being recorded:
///
///     voice [OFF] [DEEP] [HIGH] [ROBOT]
///
/// The one in use is drawn in the foreground colour, the others dim. A tap chooses, and the
/// choice is kept (docs/VOICE-CHANGER.md): whoever relies on a changed voice must not have to
/// remember it for every note.
struct VoicePicker: View {
    @ObservedObject var service: ChatService

    private var settings: AppSettings { service.store.blob.settings }

    var body: some View {
        let chosen = settings.voice
        HStack(spacing: 8) {
            Text("voice").font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
            ForEach(VoiceEffect.allCases, id: \.self) { voice in
                Button {
                    service.updateSettings { $0.voiceChanger = voice == .off ? nil : voice.rawValue }
                } label: {
                    Text("[\(voice.rawValue.uppercased())]")
                        .font(Theme.mono(11, weight: voice == chosen ? .bold : .regular))
                        .foregroundStyle(voice == chosen ? Theme.fg(settings) : Theme.fgDim(settings))
                        .fixedSize()
                }
                .accessibilityLabel(voice == .off ? "Your own voice" : "\(voice.rawValue) voice")
                .accessibilityAddTraits(voice == chosen ? .isSelected : [])
            }
            Spacer()
        }
        .padding(.horizontal, 10).padding(.top, 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Voice changer")
    }
}

/// What the composer says between `[SEND]` and the note's row, while the note is being changed
/// into its voice. A short note is changed before this is seen; a long one takes a moment.
struct VoiceBeingChanged: View {
    let settings: AppSettings

    var body: some View {
        HStack {
            Text("changing voice\u{2026}").font(Theme.mono(11)).foregroundStyle(Theme.fgDim(settings))
                .accessibilityLabel("Changing the voice of your note")
            Spacer()
        }
        .padding(.horizontal, 10).padding(.top, 8)
    }
}
