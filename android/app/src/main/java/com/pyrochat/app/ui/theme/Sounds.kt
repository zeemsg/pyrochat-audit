package com.pyrochat.app.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.pyrochat.app.R
import com.pyrochat.app.ui.Tone

/**
 * `SoundPlayer` (Core/SoundPlayer.swift) — the tx/rx terminal blips from the
 * iOS bundle (`tx.wav` 60 ms, `rx.wav` 130 ms), fire-and-forget on the
 * notification stream so the ringer/DND setting mutes them like the iOS
 * mute switch does. rx is coalesced to one blip per 300 ms.
 */
class SoundPlayer(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids: Map<Tone, Int> = mapOf(
        Tone.tx to pool.load(context, R.raw.tx, 1),
        Tone.rx to pool.load(context, R.raw.rx, 1),
    )
    private var lastRx = 0L

    /** Gate = `settings.soundsOn` (the caller also only plays while foregrounded). */
    fun play(tone: Tone, soundsOn: Boolean) {
        if (!soundsOn) return
        if (tone == Tone.rx) {
            val now = System.currentTimeMillis()
            if (now - lastRx < 300) return
            lastRx = now
        }
        val id = ids[tone] ?: return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
