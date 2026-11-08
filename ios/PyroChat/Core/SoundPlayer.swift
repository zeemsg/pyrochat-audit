import AudioToolbox
import UIKit

/// Terminal blips. System-sound channel: respects the hardware mute switch,
/// never touches the shared AVAudioSession (so it cannot fight voice-note
/// record/playback), fire-and-forget.
@MainActor
enum SoundPlayer {
    enum Tone: String { case tx, rx }

    private static var ids: [Tone: SystemSoundID] = [:]
    /// Coalesce rx blips: an offline-queue drain of 40 messages must not
    /// fire 40 rapid blips.
    private static var lastRx = Date.distantPast

    /// Call once at launch. Missing assets fail silent.
    static func prepare() {
        for tone in [Tone.tx, .rx] where ids[tone] == nil {
            guard let url = Bundle.main.url(forResource: tone.rawValue,
                                            withExtension: "wav") else { continue }
            var sid: SystemSoundID = 0
            if AudioServicesCreateSystemSoundID(url as CFURL, &sid) == kAudioServicesNoError {
                ids[tone] = sid
            }
        }
    }

    static func play(_ tone: Tone, settings: AppSettings) {
        guard settings.soundsOn,
              UIApplication.shared.applicationState == .active,   // foreground only
              !VoiceRecorder.isCapturing,                          // never blip into a recording
              let sid = ids[tone] else { return }
        if tone == .rx {
            guard Date().timeIntervalSince(lastRx) > 0.3 else { return }
            lastRx = Date()
        }
        AudioServicesPlaySystemSound(sid)
    }
}
