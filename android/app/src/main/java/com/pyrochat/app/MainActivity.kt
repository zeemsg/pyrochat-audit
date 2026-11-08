package com.pyrochat.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.pyrochat.app.platform.FileAccess
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.repeatOnLifecycle
import com.pyrochat.app.backend.PushRegistration
import com.pyrochat.app.backend.RealBackend
import com.pyrochat.app.backend.ServerConfig
import com.pyrochat.app.platform.ScreenCaptureDetector
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.PyroApp
import com.pyrochat.app.ui.theme.SoundPlayer
import kotlinx.coroutines.launch

// FragmentActivity, not ComponentActivity: androidx.biometric hosts its sheet in a fragment
// (docs/BIOMETRIC-UNLOCK.md). Everything else — Compose setContent, edge-to-edge, the activity
// result launchers — is unchanged; FragmentActivity IS a ComponentActivity.
class MainActivity : FragmentActivity() {
    private lateinit var sounds: SoundPlayer
    /** The export waiting for the system "save as" picker (kept in RAM only). */
    private var pendingSave: RealBackend.SaveRequest? = null
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val req = pendingSave; pendingSave = null
        val uri = res.data?.data
        val saved = res.resultCode == RESULT_OK && uri != null && req != null &&
            FileAccess.writeDocument(contentResolver, uri, req.bytes)
        (application as PyroChatApplication).services.backend.noteSaveOutcome(saved)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // iOS forces UIUserInterfaceStyle Dark: light system-bar icons on the black ground, whatever the device theme
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        // Screenshot shield: default ON, like iOS (black in screenshots, recents and casts).
        // DEBUG builds only: `--ez unshielded true` skips it so UI screenshots can be verified.
        val unshielded = BuildConfig.DEBUG && intent.getBooleanExtra("unshielded", false)
        if (!unshielded) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        sounds = SoundPlayer(this)

        val services = (application as PyroChatApplication).services
        PushRegistration.debugInject(intent) // DEBUG `--es fcmToken <string>`: stand-in FCM token for relay tests
        // DEBUG `--ez fake true` keeps the canned shell selectable for UI work; everything else is real.
        val backend: Backend = if (BuildConfig.DEBUG && intent.getBooleanExtra("fake", false)) {
            com.pyrochat.app.ui.FakeBackend.demoRows = intent.getIntExtra("fakeRows", 0)
            com.pyrochat.app.ui.FakeBackend.demoUpdate = intent.getStringExtra("fakeUpdate") // `name|build[|url]` → the update banner
            com.pyrochat.app.ui.FakeBackend.demoFirstRun = intent.getBooleanExtra("fakeFirstRun", false) // FIRST-RUN.md: empty home, silent pyrobot
            com.pyrochat.app.ui.FakeBackend.demoDecoyOk = intent.getBooleanExtra("fakeDecoyOk", false)     // DECOY-ACCOUNT.md §3 happy path
            com.pyrochat.app.ui.FakeBackend.demoTooLarge = intent.getBooleanExtra("fakeTooLarge", false)   // DECOY-ACCOUNT.md §1 one line
            services.fakeBackend()
        } else {
            services.backend.also { it.serverOverride = overrideFrom(intent) }
        }
        (backend as? RealBackend)?.let(::wireLifecycle)
        // FIRST-RUN.md §3: a cold start from pyrochat://redeem/<CODE> (never again on a recreation — the key applies once)
        if (savedInstanceState == null) offerRedeemLink(intent)
        if (intent?.data != null) setIntent(Intent(intent).setData(null)) // the key is in RAM now; not on the activity's intent

        // Autofill: excluded at the VIEW level as well, for anything that is not Compose. This is
        // belt and braces and not the lever that matters — Compose hosts every field as a virtual
        // view, and the framework's virtual-view path never consults `importantForAutofill`. The
        // one that works is `ContentDataType.None` on the field itself; see `TerminalTextField`.
        findViewById<android.view.View>(android.R.id.content).importantForAutofill =
            android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            PyroApp(backend, sounds, services.accessKeys) { on ->
                // `[cfg]` → block screenshots: flips the shield live (ScreenshotShield.set)
                if (unshielded) return@PyroApp
                if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /** `singleTask`: a relaunch with new debug extras — or a `pyrochat://redeem/<CODE>` link — reaches the running activity here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerRedeemLink(intent)
        setIntent(Intent(intent).setData(null)) // the key is in RAM now; do not keep it on the activity's intent
        val services = (application as PyroChatApplication).services
        PushRegistration.debugInject(intent)
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra("fake", false))) services.backend.serverOverride = overrideFrom(intent)
    }

    /**
     * FIRST-RUN.md §3: the VIEW filter (scheme `pyrochat`, host `redeem`) — hand the URI to the
     * in-memory intake; the shell opens the ACCESS KEY dialog once unlocked. Never logged.
     */
    private fun offerRedeemLink(intent: Intent?) {
        if (!BuildConfig.ACCESS_KEY_UI) return
        if (intent?.action != Intent.ACTION_VIEW) return
        // a relaunch from recents re-delivers the task's ORIGINAL VIEW intent: that key was applied (or dropped) already
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.dataString ?: return
        if (intent.scheme != REDEEM_SCHEME) return
        (application as PyroChatApplication).services.accessKeys.offer(uri)
    }

    /**
     * `PyroChatApp.body.onChange(of: scenePhase)` + `ChatView.onReceive(screenshot / capture)`:
     * onStart = `.active`, onStop = `.background` (an `onPause` alone is `.inactive` and ignored).
     */
    private fun wireLifecycle(real: RealBackend) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = real.noteForeground()
            override fun onStop(owner: LifecycleOwner) = real.noteBackground()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ScreenCaptureDetector.screenshots(this@MainActivity).collect { real.noteCapture("screenshot") } }
                launch { ScreenCaptureDetector.recording(this@MainActivity).collect { if (it) real.noteCapture("screen_record") } }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                real.saveRequests.collect { req ->
                    pendingSave = req
                    runCatching { saveLauncher.launch(FileAccess.createDocumentIntent(req.name, req.mime)) }
                        .onFailure { pendingSave = null; real.noteSaveOutcome(false) }
                }
            }
        }
    }

    private fun overrideFrom(intent: Intent?): ServerConfig.Override? =
        ServerConfig.override(intent?.getStringExtra("relay"), intent?.getStringExtra("billing"), intent?.getStringExtra("socks"))

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    private companion object {
        const val REDEEM_SCHEME = "pyrochat"
    }
}
