package com.pyrochat.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.BiometricEnable
import com.pyrochat.app.backend.ServerConfig
import com.pyrochat.app.ui.BiometricWords
import com.pyrochat.app.ui.CfgLayout
import com.pyrochat.app.ui.CfgRow
import com.pyrochat.app.ui.CfgSection
import com.pyrochat.app.ui.Format
import com.pyrochat.app.ui.IncomingKey
import com.pyrochat.app.ui.MirrorState
import com.pyrochat.app.ui.PendingKeyRules
import com.pyrochat.app.ui.components.AlertField
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.DialogButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.MenuRow
import com.pyrochat.app.ui.components.TerminalAlert
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.qr.DebugPasteField
import com.pyrochat.app.ui.qr.LinkQrPayload
import com.pyrochat.app.ui.qr.QrScanner
import com.pyrochat.app.platform.rememberBiometricPrompter
import com.pyrochat.app.ui.qr.openAppSettings
import com.pyrochat.app.push.WakePushFootnote
import com.pyrochat.app.push.rememberWakePush
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.TextSize
import com.pyrochat.app.ui.theme.mono
import kotlinx.coroutines.launch

/**
 * `SettingsView` — `[cfg]`: every section, row, toggle and alert.
 *
 * [incoming] (docs/FIRST-RUN.md §3): a key that arrived by deep link, QR or paste — the ACCESS KEY
 * dialog opens with the code filled in and submits it at once; the dialog closes on the result,
 * which shows as today (`✓ access granted: N days` / `✗ …`). A refused code shows `! not an access key`.
 * [incomingSerial] distinguishes a second delivery of the same code; [onIncomingConsumed] lets the
 * caller forget the key once it has been submitted, so coming back to this screen never re-submits it.
 */
@Composable
fun SettingsScreen(
    backend: Backend,
    onBack: () -> Unit,
    onStatus: () -> Unit,
    onLinkDesktop: () -> Unit,
    onLicenses: () -> Unit,
    incoming: IncomingKey? = null,
    incomingSerial: Long = 0L,
    onIncomingConsumed: () -> Unit = {},
    focus: CfgRow? = null,
    focusSerial: Long = 0L,
) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wakePush = rememberWakePush()   // push/WakePushUi: `PushDelegate.requestAndRegister()`
    val settings by backend.settings.collectAsStateWithLifecycle()
    val mirror by backend.mirror.collectAsStateWithLifecycle()
    val blocked by backend.blockedHashes.collectAsStateWithLifecycle()
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val accessDays by backend.accessDaysRemaining.collectAsStateWithLifecycle()
    val duressSet by backend.duressPinSet.collectAsStateWithLifecycle()
    // docs/BIOMETRIC-UNLOCK.md §2: null hides the row entirely (no hardware, or nothing enrolled)
    val biometricKind = remember { backend.biometricKind() }
    val biometricOn by backend.biometricEnabled.collectAsStateWithLifecycle()
    var biometricStatus by remember { mutableStateOf<String?>(null) }
    val biometricPrompter = rememberBiometricPrompter(
        title = stringResource(R.string.bio_prompt_title),
        subtitle = stringResource(R.string.bio_prompt_enable),
        negative = stringResource(R.string.bio_prompt_negative),
    )

    // docs/DECOY-ACCOUNT.md §3 — the row's own state lives here and NOWHERE in the backend: a
    // decoy that exists must leave no trace in what this screen renders.
    val storeTooLarge by backend.storeTooLarge.collectAsStateWithLifecycle()
    var showDecoy by remember { mutableStateOf(false) }
    var decoyUser by remember { mutableStateOf("") }
    var decoyPass by remember { mutableStateOf("") }
    var decoyStatus by remember { mutableStateOf<String?>(null) }
    var decoyBusy by remember { mutableStateOf(false) }
    var decoyReplacing by remember { mutableStateOf(false) }

    var confirmWipe by remember { mutableStateOf(false) }
    var showDuress by remember { mutableStateOf(false) }
    var duressPin by remember { mutableStateOf("") }
    var showRedeem by remember { mutableStateOf(false) }
    var accessKey by remember { mutableStateOf("") }
    var redeemStatus by remember { mutableStateOf<String?>(null) }
    var redeeming by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    /**
     * Where each row and each section ended up, so a tap on the status screen can bring the
     * setting that changes it into view (§1) and an incoming access key can still land on its own
     * result line (FIRST-RUN.md §3).
     */
    val rowY = remember { mutableStateMapOf<CfgRow, Int>() }
    val sectionY = remember { mutableStateMapOf<CfgSection, Int>() }


    /** REDEEM (tapped or automatic): one call in flight at a time; the code lives only in the call. */
    fun redeem(code: String) {
        if (redeeming || code.isBlank()) return
        redeeming = true
        scope.launch {
            redeemStatus = context.getString(R.string.redeem_status_busy)
            redeemStatus = try {
                backend.redeemAccessKey(code)
                context.getString(R.string.redeem_status_ok, backend.accessDaysRemaining.value)
            } catch (e: BackendException) {
                "✗ ${e.message}"
            } finally {
                redeeming = false
            }
            showRedeem = false // the auto-submitted dialog closes on the result; a tapped REDEEM closed it already
            accessKey = ""
        }
    }

    // FIRST-RUN.md §3: an incoming key opens the dialog pre-filled and submits immediately
    if (BuildConfig.ACCESS_KEY_UI) LaunchedEffect(incoming, incomingSerial) {
        when (incoming) {
            is IncomingKey.Key -> { accessKey = incoming.code; showRedeem = true; redeem(incoming.code) }
            IncomingKey.Refused -> redeemStatus = PendingKeyRules.NOT_A_KEY
            null -> return@LaunchedEffect
        }
        withFrameNanos { } // laid out: the ACCOUNT header knows where it is
        scroll.animateScrollTo(sectionY[CfgSection.ACCOUNT] ?: 0)
        onIncomingConsumed() // the key now lives only in the redeem call
    }

    /**
     * docs/STATUS-AND-CFG.md §1: "tapping one goes to the setting that changes it". The status
     * screen names a row; this brings it into view. [focusSerial] so tapping the same row twice
     * scrolls twice.
     */
    LaunchedEffect(focus, focusSerial) {
        val wanted = focus ?: return@LaunchedEffect
        withFrameNanos { }   // laid out: every row knows where it is
        rowY[wanted]?.let { scroll.animateScrollTo(it) }
    }
    var showBackupPass by remember { mutableStateOf(false) }
    var backupPass by remember { mutableStateOf("") }
    var backupPass2 by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    val exportStatus by backend.exportStatus.collectAsStateWithLifecycle()
    LaunchedEffect(exportStatus) { exportStatus?.let { backupStatus = it } }
    var editProxy by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }

    // docs/STATUS-AND-CFG.md §2 — the self-hoster settings, collapsed. `advanced …` and the two
    // rows behind it exist only in a private-server build (docs/SELF_HOSTING.md), on both
    // platforms, so the public app still shows nobody an address it cannot use.
    var showAdvanced by remember { mutableStateOf(false) }
    var editRelay by remember { mutableStateOf(false) }
    var editBilling by remember { mutableStateOf(false) }

    /**
     * One row of `[cfg]`, dispatched by name. The bodies are the ones that were here before this
     * screen was regrouped — §2 is a reorganisation, not a rewrite, and the words were chosen
     * carefully — so what changed is which section each lands in and, for the three rows that
     * destroy data, the colour.
     *
     * The `when` is exhaustive over [CfgRow], so a row named in [CfgLayout] and not drawn here does
     * not compile.
     */
    val renderRow: @Composable (CfgRow) -> Unit = { r ->
        when (r) {
            // rendered above every section by the caller, not inside one
            CfgRow.STATUS -> Unit

            // ---- PROTECTION ----------------------------------------------------------------
            // docs/BIOMETRIC-UNLOCK.md §2 — one row, directly above auto-lock. The toggle's state is
            // `backend.biometricEnabled`: whether the stored key exists, so a refused prompt simply
            // leaves it off and the unlock screen and this row can never drift.
            CfgRow.BIOMETRIC -> if (biometricKind != null) {
                ToggleRow(stringResource(BiometricWords.row(biometricKind)), biometricOn) { on ->
                    biometricStatus = null
                    if (!on) {
                        backend.disableBiometricUnlock()   // §2: delete the stored key immediately
                    } else {
                        scope.launch {
                            when (val res = backend.enableBiometricUnlock(biometricPrompter)) {
                                BiometricEnable.Ok, BiometricEnable.Refused -> Unit  // refused: nothing changed
                                is BiometricEnable.Failed -> biometricStatus = "✗ ${res.message}"
                            }
                        }
                    }
                }
                biometricStatus?.let { StatusLine(it) }
                Footnote(stringResource(R.string.cfg_bio_footnote))
            }
            CfgRow.AUTO_LOCK -> PickerRow(stringResource(R.string.cfg_auto_lock), Format.autoLockLabel(settings.autoLockMinutes), listOf("off", "1m", "5m", "15m")) { v ->
                backend.updateSettings { it.copy(autoLockMinutes = Format.autoLockMinutes(v)) }
            }
            // §2 "consequence gets weight": duress, dead-man and burn destroy data and wear the
            // existing alert colour, so `scanlines` and `dead-man wipe` can no longer render alike.
            CfgRow.DURESS -> ButtonRow(
                if (duressSet) stringResource(R.string.cfg_duress_set) else stringResource(R.string.cfg_duress_unset),
                bold = false, color = t.alert,
            ) { showDuress = true }
            CfgRow.DEADMAN -> {
                PickerRow(stringResource(R.string.cfg_deadman), Format.daysLabel(settings.wipeAfterDays, "off"), listOf("off", "7d", "30d", "90d"), color = t.alert) { v ->
                    backend.updateSettings { it.copy(wipeAfterDays = v.removeSuffix("d").toIntOrNull()) }
                }
                Footnote(stringResource(R.string.cfg_deadman_footnote))
            }
            // docs/DECOY-ACCOUNT.md §3 — ONE row, rendered identically whether or not a decoy
            // exists: no checkmark, no "configured", no colour change, no state read from anywhere.
            // It reads the same inside the decoy too, because a decoy that cannot set a decoy is a
            // decoy that announces itself (§4). It moves section here; it does not change.
            CfgRow.DECOY -> {
                ButtonRow(stringResource(R.string.cfg_decoy), bold = false) {
                    decoyStatus = null
                    // §3: read AFTER the tap, never by the row — the row renders identically everywhere
                    decoyReplacing = backend.decoyExists()
                    showDecoy = true
                }
                decoyStatus?.let { StatusLine(it) }
                Footnote(stringResource(R.string.cfg_decoy_footnote))
                // §1 "never trim without being asked": one line, and nothing destructive offered.
                storeTooLarge?.let {
                    Footnote(stringResource(R.string.cfg_store_too_large, Format.byteLabel(it.jsonBytes.toLong()), Format.byteLabel(it.capacityBytes.toLong())))
                }
            }
            CfgRow.BURN_ON_SCREENSHOT -> ToggleRow(stringResource(R.string.cfg_burn_on_screenshot), settings.burnOnScreenshot) { v -> backend.updateSettings { it.copy(burnOnScreenshot = v) } }
            CfgRow.BLOCK_SCREENSHOTS -> ToggleRow(stringResource(R.string.cfg_block_screenshots), settings.screenshotBlockOn) { v -> backend.updateSettings { it.copy(blockScreenshots = v) } }
            CfgRow.DEFAULT_BURN -> PickerRow(stringResource(R.string.cfg_default_burn), Format.burnLabel(settings.defaultBurnSeconds), listOf("off", "5s", "1m", "1h", "1d")) { v ->
                backend.updateSettings { it.copy(defaultBurnSeconds = Format.burnSeconds(v)) }
            }
            CfgRow.THREAD_AUTO_BURN -> PickerRow(stringResource(R.string.cfg_thread_auto_burn), Format.daysLabel(settings.threadAutoBurnDays, "never"), listOf("never", "1d", "7d", "30d")) { v ->
                backend.updateSettings { it.copy(threadAutoBurnDays = v.removeSuffix("d").toIntOrNull()) }
            }

            // ---- WHAT LEAVES THIS PHONE ----------------------------------------------------
            CfgRow.WAKE_PUSH -> {
                ToggleRow(stringResource(R.string.cfg_wake_push), settings.pushEnabled) { v ->
                    backend.updateSettings { it.copy(pushEnabled = v) }
                    if (v) wakePush.turnOn()                    // POST_NOTIFICATIONS prompt (only here, never at launch) → FCM token → relay
                    scope.launch { backend.syncPushToken() }   // off → unregister now
                }
                WakePushFootnote(settings.pushEnabled)          // iOS footnote / "blocked" / "unavailable in this build"
            }
            CfgRow.READ_RECEIPTS -> ToggleRow(stringResource(R.string.cfg_read_receipts), settings.readReceiptsOn) { v -> backend.updateSettings { it.copy(sendReadReceipts = v) } }
            CfgRow.TYPING -> ToggleRow(stringResource(R.string.cfg_typing), settings.typingOn) { v -> backend.updateSettings { it.copy(sendTyping = v) } }
            CfgRow.PROXY -> ValueRow(stringResource(R.string.cfg_socks_proxy), settings.socksProxy ?: "off", label = "Edit SOCKS proxy") { urlDraft = settings.socksProxy ?: ""; editProxy = true }
            CfgRow.DESKTOP_LINK -> when (val m = mirror) {
                is MirrorState.Linked -> Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cfg_linked, m.browserName ?: "browser", m.sas), style = mono(12), color = t.flame)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cfg_since, Format.shortClock(m.since)), style = mono(10), color = t.fgDim)
                    Spacer(Modifier.width(8.dp))
                    Glyph(stringResource(R.string.cfg_unlink), 12, weight = FontWeight.Bold, color = t.alert, label = "Unlink browser") { backend.stopMirror() }
                }
                MirrorState.Idle, is MirrorState.Failed -> ButtonRow(stringResource(R.string.cfg_link_desktop), label = "Link a desktop browser", onClick = onLinkDesktop)
            }

            // ---- APPEARANCE ----------------------------------------------------------------
            CfgRow.THEME -> PickerRow(stringResource(R.string.cfg_theme), settings.theme, com.pyrochat.app.ui.theme.Palette.themeNames) { v -> backend.updateSettings { it.copy(theme = v) } }
            // docs/TEXT-SIZE.md §1 — directly under `theme`; the row shows the rendered word, so an unknown store value reads as `normal`
            CfgRow.TEXT_SIZE -> PickerRow(stringResource(R.string.cfg_text_size), TextSize.name(settings.textSize), TextSize.names) { v -> backend.updateSettings { it.copy(textSize = v) } }
            CfgRow.SCANLINES -> ToggleRow(stringResource(R.string.cfg_scanlines), settings.scanlines) { v -> backend.updateSettings { it.copy(scanlines = v) } }
            CfgRow.SOUNDS -> ToggleRow(stringResource(R.string.cfg_sounds), settings.soundsOn) { v -> backend.updateSettings { it.copy(soundsEnabled = v) } }
            // operator notices: the toggle shows `!hideNotices`; the store key is `hideNotices` (identical on iOS)
            CfgRow.NOTICES -> {
                ToggleRow(stringResource(R.string.cfg_notices), settings.noticesOn) { v -> backend.updateSettings { it.copy(hideNotices = !v) } }
                Footnote(stringResource(R.string.cfg_notices_footnote))
            }

            // ---- ACCOUNT -------------------------------------------------------------------
            // ACCESS_KEY_UI (app/build.gradle.kts): debug only until access keys unlock something again
            CfgRow.SUBSCRIPTION -> if (BuildConfig.ACCESS_KEY_UI) ValueRow(stringResource(R.string.cfg_subscription), if (accessDays > 0) stringResource(R.string.cfg_days_remaining, accessDays) else stringResource(R.string.cfg_no_access_key))
            CfgRow.ACCESS_KEY -> if (BuildConfig.ACCESS_KEY_UI) {
                ButtonRow(stringResource(R.string.cfg_enter_access_key)) { showRedeem = true }
                redeemStatus?.let { StatusLine(it) }
            }
            CfgRow.BACKUP -> {
                ButtonRow(stringResource(R.string.cfg_export_backup), label = "Export encrypted backup") { showBackupPass = true }
                backupStatus?.let { StatusLine(it) }
                Footnote(stringResource(R.string.cfg_backup_footnote), dimmer = true)
            }
            CfgRow.BLOCKED -> if (blocked.isEmpty()) {
                ValueRow(stringResource(R.string.cfg_blocked_users), "none")
            } else {
                blocked.sorted().forEach { h ->
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("<${contacts.firstOrNull { it.hash == h }?.name ?: (h.take(12) + "…")}>", style = mono(12), color = t.flame)
                        Spacer(Modifier.weight(1f))
                        Glyph(stringResource(R.string.cfg_unblock), 12) { backend.unblockContact(h) }
                    }
                    TerminalDivider(0.3f, Modifier.padding(start = 16.dp))
                }
            }
            CfgRow.REPORT_ABUSE -> ValueRow(stringResource(R.string.cfg_report_abuse), stringResource(R.string.cfg_report_abuse_value))
            CfgRow.SUPPORT -> ValueRow(stringResource(R.string.cfg_support), stringResource(R.string.cfg_support_value))
            // AGPL notice (libsignal) + OFL (IBM Plex Mono): docs/ANDROID-RELEASE.md §6 — next to `support`, the iOS "about" spot
            CfgRow.LICENSES -> ValueRow(stringResource(R.string.cfg_licenses), stringResource(R.string.cfg_licenses_value), label = "Open licenses", onClick = onLicenses)
            CfgRow.ADVANCED -> if (ServerConfig.privateServerUi) {
                ButtonRow(stringResource(R.string.cfg_advanced), bold = false, label = "Advanced server settings") { showAdvanced = !showAdvanced }
                if (showAdvanced) {
                    ValueRow(stringResource(R.string.cfg_relay_address), settings.relayURL, label = "Edit relay address") { urlDraft = settings.relayURL; editRelay = true }
                    ValueRow(stringResource(R.string.cfg_billing_address), settings.billingURL, label = "Edit billing address") { urlDraft = settings.billingURL; editBilling = true }
                }
            }

            // ---- DANGER --------------------------------------------------------------------
            CfgRow.BURN_EVERYTHING -> ButtonRow(stringResource(R.string.cfg_burn_everything), color = t.alert) { confirmWipe = true }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.nav_back), onBack) },
            title = { Text(stringResource(R.string.home_cfg), style = mono(15, FontWeight.SemiBold)) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // docs/STATUS-AND-CFG.md §2 — the first row of the screen, above every section, and
            // the only way to the status screen.
            ButtonRow(stringResource(R.string.cfg_what_protects_me), label = "What protects me", onClick = onStatus)

            CfgLayout.sections.forEach { (section, rowsInSection) ->
                SectionHeader(
                    stringResource(section.title),
                    Modifier.onGloballyPositioned { sectionY[section] = (it.positionInParent().y + scroll.value).toInt() },
                )
                rowsInSection.forEach { r ->
                    Column(Modifier.onGloballyPositioned { rowY[r] = (it.positionInParent().y + scroll.value).toInt() }) {
                        renderRow(r)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- alerts -----------------------------------------------------------------------
    if (showBackupPass) {
        TerminalAlert(
            title = stringResource(R.string.dlg_backup_pass), message = stringResource(R.string.dlg_backup_pass_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_export)) {
                    val p1 = backupPass; val p2 = backupPass2
                    backupPass = ""; backupPass2 = ""
                    when {
                        // Minimum 4, by the owner's decision (was 12). The file IS
                        // the account, and no wipe in the app reaches a copy of it in
                        // someone's cloud storage — so a short passphrase is opened in
                        // seconds by anyone holding it. The app does not block that, it
                        // says so: same principle as everywhere else here, name what a
                        // choice costs rather than refusing without explaining.
                        p1.length < 4 -> backupStatus = context.getString(R.string.backup_status_short)
                        p1 != p2 -> backupStatus = context.getString(R.string.backup_status_mismatch)
                        else -> scope.launch {
                            backupStatus = try {
                                context.getString(
                                    if (p1.length < 12) R.string.backup_status_ready_weak
                                    else R.string.backup_status_ready,
                                    Format.byteLabel(backend.exportBackup(p1).toLong()))
                            } catch (e: BackendException) {
                                context.getString(R.string.backup_status_failed)
                            }
                        }
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) { backupPass = ""; backupPass2 = "" },
            ),
            onDismiss = { showBackupPass = false },
        ) {
            AlertField(backupPass, { backupPass = it }, stringResource(R.string.dlg_backup_pass_field1), secure = true)
            AlertField(backupPass2, { backupPass2 = it }, stringResource(R.string.dlg_backup_pass_field2), secure = true)
        }
    }
    if (editProxy) {
        TerminalAlert(
            title = stringResource(R.string.dlg_socks), message = stringResource(R.string.dlg_socks_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_set_reconnect)) {
                    val v = urlDraft.trim()
                    val value = if (parseProxy(v)) v else null
                    backend.stopMirror()
                    backend.updateSettings { it.copy(socksProxy = value) }
                    backend.goOffline()
                    scope.launch { backend.goOnline() }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { editProxy = false },
        ) {
            AlertField(urlDraft, { urlDraft = it }, stringResource(R.string.dlg_socks_field))
        }
    }
    // docs/STATUS-AND-CFG.md §2 + docs/SELF_HOSTING.md — reachable only from `advanced …`, which
    // only a private-server build draws. Both reconnect exactly the way the proxy editor does.
    if (editRelay) {
        TerminalAlert(
            title = stringResource(R.string.dlg_relay), message = stringResource(R.string.dlg_relay_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_set_reconnect)) {
                    val v = urlDraft.trim()
                    if (v.startsWith("ws://") || v.startsWith("wss://")) {
                        backend.stopMirror()
                        backend.updateSettings { it.copy(relayURL = v) }
                        backend.goOffline()
                        scope.launch { backend.goOnline() }
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { editRelay = false },
        ) {
            AlertField(urlDraft, { urlDraft = it }, stringResource(R.string.dlg_relay_field))
        }
    }
    if (BuildConfig.PRIVATE_SERVER_UI && editBilling) {
        TerminalAlert(
            title = stringResource(R.string.dlg_billing), message = stringResource(R.string.dlg_billing_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_set_reconnect)) {
                    val v = urlDraft.trim()
                    if (v.startsWith("http://") || v.startsWith("https://")) backend.updateSettings { it.copy(billingURL = v) }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { editBilling = false },
        ) {
            AlertField(urlDraft, { urlDraft = it }, stringResource(R.string.dlg_billing_field))
        }
    }
    if (BuildConfig.ACCESS_KEY_UI && showRedeem) {
        TerminalAlert(
            title = stringResource(R.string.dlg_access_key),
            message = stringResource(if (redeeming) R.string.redeem_status_busy else R.string.dlg_access_key_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_redeem)) { redeem(accessKey) },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { showRedeem = false; accessKey = "" },
        ) {
            AlertField(accessKey, { accessKey = it }, stringResource(R.string.dlg_access_key_field), capitalize = true)
        }
    }
    if (showDuress) {
        TerminalAlert(
            title = stringResource(R.string.dlg_duress), message = stringResource(R.string.dlg_duress_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_arm)) {
                    if (duressPin.length >= 4) backend.setDuressPin(duressPin)
                    duressPin = ""
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) { duressPin = "" },
            ),
            onDismiss = { showDuress = false },
        ) {
            AlertField(duressPin, { duressPin = it }, stringResource(R.string.dlg_duress_field), secure = true)
        }
    }
    if (showDecoy) {
        TerminalAlert(
            title = stringResource(R.string.dlg_decoy),
            message = stringResource(if (decoyReplacing) R.string.dlg_decoy_replace_msg else R.string.dlg_decoy_msg),
            buttons = listOf(
                DialogButton(stringResource(if (decoyReplacing) R.string.btn_decoy_replace else R.string.btn_decoy_create), destructive = decoyReplacing) {
                    val u = decoyUser.trim()
                    val p = decoyPass
                    decoyUser = ""; decoyPass = ""
                    if (u.isEmpty() || p.isEmpty() || decoyBusy) return@DialogButton
                    decoyBusy = true
                    decoyStatus = context.getString(R.string.decoy_status_busy)
                    scope.launch {
                        // §3: the relay registration is real, so this can fail in every ordinary way
                        val err = backend.setDecoyPassword(u, p)
                        decoyStatus = err?.let { "✗ $it" }
                            ?: context.getString(if (decoyReplacing) R.string.decoy_status_replaced else R.string.decoy_status_ok)
                        decoyBusy = false
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) { decoyUser = ""; decoyPass = "" },
            ),
            onDismiss = { showDecoy = false },
        ) {
            AlertField(decoyUser, { decoyUser = it }, stringResource(R.string.dlg_decoy_user))
            AlertField(decoyPass, { decoyPass = it }, stringResource(R.string.dlg_decoy_pass), secure = true)
        }
    }

    if (confirmWipe) {
        TerminalAlert(
            title = stringResource(R.string.dlg_total_wipe), message = stringResource(R.string.dlg_total_wipe_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_burn), destructive = true) { scope.launch { backend.totalWipe() } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmWipe = false },
        )
    }
}

/** `RelayClient.parseProxy` — `host:port`, port 1–65535. */
private fun parseProxy(v: String): Boolean {
    val i = v.lastIndexOf(':')
    if (i <= 0 || i == v.lastIndex) return false
    val port = v.substring(i + 1).toIntOrNull() ?: return false
    return port in 1..65535
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val t = LocalTerminal.current
    Text("── $title ──", style = mono(11), color = t.fgDim, modifier = modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun RowShell(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    TerminalDivider(0.3f, Modifier.padding(start = 16.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTerminal.current
    RowShell {
        Text(label, style = mono(13), modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = t.bg, checkedTrackColor = t.fg, checkedBorderColor = t.fg,
                uncheckedThumbColor = t.fgDim, uncheckedTrackColor = t.panel, uncheckedBorderColor = t.fgDim,
            ),
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<String>,
    /** §2 "consequence gets weight" — the alert colour for a picker that destroys data. */
    color: androidx.compose.ui.graphics.Color = LocalTerminal.current.fg,
    onSelect: (String) -> Unit,
) {
    val t = LocalTerminal.current
    var open by remember { mutableStateOf(false) }
    RowShell(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = true }) {
        Text(label, style = mono(13), color = color, modifier = Modifier.weight(1f))
        Box {
            Text("$value ↕", style = mono(13), color = t.fgDim)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.panel) {
                options.forEach { MenuRow(it, checked = it == value) { onSelect(it); open = false } }
            }
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, label: String? = null, onClick: (() -> Unit)? = null) {
    val t = LocalTerminal.current
    RowShell(
        if (onClick != null) {
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        } else Modifier,
    ) {
        // docs/TEXT-SIZE.md §3 — the VALUE carries the weight, not the title.
        //
        // A `Row` measures its unweighted children first, at the full width, and hands what is
        // left to the weighted ones. With the weight on the title, `report abuse`'s long value
        // ("in any chat: [block] → REPORT") was measured first, took the row, and left the label
        // about four characters, which then broke mid-word down four lines at `largest` on a
        // 360-dp phone. Weighted the other way the title keeps its natural width at every text
        // size and the value is the one that wraps, which is the right way round: the label names
        // the row. Caught by reading a screenshot back — no test saw it.
        Text(title, style = mono(13))
        Spacer(Modifier.width(8.dp))
        Text(value, style = mono(11), color = t.fgDim, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ButtonRow(text: String, bold: Boolean = true, color: androidx.compose.ui.graphics.Color = LocalTerminal.current.fg, label: String? = null, onClick: () -> Unit) {
    RowShell {
        Glyph(text, 13, weight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color, label = label, onClick = onClick, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusLine(text: String) {
    val t = LocalTerminal.current
    RowShell { Text(text, style = mono(11), color = t.fgDim) }
}

@Composable
private fun Footnote(text: String, dimmer: Boolean = false) {
    val t = LocalTerminal.current
    Text(text, style = mono(10), color = if (dimmer) t.fgDim.copy(alpha = t.fgDim.alpha * 0.8f) else t.fgDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/**
 * `LinkDesktopView` (`ios/PyroChat/UI/LinkDesktopView.swift`) — camera scan of the browser page's
 * `pyro-link` QR → `MirrorLink.parse` + `startMirror` (both inside [Backend.startMirror]); the
 * linked pane once the mirror is up. DEBUG builds also get the simulator's paste field.
 */
@Composable
fun LinkDesktopScreen(backend: Backend, onBack: () -> Unit) {
    val t = LocalTerminal.current
    val context = LocalContext.current
    val mirror by backend.mirror.collectAsStateWithLifecycle()
    var scanError by remember { mutableStateOf<String?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }

    /**
     * `handle(_:)` (`LinkDesktopView.swift:124-152`): true only when a link actually started (the
     * scanner then stops). A `pyro-add:` code gets the contact hint; anything that is not a
     * `pyro-link` code at all is someone's lunch QR — keep scanning silently.
     */
    val handle: (String) -> Boolean = handle@{ raw ->
        if (!LinkQrPayload.isPyroLink(raw)) {
            if (LinkQrPayload.isContactCode(raw)) scanError = LinkQrPayload.ERR_CONTACT_CODE
            return@handle false
        }
        val err = backend.startMirror(raw) // Haptics.tap() / warn() inside
        scanError = err
        err == null
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(leading = { BackButton(stringResource(R.string.home_cfg), onBack) })
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.link_title), style = mono(15, FontWeight.Bold))
            when (val m = mirror) {
                is MirrorState.Linked -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.link_linked), style = mono(16, FontWeight.Bold), color = t.flame)
                    m.browserName?.let { Text(stringResource(R.string.link_to, it), style = mono(13, FontWeight.SemiBold)) }
                    Text(stringResource(R.string.link_code, m.sas), style = mono(20, FontWeight.Bold))
                    Text(stringResource(R.string.link_sas_note), style = mono(11), color = t.fgDim)
                    Text(stringResource(R.string.link_burn_note), style = mono(11), color = t.fgDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Glyph(stringResource(R.string.btn_done), 13, weight = FontWeight.Bold, onClick = onBack)
                        Glyph(stringResource(R.string.btn_unlink), 13, weight = FontWeight.Bold, color = t.alert) { backend.stopMirror() }
                    }
                }
                MirrorState.Idle, is MirrorState.Failed -> {
                    Text(stringResource(R.string.link_instruction), style = mono(12), color = t.fgDim)
                    if (cameraDenied) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.link_denied_title), style = mono(13), color = t.alert)
                            Text(stringResource(R.string.link_denied_body), style = mono(12), color = t.fgDim)
                            Glyph(stringResource(R.string.btn_open_settings), 13, weight = FontWeight.Bold) { openAppSettings(context) }
                        }
                    } else {
                        // `.frame(maxHeight: 380)`; keyed on the mirror state so a dropped link gets a fresh latch
                        key(m) {
                            QrScanner(
                                accept = handle,
                                onDenied = { cameraDenied = true },
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 260.dp, max = 380.dp)
                                    .border(1.dp, t.fg.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    DebugPasteField(
                        stringResource(R.string.link_paste_hint), stringResource(R.string.link_paste_placeholder),
                        button = stringResource(R.string.btn_link),
                    ) { handle(it) }
                }
            }
            // a failed link (hijack guard, relay gone) keeps its reason on screen until the next scan
            (scanError ?: (mirror as? MirrorState.Failed)?.message)?.let { Text("! $it", style = mono(12), color = t.alert) }
        }
    }
}
