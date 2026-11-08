package com.pyrochat.app.push

// Pure decision logic behind the wake notification — no Android types, unit-tested in
// `app/src/test/.../push/WakeRulesTest.kt`.

/**
 * The relay's FCM message is DATA-ONLY: `{"data":{"kind":"wake"}}` and nothing else, ever
 * (`relay/.../FcmPushNotifier.messageJson`, PROTOCOL.md §11). Anything else is not ours.
 */
object WakePayload {
    const val KIND = "kind"
    const val WAKE = "wake"

    fun isWake(data: Map<String, String>): Boolean = data[KIND] == WAKE
}

/**
 * When a wake should become a notification. A live, connected app pulls its own queue — the
 * banner would only say what the screen already shows. Backgrounded, locked, or offline (the
 * relay only wakes recipients with no live socket, so this is the normal case) → notify.
 */
object WakePolicy {
    fun shouldNotify(foreground: Boolean, online: Boolean): Boolean = !(foreground && online)
}

/**
 * docs/DECOY-ACCOUNT.md §6.6 — "a wake push for the real account, arriving while the decoy is open,
 * must not surface. Nothing may render for a slot that is not the open one."
 *
 * The payload cannot help: it is `{"kind":"wake"}` and nothing else, so it never names an account,
 * and making it name one would be the disclosure this whole feature exists to prevent. The only
 * fact the device has is whether the OPEN session ever asked the relay to wake it. A session with
 * wake push off never did, so a wake arriving into it belongs to some other slot and rendering it
 * would tell the person holding the phone that another account is receiving messages.
 *
 * Locked or cold, there is no open slot to be wrong about and the banner behaves exactly as it
 * always did — which is also what keeps the two cases indistinguishable from outside.
 */
object WakeSlotPolicy {
    /**
     * @param sessionOpen a store is unlocked in this process
     * @param wakesThisSlot that session registered for wake push
     */
    fun belongsToOpenSlot(sessionOpen: Boolean, wakesThisSlot: Boolean): Boolean = !sessionOpen || wakesThisSlot
}

/**
 * Coalescing: every wake targets ONE fixed notification id, so the shade never holds more than a
 * single "incoming transmission" (iOS stacks one banner per push; Android collapses them).
 * A burst — five messages, five wakes within milliseconds — alerts once: while the last alerting
 * post is younger than [QUIET_WINDOW_MS] further wakes are dropped. The decision is taken from
 * the handler's own clock, never from `activeNotifications` (which lags `notify()` by a binder
 * round-trip — long enough for a burst to slip through). After the window a new wake re-alerts
 * (sound again, still one entry), the way a second iOS banner would; clearing the shade
 * (`WakeHandler.reset`) forgets the clock so the next wake always alerts.
 */
object WakeCoalescing {
    const val QUIET_WINDOW_MS = 3_000L

    /** @param lastAlertAtMs monotonic clock of the last alerting post this process, null if none */
    fun shouldAlert(lastAlertAtMs: Long?, nowMs: Long, quietWindowMs: Long = QUIET_WINDOW_MS): Boolean {
        if (lastAlertAtMs == null) return true
        val since = nowMs - lastAlertAtMs
        return since < 0 || since >= quietWindowMs
    }
}

/**
 * The app-icon badge (`Notification.setNumber`, shown by launchers that count) — iOS
 * `setBadgeCount(service.totalUnread)`. Android has no badge without a notification, so the
 * number rides the wake entry and is refreshed when the app backgrounds.
 */
object Badge {
    /** Total unread as known locally; unknown (locked, no session) → 0 = no number. */
    fun number(totalUnread: Int?): Int = totalUnread?.coerceAtLeast(0) ?: 0

    /** A wake proves at least one message waits that the store has not seen yet: never 0 on the wake itself. */
    fun forWake(totalUnread: Int?): Int = maxOf(1, number(totalUnread))
}
