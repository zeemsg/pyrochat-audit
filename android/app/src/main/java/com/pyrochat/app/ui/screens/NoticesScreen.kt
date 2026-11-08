package com.pyrochat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.Format
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/**
 * The `<pyrochat>` channel — operator notices (docs/PROTOCOL.md §"Operator notices") in the same
 * terminal-log dress as a chat: `── system notices ──` at the top, one `[HH:mm:ss] <pyrochat> text`
 * row per notice (a dim, tappable URL line under it when the relay attached one), and where the
 * composer would be a dim footer instead — nobody can reply to the relay. Opening marks every
 * notice seen (so does one arriving while the screen is up).
 */
@Composable
fun NoticesScreen(backend: Backend, onBack: () -> Unit) {
    val t = LocalTerminal.current
    val notices by backend.notices.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val state = rememberLazyListState()

    LaunchedEffect(notices.size, notices.lastOrNull()?.id) {
        backend.markNoticesSeen()
        if (notices.isNotEmpty()) state.scrollToItem(notices.size + 1) // newest at the bottom, like a log
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.nav_back), onBack) },
            title = { Text(stringResource(R.string.notices_channel), style = mono(14, FontWeight.SemiBold), color = t.flame) },
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "__head__") {
                Text(
                    stringResource(R.string.notices_header),
                    style = mono(11), color = t.fgDim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            items(notices, key = { it.id }) { n ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Text("[${Format.clock(n.ts)}]", style = mono(11), color = t.fgDim)
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = t.flame, fontWeight = FontWeight.SemiBold)) { append("${Format.NOTICES_CHANNEL} ") }
                                append(n.text)
                            },
                            style = mono(13),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    n.url?.let { url ->
                        Text(
                            url,
                            style = mono(11), color = t.fgDim,
                            modifier = Modifier.fillMaxWidth().padding(start = 76.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    runCatching { uriHandler.openUri(url) } // no browser on this device: nothing to do
                                }
                                .semantics { contentDescription = "Open $url" },
                        )
                    }
                }
            }
            item(key = "__bottom__") { Box(Modifier.fillMaxWidth().height(8.dp)) }
        }
        TerminalDivider(0.3f)
        // the composer's slot: a footer, since nobody can reply here
        Row(Modifier.fillMaxWidth().background(t.panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.notices_footer), style = mono(11), color = t.fgDim, modifier = Modifier.fillMaxWidth())
        }
    }
}
