package com.pyrochat.app

import android.app.Application
import com.pyrochat.app.backend.DeadmanSwitch
import com.pyrochat.app.backend.PushRegistration
import com.pyrochat.app.backend.RealBackend
import com.pyrochat.app.platform.Haptics
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.AccessKeyIntake
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.FakeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide singletons (`PyroChatApp.init` + `@StateObject AppModel`). The backend outlives
 * the activity so the state machine survives recreation.
 */
class PyroChatApplication : Application() {
    val services: AppServices by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()
        services.warmUp()
        PushRegistration.install(this) // Firebase (if configured) + the `transmissions` channel; a cold-process wake can post
    }
}

class AppServices(private val app: Application) {
    val paths = StorePaths(app)
    val deadman = DeadmanSwitch(app)
    val haptics = Haptics(app)

    /** The real thing — `RealBackend` over core-service. */
    val backend: RealBackend by lazy { RealBackend(app, paths, deadman, haptics) }

    /** docs/FIRST-RUN.md §3: the access key a `pyrochat://redeem/…` link handed us — RAM only, outlives the activity. */
    val accessKeys = AccessKeyIntake()

    private var fake: FakeBackend? = null

    /** DEBUG `--ez fake true`: the canned shell for UI work. */
    fun fakeBackend(): Backend = fake ?: FakeBackend(app, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { fake = it }

    /** `SecureStore.sweepPlaintextTmp()` at launch: decrypted tmp copies from a crashed session never survive. */
    fun warmUp() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { paths.sweepPlaintextTmp() } }
    }
}
