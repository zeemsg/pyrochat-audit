package com.pyrochat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * The voice changer end to end on real AAC (docs/VOICE-CHANGER.md): a note in the recorder's own
 * format goes in, and what comes out is decoded again and measured. iOS's twin is
 * `VoiceChangerTests`, in the simulator.
 */
@RunWith(AndroidJUnit4::class)
class VoiceChangerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rate = VoiceDsp.SAMPLE_RATE

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** Something voice-like: a fundamental with eight harmonics, breathing in loudness. */
    private fun voice(f0: Double, seconds: Double = 1.0): FloatArray = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        var v = 0.0
        for (k in 1..8) v += sin(2.0 * PI * k * f0 * t) / k
        (0.25 * v * (0.7 + 0.3 * sin(2.0 * PI * 2.0 * t))).toFloat()
    }

    /** [samples] as the recorder would have saved them. */
    private fun note(samples: FloatArray): ByteArray = VoiceChanger.encode(context, samples)

    private fun decode(m4a: ByteArray): FloatArray = Waveform.decodeChannel0(m4a)!!

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

    private fun leftovers(): Set<String> = context.cacheDir.list().orEmpty().filter { it.startsWith("vn-") }.toSet()

    /** The note is what the recorder makes: AAC at 24 kHz that the waveform draws and the player takes, as long as it was spoken. */
    private fun assertIsAVoiceNote(m4a: ByteArray, seconds: Double) {
        val samples = decode(m4a)
        assertEquals("as long as it was spoken", seconds, samples.size.toDouble() / rate, seconds * 0.06)
        val bars = Waveform.bars(m4a)
        assertEquals(Waveform.BAR_COUNT, bars.size)
        assertFalse("a real waveform, not the placeholder", bars.all { it == Waveform.PLACEHOLDER })
        // and the player takes it: it starts on these bytes
        val player = onMain { VoicePlayer(context) }
        try {
            onMain { player.toggle("changed", m4a) }
            assertEquals("changed", onMain { player.playingId.value })
        } finally {
            onMain { player.release() }
        }
    }

    @Test fun aDeepAndAHighNoteAreTheSameWordsInAnotherVoice() {
        val spoken = note(voice(150.0))
        assertIsAVoiceNote(spoken, 1.0)
        for ((effect, factor) in listOf(VoiceEffect.DEEP to 0.77, VoiceEffect.HIGH to 1.35)) {
            val changed = VoiceChanger.change(context, spoken, effect, pick = 0.5)
            assertFalse(changed.contentEquals(spoken))
            assertIsAVoiceNote(changed, 1.0)
            assertEquals("$effect: 150 Hz times $factor", 150.0 * factor, heard(decode(changed), 150.0 * factor), 150.0 * factor * 0.04)
        }
    }

    @Test fun aRobotNoteHasTheRobotsPitchWhoeverSpoke() {
        for (f0 in doubleArrayOf(120.0, 210.0)) {
            val changed = VoiceChanger.change(context, note(voice(f0)), VoiceEffect.ROBOT)
            assertIsAVoiceNote(changed, 1.0)
            assertEquals("a $f0 Hz voice", 256, period(decode(changed), 200, 300))
        }
    }

    @Test fun offHandsBackTheVeryNote() {
        val spoken = note(voice(150.0, 0.6))
        assertArrayEquals(spoken, VoiceChanger.change(context, spoken, VoiceEffect.OFF))
    }

    @Test fun everyNoteIsShiftedByItsOwnAmount() {
        val spoken = note(voice(150.0))
        val a = heard(decode(VoiceChanger.change(context, spoken, VoiceEffect.DEEP, pick = 0.0)), 150.0 * 0.74)
        val b = heard(decode(VoiceChanger.change(context, spoken, VoiceEffect.DEEP, pick = 1.0)), 150.0 * 0.80)
        assertEquals(150.0 * 0.74, a, 150.0 * 0.74 * 0.04)
        assertEquals(150.0 * 0.80, b, 150.0 * 0.80 * 0.04)
        assertTrue("two notes in the same voice are not shifted alike", b - a > 4)
    }

    @Test fun whatCannotBeChangedIsRefusedAndNeverHandedBackAsItWas() {
        val before = leftovers()
        for (bad in listOf(ByteArray(0), ByteArray(4096) { (it * 13).toByte() })) {
            for (effect in listOf(VoiceEffect.DEEP, VoiceEffect.HIGH, VoiceEffect.ROBOT)) {
                try {
                    val out = VoiceChanger.change(context, bad, effect)
                    fail("$effect: handed back ${out.size} bytes for a note that could not be changed")
                } catch (_: VoiceChanger.Failed) {
                    // the only way out
                }
            }
        }
        assertEquals("and nothing of it stays in the cache", before, leftovers())
    }

    @Test fun nothingOfTheNoteStaysInTheCache() {
        val before = leftovers()
        val spoken = note(voice(150.0, 0.8))
        for (effect in listOf(VoiceEffect.DEEP, VoiceEffect.HIGH, VoiceEffect.ROBOT)) VoiceChanger.change(context, spoken, effect)
        assertEquals(before, leftovers())
    }
}
