package com.pyrochat.app.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voices a voice note can be sent in (docs/VOICE-CHANGER.md). [wire] is what the setting is
 * stored as, the same word on iOS.
 */
enum class VoiceEffect(val wire: String) {
    OFF("off"),
    DEEP("deep"),
    HIGH("high"),
    ROBOT("robot"),
    ;

    companion object {
        /**
         * A stored word back to its voice. Nothing stored is [OFF]. A word this build does not
         * know (a voice added by a newer one, after a restore) is [DEEP] and never [OFF]: whoever
         * chose a voice is not sent out in their own because this build cannot spell it.
         */
        fun of(wire: String?): VoiceEffect = if (wire == null) OFF else entries.firstOrNull { it.wire == wire } ?: DEEP
    }
}

/**
 * The signal processing behind the voice changer: mono samples in -1…1 at [SAMPLE_RATE], in and
 * out, the same length. No Android in here, so it runs in a plain unit test, and the loops are the
 * ones in iOS `VoiceDsp.swift`: a note changed on either phone is changed the same way.
 *
 *  - [pitch]: the same words at the same speed, the voice `factor` times as high. The signal is
 *    first made `factor` times as long without touching its pitch (WSOLA: overlapping 20 ms
 *    frames, each taken from where it best continues the one before), then read back `factor`
 *    times as fast, which brings the length back and moves the pitch, formants and all.
 *  - [robot]: every 43 ms frame keeps how loud each frequency is and loses its phase, so every
 *    frame becomes the same kind of pulse and the voice has one flat pitch, [SAMPLE_RATE] / 256
 *    = 93.75 Hz, whatever the speaker's was.
 */
object VoiceDsp {
    const val SAMPLE_RATE = 24_000

    /** WSOLA: frames are 2 × [HOP] long and half overlap; a frame may be taken up to [RADIUS] either side of where it is due. */
    private const val HOP = 240
    private const val RADIUS = 240

    /** The robot's frame and step: its pitch is SAMPLE_RATE / ROBOT_HOP. */
    private const val ROBOT_FRAME = 1024
    private const val ROBOT_HOP = 256

    /** How far the resampler looks either side of a sample, at unity. */
    private const val TAPS = 8

    /** Loud enough, short of clipping. */
    private const val CEILING = 0.98

    /** The factor a note is shifted by: somewhere in the voice's range, where [pick] is 0…1. A different spot for every note. */
    fun factor(effect: VoiceEffect, pick: Double): Double = when (effect) {
        VoiceEffect.DEEP -> 0.74 + 0.06 * pick.coerceIn(0.0, 1.0)
        VoiceEffect.HIGH -> 1.30 + 0.10 * pick.coerceIn(0.0, 1.0)
        else -> 1.0
    }

    /** [samples] in the voice [effect]; [pick] (0…1) chooses the exact shift within that voice's range. */
    fun apply(samples: FloatArray, effect: VoiceEffect, pick: Double): FloatArray = when (effect) {
        VoiceEffect.OFF -> samples.copyOf()
        VoiceEffect.DEEP, VoiceEffect.HIGH -> pitch(samples, factor(effect, pick))
        VoiceEffect.ROBOT -> robot(samples)
    }

    fun pitch(samples: FloatArray, factor: Double): FloatArray {
        require(factor in 0.5..2.0) { "factor out of range" }
        if (samples.size < 2 * HOP) return samples.copyOf() // less than one frame: nothing to work with
        val stretched = wsola(samples, factor)
        val length = (samples.size * factor).roundToInt()
        return finish(resample(stretched, length, factor, samples.size))
    }

    fun robot(samples: FloatArray): FloatArray {
        val n = ROBOT_FRAME
        val length = samples.size
        if (length == 0) return FloatArray(0)
        val window = hann(n)
        // the note with a frame of silence either side, without making that copy: `padded(j)`
        val paddedSize = length + 2 * n
        val out = FloatArray(paddedSize + n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var k = 0
        while (k * ROBOT_HOP + n <= paddedSize) {
            val at = k * ROBOT_HOP
            for (i in 0 until n) {
                val j = at + i - n
                re[i] = if (j in 0 until length) samples[j] * window[i] else 0.0
                im[i] = 0.0
            }
            fft(re, im, inverse = false)
            for (i in 0 until n) {
                re[i] = sqrt(re[i] * re[i] + im[i] * im[i]) // how loud, and no longer when
                im[i] = 0.0
            }
            fft(re, im, inverse = true)
            // without phase the frame's energy sits at its start: turn it to the middle
            for (i in 0 until n) out[at + i] = (out[at + i] + re[(i + n / 2) % n] * window[i]).toFloat()
            k += 1
        }
        // squared Hann windows a quarter apart add up to 1.5; then as loud as it came in
        var spoken = 0.0
        var made = 0.0
        for (i in 0 until length) {
            spoken += samples[i].toDouble() * samples[i]
            val v = out[n + i] / 1.5
            made += v * v
        }
        val gain = sqrt(spoken / length + 1e-12) / sqrt(made / length + 1e-12)
        val y = FloatArray(length) { (out[n + it] / 1.5 * gain).toFloat() }
        return finish(y)
    }

    // ---------------------------------------------------------------- the parts
    //
    // A two-minute note is 2.9 million samples. The long buffers are kept as floats and read as
    // doubles, and nothing is copied that can be read in place: the most a note costs is about
    // three times its own size, some 40 MB for the longest.

    /** Periodic Hann window: `w[i] + w[i + n/2] == 1`. */
    internal fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }

    /**
     * [x] made [stretch] times as long, its pitch untouched. The result is the first
     * `round(x.size × stretch)` samples of the array handed back (it is a frame longer).
     */
    internal fun wsola(x: FloatArray, stretch: Double): FloatArray {
        val frame = 2 * HOP
        val m = (x.size * stretch).roundToInt()
        val padded = FloatArray(x.size + frame + RADIUS + HOP + 1)
        x.copyInto(padded)
        val window = hann(frame)
        val out = FloatArray(m + frame)
        var pos = 0
        for (i in 0 until frame) out[i] = (window[i] * padded[i]).toFloat()
        var k = 1
        while (k * HOP < m) {
            val target = (k * HOP / stretch).roundToInt()
            // what would follow the last frame if nothing were being stretched
            val ref = pos + HOP
            // where it is due is the incumbent: anything else has to be strictly more alike
            var best = 0
            var bestScore = likeness(padded, ref, target)
            val lo = maxOf(-RADIUS, -target)
            val hi = minOf(RADIUS, padded.size - frame - target)
            val natural = ref - target
            if (natural in lo..hi) {
                // Those very samples are within reach, and nothing is more like them than they
                // are: looking everywhere would only find them again. Most frames end here.
                if (natural != 0 && likeness(padded, ref, ref) > bestScore) best = natural
            } else {
                // They are out of reach: the frame has to come from somewhere else. Look everywhere.
                // How loud a place is comes from the place before it, where one sample has left
                // and one has come, and is not added up again for each.
                var loudness = product(padded, target + lo, target + lo)
                for (d in lo..hi) {
                    val start = target + d
                    if (d > lo) {
                        val gone = padded[start - 1].toDouble()
                        val come = padded[start + HOP - 1].toDouble()
                        loudness += come * come - gone * gone
                    }
                    if (d == 0) continue
                    val score = product(padded, ref, start) / sqrt(maxOf(loudness, 0.0) + 1e-9)
                    if (score > bestScore) {
                        best = d
                        bestScore = score
                    }
                }
            }
            pos = target + best
            val at = k * HOP
            for (i in 0 until frame) out[at + i] = (out[at + i] + window[i] * padded[pos + i]).toFloat()
            k += 1
        }
        return out
    }

    /** How alike the [HOP] samples at [a] and at [b] are: their correlation over the loudness of the second. */
    private fun likeness(x: FloatArray, a: Int, b: Int): Double = product(x, a, b) / sqrt(product(x, b, b) + 1e-9)

    /**
     * The [HOP] samples at [a] times those at [b], added up. In four running sums and not one:
     * an addition then does not wait for the one before it, and this is the loop the stretch
     * spends its time in. ([HOP] is a multiple of four.)
     */
    private fun product(x: FloatArray, a: Int, b: Int): Double {
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var i = 0
        while (i < HOP) {
            s0 += x[a + i].toDouble() * x[b + i]
            s1 += x[a + i + 1].toDouble() * x[b + i + 1]
            s2 += x[a + i + 2].toDouble() * x[b + i + 2]
            s3 += x[a + i + 3].toDouble() * x[b + i + 3]
            i += 4
        }
        return (s0 + s1) + (s2 + s3)
    }

    /**
     * `y[i] = z(i × ratio)` for [n] samples, reading the first [length] samples of [z]: a windowed
     * sinc, with the top of the band taken off first when it reads faster than it writes (or what
     * is up there would fold back down).
     */
    internal fun resample(z: FloatArray, length: Int, ratio: Double, n: Int): FloatArray {
        val cutoff = min(1.0, 1.0 / ratio)
        val reach = ceil(TAPS / cutoff).toInt()
        // From one tap to the next the two angles below move by a fixed step. They are turned by
        // that step rather than asked for a sine and a cosine at every tap, which is most of
        // what this loop would otherwise cost.
        val sincCos = cos(PI * cutoff)
        val sincSin = sin(PI * cutoff)
        val windowCos = cos(PI / reach)
        val windowSin = sin(PI / reach)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val centre = i * ratio
            val first = floor(centre).toInt() - reach + 1
            var t = centre - first // how far the first tap is from the centre: just under `reach`
            var sincS = sin(PI * cutoff * t)
            var sincC = cos(PI * cutoff * t)
            var windowS = sin(PI * t / reach)
            var windowC = cos(PI * t / reach)
            var sum = 0.0
            var weight = 0.0
            for (k in first until first + 2 * reach) {
                if (abs(t) < reach) {
                    val a = PI * cutoff * t
                    // right at the centre a turned sine has lost the digits a division needs
                    val sinc = if (abs(a) < 1e-4) 1.0 - a * a / 6.0 else sincS / a
                    val w = cutoff * sinc * (0.5 + 0.5 * windowC)
                    weight += w
                    if (k >= 0 && k < length) sum += w * z[k]
                }
                // the next tap is one sample further on: both angles back by their step
                val s = sincS * sincCos - sincC * sincSin
                sincC = sincC * sincCos + sincS * sincSin
                sincS = s
                val v = windowS * windowCos - windowC * windowSin
                windowC = windowC * windowCos + windowS * windowSin
                windowS = v
                t -= 1.0
            }
            y[i] = if (weight != 0.0) (sum / weight).toFloat() else 0f
        }
        return y
    }

    /** In-place radix-2 FFT; the length is a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val q = im[i]; im[i] = im[j]; im[j] = q
            }
        }
        var size = 2
        while (size <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / size
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            var start = 0
            while (start < n) {
                var turnRe = 1.0
                var turnIm = 0.0
                for (k in 0 until size / 2) {
                    val a = start + k
                    val b = a + size / 2
                    val tr = re[b] * turnRe - im[b] * turnIm
                    val ti = re[b] * turnIm + im[b] * turnRe
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = turnRe * stepRe - turnIm * stepIm
                    turnIm = turnRe * stepIm + turnIm * stepRe
                    turnRe = next
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    /** Turned down, in place, just enough never to clip. */
    private fun finish(y: FloatArray): FloatArray {
        var peak = 0.0
        for (v in y) peak = maxOf(peak, abs(v.toDouble()))
        if (peak > CEILING) {
            val gain = CEILING / peak
            for (i in y.indices) y[i] = (y[i] * gain).toFloat()
        }
        return y
    }
}
