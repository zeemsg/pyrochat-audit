import XCTest
@testable import PyroChat

/// The voice changer's arithmetic (docs/VOICE-CHANGER.md), with no codec: what goes in is a
/// made-up voice, what comes out is measured. The same cases, against the same golden
/// numbers, are in Android's `VoiceDspTest`.
final class VoiceDspTests: XCTestCase {
    private let rate = VoiceDsp.sampleRate

    /// Something voice-like: a fundamental with eight harmonics, breathing in loudness.
    private func voice(_ f0: Double, seconds: Double = 1.0) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { i in
            let t = Double(i) / Double(rate)
            var v = 0.0
            for k in 1...8 { v += sin(2.0 * Double.pi * Double(k) * f0 * t) / Double(k) }
            return Float(0.25 * v * (0.7 + 0.3 * sin(2.0 * Double.pi * 2.0 * t)))
        }
    }

    /// The period, in samples, at which the middle of `x` repeats best.
    private func period(_ x: [Float], from: Int, to: Int) -> Int {
        let a = x.count / 4, n = x.count / 2
        var best = from
        var bestScore = -Double.infinity
        x.withUnsafeBufferPointer { p in
            for lag in from...to {
                var dot = 0.0
                for i in a..<(a + n - lag) { dot += Double(p[i]) * Double(p[i + lag]) }
                let score = dot / Double(n - lag)
                if score > bestScore {
                    best = lag
                    bestScore = score
                }
            }
        }
        return best
    }

    /// The pitch of `x` in Hz, looked for within a fifth either side of `near`: a made-up voice
    /// repeats just as well every two periods, and that is not the pitch.
    private func heard(_ x: [Float], near: Double) -> Double {
        Double(rate) / Double(period(x, from: Int(Double(rate) / near * 0.8), to: Int(Double(rate) / near * 1.2) + 1))
    }

    private func rms(_ x: [Float]) -> Double {
        (x.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(x.count)).squareRoot()
    }

    func testDeepAndHighMoveThePitchByTheFactorAndKeepTheLength() {
        let spoken = voice(150)
        for factor in [0.74, 0.77, 0.80, 1.30, 1.35, 1.40] {
            let changed = VoiceDsp.pitch(spoken, factor: factor)
            XCTAssertEqual(changed.count, spoken.count, "the note is as long as it was")
            XCTAssertEqual(heard(changed, near: 150 * factor), 150 * factor, accuracy: 150 * factor * 0.025, "150 Hz times \(factor)")
            XCTAssertEqual(rms(changed), rms(spoken), accuracy: rms(spoken) * 0.12, "and about as loud")
            XCTAssertTrue(changed.allSatisfy { $0.isFinite && abs($0) <= 0.981 })
        }
    }

    func testTheRobotHasOnePitchWhateverTheSpeakersWas() {
        for f0 in [110.0, 150.0, 220.0] {
            let spoken = voice(f0)
            let changed = VoiceDsp.robot(spoken)
            XCTAssertEqual(changed.count, spoken.count)
            // 24 000 / 256 = 93.75 Hz: it repeats every 256 samples, and not at the speaker's own period
            XCTAssertEqual(period(changed, from: 200, to: 300), 256, "a \(f0) Hz voice")
            XCTAssertEqual(rms(changed), rms(spoken), accuracy: rms(spoken) * 0.2)
            XCTAssertTrue(changed.allSatisfy { $0.isFinite && abs($0) <= 0.981 })
        }
    }

    func testSilenceStaysSilent() {
        let silence = [Float](repeating: 0, count: rate)
        XCTAssertTrue(VoiceDsp.pitch(silence, factor: 0.77).allSatisfy { $0 == 0 })
        XCTAssertTrue(VoiceDsp.pitch(silence, factor: 1.35).allSatisfy { $0 == 0 })
        XCTAssertTrue(VoiceDsp.robot(silence).allSatisfy { $0 == 0 })
    }

    func testALoudNoteIsTurnedDownRatherThanClipped() {
        let loud: [Float] = (0..<rate).map { ($0 / 80) % 2 == 0 ? 0.999 : -0.999 }       // a square wave at full scale
        for changed in [VoiceDsp.pitch(loud, factor: 0.77), VoiceDsp.pitch(loud, factor: 1.35), VoiceDsp.robot(loud)] {
            XCTAssertTrue(changed.allSatisfy { $0.isFinite && abs($0) <= 0.981 })
        }
    }

    func testNotesTooShortToWorkOnAreHandled() {
        XCTAssertEqual(VoiceDsp.pitch([], factor: 0.77).count, 0)
        XCTAssertEqual(VoiceDsp.robot([]).count, 0)
        let tiny = [Float](repeating: 0.1, count: 100)
        XCTAssertEqual(VoiceDsp.pitch(tiny, factor: 1.35), tiny, "less than one frame goes back as it came")
        XCTAssertEqual(VoiceDsp.robot(tiny).count, 100)
        let oneFrame = voice(150, seconds: 0.03)
        XCTAssertEqual(VoiceDsp.pitch(oneFrame, factor: 0.77).count, oneFrame.count)
    }

    func testEveryNoteLandsSomewhereInItsVoicesRange() {
        XCTAssertEqual(VoiceDsp.factor(.deep, pick: 0), 0.74, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.deep, pick: 1), 0.80, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.high, pick: 0), 1.30, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.high, pick: 1), 1.40, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.deep, pick: 0.5), 0.77, accuracy: 1e-12)
        // a pick out of range is brought back in, never a shift outside the voice
        XCTAssertEqual(VoiceDsp.factor(.deep, pick: -3), 0.74, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.high, pick: 7), 1.40, accuracy: 1e-12)
        XCTAssertEqual(VoiceDsp.factor(.off, pick: 0.5), 1)
        XCTAssertEqual(VoiceDsp.factor(.robot, pick: 0.5), 1)
    }

    func testOffIsTheNoteAsItWasAndEveryStoredWordIsAVoice() {
        let spoken = voice(150, seconds: 0.2)
        XCTAssertEqual(VoiceDsp.apply(spoken, effect: .off, pick: 0.5), spoken)
        XCTAssertEqual(VoiceEffect.of(nil), .off)
        XCTAssertEqual(VoiceEffect.of("off"), .off)
        XCTAssertEqual(VoiceEffect.of("helium"), .deep)      // a word from a newer build: some disguise, never none
        XCTAssertEqual(VoiceEffect.of("deep"), .deep)
        XCTAssertEqual(VoiceEffect.of("high"), .high)
        XCTAssertEqual(VoiceEffect.of("robot"), .robot)
        XCTAssertEqual(VoiceEffect.allCases.map(\.rawValue), ["off", "deep", "high", "robot"])
    }

    func testTheSameNoteAndTheSamePickGiveTheSameResult() {
        let spoken = voice(180, seconds: 0.5)
        XCTAssertEqual(VoiceDsp.apply(spoken, effect: .deep, pick: 0.3), VoiceDsp.apply(spoken, effect: .deep, pick: 0.3))
        XCTAssertEqual(VoiceDsp.apply(spoken, effect: .robot, pick: 0.3), VoiceDsp.apply(spoken, effect: .robot, pick: 0.9))
        // and a different pick is a different shift
        XCTAssertNotEqual(VoiceDsp.apply(spoken, effect: .deep, pick: 0), VoiceDsp.apply(spoken, effect: .deep, pick: 1))
    }

    // MARK: the parts

    func testTheTransformUndoesItself() {
        let n = 1024
        var re = [Double](repeating: 0, count: n)
        for i in 0..<n {
            let turn = 2.0 * Double.pi * Double(i) / Double(n)
            re[i] = sin(5 * turn) + 0.3 * sin(37 * turn) + 0.2
        }
        var im = [Double](repeating: 0, count: n)
        let was = re
        VoiceDsp.fft(&re, &im, inverse: false)
        // a sine of 5 cycles sits in bins 5 and n - 5, half its length each
        XCTAssertEqual((re[5] * re[5] + im[5] * im[5]).squareRoot(), Double(n) / 2, accuracy: Double(n) * 0.02)
        VoiceDsp.fft(&re, &im, inverse: true)
        for i in 0..<n {
            XCTAssertEqual(re[i], was[i], accuracy: 1e-9)
            XCTAssertEqual(im[i], 0, accuracy: 1e-9)
        }
    }

    func testReadingAtTheSameSpeedChangesNothingAndASteadyLevelStaysSteady() {
        let z: [Float] = (0..<4000).map { Float(sin(2.0 * Double.pi * 300 * Double($0) / Double(rate))) }
        let same = VoiceDsp.resample(z, length: z.count, ratio: 1.0, count: z.count)
        for i in z.indices { XCTAssertEqual(same[i], z[i], accuracy: 1e-6) }
        let level = [Float](repeating: 0.5, count: 4000)
        for ratio in [0.77, 1.35] {
            let out = VoiceDsp.resample(level, length: level.count, ratio: ratio, count: 2000)
            for i in 100..<1900 { XCTAssertEqual(out[i], 0.5, accuracy: 1e-6) }
        }
        // and it reads no further than it is told the signal goes
        let short = VoiceDsp.resample(level, length: 1000, ratio: 1.0, count: 2000)
        XCTAssertEqual(short[500], 0.5, accuracy: 1e-6)
        XCTAssertEqual(short[1500], 0)
    }

    func testStretchingByOneLeavesTheMiddleAlone() {
        let x: [Float] = (0..<(rate / 2)).map { Float(sin(2.0 * Double.pi * 200 * Double($0) / Double(rate)) * 0.5) }
        let same = VoiceDsp.wsola(x, stretch: 1.0)
        for i in 480..<(x.count - 960) { XCTAssertEqual(same[i], x[i], accuracy: 1e-6) }
        // the stretched signal is the first `count × stretch` samples of what comes back, which is one frame longer
        XCTAssertEqual(same.count, x.count + 480)
        XCTAssertEqual(VoiceDsp.wsola(x, stretch: 1.35).count, Int((Double(x.count) * 1.35).rounded()) + 480)
        XCTAssertEqual(VoiceDsp.wsola(x, stretch: 0.77).count, Int((Double(x.count) * 0.77).rounded()) + 480)
    }

    /// Most frames are not looked for: the samples that follow the last frame are taken when
    /// they are within reach, because nothing is more like them than they are. That has to give
    /// exactly what looking everywhere gives, which is written out the plain way below.
    func testTakingTheSamplesThatFollowGivesWhatLookingEverywhereGives() {
        let x = goldenInput()
        for stretch in [0.74, 0.77, 0.80, 1.30, 1.35, 1.40] {
            XCTAssertEqual(VoiceDsp.wsola(x, stretch: stretch), everywhere(x, stretch: stretch), "stretched by \(stretch)")
        }
    }

    /// The stretch as `VoiceDsp.wsola` makes it, with every frame looked for everywhere within reach.
    private func everywhere(_ x: [Float], stretch: Double) -> [Float] {
        let hop = 240
        let radius = 240
        let frame = 2 * hop
        let m = Int((Double(x.count) * stretch).rounded())
        let padded = x + [Float](repeating: 0, count: frame + radius + hop + 1)
        let window = VoiceDsp.hann(frame)
        var out = [Float](repeating: 0, count: m + frame)
        func likeness(_ a: Int, _ b: Int) -> Double {
            var dot = 0.0
            var energy = 0.0
            for i in 0..<hop {
                let v = Double(padded[b + i])
                dot += Double(padded[a + i]) * v
                energy += v * v
            }
            return dot / (energy + 1e-9).squareRoot()
        }
        var pos = 0
        for i in 0..<frame { out[i] = Float(window[i] * Double(padded[i])) }
        var k = 1
        while k * hop < m {
            let target = Int((Double(k * hop) / stretch).rounded())
            let ref = pos + hop
            var best = 0
            var bestScore = likeness(ref, target)
            for d in max(-radius, -target)...radius where d != 0 && target + d + frame <= padded.count {
                let score = likeness(ref, target + d)
                if score > bestScore {
                    best = d
                    bestScore = score
                }
            }
            pos = target + best
            for i in 0..<frame { out[k * hop + i] = Float(Double(out[k * hop + i]) + window[i] * Double(padded[pos + i])) }
            k += 1
        }
        return out
    }

    /// The filter turns its two angles from one tap to the next instead of asking for a sine at
    /// every tap. It has to give what the plain formula gives, also where a sample falls almost
    /// exactly on one of the note's own: there a turned sine has the fewest digits left.
    func testTheFilterGivesWhatThePlainFormulaGives() {
        var seed: UInt64 = 99
        let z: [Float] = (0..<40_000).map { _ in
            seed = (seed &* 1664525 &+ 1013904223) & 0xFFFF_FFFF
            return Float(Double((seed >> 8) & 0xFFFF) / 65535.0 - 0.5) / 2
        }
        for ratio in [0.74, 0.77, 1.30, 1.35, 1.40] {
            let n = Int(Double(z.count) / ratio) - 40
            let y = VoiceDsp.resample(z, length: z.count, ratio: ratio, count: n)
            var almostOn = 0
            for i in 0..<n {
                let off = Double(i) * ratio - (Double(i) * ratio).rounded(.down)
                let almost = off != 0 && (off < 1e-9 || off > 1 - 1e-9)
                if almost { almostOn += 1 }
                if almost || i % 97 == 0 {
                    XCTAssertEqual(Double(y[i]), plain(z, ratio: ratio, at: i), accuracy: 2e-7, "read back at \(ratio), sample \(i)")
                }
            }
            if ratio == 1.35 { XCTAssertGreaterThan(almostOn, 20, "samples that fall almost on one of the note's own") }
        }
    }

    /// One sample of the read-back, with a sine and a cosine asked for at every tap.
    private func plain(_ z: [Float], ratio: Double, at i: Int) -> Double {
        let cutoff = min(1.0, 1.0 / ratio)
        let reach = Int((8 / cutoff).rounded(.up))
        let centre = Double(i) * ratio
        let first = Int(centre.rounded(.down)) - reach + 1
        var sum = 0.0
        var weight = 0.0
        for k in first..<(first + 2 * reach) {
            let t = centre - Double(k)
            if abs(t) >= Double(reach) { continue }
            let a = Double.pi * cutoff * t
            let sinc = abs(a) < 1e-12 ? 1.0 : sin(a) / a
            let w = cutoff * sinc * (0.5 + 0.5 * cos(Double.pi * t / Double(reach)))
            weight += w
            if k >= 0 && k < z.count { sum += w * Double(z[k]) }
        }
        return sum / weight
    }

    // MARK: one result on both platforms

    /// A voice that glides from 110 to 170 Hz over half a second, with a little noise: nothing
    /// in it repeats exactly.
    private func goldenInput() -> [Float] {
        var seed: UInt64 = 12345
        return (0..<12_000).map { i in
            let t = Double(i) / Double(rate)
            let phase = 2.0 * Double.pi * (110.0 * t + 60.0 * t * t)
            var v = 0.0
            for k in 1...8 { v += sin(Double(k) * phase) / Double(k) }
            seed = (seed &* 1664525 &+ 1013904223) & 0xFFFF_FFFF
            let noise = Double((seed >> 8) & 0xFFFF) / 65535.0 - 0.5
            let loudness = 0.6 + 0.4 * sin(2.0 * Double.pi * 3.0 * t)
            let sample = 0.25 * v * loudness + 0.02 * noise
            return Float(sample)
        }
    }

    /// Every fortieth sample of the three voices for `goldenInput`, as written by Android's
    /// `VoiceDspTest` (android/fixtures/voice-changer/README.md): both platforms must produce them.
    func testBothPlatformsProduceTheSameVoices() throws {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("android/fixtures/voice-changer/golden.txt")
        guard let text = try? String(contentsOf: file, encoding: .utf8) else {
            throw XCTSkip("no golden file at \(file.path)")
        }
        var golden: [String: [Float]] = [:]
        for line in text.split(separator: "\n") where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            let parts = line.split(separator: " ")
            golden[String(parts[0])] = parts.dropFirst().compactMap { Float($0) }
        }
        XCTAssertEqual(Set(golden.keys), ["deep", "high", "robot"])
        let input = goldenInput()
        let now: [(String, [Float])] = [
            ("deep", VoiceDsp.apply(input, effect: .deep, pick: 0.5)),
            ("high", VoiceDsp.apply(input, effect: .high, pick: 0.5)),
            ("robot", VoiceDsp.apply(input, effect: .robot, pick: 0.5)),
        ]
        for (name, samples) in now {
            let sampled = stride(from: 0, to: samples.count, by: 40).map { samples[$0] }
            let want = try XCTUnwrap(golden[name])
            XCTAssertEqual(sampled.count, want.count, "\(name): how many samples")
            for i in 0..<min(sampled.count, want.count) {
                XCTAssertEqual(sampled[i], want[i], accuracy: 2e-4, "\(name), sample \(i * 40)")
            }
        }
    }
}
