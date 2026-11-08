package com.pyrochat.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Port of `VoicePlayer` (`ios/PyroChat/Core/VoiceRecorder.swift:92-137`): plays one decrypted
 * voice note from memory; [toggle] on the playing id stops it; [progress] ticks every 100 ms
 * (line 111); [seek] clamps to 0…0.99 of the duration (line 122); playback end resets everything
 * (line 134-136). Plain `MediaPlayer`, no ExoPlayer.
 *
 * The bytes are fed through an in-memory [ByteArrayMediaDataSource]; only if the device's player
 * rejects that does a `cacheDir/vn-play-<uuid>.m4a` temp file get used, and it is deleted right
 * after `prepare()` (the player keeps its own file descriptor). Main-thread only.
 */
class VoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    /** 0…1 of the playing note. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var player: MediaPlayer? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val duration = p.duration
            if (duration > 0) _progress.value = (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** `toggle(id:data:)` */
    fun toggle(id: String, bytes: ByteArray) {
        if (_playingId.value == id) {
            stop()
            return
        }
        stop()
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setOnCompletionListener { stop() }
        p.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "playback error $what/$extra")
            stop(); true
        }
        try {
            try {
                p.setDataSource(ByteArrayMediaDataSource(bytes))
                p.prepare()
            } catch (e: IOException) {
                Log.i(TAG, "in-memory source rejected, using a temp file", e)
                p.reset()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                prepareFromTempFile(p, bytes)
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not play voice note", e)
            p.release(); return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not play voice note", e)
            p.release(); return
        }
        player = p
        _playingId.value = id
        _progress.value = 0f
        p.start()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    /** `seek(id:to:)` — scrub the playing note to a fraction of its duration. */
    fun seek(id: String, fraction: Float) {
        if (_playingId.value != id) return
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) return
        val target = (fraction.coerceIn(0f, 0.99f) * duration).toLong()
        p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _progress.value = fraction
    }

    /** `stop()` */
    fun stop() {
        handler.removeCallbacks(ticker)
        player?.let { p ->
            try {
                p.stop()
            } catch (_: IllegalStateException) {
                // never prepared
            }
            p.release()
        }
        player = null
        _playingId.value = null
        _progress.value = 0f
    }

    fun release() = stop()

    private fun prepareFromTempFile(p: MediaPlayer, bytes: ByteArray) {
        val tmp = File(appContext.cacheDir, "vn-play-${UUID.randomUUID()}.m4a")
        try {
            tmp.writeBytes(bytes)
            FileInputStream(tmp).use { p.setDataSource(it.fd) }
            p.prepare()
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "VoicePlayer"
        const val TICK_MS = 100L
    }
}
