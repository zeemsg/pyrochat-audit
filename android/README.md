# PyroChat — Android

1:1 port of the iOS app (same protocol, same store format, same UI). Plan: `docs/ANDROID-PLAN.md`.

```
source env.sh              # JDK 21 (Android Studio's JBR) + SDK paths
./gradlew test             # pure-JVM modules: protocol, crypto, store, service, mirror
./gradlew :app:assembleDebug
```
Modules: `core-protocol` (wire frames, envelope), `core-crypto` (libsignal stores, Ed25519 relay auth, RFC 9474),
`core-store` (Swift-compatible JSON codec, AES-GCM store, backups), `core-service` (ChatService port),
`mirror` (desktop link), `kdf-native` (NDK PBKDF2), `app` (Compose UI). iOS fixtures for the codec live in `fixtures/`.

## Push-to-wake (FCM)

Same zero-content design as iOS: the relay sends a data-only FCM message `{"kind":"wake"}` (never a
sender, never text), the app posts one "incoming transmission" notification with the default sound on
the `transmissions` channel, and the user opens the app to pull the encrypted queue. Code: `app/src/main/java/com/pyrochat/app/push/`.

The build does **not** use the `google-services` plugin and no `google-services.json` is committed.
Firebase is initialized programmatically from four values baked into `BuildConfig` from the optional,
gitignored **`app/firebase.properties`**:

```properties
projectId=your-firebase-project-id
applicationId=1:123456789012:android:0123456789abcdef
apiKey=AIza...
gcmSenderId=123456789012
```

Where the values come from (Firebase console → project settings → *General* → *Your apps* → the
Android app with package `com.pyrochat.app`; add one if missing — no SHA-1 needed for FCM):
download `google-services.json` and copy `project_info.project_id` → `projectId`,
`client[].client_info.mobilesdk_app_id` → `applicationId`, `client[].api_key[].current_key` → `apiKey`,
`project_info.project_number` → `gcmSenderId`. Never commit either file.

Without the file every field is empty, `PushOptions.fromBuildConfig()` is null and wake push is cleanly
unavailable: nothing initializes, no token is faked, and the `[cfg]` toggle's footnote says so.
The relay side needs a service account (`fcm.*` in `deploy/relay.properties`, see `docs/PROTOCOL.md` §11).

Debug builds accept `--es fcmToken <20+ chars>` as a stand-in registration token so the relay path
(`push_token` frame, `platform:"fcm"`) can be exercised on the local relay without a Firebase project.
