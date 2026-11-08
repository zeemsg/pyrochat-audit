package com.pyrochat.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.pyrochat.app.ui.orderedByActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.ChatMessage
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Contact
import com.pyrochat.app.ui.Format
import com.pyrochat.app.ui.GroupUi
import com.pyrochat.app.ui.HomeEntry
import com.pyrochat.app.ui.HomeRules
import com.pyrochat.app.ui.UiGroup
import com.pyrochat.app.ui.UiMember
import com.pyrochat.app.ui.UiNotice
import com.pyrochat.app.ui.UiUpdate
import com.pyrochat.app.ui.homeEntries
import com.pyrochat.app.ui.components.AlertField
import com.pyrochat.app.ui.components.DialogButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.TerminalAlert
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTextField
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono
import kotlinx.coroutines.launch

/** `HomeView` — the session list rendered like a process table. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    backend: Backend,
    onOpenChat: (String) -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
    onAddByQR: () -> Unit,
    onOpenNotices: () -> Unit = {},
    onNewGroup: () -> Unit = {},
) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val messages by backend.messages.collectAsStateWithLifecycle()
    val conn by backend.connState.collectAsStateWithLifecycle()
    val blocked by backend.blockedHashes.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val notices by backend.notices.collectAsStateWithLifecycle()
    val unseenNotices by backend.unseenNotices.collectAsStateWithLifecycle()
    val update by backend.updateAvailable.collectAsStateWithLifecycle()
    val groups by backend.groups.collectAsStateWithLifecycle()
    val former by backend.formerMembers.collectAsStateWithLifecycle()
    val flash by backend.flash.collectAsStateWithLifecycle()
    // the relay's own channel: pinned above every session while there is something to say, unless hidden in [cfg]
    val showNotices = notices.isNotEmpty() && settings.noticesOn

    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newContact by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<HomeEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<HomeEntry?>(null) }

    // 1:1 channels and groups in one activity-sorted list (GROUPS.md §7)
    val visible = remember(contacts, groups, blocked, query, messages) {
        val base = homeEntries(contacts.filterNot { blocked.contains(it.hash) }, groups, messages)
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { e ->
            val label = when (e) { is HomeEntry.Peer -> e.contact.name; is HomeEntry.Channel -> "#" + e.group.name }
            label.contains(q, ignoreCase = true) || (messages[e.key] ?: emptyList()).any { it.text.contains(q, ignoreCase = true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = {
                Glyph(stringResource(R.string.home_cfg), 13, color = t.fgDim, label = "Settings", onClick = onSettings)
                Glyph(stringResource(R.string.home_txt), 13, color = t.fgDim, label = "Notes", onClick = onNotes)
            },
            trailing = {
                // FIRST-RUN.md §2: `[share]` right before `[qr]` — the system share sheet with my username + add-me code
                Glyph(stringResource(R.string.home_share), 13, weight = FontWeight.Bold, color = t.fgDim, label = "Share my username") {
                    shareUsername(context, backend)
                }
                Glyph(stringResource(R.string.home_qr), 13, weight = FontWeight.Bold, color = t.fgDim, label = "Add contact by QR", onClick = onAddByQR)
                Glyph(stringResource(R.string.home_plus), 15, weight = FontWeight.Bold, color = t.fg, label = "Open new channel") { showAdd = true }
            },
        )

        // header: wordmark + link status + uid line
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.wordmark), style = mono(16, FontWeight.Bold), color = t.flame)
                Spacer(Modifier.weight(1f))
                val statusLabel = when (conn) {
                    ConnState.online -> stringResource(R.string.home_link_up)
                    ConnState.offline -> stringResource(R.string.home_link_down)
                    ConnState.connecting, ConnState.authenticating -> stringResource(R.string.home_syncing)
                }
                Glyph(
                    if (conn == ConnState.offline) "$statusLabel ${stringResource(R.string.home_retry)}" else statusLabel,
                    11,
                    color = if (conn == ConnState.online) t.fg else t.alert,
                    enabled = conn == ConnState.offline,
                    label = if (conn == ConnState.offline) "Reconnect" else "Connection status",
                ) { scope.launch { backend.goOnline() } }
            }
            Text(Format.uidLine(backend.myUsername, backend.myHash), style = mono(11), color = t.fgDim)
            // §6b: `#name was burned by the creator` — no thread remains to hold a system row
            flash?.let { Text(it, style = mono(12, FontWeight.Bold), color = t.alert, modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = "Flash" }) }
        }
        TerminalDivider()
        // PROTOCOL.md §"Latest versions": one full-width row under the header block (same spot as iOS:
        // below the uid line, above the search box), whatever the notices toggle says
        update?.let {
            UpdateRow(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TerminalDivider()
        }

        if (HomeRules.showsEmptyState(contacts, groups)) {
            // FIRST-RUN.md §1: three lines, verbatim on both platforms
            Column(Modifier.padding(20.dp).semantics { contentDescription = "No channels yet" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_empty_1), style = mono(13))
                Text(stringResource(R.string.home_empty_2), style = mono(13), color = t.fgDim)
                Text(stringResource(R.string.home_empty_3), style = mono(13), color = t.fgDim)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(t.panel).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.grep), style = mono(12), color = t.fgDim)
                TerminalTextField(query, { query = it }, Modifier.weight(1f), size = 12, contentDescription = "Search conversations")
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (showNotices) {
                item(key = "__pyrochat__") {
                    NoticeRow(notices, unseenNotices, onClick = onOpenNotices) // no swipe actions: nothing to rename or remove
                    TerminalDivider(0.3f, Modifier.padding(start = 16.dp))
                }
            }
            items(visible, key = { it.key }) { entry ->
                val dismiss = rememberSwipeToDismissBoxState()
                // a group renames only for its creator (§1); anyone may drop it from this phone
                val canRename = entry is HomeEntry.Peer || (entry is HomeEntry.Channel && entry.group.creator == backend.myHash && !entry.group.left)
                SwipeToDismissBox(
                    state = dismiss,
                    // swipe reveals the action, the confirmation alert does the rest (allowsFullSwipe: false)
                    onDismiss = { v ->
                        when (v) {
                            SwipeToDismissBoxValue.EndToStart -> if (deleteTarget == null) deleteTarget = entry
                            SwipeToDismissBoxValue.StartToEnd -> if (renameTarget == null && canRename) {
                                renameText = when (entry) { is HomeEntry.Peer -> entry.contact.name; is HomeEntry.Channel -> entry.group.name }
                                renameTarget = entry
                            }
                            SwipeToDismissBoxValue.Settled -> Unit
                        }
                        scope.launch { dismiss.reset() }
                    },
                    backgroundContent = {
                        val toDelete = dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart
                        Box(
                            Modifier.fillMaxSize().background(if (toDelete) t.alert else t.fg.copy(alpha = 0.6f)).padding(horizontal = 20.dp),
                            contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            Text(
                                if (toDelete) stringResource(R.string.home_swipe_rm) else stringResource(R.string.home_swipe_ren),
                                style = mono(12, if (toDelete) FontWeight.Bold else FontWeight.Normal), color = Color.White,
                                modifier = Modifier.semantics { contentDescription = if (toDelete) "Delete conversation" else "Rename contact" },
                            )
                        }
                    },
                ) {
                    when (entry) {
                        is HomeEntry.Peer -> ContactRow(entry.contact, messages[entry.contact.hash] ?: emptyList()) { onOpenChat(entry.contact.hash) }
                        is HomeEntry.Channel -> GroupRow(entry.group, messages[entry.group.id] ?: emptyList(), former[entry.group.id] ?: emptyList(), contacts) { onOpenChat(entry.group.id) }
                    }
                }
                TerminalDivider(0.3f, Modifier.padding(start = 16.dp))
            }
        }
    }

    if (showAdd) {
        TerminalAlert(
            title = stringResource(R.string.dlg_open_channel),
            message = addError ?: stringResource(R.string.dlg_open_channel_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_connect)) {
                    val name = newContact.trim()
                    newContact = ""
                    if (name.isEmpty()) return@DialogButton
                    scope.launch {
                        try {
                            backend.addContact(name)
                            addError = null
                        } catch (e: BackendException) {
                            // re-present with the failure — a silently dismissed alert reads as success
                            addError = "could not open <$name>: ${e.message}"
                            showAdd = true
                        }
                    }
                },
                // GROUPS.md §7: the second button opens `── new group ──`
                DialogButton(stringResource(R.string.btn_new_group)) { newContact = ""; addError = null; onNewGroup() },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) { newContact = ""; addError = null },
            ),
            onDismiss = { showAdd = false },
        ) {
            AlertField(newContact, { newContact = it }, stringResource(R.string.dlg_open_channel_field))
        }
    }

    renameTarget?.let { target ->
        val isGroup = target is HomeEntry.Channel
        TerminalAlert(
            title = stringResource(if (isGroup) R.string.dlg_rename_group else R.string.dlg_mv_name),
            message = stringResource(if (isGroup) R.string.dlg_rename_group_msg else R.string.dlg_mv_name_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_mv)) {
                    when (target) {
                        is HomeEntry.Peer -> backend.renameContact(target.contact.hash, renameText)
                        is HomeEntry.Channel -> scope.launch { runCatching { backend.renameGroup(target.group.id, renameText) } }
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { renameTarget = null },
        ) {
            AlertField(renameText, { renameText = it }, stringResource(R.string.dlg_mv_name_field))
        }
    }

    deleteTarget?.let { target ->
        TerminalAlert(
            title = stringResource(R.string.dlg_rm_channel),
            message = when (target) {
                is HomeEntry.Peer -> stringResource(R.string.dlg_rm_channel_msg)
                is HomeEntry.Channel -> stringResource(if (target.group.left) R.string.dlg_rm_group_left_msg else R.string.dlg_rm_group_msg)
            },
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_rm_rf), destructive = true) {
                    when (target) {
                        is HomeEntry.Peer -> backend.deleteContact(target.contact.hash)
                        is HomeEntry.Channel -> backend.deleteGroup(target.group.id)
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** GROUPS.md §7 home row: `#name` (flame, bold) · `N` dim, subtitle `<alice> text`, unread `[N]`. */
@Composable
private fun GroupRow(group: UiGroup, log: List<ChatMessage>, former: List<UiMember>, contacts: List<Contact>, onClick: () -> Unit) {
    val t = LocalTerminal.current
    val last = log.lastOrNull()
    val unread = Format.unreadCount(log) // system rows (screenshot alerts included) never count, as in 1:1
    Row(
        Modifier.fillMaxWidth().background(t.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "Group #${group.name}" }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${group.name}", style = mono(14, FontWeight.Bold), color = t.flame)
                Text("· ${group.members.size}", style = mono(12), color = t.fgDim)
                if (group.left) Text("[left]", style = mono(10), color = t.fgDim)
                if (unread > 0) {
                    Text("[$unread]", style = mono(11, FontWeight.Bold), color = t.bg, modifier = Modifier.background(t.fg).padding(horizontal = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (last != null) Text(Format.shortClock(last.timestamp), style = mono(10), color = t.fgDim)
            }
            Text(GroupUi.previewLine(last, group, former, contacts), style = mono(11), color = t.fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("›", style = mono(16), color = t.fgDim.copy(alpha = 0.35f))
    }
}

/**
 * `▲ update available: 0.1.0 (24) · tap to get it` — the relay says a newer build exists
 * (PROTOCOL.md §"Latest versions"). One row in flame, not dismissible, never a notice row; tappable
 * only with a url (an `ACTION_VIEW` intent opens it in the browser).
 */
@Composable
private fun UpdateRow(update: UiUpdate, modifier: Modifier = Modifier) {
    val t = LocalTerminal.current
    val context = LocalContext.current
    val url = update.url
    val label =
        if (url != null) stringResource(R.string.home_update_tap, update.name, update.build)
        else stringResource(R.string.home_update, update.name, update.build)
    val tappable = if (url != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } // no browser on this device: nothing to do
        }
    } else Modifier
    Text(
        label,
        style = mono(12),
        color = t.flame,
        modifier = modifier.fillMaxWidth().then(tappable)
            .semantics { contentDescription = "Update available: version ${update.name}, build ${update.build}" },
    )
}

/** The pinned `<pyrochat>` row: same shape as a session row, speaking for the newest operator notice. */
@Composable
private fun NoticeRow(notices: List<UiNotice>, unseen: Int, onClick: () -> Unit) {
    val t = LocalTerminal.current
    val newest = Format.newestNotice(notices)
    Row(
        Modifier.fillMaxWidth().background(t.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "System notices" }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notices_channel), style = mono(14, FontWeight.SemiBold), color = t.flame)
                if (unseen > 0) {
                    Text("[$unseen]", style = mono(11, FontWeight.Bold), color = t.bg, modifier = Modifier.background(t.fg).padding(horizontal = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (newest != null) Text(Format.shortClock(newest.ts), style = mono(10), color = t.fgDim)
            }
            Text(Format.noticeSubtitle(notices), style = mono(11), color = t.fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("›", style = mono(16), color = t.fgDim.copy(alpha = 0.35f))
    }
}

@Composable
private fun ContactRow(contact: Contact, log: List<ChatMessage>, onClick: () -> Unit) {
    val t = LocalTerminal.current
    val last = log.lastOrNull()
    val unread = Format.unreadCount(log)
    Row(
        Modifier.fillMaxWidth().background(t.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("<${contact.name}>", style = mono(14, FontWeight.SemiBold), color = t.flame)
                if (contact.verified) Text("✓", style = mono(12, FontWeight.Bold), color = t.fg)
                if (unread > 0) {
                    Text("[$unread]", style = mono(11, FontWeight.Bold), color = t.bg, modifier = Modifier.background(t.fg).padding(horizontal = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (last != null) Text(Format.shortClock(last.timestamp), style = mono(10), color = t.fgDim)
            }
            // FIRST-RUN.md §1: an empty `pyrobot` thread reads `demo peer — say hi for a 60-second tour`
            Text(HomeRules.subtitle(contact, log), style = mono(11), color = t.fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("›", style = mono(16), color = t.fgDim.copy(alpha = 0.35f))
    }
}

/**
 * `[share]` (FIRST-RUN.md §2): `Intent.ACTION_SEND` text/plain through the system chooser with
 * [HomeRules.shareText] — my username and my `pyro-add:v1:…` code, the same string MY CODE encodes.
 */
private fun shareUsername(context: android.content.Context, backend: Backend) {
    val code = backend.myAddQRPayload() ?: return // no identity yet: nothing to share
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, HomeRules.shareText(backend.myUsername, code))
    runCatching { context.startActivity(Intent.createChooser(send, null)) } // no app can take text: nothing to do
}
