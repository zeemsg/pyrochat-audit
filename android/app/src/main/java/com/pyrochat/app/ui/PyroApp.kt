package com.pyrochat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.ui.screens.AddByQRScreen
import com.pyrochat.app.ui.screens.AuthScreen
import com.pyrochat.app.ui.screens.BootScreen
import com.pyrochat.app.ui.screens.ChatScreen
import com.pyrochat.app.ui.screens.HomeScreen
import com.pyrochat.app.ui.screens.LicensesScreen
import com.pyrochat.app.ui.screens.LinkDesktopScreen
import com.pyrochat.app.ui.screens.NewGroupScreen
import com.pyrochat.app.ui.screens.NotesScreen
import com.pyrochat.app.ui.screens.NoticesScreen
import com.pyrochat.app.ui.screens.SettingsScreen
import com.pyrochat.app.ui.screens.StatusScreen
import com.pyrochat.app.ui.theme.PyroTheme
import com.pyrochat.app.ui.theme.SoundPlayer
import com.pyrochat.app.ui.theme.TerminalScreen

/**
 * `RootView` — switches on the stage machine (boot → auth → main) and hosts
 * the main navigation stack. [onShield] mirrors `ScreenshotShield.set` (the
 * `[cfg]` toggle flips FLAG_SECURE live). [keys] is the deep-link intake
 * (docs/FIRST-RUN.md §3): held while booting or locked, dropped with a notice
 * on the auth screen when no identity exists, applied once the app is unlocked.
 */
@Composable
fun PyroApp(backend: Backend, sounds: SoundPlayer, keys: AccessKeyIntake = remember { AccessKeyIntake() }, onShield: (Boolean) -> Unit) {
    val settings by backend.settings.collectAsStateWithLifecycle()
    val stage by backend.stage.collectAsStateWithLifecycle()
    val pendingKey by keys.pending.collectAsStateWithLifecycle()
    val keyNotice by keys.notice.collectAsStateWithLifecycle()

    LaunchedEffect(backend) {
        backend.tones.collect { sounds.play(it, backend.settings.value.soundsOn) }
    }
    LaunchedEffect(settings.screenshotBlockOn) { onShield(settings.screenshotBlockOn) }
    // pre-auth resolution: hold (identity exists) or drop with the notice (no identity); Main resolves in MainNav
    if (BuildConfig.ACCESS_KEY_UI) LaunchedEffect(stage, pendingKey) {
        when (stage) {
            Stage.Auth -> keys.resolve(stage)
            Stage.Main -> keys.clearNotice()
            Stage.Boot -> Unit
        }
    }

    PyroTheme(settings) {
        TerminalScreen {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (val s = stage) {
                    Stage.Boot -> BootScreen(onDone = backend::finishBoot)
                    Stage.Auth -> AuthScreen(backend, notice = keyNotice)
                    Stage.Main -> MainNav(backend, keys)
                }
            }
        }
    }
}

/** `NavigationStack(path:)` destinations reachable from Home. */
sealed interface Route {
    data object Home : Route
    data class Chat(val hash: String) : Route
    /**
     * `[cfg]`; [incoming] + [serial] carry a key into the ACCESS KEY dialog (a repeat of the same
     * code must fire again). [focus] + [focusSerial] are docs/STATUS-AND-CFG.md §1's "tapping one
     * goes to the setting that changes it": the status screen names a row and this route scrolls
     * to it, tapping the same row twice scrolling twice.
     */
    data class Settings(
        val incoming: IncomingKey? = null,
        val serial: Long = 0L,
        val focus: CfgRow? = null,
        val focusSerial: Long = 0L,
    ) : Route

    /** docs/STATUS-AND-CFG.md §1 — reached from the first row of `[cfg]` and from nowhere else. */
    data object Status : Route
    data object LinkDesktop : Route
    data object AddByQR : Route
    data object Notes : Route
    data object Licenses : Route
    /** The pinned `<pyrochat>` channel — operator notices. */
    data object SystemNotices : Route
    /** `── new group ──` (GROUPS.md §7). */
    data object NewGroup : Route
}

@Composable
private fun MainNav(backend: Backend, keys: AccessKeyIntake) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val push: (Route) -> Unit = { stack.add(it) }
    BackHandler(enabled = stack.size > 1, onBack = pop)

    /** Open `[cfg]` with a key for the ACCESS KEY dialog — on top of whatever is open, replacing an open `[cfg]`. */
    val openWithKey: (IncomingKey) -> Unit = { key ->
        val route = Route.Settings(key, serial = System.nanoTime())
        if (stack.last() is Route.Settings) stack[stack.lastIndex] = route else push(route)
    }
    // a deep-link key that arrived (or was held through unlock): apply it now
    val pendingKey by keys.pending.collectAsStateWithLifecycle()
    if (BuildConfig.ACCESS_KEY_UI) LaunchedEffect(pendingKey) { keys.resolve(Stage.Main)?.let(openWithKey) }

    when (val top = stack.last()) {
        Route.Home -> HomeScreen(
            backend,
            onOpenChat = { push(Route.Chat(it)) },
            onSettings = { push(Route.Settings()) },
            onNotes = { push(Route.Notes) },
            onAddByQR = { push(Route.AddByQR) },
            onOpenNotices = { push(Route.SystemNotices) },
            onNewGroup = { push(Route.NewGroup) },
        )
        is Route.Chat -> ChatScreen(backend, hash = top.hash, onBack = pop)
        Route.NewGroup -> NewGroupScreen(backend, onBack = pop, onCreated = { id -> pop(); push(Route.Chat(id)) })
        is Route.Settings -> SettingsScreen(
            backend, onBack = pop, onStatus = { push(Route.Status) },
            onLinkDesktop = { push(Route.LinkDesktop) }, onLicenses = { push(Route.Licenses) },
            incoming = top.incoming, incomingSerial = top.serial,
            // forget the key on the route: a return to [cfg] must never submit it again (the focus
            // travels, so a jump from the status screen survives the key being consumed)
            onIncomingConsumed = {
                if (stack.last() is Route.Settings) stack[stack.lastIndex] = Route.Settings(focus = top.focus, focusSerial = top.focusSerial)
            },
            focus = top.focus, focusSerial = top.focusSerial,
        )
        // §1: read-only. A tap leaves the screen for the `[cfg]` row that changes the fact.
        Route.Status -> StatusScreen(
            backend, onBack = pop,
            onOpenSetting = { row ->
                pop()
                (stack.last() as? Route.Settings)?.let { stack[stack.lastIndex] = it.copy(focus = row, focusSerial = System.nanoTime()) }
            },
        )
        Route.LinkDesktop -> LinkDesktopScreen(backend, onBack = pop)
        // a key scanned or pasted on the [qr] screen: leave it, open [cfg] with the dialog
        Route.AddByQR -> AddByQRScreen(backend, onBack = pop, onAccessKey = { code -> pop(); openWithKey(IncomingKey.Key(code)) })
        Route.Notes -> NotesScreen(onBack = pop)
        Route.Licenses -> LicensesScreen(onBack = pop)
        Route.SystemNotices -> NoticesScreen(backend, onBack = pop)
    }
}
