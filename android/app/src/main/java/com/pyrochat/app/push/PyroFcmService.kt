package com.pyrochat.app.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.PyroChatApplication
import com.pyrochat.app.backend.PushRegistration

/**
 * FCM entry points (manifest: `com.google.firebase.MESSAGING_EVENT`). A registration token
 * arrives or rotates → `PushRegistration.onToken` → `ChatService.sendPushToken` (iOS
 * `didRegisterForRemoteNotificationsWithDeviceToken` → `PushDelegate.onToken`). A high-priority
 * data message wakes this process even in Doze; only `{kind:"wake"}` does anything.
 */
class PyroFcmService : FirebaseMessagingService() {

    // classic registration tokens (see PushRegistration.MESSAGING_MODE); repeats are dropped there
    @Deprecated("classic-token callback — the SDK still delivers here without firebase_messaging_installation_id_enabled")
    override fun onNewToken(token: String) {
        PushRegistration.onToken(token)
    }

    /** FID-addressed registration (`FirebaseMessaging.register()`): live only with the manifest flag. */
    override fun onRegistered(installationId: String) {
        PushRegistration.onToken(installationId)
    }

    override fun onUnregistered(installationId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "unregistered")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // pull the queue now (DELIVERED receipts, badge) — only possible while unlocked in memory
        if (WakePayload.isWake(message.data)) runCatching { (application as PyroChatApplication).services.backend.backgroundDrain() }
        val outcome = WakeHandler.handle(this, message.data)
        if (BuildConfig.DEBUG) Log.d(TAG, "wake from ${message.from ?: "?"}: $outcome")
    }

    /** Tests: a bare `PyroFcmService()` has no Context (`attachBaseContext` is protected). */
    internal fun attachForTest(context: Context) = attachBaseContext(context)

    private companion object {
        const val TAG = "PyroFcmService"
    }
}
