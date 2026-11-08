package com.pyrochat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyrochat.app.R
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/** `NotesListView` (`[txt]`) — empty state only; the encrypted buffers land with the store milestone. */
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val t = LocalTerminal.current
    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.nav_back), onBack) },
            title = { Text(stringResource(R.string.home_txt), style = mono(15, FontWeight.SemiBold)) },
            trailing = { Glyph(stringResource(R.string.home_plus), 15, weight = FontWeight.Bold, label = "New note") {} },
        )
        Text(stringResource(R.string.notes_empty), style = mono(12), color = t.fgDim, modifier = Modifier.padding(16.dp))
    }
}
