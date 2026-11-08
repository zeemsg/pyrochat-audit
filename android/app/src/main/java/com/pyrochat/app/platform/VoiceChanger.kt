package com.pyrochat.app.platform

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

/**
 * Sends a voice note in another voice (docs/VOICE-CHANGER.md). The recorder's AAC is decoded,
 * changed by [VoiceDsp] and encoded again exactly as it was recorded (AAC-LC, 24 kHz, mono,
 * 32 kbps, MPEG-4), so the other side cannot tell a changed note from any other and needs no
 * update to play it. iOS `VoiceChanger`.
 *
 * It fails closed: when anything goes wrong it throws [Failed] and hands nothing back. A note
 * that was meant to be disguised is never sent as it was spoken.
 *
 * The changed audio touches the disk once, because `MediaMuxer` writes to a file: a `vn-*` file
 * in the cache directory, deleted as soon as its bytes are read and swept at launch with the
 * recorder's own (`StorePaths.sweepPlaintextTmp`). Blocking; call it off the main thread.
 */
object VoiceChanger {
    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val random = SecureRandom()

    /**
     * [m4a] in the voice [effect]. [pick] (0…1) is where in that voice's range this note lands;
     * left alone it is drawn afresh, so no two notes are shifted by quite the same amount.
     */
    @Throws(Failed::class)
    fun change(context: Context, m4a: ByteArray, effect: VoiceEffect, pick: Double = random.nextDouble()): ByteArray {
        if (effect == VoiceEffect.OFF) return m4a
        try {
            val rate = sampleRate(m4a)
            if (rate != VoiceDsp.SAMPLE_RATE) throw Failed("a voice note at $rate Hz")
            val samples = Waveform.decodeChannel0(m4a) ?: throw Failed("no audio in the recording")
            if (samples.isEmpty()) throw Failed("no audio in the recording")
            return encode(context, VoiceDsp.apply(samples, effect, pick))
        } catch (e: Failed) {
            throw e
        } catch (e: Exception) {
            throw Failed("the voice could not be changed", e)
        } catch (e: OutOfMemoryError) {
            // A long note needs a few tens of megabytes for a moment. On a phone that cannot
            // spare them the note is refused like any other that cannot be changed.
            throw Failed("no room to change a note this long", e)
        }
    }

    private fun sampleRate(m4a: ByteArray): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(m4a))
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            throw Failed("no audio in the recording")
        } finally {
            extractor.release()
        }
    }

    /** Mono floats in -1…1 at [VoiceDsp.SAMPLE_RATE] to the recorder's own format. */
    internal fun encode(context: Context, samples: FloatArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, VoiceRecorder.SAMPLE_RATE, VoiceRecorder.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, VoiceRecorder.BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val out = File(context.cacheDir, "vn-${UUID.randomUUID()}.m4a")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxing = false
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var idleRounds = 0
            while (true) {
                var moved = false
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        moved = true
                        val buf = codec.getInputBuffer(inIndex) ?: throw IOException("no input buffer")
                        val ptsUs = fed * 1_000_000L / VoiceRecorder.SAMPLE_RATE
                        if (fed >= samples.size) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(1024, samples.size - fed, buf.capacity() / 2)
                            buf.clear()
                            buf.order(ByteOrder.nativeOrder())
                            for (i in 0 until n) buf.putShort(pcm16(samples[fed + i]))
                            codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                            fed += n
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        moved = true
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        moved = true
                        val buf = codec.getOutputBuffer(outIndex) ?: throw IOException("no output buffer")
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (!muxing) throw IOException("the encoder wrote audio before saying what it was")
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                // an encoder that neither takes nor gives for ten seconds is not going to
                if (moved) idleRounds = 0 else if (++idleRounds > MAX_IDLE_ROUNDS) throw IOException("the encoder stalled before the end")
            }
            codec.stop()
            muxer.stop()
            muxing = false
            return out.readBytes()
        } finally {
            runCatching { codec?.release() }
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            out.delete()
        }
    }

    /** A voice as the composer shows it while recording: `[OFF]`, `[DEEP]`, `[HIGH]`, `[ROBOT]`. */
    fun label(effect: VoiceEffect): String = "[${effect.wire.uppercase()}]"

    /** What the composer says when a voice could not be changed. The note is gone by then. */
    const val REFUSAL = "ERR: voice not changed, nothing sent"

    private fun pcm16(v: Float): Short = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ROUNDS = 500
}
