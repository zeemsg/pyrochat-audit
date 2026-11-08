package com.pyrochat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.CfgRow
import com.pyrochat.app.ui.StatusGroup
import com.pyrochat.app.ui.StatusModel
import com.pyrochat.app.ui.StatusRow
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono

/**
 * docs/STATUS-AND-CFG.md §1 — one read-only page answering "what is protecting me right now".
 *
 * Reached from the first row of `[cfg]` and from nowhere else. Every row is a fact rather than a
 * control, and tapping one goes to the setting that changes it ([onOpenSetting]); the rows with no
 * setting behind them — the wrong-password count, the verified-contact count — simply do not move.
 *
 * ### Two rules this screen is easy to break
 *
 * **Nothing here scores anything.** The value and the note wear the ordinary foreground and dim
 * colours in every state; an `OFF` painted in the alert colour, or an `ON` in green, would be the
 * grade §1 refuses. The alert colour is `[cfg]`'s, for rows that destroy data, and has no business
 * on a page that only reports.
 *
 * **The decoy is absent, entirely.** Not `SET`, not `NOT SET`, not a greyed row. Everything this
 * screen renders comes from [StatusModel], which has no input that could answer the question —
 * see docs/DECOY-ACCOUNT.md §3, and the tests that assert it over the whole rendered surface
 * rather than over one label.
 *
 * ### Why the note is on its own line
 *
 * §1 sketches three columns. At `largest` on a 360-dp phone (docs/TEXT-SIZE.md §3) a third column
 * holding a sentence cannot exist without either an ellipsis or a horizontal scroll, and the
 * sentence is the feature. So the fact keeps the row — label left, state right — and the sentence
 * gets the line under it, at every text size, so the shape never changes with the type.
 */
@Composable
fun StatusScreen(backend: Backend, onBack: () -> Unit, onOpenSetting: (CfgRow) -> Unit) {
    val settings by backend.settings.collectAsStateWithLifecycle()
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val accessDays by backend.accessDaysRemaining.collectAsStateWithLifecycle()
    val duressSet by backend.duressPinSet.collectAsStateWithLifecycle()
    val biometricOn by backend.biometricEnabled.collectAsStateWithLifecycle()
    val unlockFailures by backend.unlockFailures.collectAsStateWithLifecycle()
    val mirror by backend.mirror.collectAsStateWithLifecycle()
    val senderHidden by backend.senderHidden.collectAsStateWithLifecycle()
    val biometricKind = remember { backend.biometricKind() }
    val scroll = rememberScrollState()

    val groups = StatusModel.groups(
        settings = settings,
        biometricKind = biometricKind,
        biometricOn = biometricOn,
        duressSet = duressSet,
        unlockFailures = unlockFailures,
        contacts = contacts,
        accessDays = accessDays,
        lastBackupAt = settings.lastBackupAt,
        mirror = mirror,
        now = System.currentTimeMillis(),
        senderHidden = senderHidden,
    )

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.home_cfg), onBack) },
            title = { Text(StatusModel.TITLE, style = mono(15, FontWeight.SemiBold)) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            groups.forEach { group -> Group(group, onOpenSetting) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Group(group: StatusGroup, onOpenSetting: (CfgRow) -> Unit) {
    val t = LocalTerminal.current
    Text(
        "── ${group.title} ──",
        style = mono(11),
        color = t.fgDim,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
    group.rows.forEach { row -> Row(row, onOpenSetting) }
}

@Composable
private fun Row(row: StatusRow, onOpenSetting: (CfgRow) -> Unit) {
    val t = LocalTerminal.current
    val target = row.target
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (target != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpenSetting(target) }
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = mono(13), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // the state, never coloured by whether it is the "good" one — §1 refuses grades
            Text(row.value, style = mono(13), color = t.fg)
        }
        if (row.note.isNotEmpty()) Text(row.note, style = mono(10), color = t.fgDim)
    }
    TerminalDivider(0.3f, Modifier.padding(start = 16.dp))
}
