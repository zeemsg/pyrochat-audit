package com.pyrochat.app.backend

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.push.Push
import com.pyrochat.app.push.WakeHandler
import com.pyrochat.app.push.WakeNotifier
import com.pyrochat.app.push.Badge

/**
 * `PushDelegate` (`App/PyroChatApp.swift`) — the wake-push registration. The FCM registration
 * token is the ONLY thing shared with the relay (RAM-only there) and pushes carry zero content.
 *
 * Token flow: `[cfg]` toggle on → `WakePushUi` handles POST_NOTIFICATIONS → [requestAndRegister]
 * → `FirebaseMessaging.getToken()` (or `PyroFcmService.onNewToken` on rotation) → [onToken] →
 * [onToken] callback = `ChatService.sendPushToken` (installed per session by `RealBackend.attach`,
 * iOS `PushDelegate.onToken = { … }`); at login the token also rides the `auth` frame through
 * `ChatService.pushTokenProvider` = [currentToken].
 *
 * Without a Firebase config in the build ([Push.available] false) every member is a safe no-op:
 * no token, no fake registration, the toggle's footnote says so.
 */
object PushRegistration {
    private const val TAG = "PushRegistration"

    /**
     * MESSAGING_MODE — firebase-messaging 25.1 deprecates `getToken` / `deleteToken` /
     * `onNewToken` in favour of `register()` / `unregister()` / `onRegistered(installationId)`,
     * which address the device by its Firebase Installation ID and only work with
     * `<meta-data firebase_messaging_installation_id_enabled=true>`. The relay sends to
     * `message.token` over FCM HTTP v1 with classic registration tokens — the mode this build
     * runs (the meta-data is NOT set). Flip the meta-data and `PyroFcmService.onRegistered` takes
     * over once the FID-addressed send path has been verified against a real project.
     */
    private const val MESSAGING_MODE = "classic-registration-token"

    @Volatile
    private var app: Context? = null

    /** iOS `PushDelegate.currentToken` — read by `ChatService.pushTokenProvider` at every login. */
    @Volatile
    var currentToken: String? = null
        private set

    /** iOS `PushDelegate.onToken` — session-bound; `RealBackend` sets it at attach and clears it with the session. */
    @Volatile
    var onToken: ((String) -> Unit)? = null

    /** DEBUG `--es fcmToken <string>`: a stand-in token so the relay path is testable without a Firebase project. */
    @Volatile
    private var debugToken: String? = null

    /** Firebase is configured in this build (`android/app/firebase.properties` at build time). */
    val available: Boolean get() = Push.available

    /** `PyroChatApplication.onCreate`: initialize Firebase (if configured) and the channel, so a cold-process wake can post. */
    fun install(app: Application) {
        this.app = app
        Push.initialize(app)
        runCatching { WakeNotifier.ensureChannel(app) }.onFailure { Log.w(TAG, "channel: ${it.message}") }
    }

    /** A token arrived or rotated (`PyroFcmService.onNewToken`, `getToken`, or the debug extra). Repeats are dropped. */
    fun onToken(token: String) {
        val t = token.trim()
        if (t.isEmpty() || t == currentToken) return
        currentToken = t
        onToken?.invoke(t)
    }

    /**
     * `UIApplication.registerForRemoteNotifications()`: ask FCM for the registration token; it
     * comes back through [onToken]. Permission is the UI's business (`WakePushUi`), not this one's.
     */
    @Suppress("DEPRECATION") // see MESSAGING_MODE
    fun requestAndRegister() {
        debugToken?.let { onToken(it); return }
        if (!Push.available) return
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { onToken(it) }
                .addOnFailureListener { Log.w(TAG, "token fetch failed: ${it.message}") }
        }.onFailure { Log.w(TAG, "messaging unavailable: ${it.message}") }
    }

    /**
     * `PushDelegate.detach()`: the toggle went off, or the account was wiped (duress, dead-man,
     * total wipe — pre-auth, needs no keys). Invalidates the token at Google (the relay's copy
     * then bounces UNREGISTERED and is dropped), clears the shade, badge 0. Safe without Firebase.
     */
    @Suppress("DEPRECATION") // see MESSAGING_MODE
    fun detach() {
        currentToken = null
        if (Push.available) {
            runCatching {
                FirebaseMessaging.getInstance().deleteToken()
                    .addOnFailureListener { Log.w(TAG, "deleteToken failed: ${it.message}") }
            }.onFailure { Log.w(TAG, "messaging unavailable: ${it.message}") }
        }
        clearNotifications()
    }

    /** `removeAllDeliveredNotifications` + badge 0 — on unlock and whenever the app comes to the foreground. */
    fun clearNotifications() {
        app?.let(WakeNotifier::clearAll)
        WakeHandler.reset()
    }

    /** `setBadgeCount(totalUnread)` on the background transition (null = locked → no number). */
    fun syncBadge(totalUnread: Int?) {
        app?.let { WakeNotifier.syncBadge(it, Badge.number(totalUnread)) }
    }

    /** Honest state for the `[cfg]` footnote. */
    fun notificationsAllowed(): Boolean = app?.let(WakeNotifier::notificationsAllowed) ?: false

    /** DEBUG builds only: `am start … --es fcmToken <20+ chars>` injects the stand-in token (relay shape `[A-Za-z0-9_:.\-]{20,4096}`). */
    fun debugInject(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val t = intent?.getStringExtra("fcmToken")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        debugToken = t
        onToken(t)
    }

    /** Tests: forget an injected stand-in token. */
    internal fun debugClear() {
        debugToken = null
    }
}
