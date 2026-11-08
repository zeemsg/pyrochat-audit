package com.pyrochat.app.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Port of `AutoLock` (`ios/PyroChat/App/PyroChatApp.swift:70-80`) and the setting decode
 * (`Models.swift:227-233`): pure decisions, no clock or UI coupling.
 */
object AutoLock {
    const val DEFAULT_MINUTES = 5

    /** `AppSettings.autoLockAfterMinutes`: nil → 5 (secure default), 0 → null (off), m → m. */
    fun timeoutMinutes(autoLockMinutes: Int?): Int? = when (autoLockMinutes) {
        null -> DEFAULT_MINUTES
        0 -> null
        else -> autoLockMinutes
    }

    /**
     * `AutoLock.shouldLock`: false without a background stamp or with the lock off; a negative
     * elapsed (wall clock moved back while away) fails CLOSED; else `elapsed ≥ minutes·60`.
     */
    fun shouldLock(backgroundedAtMillis: Long?, timeoutMinutes: Int?, nowMillis: Long): Boolean {
        if (backgroundedAtMillis == null || timeoutMinutes == null) return false
        val elapsed = nowMillis - backgroundedAtMillis
        if (elapsed < 0) return true
        return elapsed >= timeoutMinutes.toLong() * 60_000L
    }
}

/**
 * `AppModel.noteScenePhase` (`PyroChatApp.swift:140-171`): `.background` stamps the wall clock
 * ONCE (RAM only — a killed process relaunches into the unlock prompt anyway), `.inactive` never
 * arms, `.active` evaluates and clears. On Android the whole-app equivalents are the activity's
 * `onStop` (background) and `onStart` (active); `onPause` without `onStop` (a dialog, the
 * app-switcher peek) is `.inactive` and is ignored. Uses the wall clock like iOS `Date()` so the
 * clock-moved-back case locks.
 */
class AutoLockTimer(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile
    var backgroundedAt: Long? = null
        private set

    /** `.background` */
    fun noteBackground() {
        if (backgroundedAt == null) backgroundedAt = clock()
    }

    /** `.active` — true when the caller must `lock()`; the stamp is cleared either way. */
    fun noteForeground(timeoutMinutes: Int?): Boolean {
        val bg = backgroundedAt
        backgroundedAt = null
        return AutoLock.shouldLock(bg, timeoutMinutes, clock())
    }

    fun clear() { backgroundedAt = null }

    /**
     * Attach to the activity's (or `ProcessLifecycleOwner`'s) lifecycle: `timeoutMinutes` reads the
     * live setting through [AutoLock.timeoutMinutes]; `onLock` is `AppModel.lock()`.
     */
    fun observer(timeoutMinutes: () -> Int?, onLock: () -> Unit): DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = noteBackground()
        override fun onStart(owner: LifecycleOwner) {
            if (noteForeground(timeoutMinutes())) onLock()
        }
    }
}
