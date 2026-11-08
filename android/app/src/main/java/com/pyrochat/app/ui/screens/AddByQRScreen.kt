package com.pyrochat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.ui.qr.AddQrPayload
import com.pyrochat.app.ui.qr.PasteField
import com.pyrochat.app.ui.qr.QrImage
import com.pyrochat.app.ui.qr.QrScanner
import com.pyrochat.app.ui.qr.openAppSettings
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.SuppressScanlines
import com.pyrochat.app.ui.theme.mono
import kotlinx.coroutines.launch

/**
 * `AddByQRView` (`ios/PyroChat/UI/AddByQRView.swift`) — `[qr]`: MY CODE / SCAN. The QR carries
 * the username AND the identity public key, so a successful scan adds the contact and verifies
 * the safety number in one step. State names follow the Swift view.
 *
 * FIRST-RUN.md §3: an access key in either form (`pyro-key:v1:<CODE>` / `pyrochat://redeem/<CODE>`)
 * scanned or pasted here goes to [onAccessKey] — the ACCESS KEY dialog opens with it and submits.
 */
@Composable
fun AddByQRScreen(backend: Backend, onBack: () -> Unit, onAccessKey: (String) -> Unit = {}) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }                 // 0 = my code, 1 = scan
    var status by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    /** bump to rebuild the scanner (fresh one-shot latch) after a failure */
    var scanGeneration by remember { mutableIntStateOf(0) }
    // `.background(Theme.bg.ignoresSafeArea())` — this view has no `.terminalScreen`: no scanlines over the code
    SuppressScanlines()

    /**
     * `handle(_:)`: true only for a well-formed contact code (the scanner then stops while the
     * add runs). Wrong or broken codes keep the camera live and explain themselves.
     */
    val handle: (String) -> Boolean = handle@{ raw ->
        if (busy) return@handle false
        when (val parsed = AddQrPayload.parse(raw)) {
            AddQrPayload.Parsed.DesktopLink -> { status = AddQrPayload.STATUS_DESKTOP_LINK; false }
            AddQrPayload.Parsed.Unreadable -> { status = AddQrPayload.STATUS_UNREADABLE; false }
            AddQrPayload.Parsed.NotAKey -> { if (BuildConfig.ACCESS_KEY_UI) status = AddQrPayload.STATUS_NOT_A_KEY; false }
            AddQrPayload.Parsed.Foreign -> false
            is AddQrPayload.Parsed.AccessKey -> { onAccessKey(parsed.code); true } // the scanner stops: the dialog takes over
            is AddQrPayload.Parsed.Contact -> {
                val username = parsed.username
                busy = true
                status = AddQrPayload.statusOpening(username)
                scope.launch {
                    try {
                        val verified = backend.addContactViaQR(username, parsed.identityKey) // Haptics.tap() inside
                        added = true
                        status = AddQrPayload.statusAdded(username, verified)
                    } catch (e: BackendException) {
                        status = AddQrPayload.statusFailed(username, e.message ?: "") // Haptics.warn() inside
                    } finally {
                        busy = false
                    }
                }
                true
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(leading = { BackButton(stringResource(R.string.nav_back), onBack) })
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.qr_title), style = mono(15, FontWeight.Bold))
            // segmented picker MY CODE / SCAN
            Row(Modifier.fillMaxWidth().border(1.dp, t.fgDim)) {
                listOf(stringResource(R.string.qr_my_code), stringResource(R.string.qr_scan)).forEachIndexed { i, label ->
                    Box(
                        Modifier.weight(1f).background(if (mode == i) t.fg else Color.Transparent)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { mode = i }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = mono(13, FontWeight.Bold), color = if (mode == i) t.bg else t.fg)
                    }
                }
            }
            when {
                mode == 0 -> MyCodePane(backend)
                added -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.qr_channel_open), style = mono(16, FontWeight.Bold), color = t.flame)
                    status?.let { Text(it, style = mono(12)) }
                    Glyph(stringResource(R.string.btn_done), 13, weight = FontWeight.Bold, onClick = onBack)
                }
                cameraDenied -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.qr_denied_title), style = mono(13, FontWeight.Bold), color = t.alert)
                    Text(stringResource(R.string.qr_denied_body), style = mono(11), color = t.fgDim)
                    Glyph(stringResource(R.string.btn_open_settings), 13, weight = FontWeight.Bold) { openAppSettings(context) }
                    // FIRST-RUN.md §2: no camera is no reason to lose the paste field (iOS shows it under the denied pane too)
                    PasteField(stringResource(R.string.qr_paste_hint), stringResource(R.string.qr_paste_placeholder)) { handle(it) }
                }
                else -> {
                    // `.frame(maxWidth: .infinity, minHeight: 260)` sharing the leftover height with the trailing Spacer
                    key(scanGeneration) {
                        QrScanner(
                            accept = handle,
                            onDenied = { cameraDenied = true },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 260.dp)
                                .border(1.dp, t.fg.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                        )
                    }
                    // FIRST-RUN.md §2: on every build — the recipient pastes the shared `pyro-add:` code (or an access key)
                    PasteField(stringResource(R.string.qr_paste_hint), stringResource(R.string.qr_paste_placeholder)) { handle(it) }
                }
            }
            // the added pane already carries the status line (iOS repeats it below the pane)
            if (!added) status?.let { Text(it, style = mono(12), color = if (it.startsWith("!")) t.alert else t.fg) }
            if (mode == 1 && !added && !busy && status?.startsWith("!") == true) {
                Glyph(stringResource(R.string.btn_rescan), 13, weight = FontWeight.Bold) {
                    status = null
                    scanGeneration += 1        // fresh scanner, latch reset
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** `myCode`: the QR (`frame(maxWidth: 260)`, white pad), `<username>` in flame, the note. */
@Composable
private fun MyCodePane(backend: Backend) {
    val t = LocalTerminal.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        backend.myAddQRPayload()?.let { payload ->
            val bytes = remember(payload) { payload.toByteArray(Charsets.UTF_8) }
            QrImage(bytes, Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f), contentDescription = stringResource(R.string.qr_my_code_label))
        }
        Text("<${backend.myUsername}>", style = mono(14, FontWeight.Bold), color = t.flame)
        Text(stringResource(R.string.qr_my_code_note), style = mono(11), color = t.fgDim)
    }
}
