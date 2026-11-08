package com.pyrochat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WaveformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private var cached: ByteArray? = null
        @Synchronized fun fixture(context: Context): ByteArray = cached ?: AacFixture.tone(context).also { cached = it }
    }

    @Test fun thirtyTwoBarsInRangeWithUnitPeak() {
        val bars = Waveform.bars(fixture(context))
        assertEquals(Waveform.BAR_COUNT, bars.size)
        assertTrue(bars.all { it in Waveform.FLOOR..1f })
        assertEquals(1f, bars.max(), 0f)
    }

    @Test fun deterministicAcrossDecodes() {
        val m4a = fixture(context)
        val a = Waveform.bars(m4a)
        val b = Waveform.bars(m4a)
        assertArrayEquals(a, b, 0f)
        assertArrayEquals(a, Waveform.bars("msg-1", m4a), 0f)
        assertArrayEquals(a, Waveform.bars("msg-1", ByteArray(0)), 0f)   // cached by id, bytes not re-read
    }

    @Test fun loudHalfIsLouderThanQuietHalf() {
        val bars = Waveform.bars(fixture(context))
        val loud = bars.take(14).average()      // skip the edges: encoder priming + envelope step
        val quiet = bars.drop(18).average()
        assertTrue("loud=$loud quiet=$quiet", loud > quiet * 2.5)
        // The last bucket is mostly encoder padding after the tone's end once the priming frames are
        // trimmed like CoreAudio does (iOS draws the same 0.12 there — fixtures/voice/*.ios-bars.json).
        val quietBars = bars.drop(18).dropLast(1)
        assertTrue("quiet bars must sit above the 0.12 floor: $quietBars", quietBars.all { it > Waveform.FLOOR })   // 0.2/0.8 = 0.25 ≫ 0.12
    }

    @Test fun decodedPcmIsChannel0AtTheFileLength() {
        val samples = Waveform.decodeChannel0(fixture(context))
        assertNotNull(samples); samples!!
        // 1 s at 24 kHz; the decoder may keep or trim the AAC priming/padding (≤ 2048+1024 frames)
        assertTrue("frames=${samples.size}", samples.size in 22_000..27_500)
        assertTrue(samples.all { it in -1f..1f })
    }

    @Test fun undecodableBytesGiveThePlaceholder() {
        assertArrayEquals(Waveform.placeholder(), Waveform.bars(ByteArray(0)), 0f)
        assertArrayEquals(Waveform.placeholder(), Waveform.bars(ByteArray(4096) { (it * 13).toByte() }), 0f)
        assertArrayEquals(Waveform.placeholder(), Waveform.bars("not audio at all".toByteArray()), 0f)
    }

    /**
     * Cross-platform handshake: drops the fixture and the Android-computed bars where `adb pull`
     * can reach them (`android/fixtures/voice/`), so the iOS side can run
     * `Waveform.bars(for:data:)` on the same file and diff.
     */
    @Test fun exportsFixtureAndBarsForIos() {
        val m4a = fixture(context)
        val bars = Waveform.bars(m4a)
        val dir = File(context.getExternalFilesDir(null), "waveform-fixture").apply { mkdirs() }
        File(dir, "tone-1s-24k-mono-32k.m4a").writeBytes(m4a)
        File(dir, "tone-1s-24k-mono-32k.android-bars.json").writeText(
            buildString {
                append("{\n  \"source\": \"AacFixture.tone: 440 Hz, 0.8 amplitude for 0.5 s then 0.2, platform AAC-LC 24 kHz mono 32 kbps\",\n")
                append("  \"decoder\": \"MediaExtractor+MediaCodec ${android.os.Build.VERSION.SDK_INT}\",\n")
                append("  \"barCount\": ${bars.size},\n  \"bars\": [")
                append(bars.joinToString(", ") { "%.6f".format(it) })
                append("]\n}\n")
            },
        )
        assertTrue(File(dir, "tone-1s-24k-mono-32k.m4a").length() > 1000)
    }
}
