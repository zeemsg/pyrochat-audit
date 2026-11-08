package com.pyrochat.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyrochat.app.BuildConfig
import com.pyrochat.app.R
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/** The IBM Plex Mono OFL, shipped verbatim from `app/licenses/` (an assets dir, see app/build.gradle.kts). */
private const val OFL_ASSET = "OFL_IBM_PLEX_MONO.txt"

/** libsignal's own third-party notices (its Rust crates), shipped inside the libsignal-android AAR. */
private const val LIBSIGNAL_ACK_ASSET = "acknowledgments/libsignal.md"

private fun Context.assetLines(name: String): List<String> =
    runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("").lines()

/**
 * `[cfg]` → SAFETY → licenses. The AGPL obligation from `docs/APP_STORE.md` §"AGPL obligations"
 * (Signal's copyright + AGPL notice for libsignal), PyroChat's own AGPLv3 line, the OFL of the
 * bundled IBM Plex Mono faces and, on request, libsignal's 379 KB third-party notices
 * (`docs/ANDROID-RELEASE.md` §6). A LazyColumn: the notices are ~10 k lines.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val t = LocalTerminal.current
    val context = LocalContext.current
    val ofl = remember(context) { context.assetLines(OFL_ASSET).filter { it.isNotBlank() } }
    var showAck by remember { mutableStateOf(false) }
    val ack = remember(showAck) { if (showAck) context.assetLines(LIBSIGNAL_ACK_ASSET) else emptyList() }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.home_cfg), onBack) },
            title = { Text(stringResource(R.string.lic_title), style = mono(15, FontWeight.SemiBold)) },
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { Header(stringResource(R.string.lic_section_pyrochat)) }
            item { Body(stringResource(R.string.lic_pyrochat, BuildConfig.VERSION_NAME)) }
            item { Dim(stringResource(R.string.lic_agpl_notice)) }

            item { Header(stringResource(R.string.lic_section_libsignal)) }
            item { Body(stringResource(R.string.lic_libsignal)) }
            item { Dim(stringResource(R.string.lic_libsignal_use)) }
            item {
                Glyph(
                    if (showAck) stringResource(R.string.lic_ack_hide) else stringResource(R.string.lic_ack_show), 12,
                    weight = FontWeight.Bold, label = "libsignal third-party notices",
                    modifier = Modifier.padding(vertical = 8.dp),
                ) { showAck = !showAck }
            }
            if (showAck) {
                if (ack.isEmpty()) item { Dim(stringResource(R.string.lic_asset_missing, LIBSIGNAL_ACK_ASSET)) }
                items(ack) { line -> Text(line, style = mono(9), color = t.fgDim, modifier = Modifier.fillMaxWidth()) }
            }

            item { Header(stringResource(R.string.lic_section_plex)) }
            item { Body(stringResource(R.string.lic_plex)) }
            if (ofl.isEmpty()) item { Dim(stringResource(R.string.lic_asset_missing, OFL_ASSET)) }
            items(ofl) { line -> Text(line, style = mono(9), color = t.fgDim, modifier = Modifier.fillMaxWidth()) }
            item { Text("", style = mono(12), modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Header(title: String) {
    Text("── $title ──", style = mono(11), color = LocalTerminal.current.fgDim, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = mono(12), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Dim(text: String) {
    Text(text, style = mono(11), color = LocalTerminal.current.fgDim, modifier = Modifier.padding(vertical = 4.dp))
}
