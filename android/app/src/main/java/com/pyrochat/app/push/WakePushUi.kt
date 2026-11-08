package com.pyrochat.app.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pyrochat.app.R
import com.pyrochat.app.backend.PushRegistration
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/**
 * The `[cfg]` → "wake push (zero-content)" toggle's permission flow, the port of
 * `PushDelegate.requestAndRegister()` (`PyroChatApp.swift`): asked ONLY when the user turns the
 * toggle on, never at launch, never provisional.
 *
 * - permission already granted (or Android < 13) → register (fetch the FCM token → relay)
 * - never asked → the system prompt; granted → register; declined → register anyway (parity:
 *   the token stays useful — allow notifications later in Settings and wakes just work)
 * - Android will not prompt again (denied twice) → register anyway AND deep-link to the app's
 *   notification settings, the way iOS opens `openNotificationSettingsURLString`
 */
class WakePush internal constructor(private val context: Context, private val ask: () -> Unit) {
    fun turnOn() {
        if (WakePushPermission.granted(context)) PushRegistration.requestAndRegister() else ask()
    }
}

@Composable
fun rememberWakePush(): WakePush {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        WakePushPermission.afterResult(context, granted)
    }
    return remember(context, launcher) { WakePush(context) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
}

object WakePushPermission {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun afterResult(context: Context, granted: Boolean) {
        PushRegistration.requestAndRegister() // register anyway — see class doc
        if (granted) return
        val activity = context.findActivity()
        val promptStillPossible = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (!promptStillPossible) openNotificationSettings(context) // Settings is the only way left
    }

    /** The app's page in system notification settings (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`). */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * The footnote under the toggle, honest about the three states: no Firebase config in this build
 * (always shown), notifications blocked by the OS while the toggle is on (tap → Settings), or the
 * iOS wording. Re-evaluated on every resume so a trip to Settings is reflected at once.
 */
@Composable
fun WakePushFootnote(enabled: Boolean) {
    val context = LocalContext.current
    val t = LocalTerminal.current
    var allowed by remember { mutableStateOf(PushRegistration.notificationsAllowed()) }
    LifecycleResumeEffect(Unit) {
        allowed = PushRegistration.notificationsAllowed()
        onPauseOrDispose { }
    }
    val base = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    when {
        !PushRegistration.available -> Text(stringResource(R.string.cfg_wake_push_unavailable), style = mono(10), color = t.fgDim, modifier = base)
        !enabled -> Unit
        !allowed -> Text(
            stringResource(R.string.cfg_wake_push_blocked), style = mono(10), color = t.alert,
            modifier = Modifier.clickable { WakePushPermission.openNotificationSettings(context) }.then(base),
        )
        else -> Text(stringResource(R.string.cfg_wake_push_footnote), style = mono(10), color = t.fgDim, modifier = base)
    }
}
