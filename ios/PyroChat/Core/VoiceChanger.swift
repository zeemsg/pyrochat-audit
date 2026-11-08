import Foundation
import AVFoundation

/// Sends a voice note in another voice (docs/VOICE-CHANGER.md). The recorder's AAC is decoded,
/// changed by `VoiceDsp` and encoded again exactly as it was recorded (AAC, 24 kHz, mono,
/// 32 kbps, m4a), so the other side cannot tell a changed note from any other and needs no
/// update to play it. Android's `VoiceChanger`.
///
/// It fails closed: when anything goes wrong it throws and hands nothing back. A note that
/// was meant to be disguised is never sent as it was spoken.
///
/// Like the recorder and the waveform, it needs the audio in a file for the moments
/// AVAudioFile works on it: a `vn-*` directory in tmp, removed before this returns and swept
/// at launch with the recorder's own (`SecureStore.sweepPlaintextTmp`).
enum VoiceChanger {
    struct Failed: Error, CustomStringConvertible {
        let description: String
    }

    /// What the composer says when a voice could not be changed. The note is gone by then:
    /// it is never sent as it was spoken.
    static let refusal = "ERR: voice not changed, nothing sent"

    /// `m4a` in the voice `effect`, off the main thread. `pick` (0…1) is where in that voice's
    /// range this note lands; left alone it is drawn afresh, so no two notes are shifted by
    /// quite the same amount.
    static func change(_ m4a: Data, to effect: VoiceEffect, pick: Double? = nil) async throws -> Data {
        guard effect != .off else { return m4a }
        let spot = pick ?? Double.random(in: 0..<1)
        return try await Task.detached(priority: .userInitiated) {
            try changeNow(m4a, to: effect, pick: spot)
        }.value
    }

    static func changeNow(_ m4a: Data, to effect: VoiceEffect, pick: Double) throws -> Data {
        guard effect != .off else { return m4a }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("vn-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let spoken = dir.appendingPathComponent("in.m4a")
            let changed = dir.appendingPathComponent("out.m4a")
            try m4a.write(to: spoken, options: [.completeFileProtection])
            let samples = try decode(spoken)
            try encode(VoiceDsp.apply(samples, effect: effect, pick: pick), to: changed)
            let data = try Data(contentsOf: changed)
            guard !data.isEmpty else { throw Failed(description: "nothing was written") }
            return data
        } catch let failure as Failed {
            throw failure
        } catch {
            throw Failed(description: "the voice could not be changed (\(error.localizedDescription))")
        }
    }

    /// Channel 0 of a voice note as floats in -1…1.
    static func decode(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        guard Int(format.sampleRate) == VoiceDsp.sampleRate else {
            throw Failed(description: "a voice note at \(Int(format.sampleRate)) Hz")
        }
        let frames = AVAudioFrameCount(file.length)
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
            throw Failed(description: "no audio in the recording")
        }
        try file.read(into: buffer)
        guard let channel = buffer.floatChannelData?[0], buffer.frameLength > 0 else {
            throw Failed(description: "no audio in the recording")
        }
        return Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
    }

    /// Mono floats at `VoiceDsp.sampleRate` to the recorder's own format.
    static func encode(_ samples: [Float], to url: URL) throws {
        guard let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(VoiceDsp.sampleRate),
                                      channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(max(samples.count, 1))),
              let channel = buffer.floatChannelData?[0] else {
            throw Failed(description: "no room for the changed audio")
        }
        samples.withUnsafeBufferPointer { src in
            if let base = src.baseAddress { channel.update(from: base, count: samples.count) }
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        // The file is complete only once the AVAudioFile is gone: keep it inside this scope.
        do {
            let file = try AVAudioFile(forWriting: url, settings: VoiceRecorder.settings,
                                       commonFormat: .pcmFormatFloat32, interleaved: false)
            try file.write(from: buffer)
        }
    }
}
