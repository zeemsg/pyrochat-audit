package com.pyrochat.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Port of `VoiceRecorder` (`ios/PyroChat/Core/VoiceRecorder.swift:8-88`): AAC-LC in an MPEG-4
 * container, **24 000 Hz, mono, 32 kbps** (lines 31-36), hard cap **120 s** (line 12 /
 * `record(forDuration:)` line 40), notes shorter than **0.5 s** discarded (line 66). The audio
 * source is `VOICE_COMMUNICATION` with `AudioManager.MODE_IN_COMMUNICATION` while capturing — the
 * platform's VoIP tuning (AEC/AGC/NS), i.e. the `.voiceChat` session mode iOS asks for (line 26).
 *
 * The recording exists only as `cacheDir/vn-<uuid>.m4a` between start and [finish]/[cancel] and is
 * deleted the moment the bytes are in memory (iOS line 65). [elapsed] ticks every 200 ms like the
 * iOS timer (line 44); [level] is the meter for the live UI — `MediaRecorder.maxAmplitude`
 * normalised to 0…1 at the same cadence. Call everything from the main thread.
 */
class VoiceRecorder(context: Context) {
    class Recording(val bytes: ByteArray, val durationSeconds: Double)

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _isRecording = MutableStateFlow(false)
    /** iOS `isRecording` — false once the 120 s cap auto-stopped the recorder (bytes still collectable). */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsed = MutableStateFlow(0.0)
    /** Seconds since start (iOS `elapsed`). */
    val elapsed: StateFlow<Double> = _elapsed.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Live meter 0…1 (peak amplitude in the last tick). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var stoppedAt: Long? = null
    private var previousMode = AudioManager.MODE_NORMAL

    private val ticker = object : Runnable {
        override fun run() {
            val rec = recorder ?: return
            _elapsed.value = secondsSinceStart()
            if (_isRecording.value) {
                _level.value = try {
                    (rec.maxAmplitude / 32767f).coerceIn(0f, 1f)
                } catch (_: IllegalStateException) {
                    0f
                }
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    /** `begin()`: false when the mic permission is missing or the recorder cannot start. */
    fun begin(): Boolean {
        if (recorder != null) return false
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val out = File(appContext.cacheDir, "vn-${UUID.randomUUID()}.m4a")
        val rec = newRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setAudioChannels(CHANNELS)
            rec.setMaxDuration(MAX_SECONDS * 1000)
            rec.setOutputFile(out.absolutePath)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
            }
            rec.setOnErrorListener { _, _, _ -> onMaxDuration() }
            rec.prepare()
            rec.start()
        } catch (e: IOException) {
            Log.w(TAG, "recorder failed to start", e)
            rec.release(); out.delete(); return false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "recorder failed to start", e)
            rec.release(); out.delete(); return false
        } catch (e: RuntimeException) {
            Log.w(TAG, "recorder failed to start", e)
            rec.release(); out.delete(); return false
        }
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        recorder = rec
        file = out
        startedAt = SystemClock.elapsedRealtime()
        stoppedAt = null
        isCapturing = true
        _isRecording.value = true
        _elapsed.value = 0.0
        _level.value = 0f
        handler.postDelayed(ticker, TICK_MS)
        return true
    }

    /** `finish()`: stop, return the bytes (temp file deleted), or null if shorter than 0.5 s. */
    fun finish(): Recording? {
        val rec = recorder ?: return null
        val out = file ?: return null
        handler.removeCallbacks(ticker)
        val duration = secondsSinceStart()
        stopAndRelease(rec)
        recorder = null
        file = null
        try {
            if (duration < MIN_SECONDS) return null
            val bytes = try {
                out.readBytes()
            } catch (_: IOException) {
                return null
            }
            return Recording(bytes, duration)
        } finally {
            out.delete()
        }
    }

    /** `cancel()`: discard everything. */
    fun cancel() {
        handler.removeCallbacks(ticker)
        recorder?.let(::stopAndRelease)
        recorder = null
        file?.delete()
        file = null
    }

    private fun onMaxDuration() {
        // MediaRecorder already stopped itself (iOS: `rec.isRecording == false`); keep the file for finish()
        if (stoppedAt == null) stoppedAt = SystemClock.elapsedRealtime()
        _isRecording.value = false
        _elapsed.value = secondsSinceStart()
        _level.value = 0f
        isCapturing = false
    }

    private fun stopAndRelease(rec: MediaRecorder) {
        try {
            rec.stop()
        } catch (_: RuntimeException) {
            // already stopped by the duration cap, or nothing valid was recorded
        }
        rec.release()
        // release the VoIP mode so post-send blips aren't ducked (iOS: setActive(false))
        audioManager.mode = previousMode
        isCapturing = false
        _isRecording.value = false
        _level.value = 0f
    }

    private fun secondsSinceStart(): Double = ((stoppedAt ?: SystemClock.elapsedRealtime()) - startedAt) / 1000.0

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 24_000
        const val BIT_RATE = 32_000
        const val CHANNELS = 1
        const val MAX_SECONDS = 120
        const val MIN_SECONDS = 0.5
        const val TICK_MS = 200L

        /** True while the mic is live — `SoundPlayer` suppresses blips (iOS `isCapturing`). */
        @Volatile
        var isCapturing: Boolean = false
            private set
    }
}
