import Foundation

/// The voices a voice note can be sent in (docs/VOICE-CHANGER.md). The raw value is what the
/// setting is stored as, the same word on Android.
enum VoiceEffect: String, CaseIterable {
    case off, deep, high, robot

    /// A stored word back to its voice. Nothing stored is `.off`. A word this build does not
    /// know (a voice added by a newer one, after a restore) is `.deep` and never `.off`:
    /// whoever chose a voice is not sent out in their own because this build cannot spell it.
    static func of(_ wire: String?) -> VoiceEffect {
        guard let wire else { return .off }
        return VoiceEffect(rawValue: wire) ?? .deep
    }
}

/// The signal processing behind the voice changer: mono samples in -1…1 at `sampleRate`, in
/// and out, the same length. Nothing of AVFoundation in here, so it runs in a plain unit test,
/// and the loops are the ones in Android's `VoiceDsp.kt`: a note changed on either phone is
/// changed the same way.
///
///  - `pitch`: the same words at the same speed, the voice `factor` times as high. The signal
///    is first made `factor` times as long without touching its pitch (WSOLA: overlapping
///    20 ms frames, each taken from where it best continues the one before), then read back
///    `factor` times as fast, which brings the length back and moves the pitch, formants and all.
///  - `robot`: every 43 ms frame keeps how loud each frequency is and loses its phase, so
///    every frame becomes the same kind of pulse and the voice has one flat pitch,
///    `sampleRate` / 256 = 93.75 Hz, whatever the speaker's was.
enum VoiceDsp {
    static let sampleRate = 24_000

    /// WSOLA: frames are 2 × `hop` long and half overlap; a frame may be taken up to `radius`
    /// either side of where it is due.
    private static let hop = 240
    private static let radius = 240

    /// The robot's frame and step: its pitch is `sampleRate / robotHop`.
    private static let robotFrame = 1024
    private static let robotHop = 256

    /// How far the resampler looks either side of a sample, at unity.
    private static let taps = 8

    /// Loud enough, short of clipping.
    private static let ceiling = 0.98

    /// The factor a note is shifted by: somewhere in the voice's range, where `pick` is 0…1.
    /// A different spot for every note.
    static func factor(_ effect: VoiceEffect, pick: Double) -> Double {
        let p = min(max(pick, 0), 1)
        switch effect {
        case .deep: return 0.74 + 0.06 * p
        case .high: return 1.30 + 0.10 * p
        default: return 1
        }
    }

    /// `samples` in the voice `effect`; `pick` (0…1) chooses the exact shift within that voice's range.
    static func apply(_ samples: [Float], effect: VoiceEffect, pick: Double) -> [Float] {
        switch effect {
        case .off: return samples
        case .deep, .high: return pitch(samples, factor: factor(effect, pick: pick))
        case .robot: return robot(samples)
        }
    }

    static func pitch(_ samples: [Float], factor: Double) -> [Float] {
        precondition((0.5...2.0).contains(factor), "factor out of range")
        if samples.count < 2 * hop { return samples }       // less than one frame: nothing to work with
        let stretched = wsola(samples, stretch: factor)
        let length = Int((Double(samples.count) * factor).rounded())
        return finish(resample(stretched, length: length, ratio: factor, count: samples.count))
    }

    static func robot(_ samples: [Float]) -> [Float] {
        let n = robotFrame
        let length = samples.count
        if length == 0 { return [] }
        let window = hann(n)
        // the note with a frame of silence either side, without making that copy
        let paddedSize = length + 2 * n
        var out = [Float](repeating: 0, count: paddedSize + n)
        var re = [Double](repeating: 0, count: n)
        var im = [Double](repeating: 0, count: n)
        var k = 0
        while k * robotHop + n <= paddedSize {
            let at = k * robotHop
            for i in 0..<n {
                let j = at + i - n
                re[i] = j >= 0 && j < length ? Double(samples[j]) * window[i] : 0
                im[i] = 0
            }
            fft(&re, &im, inverse: false)
            for i in 0..<n {
                re[i] = (re[i] * re[i] + im[i] * im[i]).squareRoot()       // how loud, and no longer when
                im[i] = 0
            }
            fft(&re, &im, inverse: true)
            // without phase the frame's energy sits at its start: turn it to the middle
            for i in 0..<n { out[at + i] = Float(Double(out[at + i]) + re[(i + n / 2) % n] * window[i]) }
            k += 1
        }
        // squared Hann windows a quarter apart add up to 1.5; then as loud as it came in
        var spoken = 0.0
        var made = 0.0
        for i in 0..<length {
            spoken += Double(samples[i]) * Double(samples[i])
            let v = Double(out[n + i]) / 1.5
            made += v * v
        }
        let gain = (spoken / Double(length) + 1e-12).squareRoot() / (made / Double(length) + 1e-12).squareRoot()
        var y = [Float](repeating: 0, count: length)
        for i in 0..<length { y[i] = Float(Double(out[n + i]) / 1.5 * gain) }
        return finish(y)
    }

    // MARK: the parts
    //
    // A two-minute note is 2.9 million samples. The long buffers are kept as floats and read as
    // doubles, and nothing is copied that can be read in place: the most a note costs is about
    // three times its own size, some 40 MB for the longest.

    /// Periodic Hann window: `w[i] + w[i + n/2] == 1`.
    static func hann(_ n: Int) -> [Double] {
        (0..<n).map { 0.5 - 0.5 * cos(2.0 * Double.pi * Double($0) / Double(n)) }
    }

    /// `x` made `stretch` times as long, its pitch untouched. The result is the first
    /// `round(x.count × stretch)` samples of the array handed back (it is a frame longer).
    static func wsola(_ x: [Float], stretch: Double) -> [Float] {
        let frame = 2 * hop
        let m = Int((Double(x.count) * stretch).rounded())
        var padded = x
        padded.append(contentsOf: [Float](repeating: 0, count: frame + radius + hop + 1))
        let window = hann(frame)
        var out = [Float](repeating: 0, count: m + frame)
        var pos = 0
        for i in 0..<frame { out[i] = Float(window[i] * Double(padded[i])) }
        var k = 1
        padded.withUnsafeBufferPointer { p in
            while k * hop < m {
                let target = Int((Double(k * hop) / stretch).rounded())
                // what would follow the last frame if nothing were being stretched
                let ref = pos + hop
                // where it is due is the incumbent: anything else has to be strictly more alike
                var best = 0
                var bestScore = likeness(p, ref, target)
                let lo = max(-radius, -target)
                let hi = min(radius, p.count - frame - target)
                let natural = ref - target
                if natural >= lo && natural <= hi {
                    // Those very samples are within reach, and nothing is more like them than
                    // they are: looking everywhere would only find them again. Most frames end here.
                    if natural != 0 && likeness(p, ref, ref) > bestScore { best = natural }
                } else if lo <= hi {
                    // They are out of reach: the frame has to come from somewhere else. Look everywhere.
                    // How loud a place is comes from the place before it, where one sample has
                    // left and one has come, and is not added up again for each.
                    var loudness = product(p, target + lo, target + lo)
                    for d in lo...hi {
                        let start = target + d
                        if d > lo {
                            let gone = Double(p[start - 1])
                            let come = Double(p[start + hop - 1])
                            loudness += come * come - gone * gone
                        }
                        if d == 0 { continue }
                        let score = product(p, ref, start) / (max(loudness, 0) + 1e-9).squareRoot()
                        if score > bestScore {
                            best = d
                            bestScore = score
                        }
                    }
                }
                pos = target + best
                let at = k * hop
                for i in 0..<frame { out[at + i] = Float(Double(out[at + i]) + window[i] * Double(p[pos + i])) }
                k += 1
            }
        }
        return out
    }

    /// How alike the `hop` samples at `a` and at `b` are: their correlation over the loudness
    /// of the second.
    private static func likeness(_ x: UnsafeBufferPointer<Float>, _ a: Int, _ b: Int) -> Double {
        product(x, a, b) / (product(x, b, b) + 1e-9).squareRoot()
    }

    /// The `hop` samples at `a` times those at `b`, added up. In four running sums and not
    /// one: an addition then does not wait for the one before it, and this is the loop the
    /// stretch spends its time in. (`hop` is a multiple of four.)
    private static func product(_ x: UnsafeBufferPointer<Float>, _ a: Int, _ b: Int) -> Double {
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var i = 0
        while i < hop {
            s0 += Double(x[a + i]) * Double(x[b + i])
            s1 += Double(x[a + i + 1]) * Double(x[b + i + 1])
            s2 += Double(x[a + i + 2]) * Double(x[b + i + 2])
            s3 += Double(x[a + i + 3]) * Double(x[b + i + 3])
            i += 4
        }
        return (s0 + s1) + (s2 + s3)
    }

    /// `y[i] = z(i × ratio)` for `count` samples, reading the first `length` samples of `z`: a
    /// windowed sinc, with the top of the band taken off first when it reads faster than it
    /// writes (or what is up there would fold back down).
    static func resample(_ z: [Float], length: Int, ratio: Double, count: Int) -> [Float] {
        let cutoff = min(1.0, 1.0 / ratio)
        let reach = Int((Double(taps) / cutoff).rounded(.up))
        // From one tap to the next the two angles below move by a fixed step. They are turned
        // by that step rather than asked for a sine and a cosine at every tap, which is most
        // of what this loop would otherwise cost.
        let sincCos = cos(Double.pi * cutoff)
        let sincSin = sin(Double.pi * cutoff)
        let windowCos = cos(Double.pi / Double(reach))
        let windowSin = sin(Double.pi / Double(reach))
        var y = [Float](repeating: 0, count: count)
        z.withUnsafeBufferPointer { src in
            for i in 0..<count {
                let centre = Double(i) * ratio
                let first = Int(centre.rounded(.down)) - reach + 1
                var t = centre - Double(first)    // how far the first tap is from the centre: just under `reach`
                var sincS = sin(Double.pi * cutoff * t)
                var sincC = cos(Double.pi * cutoff * t)
                var windowS = sin(Double.pi * t / Double(reach))
                var windowC = cos(Double.pi * t / Double(reach))
                var sum = 0.0
                var weight = 0.0
                for k in first..<(first + 2 * reach) {
                    if abs(t) < Double(reach) {
                        let a = Double.pi * cutoff * t
                        // right at the centre a turned sine has lost the digits a division needs
                        let sinc = abs(a) < 1e-4 ? 1.0 - a * a / 6.0 : sincS / a
                        let w = cutoff * sinc * (0.5 + 0.5 * windowC)
                        weight += w
                        if k >= 0 && k < length { sum += w * Double(src[k]) }
                    }
                    // the next tap is one sample further on: both angles back by their step
                    let s = sincS * sincCos - sincC * sincSin
                    sincC = sincC * sincCos + sincS * sincSin
                    sincS = s
                    let v = windowS * windowCos - windowC * windowSin
                    windowC = windowC * windowCos + windowS * windowSin
                    windowS = v
                    t -= 1.0
                }
                y[i] = weight != 0 ? Float(sum / weight) : 0
            }
        }
        return y
    }

    /// In-place radix-2 FFT; the length is a power of two.
    static func fft(_ re: inout [Double], _ im: inout [Double], inverse: Bool) {
        let n = re.count
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while j & bit != 0 {
                j ^= bit
                bit >>= 1
            }
            j ^= bit
            if i < j {
                re.swapAt(i, j)
                im.swapAt(i, j)
            }
        }
        var size = 2
        while size <= n {
            let angle = (inverse ? 2.0 : -2.0) * Double.pi / Double(size)
            let stepRe = cos(angle)
            let stepIm = sin(angle)
            var start = 0
            while start < n {
                var turnRe = 1.0
                var turnIm = 0.0
                for k in 0..<(size / 2) {
                    let a = start + k
                    let b = a + size / 2
                    let tr = re[b] * turnRe - im[b] * turnIm
                    let ti = re[b] * turnIm + im[b] * turnRe
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    let next = turnRe * stepRe - turnIm * stepIm
                    turnIm = turnRe * stepIm + turnIm * stepRe
                    turnRe = next
                }
                start += size
            }
            size *= 2
        }
        if inverse {
            for i in 0..<n {
                re[i] /= Double(n)
                im[i] /= Double(n)
            }
        }
    }

    /// Turned down just enough never to clip.
    private static func finish(_ y: [Float]) -> [Float] {
        var peak = 0.0
        for v in y { peak = max(peak, abs(Double(v))) }
        guard peak > ceiling else { return y }
        let gain = ceiling / peak
        return y.map { Float(Double($0) * gain) }
    }
}
