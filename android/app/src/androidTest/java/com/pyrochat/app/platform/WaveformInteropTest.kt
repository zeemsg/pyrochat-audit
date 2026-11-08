package com.pyrochat.app.platform

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * Android half of the cross-platform waveform proof (`android/fixtures/voice/README.md`):
 * [Waveform.bars] must equal the REAL iOS `Waveform.bars(for:data:)` output for the same bytes in
 * both encoding directions — the Android-encoded `tone-1s-24k-mono-32k.m4a` and the iOS-encoded
 * `ios-tone-1s.m4a`, each with the bars CoreAudio computed for it (`*.ios-bars.json`, written by
 * `ios/Tests/WaveformFixtureTests.swift`).
 *
 * The fixtures are read from the app's external files ROOT (`getExternalFilesDir(null)`) — push
 * them first:
 *
 *   adb push android/fixtures/voice/. /sdcard/Android/data/com.pyrochat.app/files/
 *
 * (the root, not a subdirectory: a directory created by `adb shell mkdir` belongs to `shell` with
 * mode 770 and the app cannot enter it, while pushed FILES are 666 inside the app-owned `files/`).
 * Skipped, not failed, when absent: `connectedAndroidTest` uninstalls the app and wipes that
 * directory after every run. Each comparison writes `<name>.android-bars.json` next to the inputs
 * and logs the per-bar report under the tag `WaveformInteropTest`.
 */
@RunWith(AndroidJUnit4::class)
class WaveformInteropTest {
    companion object {
        private const val TAG = "WaveformInteropTest"
        const val ANDROID_FIXTURE = "tone-1s-24k-mono-32k"
        const val IOS_FIXTURE = "ios-tone-1s"

        /**
         * Both sides run the identical Float arithmetic over the decoded PCM, so the only remaining
         * source of difference is the AAC decoder itself: Android's emits 16-bit PCM (quantisation
         * ≤ 1/65536 per sample) and CoreAudio's Float32 output differs in the last bits. A bar is an
         * RMS over ≥ 750 samples normalised by the peak bar, so those per-sample errors average down
         * to ~1e-5…1e-4; 1e-3 is an order of magnitude above that and still only 0.02 pt at the 18 pt
         * bar height (`ChatView.swift:421`) — invisible. A missed priming/padding trim, by contrast,
         * shifts every bucket and shows up as 0.1…0.9.
         */
        const val TOLERANCE = 1e-3f
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File get() = checkNotNull(context.getExternalFilesDir(null)) { "no external files dir" }

    private class IosBars(val source: String, val sha256: String, val frames: Int, val sampleRate: Int, val bars: FloatArray)

    private fun fixture(name: String): Pair<ByteArray, IosBars> {
        val m4a = File(dir, "$name.m4a")
        val json = File(dir, "$name.ios-bars.json")
        assumeTrue(
            "missing ${m4a.name} / ${json.name} in $dir — adb push android/fixtures/voice/. /sdcard/Android/data/${context.packageName}/files/",
            m4a.isFile && json.isFile,
        )
        val bytes = m4a.readBytes()
        val o = JSONObject(json.readText())
        val arr = o.getJSONArray("bars")
        val ios = IosBars(
            o.optString("source"), o.getString("sha256"), o.getInt("frames"), o.getInt("sampleRate"),
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() },
        )
        assertEquals("$name.m4a is not the file the iOS bars were computed for", ios.sha256, sha256(bytes))
        assertEquals(Waveform.BAR_COUNT, ios.bars.size)
        assertEquals(VoiceRecorder.SAMPLE_RATE, ios.sampleRate)
        return bytes to ios
    }

    @Test fun androidEncodedFixtureMatchesIosBars() = compare(ANDROID_FIXTURE)

    @Test fun iosEncodedFixtureMatchesIosBars() = compare(IOS_FIXTURE)

    /** File-level playback contract: a real iOS voice note is extractable and decodable here. */
    @Test fun iosEncodedFixtureIsPlayableOnAndroid() {
        val (bytes, _) = fixture(IOS_FIXTURE)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            assertEquals("exactly one audio track", 1, audio.size)
            val f = audio[0]
            Log.i(TAG, "$IOS_FIXTURE track format: $f")
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, f.getString(MediaFormat.KEY_MIME))   // "audio/mp4a-latm"
            assertEquals(VoiceRecorder.SAMPLE_RATE, f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(VoiceRecorder.CHANNELS, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(1_000_000.0, f.getLong(MediaFormat.KEY_DURATION).toDouble(), 150_000.0)
            assertNotNull("no AAC decoder for $f", MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(f))
        } finally {
            extractor.release()
        }
    }

    private fun compare(name: String) {
        val (bytes, ios) = fixture(name)
        val trackFormat = audioTrackFormat(bytes)
        Log.i(TAG, "$name track format: $trackFormat")
        val samples = Waveform.decodeChannel0(bytes)
        assertNotNull("no audio track decoded", samples); samples!!
        val bars = Waveform.bars(bytes)
        val deltas = FloatArray(bars.size) { abs(bars[it] - ios.bars[it]) }
        val worst = deltas.indices.maxByOrNull { deltas[it] } ?: 0
        val report = buildString {
            appendLine("$name: android frames=${samples.size} ios frames=${ios.frames} max|delta|=${deltas[worst]} at bar $worst")
            for (b in bars.indices) {
                appendLine("  bar %2d  android=%.6f  ios=%.6f  delta=%.6f".format(Locale.ROOT, b, bars[b], ios.bars[b], deltas[b]))
            }
        }
        Log.i(TAG, report)
        writeAndroidBars(name, ios, bytes, samples.size, bars, deltas, trackFormat)
        dumpPcm(name, samples)   // diagnostics: `<name>.android-pcm.f32le`, same layout as the iOS `*.ios-pcm.f32le`
        assertEquals("$name: decoded frame count differs from AVAudioFile (priming/padding trim)\n$report", ios.frames, samples.size)
        assertTrue("$name: max |android - ios| = ${deltas[worst]} at bar $worst exceeds $TOLERANCE\n$report", deltas[worst] <= TOLERANCE)
    }

    private fun audioTrackFormat(bytes: ByteArray): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            return (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } finally {
            extractor.release()
        }
    }

    /** Same shape as the iOS `*.ios-bars.json`, plus the per-bar deltas — pulled into `android/fixtures/voice/`. */
    private fun writeAndroidBars(
        name: String, ios: IosBars, bytes: ByteArray, frames: Int, bars: FloatArray, deltas: FloatArray, trackFormat: MediaFormat,
    ) {
        val codec = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(trackFormat) ?: "?"
        val text = buildString {
            appendLine("{")
            appendLine("  \"source\": ${JSONObject.quote(ios.source)},")
            appendLine("  \"decoder\": ${JSONObject.quote("MediaExtractor+MediaCodec API ${Build.VERSION.SDK_INT}, $codec")},")
            appendLine("  \"trackFormat\": ${JSONObject.quote(trackFormat.toString())},")
            appendLine("  \"sha256\": ${JSONObject.quote(sha256(bytes))},")
            appendLine("  \"sampleRate\": ${VoiceRecorder.SAMPLE_RATE},")
            appendLine("  \"frames\": $frames,")
            appendLine("  \"barCount\": ${bars.size},")
            appendLine("  \"bars\": [${bars.joinToString(", ") { it.toString() }}],")
            appendLine("  \"iosFrames\": ${ios.frames},")
            appendLine("  \"maxDeltaVsIos\": ${deltas.max()},")
            appendLine("  \"deltasVsIos\": [${deltas.joinToString(", ") { it.toString() }}]")
            appendLine("}")
        }
        File(dir, "$name.android-bars.json").writeText(text)
    }

    private fun dumpPcm(name: String, samples: FloatArray) {
        val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) buf.putFloat(v)
        File(dir, "$name.android-pcm.f32le").writeBytes(buf.array())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
}
