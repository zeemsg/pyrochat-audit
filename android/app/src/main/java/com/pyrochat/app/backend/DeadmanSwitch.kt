package com.pyrochat.app.backend

import android.content.Context
import android.content.SharedPreferences

/**
 * Port of `Core/DeadmanSwitch.swift`: if the app is not OPENED for N days, the next launch wipes
 * everything — the same shredder as the duress PIN. "Opened" = any presence (unlock, foreground,
 * background); every one re-arms the deadline, so an actively used device can never wipe itself.
 * The wipe fires ONLY pre-auth (no live session, no store in RAM that a later flush could
 * resurrect).
 *
 * The deadline must be readable BEFORE the store unlocks, so it lives outside the encrypted
 * blob: iOS `UserDefaults["pyro.deadline"]` → plain `SharedPreferences` here (one datum for
 * forensics: a future date, deleted whenever the switch is off, fired, or ANY wipe path runs).
 */
class DeadmanSwitch(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** The armed deadline in epoch millis, or null. */
    val deadline: Long?
        get() = prefs.getLong(KEY, 0L).takeIf { it > 0L }

    /** The interval an armed deadline was set with, so any presence can re-arm it honestly. */
    private val intervalDays: Int?
        get() = prefs.getInt(KEY_DAYS, 0).takeIf { it > 0 }

    /**
     * `arm(days:)` — the EXPLICIT setting, from the account that owns it: the `[cfg]` picker
     * changing. `null`/0 disarms, because the person in this account just said off.
     */
    fun arm(days: Int?) {
        if (days == null || days <= 0) {
            disarm()
            return
        }
        prefs.edit().putLong(KEY, clock() + days.toLong() * DAY_MS).putInt(KEY_DAYS, days).apply()
    }

    /**
     * Presence — an unlock, a foreground, a background. "Opened" is any of those, and every one
     * re-arms, so an actively used device can never wipe itself.
     *
     * docs/DECOY-ACCOUNT.md §4/§5: an account with NO dead-man of its own must not clear one
     * another account set. Opening a decoy is opening the app, so an existing deadline is pushed
     * FORWARD by the interval its owner chose — never dropped. Without this, unlocking the decoy
     * silently switched off the one mechanism whose entire purpose is to fire when the phone is
     * taken, by exactly the session a person is made to open.
     */
    fun notePresence(days: Int?) {
        if (days != null && days > 0) {
            arm(days)
            return
        }
        val next = presenceDeadline(deadline, intervalDays, clock()) ?: return
        prefs.edit().putLong(KEY, next).apply()
    }

    fun disarm() {
        prefs.edit().remove(KEY).remove(KEY_DAYS).apply()
    }

    /**
     * `fireIfExpired()` — call ONLY pre-auth. Returns true when the deadline had passed; the
     * caller then runs the obliterate + push detach. The deadline is cleared either way once fired.
     */
    fun fireIfExpired(): Boolean {
        if (!shouldWipe(deadline, clock())) return false
        disarm()
        return true
    }

    companion object {
        const val PREFS = "pyro"
        const val KEY = "pyro.deadline"

        /** The interval behind [KEY], so presence in an account that has no dead-man can re-arm it. */
        const val KEY_DAYS = "pyro.deadline.days"
        const val DAY_MS = 86_400_000L

        /** Pure decision (`DeadmanSwitch.shouldWipe`): wipe iff a deadline exists and is past. */
        fun shouldWipe(deadline: Long?, now: Long): Boolean = deadline != null && now >= deadline

        /**
         * Pure decision behind [notePresence] for a session with NO dead-man of its own
         * (docs/DECOY-ACCOUNT.md §4/§5). Returns the new deadline, or null to change nothing.
         *
         * Opening a decoy is opening the app, so an armed deadline is pushed FORWARD by the
         * interval its owner chose — never dropped. An account that never set one has no standing
         * to clear one, which is the whole bug: the mechanism whose purpose is to fire when the
         * phone is taken was being switched off by exactly the session a person is made to open.
         */
        fun presenceDeadline(deadline: Long?, intervalDays: Int?, now: Long): Long? {
            if (deadline == null) return null                       // nothing armed: nothing to preserve
            if (intervalDays == null || intervalDays <= 0) return null  // no interval on record: leave it be
            return now + intervalDays.toLong() * DAY_MS
        }
    }
}
