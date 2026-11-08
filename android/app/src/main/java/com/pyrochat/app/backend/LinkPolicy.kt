package com.pyrochat.app.backend

/**
 * Battery: while the app is in the background there is no reason to keep the relay socket
 * (and its 30 s keepalive pings) alive when a push can wake us — the relay queues mail for
 * 48 h and FCM delivers the "incoming transmission" ping. Without push the socket is the
 * only way to hear about mail, so it stays. iOS gets the same behaviour from the OS itself
 * (the app is suspended in the background).
 */
object LinkPolicy {
    /** Grace period before dropping the link, so a quick app switch doesn't cost a reconnect. */
    const val BACKGROUND_DROP_MS = 60_000L

    fun dropsLinkInBackground(pushEnabled: Boolean, pushAvailable: Boolean): Boolean = pushEnabled && pushAvailable
}
