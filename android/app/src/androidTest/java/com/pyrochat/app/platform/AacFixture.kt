package com.pyrochat.app.platform

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic test audio: a 440 Hz tone with a per-sample amplitude envelope, encoded with the
 * platform AAC-LC encoder at the iOS voice-note parameters (24 kHz mono 32 kbps, MPEG-4). The
 * emulator's microphone is unreliable, so recorder-independent tests use this.
 */
object AacFixture {
    const val SAMPLE_RATE = VoiceRecorder.SAMPLE_RATE
    const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC

    fun tone(context: Context, seconds: Double = 1.0, envelope: (Int) -> Float = { i -> if (i < SAMPLE_RATE / 2) 0.8f else 0.2f }): ByteArray {
        val frames = (seconds * SAMPLE_RATE).toInt()
        val pcm = ShortArray(frames) { i ->
            (sin(2.0 * PI * 440.0 * i / SAMPLE_RATE) * envelope(i) * Short.MAX_VALUE).toInt().toShort()
        }
        return encode(context, pcm)
    }

    fun encode(context: Context, pcm: ShortArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, VoiceRecorder.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, VoiceRecorder.BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val out = File(context.cacheDir, "fixture-${UUID.randomUUID()}.m4a")
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val ptsUs = fed * 1_000_000L / SAMPLE_RATE
                        if (fed >= pcm.size) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(1024, pcm.size - fed, buf.capacity() / 2)
                            buf.clear()
                            buf.order(ByteOrder.nativeOrder())
                            for (i in 0 until n) buf.putShort(pcm[fed + i])
                            codec.queueInputBuffer(inIdx, 0, n * 2, ptsUs, 0)
                            fed += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIdx >= 0 -> {
                        val buf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxing) { "output before format" }
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
            if (muxing) muxer.stop()
            muxer.release()
        }
        return out.readBytes().also { out.delete() }
    }
}
