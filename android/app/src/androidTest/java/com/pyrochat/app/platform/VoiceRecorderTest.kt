package com.pyrochat.app.platform

import android.Manifest
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceRecorderTest {
    @get:Rule
    val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun tempVoiceFiles() = context.cacheDir.listFiles()?.filter { it.name.startsWith("vn-") }.orEmpty()

    @Test fun recordsAacLcInMp4AtTheIosParameters() {
        val rec = VoiceRecorder(context)
        assertTrue("recorder must start (mic permission granted by the rule)", onMain { rec.begin() })
        assertTrue(VoiceRecorder.isCapturing)
        assertTrue(onMain { rec.isRecording.value })
        assertEquals(1, tempVoiceFiles().size)
        Thread.sleep(1500)
        assertTrue(onMain { rec.elapsed.value } >= 1.0)
        val out = onMain { rec.finish() }
        assertNotNull("recording of 1.5 s must be returned", out); out!!
        assertTrue("duration=${out.durationSeconds}", out.durationSeconds in 1.2..4.0)
        assertFalse(VoiceRecorder.isCapturing)
        assertFalse(onMain { rec.isRecording.value })
        assertTrue("temp file must be gone after finish()", tempVoiceFiles().isEmpty())

        // container: ISO BMFF 'ftyp' box at offset 4
        assertEquals("ftyp", String(out.bytes, 4, 4, Charsets.US_ASCII))
        // codec/format via the platform demuxer (the emulator mic may be silent: assert format, not amplitude)
        val extractor = MediaExtractor()
        extractor.setDataSource(ByteArrayMediaDataSource(out.bytes))
        assertEquals(1, extractor.trackCount)
        val f = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, f.getString(MediaFormat.KEY_MIME))
        assertEquals(VoiceRecorder.SAMPLE_RATE, f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(VoiceRecorder.CHANNELS, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        extractor.release()
        // and it decodes: 32 bars come out (silence → all at the floor, a signal → a unit peak)
        val bars = Waveform.bars(out.bytes)
        assertEquals(32, bars.size)
        assertTrue(bars.all { it in Waveform.FLOOR..1f })
    }

    @Test fun shorterThanHalfASecondIsDiscarded() {
        val rec = VoiceRecorder(context)
        assertTrue(onMain { rec.begin() })
        Thread.sleep(150)
        assertNull(onMain { rec.finish() })
        assertTrue(tempVoiceFiles().isEmpty())
        assertFalse(VoiceRecorder.isCapturing)
    }

    @Test fun cancelLeavesNothingBehind() {
        val rec = VoiceRecorder(context)
        assertTrue(onMain { rec.begin() })
        Thread.sleep(300)
        onMain { rec.cancel() }
        assertTrue(tempVoiceFiles().isEmpty())
        assertFalse(VoiceRecorder.isCapturing)
        assertFalse(onMain { rec.isRecording.value })
        assertNull(onMain { rec.finish() })   // nothing to finish
    }

    @Test fun cannotBeginTwice() {
        val rec = VoiceRecorder(context)
        assertTrue(onMain { rec.begin() })
        assertFalse(onMain { rec.begin() })
        onMain { rec.cancel() }
    }
}
