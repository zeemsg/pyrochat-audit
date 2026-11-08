import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- release identity (the ONE place both numbers live) -----------------------------------
// versionName mirrors the iOS `MARKETING_VERSION` in ios/project.yml — one release train, the
// same number on both stores. versionCode is Play's monotonic upload counter (the iOS analogue
// is CURRENT_PROJECT_VERSION, but the two are independent counters).
// To bump: change MARKETING_VERSION in ios/project.yml, copy it here, and add 1 to
// PLAY_VERSION_CODE for EVERY upload to Play — including a re-upload of the same versionName,
// Play refuses a reused code. Never decrease either.
val MARKETING_VERSION = "1.0"
val PLAY_VERSION_CODE = 21

// ---- upload key (docs/ANDROID-RELEASE.md §2) ------------------------------------------------
// The four PYRO_UPLOAD_* values live in ~/.gradle/gradle.properties on the build Mac, never in
// the repo. When any is missing the release build is produced UNSIGNED (fine for local checks,
// refused by Play and by `adb install`) and one warning says so.
val uploadStore = providers.gradleProperty("PYRO_UPLOAD_STORE").orNull?.trim()
val uploadStorePass = providers.gradleProperty("PYRO_UPLOAD_STORE_PASS").orNull
val uploadKeyAlias = providers.gradleProperty("PYRO_UPLOAD_KEY_ALIAS").orNull?.trim()
val uploadKeyPass = providers.gradleProperty("PYRO_UPLOAD_KEY_PASS").orNull
val uploadSigningReady =
    !uploadStore.isNullOrEmpty() && !uploadStorePass.isNullOrEmpty() && !uploadKeyAlias.isNullOrEmpty() && !uploadKeyPass.isNullOrEmpty() &&
        File(uploadStore).isFile
if (!uploadSigningReady) {
    logger.warn(
        "WARNING: PyroChat release signing is NOT configured — PYRO_UPLOAD_STORE / PYRO_UPLOAD_STORE_PASS / " +
            "PYRO_UPLOAD_KEY_ALIAS / PYRO_UPLOAD_KEY_PASS are missing from ~/.gradle/gradle.properties (or the keystore " +
            "file does not exist). assembleRelease / bundleRelease will produce UNSIGNED artifacts. See docs/ANDROID-RELEASE.md §2.",
    )
}

android {
    namespace = "com.pyrochat.app"
    compileSdk = 37   // Compose 2026.09 BOM requires 37; targetSdk stays 36 (Play)
    defaultConfig {
        applicationId = "com.pyrochat.app"   // same identity as the iOS bundle
        minSdk = 29                          // Android 10
        targetSdk = 36
        versionCode = PLAY_VERSION_CODE
        versionName = MARKETING_VERSION
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        // iOS `PRIVATE_SERVER_UI` compile flag: relay/billing address UI exists only in a
        // private-server build. The public build ships the built-in relay (ServerConfig).
        buildConfigField("boolean", "PRIVATE_SERVER_UI", "false")
        // iOS `ACCESS_KEY_UI` (ios/project.yml): everything about access keys: the [cfg] subscription
        // / ENTER ACCESS KEY rows, the status screen's "access key" row, the dialog, the redeem call
        // and the pyrochat://redeem link (src/debug/AndroidManifest.xml). The app is free and the
        // relay enforces no token. Debug keeps them for the later feature; R8 strips them from Release.
        buildConfigField("boolean", "ACCESS_KEY_UI", "false")
        // docs/SEALED-SENDER.md §7: the throwaway sealed-sender trust root, for a relay on the
        // developer's machine. Its private half is in the repository, so a Release build carries
        // an empty string here and trusts only the production root compiled into core-crypto.
        buildConfigField("String", "SEALED_TEST_ROOT", "\"\"")
        // LOCAL_HARNESS: the launch extras that point the app at a relay on the developer's machine
        // (ServerConfig.override). Debug has them and so does `releaseCheck` below; the release
        // build does not, and R8 removes what is behind them.
        buildConfigField("boolean", "LOCAL_HARNESS", "false")
        // push/PushOptions: Firebase values from the OPTIONAL, gitignored app/firebase.properties
        // (format + origin in android/README.md). No google-services plugin, no JSON in the tree;
        // absent file ⇒ "" everywhere ⇒ wake push is cleanly unavailable in that build.
        val firebaseProps = Properties().also { p ->
            val f = project.file("firebase.properties")
            if (f.isFile) f.inputStream().use(p::load)
        }
        fun fcm(key: String) = "\"" + firebaseProps.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "FCM_PROJECT_ID", fcm("projectId"))
        buildConfigField("String", "FCM_APPLICATION_ID", fcm("applicationId"))
        buildConfigField("String", "FCM_API_KEY", fcm("apiKey"))
        buildConfigField("String", "FCM_SENDER_ID", fcm("gcmSenderId"))
    }
    signingConfigs {
        if (uploadSigningReady) {
            create("release") {
                storeFile = file(uploadStore!!)
                storePassword = uploadStorePass
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPass
                enableV1Signing = false   // minSdk 29 verifies v2/v3 natively; v1 (JAR) would only leak file names
                enableV2Signing = true
                enableV3Signing = true    // key rotation later without a new package
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "LOCAL_HARNESS", "true")
            buildConfigField("boolean", "ACCESS_KEY_UI", "true")
            buildConfigField("String", "SEALED_TEST_ROOT", "\"Ba/550LupgYMzcYETJWXYu0sm+4OU5QhjsJxvqRehokn\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")   // null ⇒ unsigned (warned above)
        }
        // The release build as R8 leaves it (same rules, same switches, the production trust root
        // only), for trying before an upload: unlike the release it can be pointed at a relay on
        // this machine and may speak plain ws:// to it (src/releaseCheck). It has its own package
        // name and the debug key, so it cannot be installed over the real app or uploaded as it.
        // docs/ANDROID-RELEASE.md §1 and §8: `./gradlew :app:assembleReleaseCheck`, then deploy/android-release-check.py
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".check"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "LOCAL_HARNESS", "true")
        }
    }
    sourceSets {
        getByName("main") {
            // ui/screens/LicensesScreen reads the IBM Plex Mono OFL from here (one copy; a .txt under res/font breaks the build)
            assets.directories.add("licenses")
        }
    }
    androidResources {
        // aapt defaults + the libsignal AAR's acknowledgments for its TESTING library (we ship
        // assets/acknowledgments/libsignal.md — shown on the licenses screen — but never the testing .so)
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!libsignal-testing.md"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.DEBUG gates the screenshot-shield bypass in MainActivity; VERSION_NAME on the licenses screen
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true   // required by libsignal-android
    }
    packaging {
        // libsignal-client ships desktop natives; the AAR brings the Android ones
        resources { excludes += listOf("**/*.dylib", "**/*.dll", "**/libsignal_jni.so", "**/libsignal_jni_testing.so", "META-INF/AL2.0", "META-INF/LGPL2.1") }
        jniLibs { excludes += listOf("**/libsignal_jni_testing.so") }
    }
}
dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-crypto"))
    implementation(project(":core-store"))
    implementation(project(":core-service"))
    implementation(project(":mirror"))
    implementation(project(":kdf-native"))
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // docs/BIOMETRIC-UNLOCK.md: BiometricPrompt + BiometricManager (it also brings androidx.fragment, which MainActivity now extends)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)   // lifecycleScope + repeatOnLifecycle (MainActivity)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    // ui/qr: CameraX preview + ML Kit QR analyzer (bundled model, works offline), ZXing QR generation
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    // push: FCM client, initialized programmatically (push/Push.kt) — no google-services plugin
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)   // GrantPermissionRule (platform tests: RECORD_AUDIO)
    // docs/DECOY-ACCOUNT.md §5 — AuthScreenFirstScreenTest renders the auth screen and reads it back
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)  // over the 3.5.0 it drags in: API 36 removed InputManager.getInstance()
    debugImplementation(libs.compose.ui.test.manifest)
}
