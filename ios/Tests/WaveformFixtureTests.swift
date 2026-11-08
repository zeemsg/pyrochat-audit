import XCTest
import AVFoundation
import AudioToolbox
import CryptoKit
@testable import PyroChat

/// iOS half of the cross-platform waveform proof (`android/fixtures/voice/README.md`):
/// `Waveform.bars(for:data:)` (`Core/VoiceRecorder.swift`) and its Android port
/// (`app/platform/Waveform.kt`) must draw the same 32 bars for the same `.m4a`, in BOTH
/// encoding directions. A NO-OP (XCTSkip) unless `PYRO_WAVE_DIR` is set — from xcodebuild
/// pass `TEST_RUNNER_PYRO_WAVE_DIR=/abs/path`. That directory must NOT live under ~/Desktop
/// (macOS TCC hangs the simulator test host on open(2) there, no prompt): use the session
/// scratchpad and copy the results into `android/fixtures/voice/` afterwards.
///
/// Input (copied into PYRO_WAVE_DIR beforehand):
///   tone-1s-24k-mono-32k.m4a            Android MediaCodec+MediaMuxer output (AacFixture.kt)
/// Outputs (written into PYRO_WAVE_DIR):
///   tone-1s-24k-mono-32k.ios-bars.json  the REAL iOS bars for the Android file
///   ios-tone-1s.m4a                     an iOS-encoded note at EXACTLY the VoiceRecorder
///                                       settings (encoded only when absent, else re-verified),
///                                       + ios-tone-1s.ios-bars.json
///   *.ios-pcm.f32le                     channel-0 PCM as AVAudioFile decodes it (diagnostics)
///
/// The asserting half of the comparison is Android's `WaveformInteropTest` (tolerance 1e-3
/// per bar); this side only records what CoreAudio produces and, when an
/// `*.android-bars.json` sits next to the input, prints the per-bar delta for the log.
final class WaveformFixtureTests: XCTestCase {

    static let androidFixture = "tone-1s-24k-mono-32k"
    static let iosFixture = "ios-tone-1s"
    static let sampleRate = 24_000.0

    /// EXACTLY `VoiceRecorder.begin()`'s AVAudioRecorder settings (VoiceRecorder.swift:31-36).
    static let recorderSettings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 24_000,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 32_000,
    ]

    // MARK: (a) Android-encoded fixture through the real iOS decoder + bar math

    func testAndroidEncodedFixtureThroughIOSDecoder() throws {
        let dir = try waveDir()
        let url = dir.appendingPathComponent("\(Self.androidFixture).m4a")
        let data = try Data(contentsOf: url)
        XCTAssertGreaterThan(data.count, 1_000)

        // Reverse playback contract at file level: the MediaMuxer output opens through
        // CoreAudio as 24 kHz mono AAC of ~1 s, and AVAudioPlayer (VoicePlayer's engine,
        // VoiceRecorder.swift:104) accepts the bytes.
        let probe = try decode(url)
        XCTAssertEqual(probe.fileSampleRate, Self.sampleRate)
        XCTAssertEqual(probe.channels, 1)
        XCTAssertTrue((22_000...27_500).contains(probe.samples.count), "frames=\(probe.samples.count)")
        // MediaMuxer writes neither `elst` nor `iTunSMPB`; CoreAudio then ASSUMES the standard
        // AAC-LC encoder delay of 2112 frames and no remainder — this is the rule the Android
        // decoder mirrors (26 packets × 1024 − 2112 = 24512 readable frames).
        XCTAssertEqual(probe.packetTable.priming, 2112)
        XCTAssertEqual(probe.packetTable.remainder, 0)
        XCTAssertEqual(probe.samples.count, probe.packetTable.validFrames)
        let player = try AVAudioPlayer(data: data)
        XCTAssertEqual(player.duration, 1, accuracy: 0.15)

        let bars = Waveform.bars(for: "wave-fixture-android-\(UUID().uuidString)", data: data)
        XCTAssertEqual(bars.count, Waveform.barCount)
        XCTAssertEqual(bars.max(), 1)
        XCTAssertTrue(bars.allSatisfy { $0 >= 0.12 && $0 <= 1 })

        try export(name: Self.androidFixture, data: data, probe: probe, bars: bars, dir: dir,
                   source: "AacFixture.tone: 440 Hz, 0.8 amplitude for 0.5 s then 0.2, 1 s",
                   encoder: "Android MediaCodec AAC-LC + MediaMuxer, 24 kHz mono 32 kbps (AacFixture.kt)")
        logDelta(name: Self.androidFixture, iosBars: bars, dir: dir)
    }

    // MARK: (b) iOS-encoded fixture at the recorder's settings

    func testIOSEncodedToneFixture() throws {
        let dir = try waveDir()
        let url = dir.appendingPathComponent("\(Self.iosFixture).m4a")
        // Re-encoding is not byte-stable (CoreAudio stamps the creation time into `mvhd`) and
        // the Android bars are pinned to one SHA-256, so an existing file is reused and
        // re-verified; delete it from PYRO_WAVE_DIR to encode a fresh fixture.
        if FileManager.default.fileExists(atPath: url.path) {
            print("WaveformFixture \(Self.iosFixture): reusing the existing file")
        } else {
            try Self.encodeTone(to: url)
            print("WaveformFixture \(Self.iosFixture): encoded a fresh file")
        }
        let data = try Data(contentsOf: url)
        XCTAssertGreaterThan(data.count, 1_000)
        // The container is mostly a `free` reservation (AudioFile's optimised layout); the AAC
        // payload of a pure tone under the encoder's rate control is far below 32 kbps × 1 s.
        let payload = try Self.audioDataByteCount(url)
        XCTAssertTrue((500...5_000).contains(payload), "AAC payload bytes=\(payload)")

        let probe = try decode(url)
        XCTAssertEqual(probe.fileSampleRate, Self.sampleRate)
        XCTAssertEqual(probe.channels, 1)
        // CoreAudio honours the gapless metadata its own encoder wrote — `iTunSMPB` in
        // udta/meta/ilst (no `elst`): 2112 priming frames + 512 remainder frames are trimmed,
        // so the readable length is exactly the 24 000 frames fed in.
        XCTAssertEqual(probe.samples.count, 24_000, "AVAudioFile must trim priming + padding")
        XCTAssertEqual(probe.packetTable.priming, 2112)
        XCTAssertEqual(probe.packetTable.remainder, 512)
        XCTAssertEqual(probe.packetTable.validFrames, 24_000)

        let bars = Waveform.bars(for: "wave-fixture-ios-\(UUID().uuidString)", data: data)
        XCTAssertEqual(bars.count, Waveform.barCount)
        XCTAssertEqual(bars.max(), 1)
        // per = 24000/32 = 750 frames; the 0.4–0.6 s gap is frames 9600..<14400 = bars 12.8…19.2
        XCTAssertGreaterThan(bars[0], 0.8, "bar 0 is tone: no leading priming silence survives on iOS")
        for b in 14...17 { XCTAssertLessThanOrEqual(bars[b], 0.13, "gap bar \(b) must sit at the 0.12 floor") }
        for b in [13, 18] { XCTAssertLessThan(bars[b], 0.35, "gap-edge bar \(b)") }
        for b in [2, 5, 8, 11, 22, 26, 30] { XCTAssertGreaterThan(bars[b], 0.9, "tone bar \(b)") }

        try export(name: Self.iosFixture, data: data, probe: probe, bars: bars, dir: dir,
                   source: "440 Hz at -6 dBFS (0.5 amplitude), silent 0.4-0.6 s, 1 s",
                   encoder: "AVAudioFile(forWriting:settings:) with VoiceRecorder.swift:31-36 settings (CoreAudio AAC-LC 24 kHz mono 32 kbps)")
        logDelta(name: Self.iosFixture, iosBars: bars, dir: dir)
    }

    // MARK: - helpers

    private func waveDir() throws -> URL {
        guard let path = ProcessInfo.processInfo.environment["PYRO_WAVE_DIR"], !path.isEmpty else {
            throw XCTSkip("PYRO_WAVE_DIR not set — waveform fixture export is opt-in")
        }
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The recorder's exact settings through the same CoreAudio AAC encoder AVAudioRecorder
    /// drives — the recorder itself cannot run in the simulator test host (no microphone,
    /// TCC prompt, non-deterministic input).
    static func encodeTone(to url: URL) throws {
        try encode(to: url) { i in
            let t = Double(i) / sampleRate
            let inGap = t >= 0.4 && t < 0.6
            return inGap ? 0 : Float(0.5 * sin(2 * .pi * 440 * t))   // −6 dBFS
        }
    }

    static func encode(to url: URL, frames total: Int = 24_000, sample: (Int) -> Float) throws {
        let file = try AVAudioFile(forWriting: url, settings: recorderSettings)
        let fmt = file.processingFormat
        XCTAssertEqual(fmt.sampleRate, sampleRate)
        XCTAssertEqual(fmt.channelCount, 1)
        guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(total)),
              let ch = buf.floatChannelData?[0] else {
            throw NSError(domain: "WaveformFixtureTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "no PCM buffer"])
        }
        for i in 0..<total { ch[i] = sample(i) }
        buf.frameLength = AVAudioFrameCount(total)
        try file.write(from: buf)
        // `file` is released on return: ExtAudioFile flushes the encoder and finalises the moov.
    }

    /// Guards the fixture's claim of "the recorder's settings": a tone compresses to almost
    /// nothing under any bit rate, so encode incompressible noise and check the AAC payload
    /// lands at 32 kbps × 1 s ≈ 4 000 bytes (the encoder's default for 24 kHz mono is higher).
    func testRecorderBitRateIsHonouredByAVAudioFile() throws {
        let dir = try waveDir()
        let url = dir.appendingPathComponent("noise-bitrate-check.m4a")
        try? FileManager.default.removeItem(at: url)
        var state: UInt32 = 0x1234_5678
        try Self.encode(to: url) { _ in
            state = state &* 1_664_525 &+ 1_013_904_223           // deterministic LCG
            return Float(Int32(bitPattern: state)) / Float(Int32.max) * 0.5
        }
        let payload = try Self.audioDataByteCount(url)
        print("WaveformFixture noise payload bytes=\(payload) (32 kbps × 1 s = 4000)")
        XCTAssertTrue((2_800...5_200).contains(payload), "AAC payload bytes=\(payload); AVEncoderBitRateKey not applied?")
        try? FileManager.default.removeItem(at: url)
    }

    struct PacketTable { let validFrames: Int; let priming: Int; let remainder: Int }

    private static func audioFile<T>(_ url: URL, _ body: (AudioFileID) throws -> T) throws -> T {
        var fileID: AudioFileID?
        let status = AudioFileOpenURL(url as CFURL, .readPermission, kAudioFileM4AType, &fileID)
        guard status == noErr, let fileID else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "AudioFileOpenURL"])
        }
        defer { AudioFileClose(fileID) }
        return try body(fileID)
    }

    /// `kAudioFilePropertyAudioDataByteCount`: the AAC payload, ignoring moov/free/udta.
    static func audioDataByteCount(_ url: URL) throws -> Int {
        try audioFile(url) { fileID in
            var bytes: UInt64 = 0
            var size = UInt32(MemoryLayout<UInt64>.size)
            let status = AudioFileGetProperty(fileID, kAudioFilePropertyAudioDataByteCount, &size, &bytes)
            guard status == noErr else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "AudioDataByteCount"])
            }
            return Int(bytes)
        }
    }

    /// `kAudioFilePropertyPacketTableInfo`: what CoreAudio trims — from `iTunSMPB`/`elst`
    /// when present, otherwise its AAC default (2112 priming, 0 remainder).
    static func packetTable(_ url: URL) throws -> PacketTable {
        try audioFile(url) { fileID in
            var info = AudioFilePacketTableInfo()
            var size = UInt32(MemoryLayout<AudioFilePacketTableInfo>.size)
            let status = AudioFileGetProperty(fileID, kAudioFilePropertyPacketTableInfo, &size, &info)
            guard status == noErr else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "PacketTableInfo"])
            }
            return PacketTable(validFrames: Int(info.mNumberValidFrames), priming: Int(info.mPrimingFrames),
                               remainder: Int(info.mRemainderFrames))
        }
    }

    struct Probe {
        let fileSampleRate: Double
        let channels: AVAudioChannelCount
        let samples: [Float]
        let packetTable: PacketTable
    }

    /// Channel 0 exactly as `Waveform.bars` sees it (AVAudioFile → Float32 processing format).
    private func decode(_ url: URL) throws -> Probe {
        let file = try AVAudioFile(forReading: url)
        let frames = AVAudioFrameCount(file.length)
        guard frames > 0, let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frames) else {
            throw NSError(domain: "WaveformFixtureTests", code: 2, userInfo: [NSLocalizedDescriptionKey: "empty file"])
        }
        try file.read(into: buf)
        guard let ch = buf.floatChannelData?[0] else {
            throw NSError(domain: "WaveformFixtureTests", code: 3, userInfo: [NSLocalizedDescriptionKey: "no float channel"])
        }
        return Probe(fileSampleRate: file.fileFormat.sampleRate,
                     channels: file.fileFormat.channelCount,
                     samples: Array(UnsafeBufferPointer(start: ch, count: Int(buf.frameLength))),
                     packetTable: try Self.packetTable(url))
    }

    private func export(name: String, data: Data, probe: Probe, bars: [Float], dir: URL,
                        source: String, encoder: String) throws {
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        var json = "{\n"
        json += "  \"source\": \"\(source)\",\n"
        json += "  \"encoder\": \"\(encoder)\",\n"
        json += "  \"decoder\": \"AVAudioFile (CoreAudio), iOS simulator \(os)\",\n"
        json += "  \"sha256\": \"\(sha)\",\n"
        json += "  \"sampleRate\": \(Int(probe.fileSampleRate)),\n"
        json += "  \"frames\": \(probe.samples.count),\n"
        json += "  \"coreAudioPacketTable\": {\"validFrames\": \(probe.packetTable.validFrames), "
        json += "\"priming\": \(probe.packetTable.priming), \"remainder\": \(probe.packetTable.remainder)},\n"
        json += "  \"barCount\": \(bars.count),\n"
        json += "  \"bars\": [" + bars.map { "\($0)" }.joined(separator: ", ") + "]\n"
        json += "}\n"
        try json.write(to: dir.appendingPathComponent("\(name).ios-bars.json"), atomically: true, encoding: .utf8)
        let pcm = probe.samples.withUnsafeBytes { Data($0) }   // native (little-endian) Float32
        try pcm.write(to: dir.appendingPathComponent("\(name).ios-pcm.f32le"))
        print("WaveformFixture \(name): sha256=\(sha) frames=\(probe.samples.count) packetTable=\(probe.packetTable) bars=\(bars)")
    }

    /// Informational: per-bar delta against an Android-computed `*.android-bars.json`, if present.
    private func logDelta(name: String, iosBars: [Float], dir: URL) {
        let url = dir.appendingPathComponent("\(name).android-bars.json")
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let android = (obj["bars"] as? [Double])?.map(Float.init),
              android.count == iosBars.count else {
            print("WaveformFixture \(name): no android-bars.json to diff against")
            return
        }
        let deltas = zip(android, iosBars).map { abs($0 - $1) }
        let worst = deltas.indices.max { deltas[$0] < deltas[$1] } ?? 0
        print("WaveformFixture \(name): max |android − ios| = \(deltas[worst]) at bar \(worst); androidFrames=\(obj["frames"] ?? "?")")
    }
}
