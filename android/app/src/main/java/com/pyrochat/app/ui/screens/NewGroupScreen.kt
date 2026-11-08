package com.pyrochat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.R
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.GroupUi
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.BoxedButton
import com.pyrochat.app.ui.components.LabeledField
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono
import kotlinx.coroutines.launch

/**
 * `── new group ──` (docs/GROUPS.md §7): `name:` field, contact picker (checkboxes, max 19),
 * policy picker (`alert` / `burn for the screenshotter` / `burn and remove`, default the last),
 * `[ CREATE ]`. Refusals flash the verbatim error under the button.
 */
@Composable
fun NewGroupScreen(backend: Backend, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val blocked by backend.blockedHashes.collectAsStateWithLifecycle()
    val pickable = remember(contacts, blocked) { contacts.filterNot { it.hash in blocked } }
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var policy by remember { mutableStateOf(GroupUi.POLICY_LABELS.last()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(leading = { BackButton(stringResource(R.string.nav_back), onBack) })
        Text(stringResource(R.string.ng_title), style = mono(15, FontWeight.Bold), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LabeledField(stringResource(R.string.ng_name), name, { name = it.take(64); error = null }, Modifier.padding(horizontal = 16.dp), placeholder = "ops")
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ng_members), style = mono(12), color = t.fgDim, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (pickable.isEmpty()) {
                item { Text(stringResource(R.string.ng_no_contacts), style = mono(12), color = t.fgDim, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(pickable, key = { it.hash }) { c ->
                val on = c.hash in selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (on) selected = selected - c.hash
                            else if (selected.size >= GroupUi.MAX_PICKED) error = GroupUi.ERR_GROUP_FULL
                            else { selected = selected + c.hash; error = null }
                        }
                        .semantics { contentDescription = (if (on) "Deselect " else "Select ") + c.name }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (on) "[x]" else "[ ]", style = mono(13, FontWeight.Bold), color = if (on) t.fg else t.fgDim)
                    Text("<${c.name}>", style = mono(13, FontWeight.SemiBold), color = t.flame)
                    if (c.verified) Text("✓", style = mono(12, FontWeight.Bold), color = t.fg)
                }
                TerminalDivider(0.3f)
            }
        }
        Column(Modifier.fillMaxWidth().background(t.panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ng_policy), style = mono(12), color = t.fgDim)
            GroupUi.POLICY_LABELS.forEach { label ->
                val on = label == policy
                Row(
                    Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { policy = label }
                        .semantics { contentDescription = "Policy $label" }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (on) "(•)" else "( )", style = mono(13, FontWeight.Bold), color = if (on) t.fg else t.fgDim)
                    Text(label, style = mono(13), color = if (on) t.fg else t.fgDim)
                }
            }
            error?.let { Text(it, style = mono(12), color = t.alert) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size}/${GroupUi.MAX_PICKED}", style = mono(11), color = t.fgDim)
                Spacer(Modifier.weight(1f))
                BoxedButton(stringResource(R.string.btn_create), enabled = !busy && name.isNotBlank() && selected.isNotEmpty(), label = "Create group") {
                    busy = true
                    scope.launch {
                        try {
                            val id = backend.createGroup(name, selected.toList(), GroupUi.policyWire(policy), null)
                            onCreated(id)
                        } catch (e: BackendException) {
                            error = e.message ?: "ERR: could not create group"
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
