package com.pyrochat.app.platform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Port of `Waveform` (`ios/PyroChat/Core/VoiceRecorder.swift:142-186`): 32 RMS bars in 0…1 for a
 * voice note's AAC bytes. [fromSamples] is the exact arithmetic of lines 164-175 (Float
 * accumulation in the same order, `per = max(1, n / 32)`, `sqrt(sum / count)`, peak-normalised
 * with a **0.12 floor** so silence still draws); [placeholder] is lines 183-185 (all 0.3).
 *
 * PCM comes from `MediaExtractor` + `MediaCodec` reading an in-memory source — channel 0 only,
 * like `floatChannelData?[0]`, trimmed of the AAC encoder priming/padding exactly the way
 * `AVAudioFile` trims it ([GaplessTrim]). The AAC decoder is the platform's (iOS uses CoreAudio's),
 * so the decoded floats differ only in the last bits: `WaveformInteropTest` holds both platforms
 * to the iOS-computed bars of `android/fixtures/voice/` within 1e-3 per bar, in both encoding
 * directions.
 */
object Waveform {
    private const val TAG = "Waveform"
    const val BAR_COUNT = 32
    const val FLOOR = 0.12f
    const val PLACEHOLDER = 0.3f

    private val cache by lazy { LruCache<String, FloatArray>(512) }

    /** `Waveform.bars(for:data:)` — cached per message id. */
    fun bars(id: String, m4a: ByteArray): FloatArray {
        cache.get(id)?.let { return it }
        val out = bars(m4a)
        cache.put(id, out)
        return out
    }

    /** Decode + [fromSamples]; the placeholder when the bytes don't decode. */
    fun bars(m4a: ByteArray, count: Int = BAR_COUNT): FloatArray {
        val samples = try {
            decodeChannel0(m4a)
        } catch (e: IOException) {
            Log.w(TAG, "voice note undecodable", e); null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "voice note undecodable", e); null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "voice note undecodable", e); null
        } ?: return placeholder(count)
        if (samples.isEmpty()) return placeholder(count)
        return fromSamples(samples, samples.size, count)
    }

    fun placeholder(count: Int = BAR_COUNT): FloatArray = FloatArray(count) { PLACEHOLDER }

    /**
     * `VoiceRecorder.swift:164-175` verbatim: `n` frames of channel 0, `per = max(1, n / barCount)`,
     * bar `b` = RMS of `[b·per, min(b·per + per, n))` (0 when empty), then `max(0.12, v / peak)`
     * with `peak = max(maxBar, 0.001)`.
     */
    fun fromSamples(samples: FloatArray, n: Int = samples.size, count: Int = BAR_COUNT): FloatArray {
        val per = maxOf(1, n / count)
        val out = FloatArray(count)
        for (b in 0 until count) {
            val lo = b * per
            val hi = minOf(lo + per, n)
            if (lo >= hi) { out[b] = 0f; continue }
            var sum = 0f
            for (i in lo until hi) sum += samples[i] * samples[i]
            out[b] = sqrt(sum / (hi - lo).toFloat())
        }
        var maxBar = Float.NEGATIVE_INFINITY
        for (v in out) if (v > maxBar) maxBar = v
        val peak = maxOf(if (out.isEmpty()) 1f else maxBar, 0.001f)
        for (b in out.indices) out[b] = maxOf(FLOOR, out[b] / peak)
        return out
    }

    /** Channel-0 PCM as floats in −1…1 (16-bit → /32768, float output passed through); null if there is no audio track. */
    @Throws(IOException::class)
    internal fun decodeChannel0(m4a: ByteArray): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(m4a))
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            val trackFormat = format ?: return null
            extractor.selectTrack(track)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val trim = GaplessTrim.of(trackFormat, mime)
            // The gapless keys are consumed HERE, not by the codec: whether a decoder applies them
            // itself is implementation-defined (ACodec / CCodec / OEM), and applying them twice
            // would eat real audio. Stripped from the format, every codec emits every frame.
            trackFormat.removeKey(GaplessTrim.KEY_ENCODER_DELAY)
            trackFormat.removeKey(GaplessTrim.KEY_ENCODER_PADDING)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                return trim.apply(drain(codec, extractor, trackFormat))
            } finally {
                try { codec.stop() } catch (_: IllegalStateException) { /* never started */ }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(codec: MediaCodec, extractor: MediaExtractor, trackFormat: MediaFormat): FloatArray {
        var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val acc = FloatAccumulator()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleRounds = 0
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex) ?: throw IllegalStateException("no input buffer")
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    idleRounds = 0
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (i in 0 until frames) acc.add(fb.get(i * channels))
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (i in 0 until frames) acc.add(sb.get(i * channels) / 32768f)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                else -> {
                    if (inputDone && ++idleRounds > MAX_IDLE_ROUNDS) throw IOException("decoder stalled before end of stream")
                }
            }
        }
        return acc.toArray()
    }

    private class FloatAccumulator {
        private var data = FloatArray(24_000 * 4)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    /**
     * Mirror of what CoreAudio trims when `AVAudioFile` reads the same bytes on iOS
     * (`kAudioFilePropertyPacketTableInfo`), so the PCM the bar math sees starts and ends on the
     * same frame on both platforms:
     *
     *  - **Gapless metadata present** — an `iTunSMPB` ilst atom (what AVAudioRecorder / CoreAudio
     *    write; a real iOS voice note has this and NO `elst`) or a single-entry `elst`:
     *    `MediaExtractor` surfaces both as `"encoder-delay"` / `"encoder-padding"` (frames);
     *    trim exactly those. `ios-tone-1s.m4a`: 2112 + 512 of 26 × 1024 → 24 000 frames.
     *  - **No metadata** — Android's `MediaMuxer` writes neither: CoreAudio then ASSUMES the
     *    standard AAC-LC encoder delay of [AAC_DEFAULT_PRIMING] frames and no padding
     *    (`tone-1s-24k-mono-32k.m4a`: 26 × 1024 − 2112 = 24 512 valid frames on iOS). Without this
     *    rule Android kept ~2 bars of leading silence that iOS never shows.
     *
     * Non-AAC audio never reaches this path (voice notes are always AAC-LC); it is left untrimmed.
     */
    internal class GaplessTrim(val delay: Int, val padding: Int) {
        fun apply(pcm: FloatArray): FloatArray {
            val from = delay.coerceIn(0, pcm.size)
            val to = (pcm.size - padding).coerceIn(from, pcm.size)
            return if (from == 0 && to == pcm.size) pcm else pcm.copyOfRange(from, to)
        }

        companion object {
            /** `MediaFormat.KEY_ENCODER_DELAY` / `KEY_ENCODER_PADDING` (API 30 constants; the extractor sets the strings on every level). */
            const val KEY_ENCODER_DELAY = "encoder-delay"
            const val KEY_ENCODER_PADDING = "encoder-padding"
            /** CoreAudio's assumed AAC-LC priming when the container carries no gapless metadata (2 × 1024 + 64). */
            const val AAC_DEFAULT_PRIMING = 2112

            fun of(format: MediaFormat, mime: String): GaplessTrim {
                val hasDelay = format.containsKey(KEY_ENCODER_DELAY)
                val hasPadding = format.containsKey(KEY_ENCODER_PADDING)
                if (hasDelay || hasPadding) {
                    return GaplessTrim(
                        if (hasDelay) format.getInteger(KEY_ENCODER_DELAY) else 0,
                        if (hasPadding) format.getInteger(KEY_ENCODER_PADDING) else 0,
                    )
                }
                return if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) GaplessTrim(AAC_DEFAULT_PRIMING, 0) else GaplessTrim(0, 0)
            }
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ROUNDS = 500
}
