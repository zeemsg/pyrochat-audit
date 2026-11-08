package com.pyrochat.app.push

import com.google.firebase.FirebaseOptions
import com.pyrochat.app.BuildConfig
import java.util.Properties

/**
 * The four values a Firebase project hands out for the Android app `com.pyrochat.app`, baked into
 * `BuildConfig` at build time from the OPTIONAL, gitignored `android/app/firebase.properties`
 * (`app/build.gradle.kts`; file format + where the values come from: `android/README.md`).
 *
 * No `google-services` Gradle plugin and no `google-services.json` in the tree: the SDK is
 * configured programmatically from these ([Push.initialize]). An absent file bakes `""` into every
 * field, [from] yields null, and wake push is cleanly unavailable in that build — nothing crashes,
 * no token is ever faked.
 */
data class PushOptions(val projectId: String, val applicationId: String, val apiKey: String, val senderId: String) {

    fun toFirebaseOptions(): FirebaseOptions = FirebaseOptions.Builder()
        .setProjectId(projectId)
        .setApplicationId(applicationId)
        .setApiKey(apiKey)
        .setGcmSenderId(senderId)
        .build()

    companion object {
        /** `firebase.properties` keys (also the `BuildConfig.FCM_*` fields, see `app/build.gradle.kts`). */
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_APPLICATION_ID = "applicationId"
        const val KEY_API_KEY = "apiKey"
        const val KEY_SENDER_ID = "gcmSenderId"

        /** Firebase Android app id: `1:<project number>:android:<hex>`; the project number IS the sender id. */
        private val APP_ID = Regex("1:([0-9]+):android:[0-9a-f]+")

        /**
         * Pure. Null unless all four values are present, the sender id is numeric, the application
         * id has Firebase's shape and its embedded project number matches the sender id — two
         * values copied from different projects would otherwise register a token nobody can wake.
         */
        fun from(projectId: String?, applicationId: String?, apiKey: String?, senderId: String?): PushOptions? {
            val p = projectId?.trim().orEmpty()
            val a = applicationId?.trim().orEmpty()
            val k = apiKey?.trim().orEmpty()
            val s = senderId?.trim().orEmpty()
            if (p.isEmpty() || a.isEmpty() || k.isEmpty() || s.isEmpty()) return null
            if (!s.all { it.isDigit() }) return null
            val m = APP_ID.matchEntire(a) ?: return null
            if (m.groupValues[1] != s) return null
            return PushOptions(p, a, k, s)
        }

        /** The same loader over a parsed `firebase.properties` (what the build script bakes in). */
        fun fromProperties(props: Properties): PushOptions? = from(
            props.getProperty(KEY_PROJECT_ID),
            props.getProperty(KEY_APPLICATION_ID),
            props.getProperty(KEY_API_KEY),
            props.getProperty(KEY_SENDER_ID),
        )

        /** What THIS build carries: `""` everywhere when the file was absent at build time. */
        fun fromBuildConfig(): PushOptions? =
            from(BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_APPLICATION_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID)
    }
}
