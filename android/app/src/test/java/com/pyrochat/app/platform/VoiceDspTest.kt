package com.pyrochat.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice changer's arithmetic (docs/VOICE-CHANGER.md), with no codec and no phone: what goes
 * in is a made-up voice, what comes out is measured. The same cases, against the same golden
 * numbers, are in iOS `VoiceDspTests`.
 */
class VoiceDspTest {
    private val rate = VoiceDsp.SAMPLE_RATE

    /** Something voice-like: a fundamental with eight harmonics, breathing in loudness. */
    private fun voice(f0: Double, seconds: Double = 1.0): FloatArray = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        var v = 0.0
        for (k in 1..8) v += sin(2.0 * PI * k * f0 * t) / k
        (0.25 * v * (0.7 + 0.3 * sin(2.0 * PI * 2.0 * t))).toFloat()
    }

    /** The period, in samples, at which the middle of [x] repeats best. */
    private fun period(x: FloatArray, from: Int, to: Int): Int {
        val a = x.size / 4
        val n = x.size / 2
        var best = from
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in from..to) {
            var dot = 0.0
            for (i in a until a + n - lag) dot += x[i].toDouble() * x[i + lag]
            val score = dot / (n - lag)
            if (score > bestScore) {
                best = lag
                bestScore = score
            }
        }
        return best
    }

    /**
     * The pitch of [x] in Hz, looked for within a fifth either side of [near]: a made-up voice
     * repeats just as well every two periods, and that is not the pitch.
     */
    private fun heard(x: FloatArray, near: Double): Double =
        rate.toDouble() / period(x, (rate / near * 0.8).toInt(), (rate / near * 1.2).toInt() + 1)

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test fun deepAndHighMoveThePitchByTheFactorAndKeepTheLength() {
        val spoken = voice(150.0)
        for (factor in doubleArrayOf(0.74, 0.77, 0.80, 1.30, 1.35, 1.40)) {
            val changed = VoiceDsp.pitch(spoken, factor)
            assertEquals("the note is as long as it was", spoken.size, changed.size)
            assertEquals("150 Hz times $factor", 150.0 * factor, heard(changed, 150.0 * factor), 150.0 * factor * 0.025)
            assertEquals("and about as loud", rms(spoken), rms(changed), rms(spoken) * 0.12)
            assertTrue(changed.all { it.isFinite() && abs(it) <= 0.981f })
        }
    }

    @Test fun theRobotHasOnePitchWhateverTheSpeakersWas() {
        for (f0 in doubleArrayOf(110.0, 150.0, 220.0)) {
            val spoken = voice(f0)
            val changed = VoiceDsp.robot(spoken)
            assertEquals(spoken.size, changed.size)
            // 24 000 / 256 = 93.75 Hz: it repeats every 256 samples, and not at the speaker's own period
            assertEquals("a $f0 Hz voice", 256, period(changed, 200, 300))
            assertEquals(rms(spoken), rms(changed), rms(spoken) * 0.2)
            assertTrue(changed.all { it.isFinite() && abs(it) <= 0.981f })
        }
    }

    @Test fun silenceStaysSilent() {
        val silence = FloatArray(rate)
        assertTrue(VoiceDsp.pitch(silence, 0.77).all { it == 0f })
        assertTrue(VoiceDsp.pitch(silence, 1.35).all { it == 0f })
        assertTrue(VoiceDsp.robot(silence).all { it == 0f })
    }

    @Test fun aLoudNoteIsTurnedDownRatherThanClipped() {
        val loud = FloatArray(rate) { i -> if ((i / 80) % 2 == 0) 0.999f else -0.999f } // a square wave at full scale
        for (changed in listOf(VoiceDsp.pitch(loud, 0.77), VoiceDsp.pitch(loud, 1.35), VoiceDsp.robot(loud))) {
            assertTrue(changed.all { it.isFinite() && abs(it) <= 0.981f })
        }
    }

    @Test fun notesTooShortToWorkOnAreHandled() {
        assertEquals(0, VoiceDsp.pitch(FloatArray(0), 0.77).size)
        assertEquals(0, VoiceDsp.robot(FloatArray(0)).size)
        val tiny = FloatArray(100) { 0.1f }
        assertArrayEquals("less than one frame goes back as it came", tiny, VoiceDsp.pitch(tiny, 1.35), 0f)
        assertEquals(100, VoiceDsp.robot(tiny).size)
        val oneFrame = voice(150.0, 0.03)
        assertEquals(oneFrame.size, VoiceDsp.pitch(oneFrame, 0.77).size)
    }

    @Test fun everyNoteLandsSomewhereInItsVoicesRange() {
        assertEquals(0.74, VoiceDsp.factor(VoiceEffect.DEEP, 0.0), 1e-12)
        assertEquals(0.80, VoiceDsp.factor(VoiceEffect.DEEP, 1.0), 1e-12)
        assertEquals(1.30, VoiceDsp.factor(VoiceEffect.HIGH, 0.0), 1e-12)
        assertEquals(1.40, VoiceDsp.factor(VoiceEffect.HIGH, 1.0), 1e-12)
        assertEquals(0.77, VoiceDsp.factor(VoiceEffect.DEEP, 0.5), 1e-12)
        // a pick out of range is brought back in, never a shift outside the voice
        assertEquals(0.74, VoiceDsp.factor(VoiceEffect.DEEP, -3.0), 1e-12)
        assertEquals(1.40, VoiceDsp.factor(VoiceEffect.HIGH, 7.0), 1e-12)
        assertEquals(1.0, VoiceDsp.factor(VoiceEffect.OFF, 0.5), 0.0)
        assertEquals(1.0, VoiceDsp.factor(VoiceEffect.ROBOT, 0.5), 0.0)
    }

    @Test fun offIsTheNoteAsItWasAndEveryStoredWordIsAVoice() {
        val spoken = voice(150.0, 0.2)
        assertArrayEquals(spoken, VoiceDsp.apply(spoken, VoiceEffect.OFF, 0.5), 0f)
        assertEquals(VoiceEffect.OFF, VoiceEffect.of(null))
        assertEquals(VoiceEffect.OFF, VoiceEffect.of("off"))
        assertEquals(VoiceEffect.DEEP, VoiceEffect.of("helium")) // a word from a newer build: some disguise, never none
        assertEquals(VoiceEffect.DEEP, VoiceEffect.of("deep"))
        assertEquals(VoiceEffect.HIGH, VoiceEffect.of("high"))
        assertEquals(VoiceEffect.ROBOT, VoiceEffect.of("robot"))
        assertEquals(listOf("off", "deep", "high", "robot"), VoiceEffect.entries.map { it.wire })
    }

    @Test fun theSameNoteAndTheSamePickGiveTheSameResult() {
        val spoken = voice(180.0, 0.5)
        assertArrayEquals(VoiceDsp.apply(spoken, VoiceEffect.DEEP, 0.3), VoiceDsp.apply(spoken, VoiceEffect.DEEP, 0.3), 0f)
        assertArrayEquals(VoiceDsp.apply(spoken, VoiceEffect.ROBOT, 0.3), VoiceDsp.apply(spoken, VoiceEffect.ROBOT, 0.9), 0f)
        // and a different pick is a different shift
        assertTrue(!VoiceDsp.apply(spoken, VoiceEffect.DEEP, 0.0).contentEquals(VoiceDsp.apply(spoken, VoiceEffect.DEEP, 1.0)))
    }

    // ---------------------------------------------------------------- the parts

    @Test fun theTransformUndoesItself() {
        val n = 1024
        val re = DoubleArray(n) { sin(2.0 * PI * 5 * it / n) + 0.3 * sin(2.0 * PI * 37 * it / n) + 0.2 }
        val im = DoubleArray(n)
        val was = re.copyOf()
        VoiceDsp.fft(re, im, inverse = false)
        // a sine of 5 cycles sits in bins 5 and n - 5, half its length each
        assertEquals(n / 2.0, sqrt(re[5] * re[5] + im[5] * im[5]), n * 0.02)
        VoiceDsp.fft(re, im, inverse = true)
        for (i in 0 until n) {
            assertEquals(was[i], re[i], 1e-9)
            assertEquals(0.0, im[i], 1e-9)
        }
    }

    @Test fun readingAtTheSameSpeedChangesNothingAndASteadyLevelStaysSteady() {
        val z = FloatArray(4000) { sin(2.0 * PI * 300 * it / rate).toFloat() }
        val same = VoiceDsp.resample(z, z.size, 1.0, z.size)
        for (i in z.indices) assertEquals(z[i], same[i], 1e-6f)
        val level = FloatArray(4000) { 0.5f }
        for (ratio in doubleArrayOf(0.77, 1.35)) {
            val out = VoiceDsp.resample(level, level.size, ratio, 2000)
            for (i in 100 until 1900) assertEquals(0.5f, out[i], 1e-6f)
        }
        // and it reads no further than it is told the signal goes
        val short = VoiceDsp.resample(level, 1000, 1.0, 2000)
        assertEquals(0.5f, short[500], 1e-6f)
        assertEquals(0f, short[1500], 0f)
    }

    @Test fun stretchingByOneLeavesTheMiddleAlone() {
        val x = FloatArray(rate / 2) { (sin(2.0 * PI * 200 * it / rate) * 0.5).toFloat() }
        val same = VoiceDsp.wsola(x, 1.0)
        for (i in 480 until x.size - 960) assertEquals(x[i], same[i], 1e-6f)
        // the stretched signal is the first `size × stretch` samples of what comes back, which is one frame longer
        assertEquals(x.size + 480, same.size)
        assertEquals(Math.round(x.size * 1.35).toInt() + 480, VoiceDsp.wsola(x, 1.35).size)
        assertEquals(Math.round(x.size * 0.77).toInt() + 480, VoiceDsp.wsola(x, 0.77).size)
    }

    /**
     * Most frames are not looked for: the samples that follow the last frame are taken when they
     * are within reach, because nothing is more like them than they are. That has to give exactly
     * what looking everywhere gives, which is written out the plain way below.
     */
    @Test fun takingTheSamplesThatFollowGivesWhatLookingEverywhereGives() {
        val x = goldenInput()
        for (stretch in doubleArrayOf(0.74, 0.77, 0.80, 1.30, 1.35, 1.40)) {
            assertArrayEquals("stretched by $stretch", everywhere(x, stretch), VoiceDsp.wsola(x, stretch), 0f)
        }
    }

    /** The stretch as [VoiceDsp.wsola] makes it, with every frame looked for everywhere within reach. */
    private fun everywhere(x: FloatArray, stretch: Double): FloatArray {
        val hop = 240
        val radius = 240
        val frame = 2 * hop
        val m = Math.round(x.size * stretch).toInt()
        val padded = x.copyOf(x.size + frame + radius + hop + 1)
        val window = VoiceDsp.hann(frame)
        val out = FloatArray(m + frame)
        fun likeness(a: Int, b: Int): Double {
            var dot = 0.0
            var energy = 0.0
            for (i in 0 until hop) {
                val v = padded[b + i].toDouble()
                dot += padded[a + i] * v
                energy += v * v
            }
            return dot / sqrt(energy + 1e-9)
        }
        var pos = 0
        for (i in 0 until frame) out[i] = (window[i] * padded[i]).toFloat()
        var k = 1
        while (k * hop < m) {
            val target = Math.round(k * hop / stretch).toInt()
            val ref = pos + hop
            var best = 0
            var bestScore = likeness(ref, target)
            for (d in maxOf(-radius, -target)..radius) {
                if (d == 0 || target + d + frame > padded.size) continue
                val score = likeness(ref, target + d)
                if (score > bestScore) {
                    best = d
                    bestScore = score
                }
            }
            pos = target + best
            for (i in 0 until frame) out[k * hop + i] = (out[k * hop + i] + window[i] * padded[pos + i]).toFloat()
            k += 1
        }
        return out
    }

    /**
     * The filter turns its two angles from one tap to the next instead of asking for a sine at
     * every tap. It has to give what the plain formula gives, also where a sample falls almost
     * exactly on one of the note's own: there a turned sine has the fewest digits left.
     */
    @Test fun theFilterGivesWhatThePlainFormulaGives() {
        var seed = 99L
        val z = FloatArray(40_000) {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            (((seed shr 8) and 0xFFFFL).toDouble() / 65535.0 - 0.5).toFloat() / 2
        }
        for (ratio in doubleArrayOf(0.74, 0.77, 1.30, 1.35, 1.40)) {
            val n = (z.size / ratio).toInt() - 40
            val y = VoiceDsp.resample(z, z.size, ratio, n)
            var almostOn = 0
            for (i in 0 until n) {
                val off = i * ratio - Math.floor(i * ratio)
                val almost = off != 0.0 && (off < 1e-9 || off > 1 - 1e-9)
                if (almost) almostOn += 1
                if (almost || i % 97 == 0) assertEquals("read back at $ratio, sample $i", plain(z, ratio, i), y[i].toDouble(), 2e-7)
            }
            if (ratio == 1.35) assertTrue("samples that fall almost on one of the note's own: $almostOn", almostOn > 20)
        }
    }

    /** One sample of the read-back, with a sine and a cosine asked for at every tap. */
    private fun plain(z: FloatArray, ratio: Double, i: Int): Double {
        val cutoff = minOf(1.0, 1.0 / ratio)
        val reach = Math.ceil(8 / cutoff).toInt()
        val centre = i * ratio
        val first = Math.floor(centre).toInt() - reach + 1
        var sum = 0.0
        var weight = 0.0
        for (k in first until first + 2 * reach) {
            val t = centre - k
            if (abs(t) >= reach) continue
            val a = PI * cutoff * t
            val w = cutoff * (if (abs(a) < 1e-12) 1.0 else sin(a) / a) * (0.5 + 0.5 * Math.cos(PI * t / reach))
            weight += w
            if (k in z.indices) sum += w * z[k]
        }
        return sum / weight
    }

    // ---------------------------------------------------------------- one result on both platforms

    /** A voice that glides from 110 to 170 Hz over half a second, with a little noise: nothing in it repeats exactly. */
    private fun goldenInput(): FloatArray {
        var seed = 12345L
        return FloatArray(12_000) { i ->
            val t = i.toDouble() / rate
            val phase = 2.0 * PI * (110.0 * t + 60.0 * t * t)
            var v = 0.0
            for (k in 1..8) v += sin(k * phase) / k
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            val noise = ((seed shr 8) and 0xFFFFL).toDouble() / 65535.0 - 0.5
            (0.25 * v * (0.6 + 0.4 * sin(2.0 * PI * 3.0 * t)) + 0.02 * noise).toFloat()
        }
    }

    private val goldenFile = File("../fixtures/voice-changer/golden.txt")

    private fun sampled(x: FloatArray): List<Float> = x.indices.step(40).map { x[it] }

    /**
     * Every fortieth sample of the three voices for [goldenInput], as iOS and Android must both
     * produce them (android/fixtures/voice-changer/README.md). Run with `PYRO_VOICE_GOLDEN=write`
     * to write the file anew after a deliberate change to the arithmetic.
     */
    @Test fun bothPlatformsProduceTheSameVoices() {
        val input = goldenInput()
        val now = linkedMapOf(
            "deep" to sampled(VoiceDsp.apply(input, VoiceEffect.DEEP, 0.5)),
            "high" to sampled(VoiceDsp.apply(input, VoiceEffect.HIGH, 0.5)),
            "robot" to sampled(VoiceDsp.apply(input, VoiceEffect.ROBOT, 0.5)),
        )
        if (System.getenv("PYRO_VOICE_GOLDEN") == "write") {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(now.entries.joinToString("\n", postfix = "\n") { (name, values) -> name + " " + values.joinToString(" ") { "%.7f".format(java.util.Locale.ROOT, it) } })
        }
        assumeTrue("no golden file at ${goldenFile.absolutePath}", goldenFile.isFile)
        val golden = goldenFile.readLines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.trim().split(" ")
            parts[0] to parts.drop(1).map { it.toFloat() }
        }
        assertEquals(setOf("deep", "high", "robot"), golden.keys)
        for ((name, values) in now) {
            val want = golden.getValue(name)
            assertEquals("$name: how many samples", want.size, values.size)
            for (i in values.indices) assertEquals("$name, sample ${i * 40}", want[i], values[i], 2e-4f)
        }
    }
}
