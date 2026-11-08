import Foundation
import AVFoundation

/// Records voice notes as AAC (m4a): small, iOS-native, ≤120 s.
/// The recording only ever exists as a temp file for the seconds between
/// stop and encrypt; it is deleted the moment the ciphertext exists.
@MainActor
final class VoiceRecorder: NSObject, ObservableObject {
    @Published var isRecording = false
    @Published var elapsed: TimeInterval = 0

    static let maxSeconds: TimeInterval = 120

    /// What a voice note is: AAC, 24 kHz, mono, 32 kbps. `VoiceChanger` writes the same, so a
    /// changed note is told from no other.
    nonisolated(unsafe) static let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 24_000,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 32_000,
    ]

    /// True while the mic is live — SoundPlayer suppresses blips so a tx
    /// tick never lands inside a recording. nonisolated(unsafe): plain Bool,
    /// written from MainActor paths + deinit's exclusive teardown only.
    nonisolated(unsafe) static private(set) var isCapturing = false

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var fileURL: URL?

    func begin() async -> Bool {
        let session = AVAudioSession.sharedInstance()
        guard await AVAudioApplication.requestRecordPermission() else { return false }
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
        try? session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("vn-\(UUID().uuidString).m4a")
        guard let rec = try? AVAudioRecorder(url: url, settings: Self.settings) else { return false }
        fileURL = url
        recorder = rec
        rec.record(forDuration: Self.maxSeconds)
        Self.isCapturing = true
        isRecording = true
        elapsed = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let rec = self.recorder else { return }
                self.elapsed = rec.currentTime
                if !rec.isRecording { self.isRecording = false }
            }
        }
        return true
    }

    /// Stop and return the audio bytes (deleting the temp file), or nil.
    func finish() -> (data: Data, duration: TimeInterval)? {
        timer?.invalidate(); timer = nil
        guard let rec = recorder, let url = fileURL else { return nil }
        let duration = rec.currentTime
        rec.stop()
        recorder = nil
        Self.isCapturing = false
        isRecording = false
        // release the .playAndRecord session so post-send blips aren't ducked
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        defer { try? FileManager.default.removeItem(at: url) }
        guard duration >= 0.5, let data = try? Data(contentsOf: url) else { return nil }
        return (data, duration)
    }

    /// Deterministic teardown: a lock/teardown mid-recording must not leave
    /// the mic live, the timer armed, or plaintext audio in tmp.
    deinit {
        timer?.invalidate()
        recorder?.stop()
        Self.isCapturing = false
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
    }

    func cancel() {
        timer?.invalidate(); timer = nil
        recorder?.stop()
        recorder = nil
        Self.isCapturing = false
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
    }
}

/// Plays a decrypted voice note from memory, with scrub support.
@MainActor
final class VoicePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var playingId: String?
    @Published var progress: Double = 0     // 0…1 for the playing note
    private var player: AVAudioPlayer?
    private var ticker: Timer?

    func toggle(id: String, data: Data) {
        if playingId == id {
            stop()
            return
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        guard let p = try? AVAudioPlayer(data: data) else { return }
        player = p
        p.delegate = self
        p.play()
        playingId = id
        progress = 0
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.player, p.duration > 0 else { return }
                self.progress = p.currentTime / p.duration
            }
        }
    }

    /// Scrub the playing note to a fraction of its duration.
    func seek(id: String, to fraction: Double) {
        guard playingId == id, let p = player, p.duration > 0 else { return }
        p.currentTime = max(0, min(fraction, 0.99)) * p.duration
        progress = fraction
    }

    func stop() {
        ticker?.invalidate(); ticker = nil
        player?.stop()
        player = nil
        playingId = nil
        progress = 0
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

/// Real waveform bars from a voice note's AAC bytes. The plaintext touches a
/// tmp file only for the milliseconds AVAudioFile needs to read samples —
/// deleted immediately, and "waveform-" dirs are covered by the launch sweep.
enum Waveform {
    static let barCount = 32
    private static let cache = NSCache<NSString, NSArray>()

    static func bars(for id: String, data: Data) -> [Float] {
        if let hit = cache.object(forKey: id as NSString) as? [Float] { return hit }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("waveform-\(UUID().uuidString)", isDirectory: true)
        let url = dir.appendingPathComponent("v.m4a")
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try data.write(to: url, options: [.completeFileProtection])
            let file = try AVAudioFile(forReading: url)
            let format = file.processingFormat
            let frames = AVAudioFrameCount(file.length)
            guard frames > 0,
                  let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
                return placeholder
            }
            try file.read(into: buf)
            guard let samples = buf.floatChannelData?[0] else { return placeholder }
            let n = Int(buf.frameLength)
            let per = max(1, n / barCount)
            var out: [Float] = []
            for b in 0..<barCount {
                let lo = b * per, hi = min(lo + per, n)
                guard lo < hi else { out.append(0); continue }
                var sum: Float = 0
                for i in lo..<hi { sum += samples[i] * samples[i] }
                out.append(sqrt(sum / Float(hi - lo)))
            }
            let peak = max(out.max() ?? 1, 0.001)
            let norm = out.map { max(0.12, $0 / peak) }   // floor so silence still draws
            cache.setObject(norm as NSArray, forKey: id as NSString)
            return norm
        } catch {
            return placeholder
        }
    }

    private static var placeholder: [Float] {
        (0..<barCount).map { _ in 0.3 }
    }
}
