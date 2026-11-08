package com.pyrochat.app.push

import android.content.Context
import android.os.SystemClock

/**
 * One wake, end to end: payload check → live-app suppression → coalescing → post. Runs on the
 * FCM service thread; everything it reads is volatile RAM. [reset] forgets the coalescing clock
 * (the shade was cleared, the next wake must alert).
 */
object WakeHandler {
    enum class Outcome {
        /** not `{kind:"wake"}` — not ours */
        IGNORED,
        /** app in the foreground with the link up: the socket pulls the queue itself */
        LIVE,
        /** a burst: the showing entry alerted moments ago */
        COALESCED,

        /**
         * docs/DECOY-ACCOUNT.md §6.6: a session is open and it is not the one the relay is waking,
         * so nothing renders. The wake is dropped, not deferred — there is nothing this slot could
         * honestly say about another slot's message.
         */
        OTHER_SLOT,
        /** posted with sound + vibration */
        POSTED,
    }

    @Volatile
    private var lastAlertAt: Long? = null

    /**
     * @param sessionOpen whether a store is unlocked in this process — docs/DECOY-ACCOUNT.md §6.6
     */
    fun handle(
        context: Context,
        data: Map<String, String>,
        now: Long = SystemClock.elapsedRealtime(),
        sessionOpen: Boolean = AppPresence.sessionOpen,
    ): Outcome {
        if (!WakePayload.isWake(data)) return Outcome.IGNORED
        if (!WakeSlotPolicy.belongsToOpenSlot(sessionOpen, AppPresence.wakesThisSlot())) return Outcome.OTHER_SLOT
        if (!WakePolicy.shouldNotify(AppPresence.foreground, AppPresence.online())) return Outcome.LIVE
        if (!WakeCoalescing.shouldAlert(lastAlertAt, now)) return Outcome.COALESCED
        WakeNotifier.post(context, Badge.forWake(AppPresence.totalUnread()), alert = true)
        lastAlertAt = now
        return Outcome.POSTED
    }

    fun reset() {
        lastAlertAt = null
    }
}
