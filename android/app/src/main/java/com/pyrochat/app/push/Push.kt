package com.pyrochat.app.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Programmatic Firebase bootstrap — the only place the SDK is initialized. Called once from
 * `PyroChatApplication.onCreate` (via `PushRegistration.install`). Without baked-in options
 * ([PushOptions.fromBuildConfig] == null) nothing is initialized and [available] stays false:
 * every caller checks it before touching `FirebaseMessaging`, which would otherwise throw.
 *
 * Auto-init is off in the manifest (`firebase_messaging_auto_init_enabled=false`): no token is
 * generated until the user turns the `[cfg]` toggle on, exactly like iOS asks for the APNs token
 * only from that toggle.
 */
object Push {
    private const val TAG = "Push"

    @Volatile
    var available: Boolean = false
        private set

    fun initialize(context: Context, options: PushOptions? = PushOptions.fromBuildConfig()): Boolean {
        if (options == null) {
            available = false
            Log.i(TAG, "no firebase config in this build — wake push unavailable")
            return false
        }
        available = runCatching {
            if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                FirebaseApp.initializeApp(context, options.toFirebaseOptions())
            }
            true
        }.onFailure { Log.w(TAG, "firebase init failed: ${it.message}") }.getOrDefault(false)
        if (available) Log.i(TAG, "firebase ready (project ${options.projectId})")
        return available
    }
}
