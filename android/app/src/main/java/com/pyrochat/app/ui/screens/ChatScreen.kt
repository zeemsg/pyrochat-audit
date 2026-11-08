package com.pyrochat.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyrochat.app.R
import com.pyrochat.app.platform.FileAccess
import com.pyrochat.app.platform.ImageNormalizer
import com.pyrochat.app.platform.VoiceEffect
import com.pyrochat.app.platform.VoicePlayer
import com.pyrochat.app.platform.VoiceRecorder
import com.pyrochat.app.ui.DetailLine
import com.pyrochat.app.ui.DetailsInput
import com.pyrochat.app.ui.MessageDetails
import com.pyrochat.app.ui.UiRemovedField
import com.pyrochat.app.ui.UiScrubReceipt
import com.pyrochat.app.ui.Backend
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.ChatMessage
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Format
import com.pyrochat.app.ui.GroupUi
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.qr.QrImage
import com.pyrochat.app.ui.Status
import com.pyrochat.app.ui.TerminalLogMath
import com.pyrochat.app.ui.UiGroup
import com.pyrochat.app.ui.UiMember
import com.pyrochat.app.ui.components.AlertField
import com.pyrochat.app.ui.components.BackButton
import com.pyrochat.app.ui.components.DialogButton
import com.pyrochat.app.ui.components.Glyph
import com.pyrochat.app.ui.components.MenuRow
import com.pyrochat.app.ui.components.TerminalAlert
import com.pyrochat.app.ui.components.TerminalConfirmSheet
import com.pyrochat.app.ui.components.TerminalDivider
import com.pyrochat.app.ui.components.TerminalTextField
import com.pyrochat.app.ui.components.TerminalTopBar
import com.pyrochat.app.ui.components.VoiceBeingChanged
import com.pyrochat.app.ui.components.VoiceRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pyrochat.app.ui.theme.LocalTerminal
import com.pyrochat.app.ui.theme.mono
import com.pyrochat.app.ui.theme.monoFixed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** docs/SCRUB-AND-WIPE.md §B3 — long enough to read the line, short enough to stay a flash. */
private const val SCRUB_LINE_MIN_MS = 3_000L

/** `ChatView` — one conversation as a terminal log with a `>` prompt composer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(backend: Backend, hash: String, onBack: () -> Unit) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val messages by backend.messages.collectAsStateWithLifecycle()
    val settings by backend.settings.collectAsStateWithLifecycle()
    val typing by backend.typingPeers.collectAsStateWithLifecycle()
    val voiceBeingChanged by backend.voiceBeingChanged.collectAsStateWithLifecycle()
    val blobProgress by backend.blobProgress.collectAsStateWithLifecycle()
    val rxTransfers by backend.rxTransfers.collectAsStateWithLifecycle()
    val groups by backend.groups.collectAsStateWithLifecycle()
    val groupTyping by backend.groupTyping.collectAsStateWithLifecycle()
    val formerMembers by backend.formerMembers.collectAsStateWithLifecycle()

    // one screen, two shapes: a 1:1 channel (contact) or a group channel (GROUPS.md §7) — the thread key tells
    val contact = contacts.firstOrNull { it.hash == hash }
    val group = groups.firstOrNull { it.id == hash }
    if (contact == null && group == null) { LaunchedEffect(Unit) { onBack() }; return }
    val log = messages[hash] ?: emptyList()
    val name = contact?.name ?: group!!.name
    val former = formerMembers[hash] ?: emptyList()
    var showMembers by remember { mutableStateOf(false) }
    var verifyHash by remember { mutableStateOf<String?>(null) }
    // `<you>` flame / `<alice>` in her colour / `<bob>?` dim (§5, §7); 1:1 rows keep the flame tag
    fun tag(msg: ChatMessage): Pair<String, Color> {
        if (group == null) return (if (msg.direction == Direction.outgoing) "<you>" else "<$name>") to t.flame
        val st = GroupUi.senderTag(msg, group, former, contacts)
        val colour = when {
            st.dim -> t.fgDim
            st.rgb != null -> Color(0xFF000000L or st.rgb.toLong())
            else -> t.flame
        }
        return st.text to colour
    }

    var draft by remember { mutableStateOf(backend.draft(hash)) }
    var confirmBurn by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var jumpTarget by remember { mutableStateOf<String?>(null) }
    var detailsTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteBothTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var exportMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var viewerMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showAttachBar by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }
    // docs/SCRUB-AND-WIPE.md §B3 — the one line under the compose bar while the attachment is on
    // its way out, and the sheet it opens when tapped. Both go the moment the send returns.
    var scrubNotice by remember { mutableStateOf<UiScrubReceipt?>(null) }
    var scrubSheet by remember { mutableStateOf<UiScrubReceipt?>(null) }
    /**
     * §B3 says the line is "visible before it goes" and "disappears when the message is sent".
     * This app has no staging step — picking a file sends it — so the send is what it waits on,
     * with a floor underneath so a fast send cannot make the line unreadable. (Spec silent on a
     * UI with no staging step — see the report.)
     */
    suspend fun showingScrubLine(receipt: UiScrubReceipt?, send: suspend () -> Unit) {
        if (receipt == null || receipt.removed.isEmpty()) { send(); return }
        scrubNotice = receipt
        val shownAt = System.currentTimeMillis()
        try {
            send()
        } finally {
            val left = SCRUB_LINE_MIN_MS - (System.currentTimeMillis() - shownAt)
            if (left > 0) delay(left)
            if (scrubNotice === receipt) scrubNotice = null
        }
    }
    var ttlMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recElapsed by remember { mutableIntStateOf(0) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(hash) {
        backend.setActiveThread(hash)
        while (true) { delay(1000); now = System.currentTimeMillis() }   // 🔥N ticks
    }
    DisposableEffect(hash) { onDispose { backend.setActiveThread(null) } }
    LaunchedEffect(recording) { recElapsed = 0; while (recording) { delay(1000); recElapsed++ } }
    LaunchedEffect(playingId) {
        val id = playingId ?: return@LaunchedEffect
        val dur = log.firstOrNull { it.id == id }?.voiceDuration ?: 1.0
        val steps = (dur * 10).toInt().coerceAtLeast(1)
        for (i in 1..steps) { delay(100); playProgress = i.toFloat() / steps }
        playingId = null; playProgress = 0f
    }
    fun flash(text: String) {
        attachError = text
        scope.launch { delay(4000); if (attachError == text) attachError = null }
    }
    // ChatView.performExport → ExportPicker: the system save picker; bytes stay in RAM until written where the user picks
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val bytes = pendingExport; pendingExport = null
        val uri = res.data?.data
        if (res.resultCode == android.app.Activity.RESULT_OK && uri != null && bytes != null) {
            if (FileAccess.writeDocument(context.contentResolver, uri, bytes)) flash("\u2713 exported") else flash("ERR: could not export")
        }
    }
    // a refused attachment (`ERR: …`) flashes like iOS `flashError`
    suspend fun attempt(action: suspend () -> Unit) {
        try { action() } catch (e: BackendException) { flash(e.message ?: "ERR: could not send") }
    }
    // --- attachment pickers (ChatView.swift:198-232): photosPicker → ImageNormalizer, fileImporter → AttachmentGate ---
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // §B1: the picked image is staged and scrubbed on a COPY, then normalised from that copy
            val name = withContext(Dispatchers.IO) { FileAccess.queryMeta(context.contentResolver, uri).first } ?: "image"
            val mime = withContext(Dispatchers.IO) { FileAccess.mimeFor(context.contentResolver, uri, name) }
            val staged = withContext(Dispatchers.IO) {
                runCatching { FileAccess.stageAndScrub(context, context.contentResolver, uri, name, mime) }.getOrNull()
            }
            val norm = withContext(Dispatchers.IO) {
                runCatching {
                    if (staged != null) ImageNormalizer.normalize(staged.cleaned) else ImageNormalizer.normalize(context.contentResolver, uri)
                }.getOrNull()
            }
            // §B2: the JPEG re-encode below writes pixels and nothing else, so an image is ALWAYS
            // cleaned here — even one whose container the scrubber has no rewriter for
            val receipt = staged?.let { UiScrubReceipt(name, it.result.removed.map { r -> UiRemovedField(r.label, r.value) }, supported = true) }
            withContext(Dispatchers.IO) { staged?.cleanup() }
            if (norm == null) { flash("ERR: could not read image"); return@launch }
            showAttachBar = false
            showingScrubLine(receipt) { attempt { backend.sendImage(norm.jpeg, norm.width, norm.height, hash, receipt) } }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val picked = withContext(Dispatchers.IO) { FileAccess.readPicked(context.contentResolver, uri) }) {
                is FileAccess.PickResult.Refused -> flash(picked.message)
                is FileAccess.PickResult.Ok -> {
                    showAttachBar = false
                    // §B1: scrubbed on a copy in the cache, before a byte of it is encrypted
                    val work = withContext(Dispatchers.IO) {
                        runCatching { FileAccess.scrubBytes(context, picked.file.name, picked.file.mime, picked.file.bytes) }.getOrNull()
                    }
                    val bytes = withContext(Dispatchers.IO) { runCatching { work?.cleaned?.readBytes() }.getOrNull() } ?: picked.file.bytes
                    val receipt = work?.let {
                        UiScrubReceipt(picked.file.name, it.result.removed.map { r -> UiRemovedField(r.label, r.value) }, it.result.supported)
                    }
                    withContext(Dispatchers.IO) { work?.cleanup() }
                    showingScrubLine(receipt) { attempt { backend.sendFile(bytes, picked.file.name, picked.file.mime, hash, receipt) } }
                }
            }
        }
    }
    // --- voice notes (VoiceRecorder / VoicePlayer): mic permission on first use, like iOS `AVAudioSession.requestRecordPermission` ---
    val recorder = remember { VoiceRecorder(context) }
    val player = remember { VoicePlayer(context) }
    val playerId by player.playingId.collectAsStateWithLifecycle()
    val playerProgress by player.progress.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { recorder.cancel(); player.release() } }
    fun beginRecording() {
        if (recorder.begin()) recording = true else flash("ERR: could not start recording")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else flash("ERR: microphone access denied")
    }

    Column(Modifier.fillMaxSize()) {
        TerminalTopBar(
            leading = { BackButton(stringResource(R.string.nav_back), onBack) },
            title = {
                if (group != null) {
                    // `#name · 12` → the members sheet
                    Text(
                        group.title, style = mono(14, FontWeight.SemiBold), color = t.flame,
                        // one 44-dp line: a long name (or a big `text size`) is elided, never wrapped and clipped
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showMembers = true }
                            .semantics { contentDescription = "Members" },
                    )
                } else Row(
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showVerify = true },
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "<$name>", style = mono(14, FontWeight.SemiBold), color = t.flame,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact!!.verified) Text("✓", style = mono(12, FontWeight.Bold), color = t.fg)
                }
            },
            trailing = {
                Glyph(stringResource(R.string.chat_search), 12, weight = FontWeight.Bold, color = t.fgDim, label = "Search in conversation") { showSearch = true }
                if (group == null) {
                Glyph(stringResource(R.string.chat_block), 12, weight = FontWeight.Bold, color = t.fgDim, label = "Block user") { confirmBlock = true }
                Glyph(stringResource(R.string.chat_burn), 12, weight = FontWeight.Bold, color = t.alert, label = "Burn thread") { confirmBurn = true }
                Box {
                    Glyph(stringResource(R.string.chat_ttl), 12, weight = FontWeight.Bold, color = t.fgDim, label = "Self-destruct timer for this conversation") { ttlMenu = true }
                    DropdownMenu(expanded = ttlMenu, onDismissRequest = { ttlMenu = false }, containerColor = t.panel) {
                        Text(stringResource(R.string.chat_ttl_section), style = mono(11), color = t.fgDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        listOf(
                            stringResource(R.string.ttl_global_default) to null,
                            stringResource(R.string.ttl_off) to 0L,
                            "5s" to 5L, "1m" to 60L, "1h" to 3600L, "1d" to 86400L,
                        ).forEach { (label, secs) ->
                            MenuRow(label, checked = contact!!.burnSeconds == secs) { backend.setBurnDefault(secs, hash); ttlMenu = false }
                        }
                    }
                }
                }
            },
        )

        // ---- log ------------------------------------------------------------------
        TerminalLog(
            log = log, emptyHeader = if (group != null) stringResource(R.string.chat_header_group_empty, group.name) else stringResource(R.string.chat_header_empty, name),
            modifier = Modifier.weight(1f), jumpTarget = jumpTarget, onJumped = { jumpTarget = null },
            footer = {
                rxTransfers.filter { it.thread == hash }.sortedBy { it.id }.forEach { tr ->
                    val pct = if (tr.total > 0) (100 * tr.received / tr.total).toInt() else 0
                    Text("[..] RX ${tr.subtype} $pct%", style = mono(11), color = t.fgDim)
                }
            },
        ) { msg ->
            val needsReport = backend.needsVisibilityReport(msg)
            if (needsReport) {
                LaunchedEffect(msg.id) { delay(500); backend.noteRowVisible(msg.id, hash) }   // VisibilityReporter dwell
            }
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = {}, onLongClick = { menu = true },
            )) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Text("[${Format.clock(msg.timestamp)}]", style = mono(11), color = t.fgDim)
                    Box(Modifier.weight(1f)) {
                        val (tagText, tagColour) = tag(msg)
                        when (msg.kind) {
                            Kind.system -> Text(msg.text, style = mono(12), color = t.alert)
                            Kind.voice -> VoiceLine(
                                msg, tagText, tagColour, playerId == msg.id || playingId == msg.id,
                                if (playerId == msg.id) playerProgress else playProgress, backend.voiceBars(msg),
                            ) {
                                // real bytes → VoicePlayer (progress from the player); no bytes → the placeholder sweep
                                val bytes = backend.voiceData(msg)
                                if (bytes != null) player.toggle(msg.id, bytes)
                                else playingId = if (playingId == msg.id) null else msg.id
                            }
                            Kind.image -> ImageLine(msg, tagText, tagColour, backend.attachmentData(msg), onOpen = { viewerMessage = msg }, onExport = { exportMessage = msg })
                            Kind.file -> FileLine(msg, tagText, tagColour, onExport = { exportMessage = msg })
                            Kind.text -> Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = tagColour, fontWeight = FontWeight.SemiBold)) {
                                        append("$tagText ")
                                    }
                                    if (msg.viaWeb == true) withStyle(SpanStyle(color = t.fgDim, fontSize = 10.sp)) { append("[web] ") }
                                    append(msg.text)
                                },
                                style = mono(13),
                            )
                        }
                    }
                    TrailingStatus(
                        msg, blobProgress[msg.id], settings.readReceiptsOn, now,
                        // own group rows read `READ 7/9` / `DELIVERED 4/9` (§1)
                        word = if (group != null && msg.direction == Direction.outgoing) GroupUi.statusWord(msg, group, backend.myHash, settings.readReceiptsOn) else null,
                    ) {
                        scope.launch { backend.retry(msg.id, hash) }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = t.panel) {
                    if (msg.kind == Kind.text) MenuRow(stringResource(R.string.ctx_copy)) {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(sensitiveClip(msg.text)); menu = false
                    }
                    // MESSAGE-DETAILS.md §1: `details` for EVERY row — system, burn and attachments too
                    MenuRow(stringResource(R.string.ctx_details)) { detailsTarget = msg; menu = false }
                    MenuRow(stringResource(R.string.ctx_delete_for_me), destructive = true) { deleteTarget = msg; menu = false }
                    if (msg.direction == Direction.outgoing) MenuRow(stringResource(if (group != null) R.string.ctx_delete_for_all else R.string.ctx_delete_for_both), destructive = true) { deleteBothTarget = msg; menu = false }
                }
            }
        }

        // ---- status strip ---------------------------------------------------------
        if (group != null) {
            GroupUi.typingStrip(group, groupTyping[hash] ?: emptySet())?.let { strip ->
                Text(strip, style = mono(11), color = t.fgDim, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp))
            }
        } else if (typing.contains(hash)) {
            Text(stringResource(R.string.chat_typing, name), style = mono(11), color = t.fgDim, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp))
        }
        attachError?.let {
            Text(it, style = mono(12), color = t.alert, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        // docs/SCRUB-AND-WIPE.md §B3 — `removed author, gps and 1 more from report.pdf`, tappable
        scrubNotice?.let { receipt ->
            Text(
                stringResource(R.string.chat_scrub_removed, MessageDetails.removedSummary(receipt.removed.map { it.label }), receipt.fileName),
                style = mono(11), color = t.fgDim,
                modifier = Modifier.fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scrubSheet = receipt }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { contentDescription = "What was removed" },
            )
        }

        // ---- attach bar -------------------------------------------------------------
        if (showAttachBar) {
            Row(
                Modifier.fillMaxWidth().background(t.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(stringResource(R.string.chat_attach_img), 13) { imagePicker.launch(arrayOf("image/*")) }
                Glyph(stringResource(R.string.chat_attach_file), 13) { filePicker.launch(arrayOf("*/*")) }
                Spacer(Modifier.weight(1f))
                Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, color = t.fgDim) { showAttachBar = false }
            }
        }

        // docs/VOICE-CHANGER.md: while a note is being recorded, the voice it will go out in. The one in
        // use is drawn in the foreground colour; a tap chooses, and the choice is kept, so whoever
        // relies on a changed voice does not have to remember it for every note.
        if (recording && group?.left != true) {
            VoiceRow(settings.voice) { voice ->
                backend.updateSettings { it.copy(voiceChanger = if (voice == VoiceEffect.OFF) null else voice.wire) }
            }
        }
        // and once it is sent, until its row is there: a long note takes a moment to change
        if (hash in voiceBeingChanged) VoiceBeingChanged()

        // ---- composer -----------------------------------------------------------------
        if (group?.left == true) {
            // removed or left: the thread stays readable, nothing can be sent (§1, §7)
            Text(GroupUi.ERR_NOT_IN_GROUP, style = mono(12), color = t.alert, modifier = Modifier.fillMaxWidth().background(t.panel).padding(12.dp))
        } else Row(
            Modifier.fillMaxWidth().background(t.panel).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Text("● REC ${recElapsed}s", style = mono(14, FontWeight.Bold), color = t.alert)
                Spacer(Modifier.weight(1f))
                Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, color = t.alert) { recorder.cancel(); recording = false }
                Glyph(stringResource(R.string.chat_rec_send), 13, weight = FontWeight.Bold) {
                    val rec = recorder.finish()   // null = shorter than 0.5 s (discarded, like iOS)
                    recording = false
                    if (rec == null) flash("ERR: recording too short")
                    // the backend sends it in the voice chosen above, or refuses: never as it was spoken
                    else scope.launch { attempt { backend.sendVoice(rec.bytes, rec.durationSeconds, hash) } }
                }
            } else {
                Text(">", style = mono(15, FontWeight.Bold))
                TerminalTextField(
                    draft,
                    { text ->
                        draft = text
                        backend.setDraft(text, hash)
                        if (text.isNotEmpty()) backend.noteComposerActivity(hash)
                    },
                    // one to four lines of type, so the box grows with the type (docs/TEXT-SIZE.md)
                    Modifier.weight(1f).heightIn(min = (25 * t.textScale).dp, max = (84 * t.textScale).dp)
                        // docs/DECOY-ACCOUNT.md §1: the composer losing focus is one of the moments a
                        // draft is allowed to cost a 2 MiB write. Typing costs none.
                        .onFocusChanged { if (!it.isFocused) backend.persistDeferred() },
                    size = 14, singleLine = false, maxLines = 4, contentDescription = "Message",
                )
                Glyph(stringResource(R.string.home_plus), 13, weight = FontWeight.Bold, label = "Attach image or file") { showAttachBar = !showAttachBar }
                Glyph(stringResource(R.string.chat_mic), 13, label = "Record voice note") {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                Glyph(stringResource(R.string.chat_tx), 13, weight = FontWeight.Bold, label = "Send") {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Glyph
                    draft = ""
                    backend.setDraft("", hash)
                    scope.launch { backend.sendText(text, hash) }
                }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------------------
    if (confirmBlock) {
        TerminalConfirmSheet(
            title = stringResource(R.string.dlg_block_title, name),
            message = stringResource(R.string.dlg_block_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_block)) { backend.blockContact(hash) },
                DialogButton(stringResource(R.string.btn_report_block), destructive = true) { scope.launch { backend.reportAndBlock(hash) } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmBlock = false },
        )
    }
    deleteBothTarget?.let { m ->
        TerminalConfirmSheet(
            title = stringResource(if (group != null) R.string.dlg_rm_all_title else R.string.dlg_rm_both_title),
            message = if (group != null) stringResource(R.string.dlg_rm_all_msg) else stringResource(R.string.dlg_rm_both_msg, name),
            buttons = listOf(
                DialogButton(stringResource(if (group != null) R.string.btn_delete_for_all else R.string.btn_delete_for_both), destructive = true) {
                    scope.launch {
                        if (!backend.deleteForBoth(m.id, hash)) flash(backend.lastError.value ?: context.getString(R.string.err_could_not_retract))
                    }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { deleteBothTarget = null },
        )
    }
    deleteTarget?.let { m ->
        TerminalAlert(
            title = stringResource(R.string.dlg_rm_message), message = stringResource(R.string.dlg_rm_message_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_rm_f), destructive = true) { backend.deleteMessage(m.id, hash) },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { deleteTarget = null },
        )
    }
    if (confirmBurn) {
        TerminalAlert(
            title = stringResource(R.string.dlg_burn_thread), message = stringResource(R.string.dlg_burn_thread_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_burn), destructive = true) { scope.launch { backend.burnThread(hash) } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmBurn = false },
        )
    }
    exportMessage?.let {
        TerminalAlert(
            title = stringResource(R.string.dlg_export), message = stringResource(R.string.dlg_export_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_export), destructive = true) {
                    val bytes = backend.attachmentData(it)
                    if (bytes == null) flash(context.getString(R.string.img_missing))
                    else runCatching {
                        pendingExport = bytes
                        val mime = if (it.kind == Kind.image) "image/jpeg" else (it.fileMime ?: "application/octet-stream")
                        exportLauncher.launch(FileAccess.createDocumentIntent(FileAccess.exportFileName(it.kind == Kind.image, it.id, it.fileName), mime))
                    }.onFailure { pendingExport = null; flash("ERR: could not export") }
                },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { exportMessage = null },
        )
    }
    viewerMessage?.let { m ->
        ImageViewer(bytes = backend.attachmentData(m), onClose = { viewerMessage = null }, onExport = { viewerMessage = null; exportMessage = m })
    }

    if (showSearch) {
        val matches = remember(log, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) emptyList() else log.filter {
                it.kind != Kind.system && (it.text.contains(q, true) || (it.fileName?.contains(q, true) ?: false))
            }
        }
        ModalBottomSheet(onDismissRequest = { showSearch = false; searchQuery = "" }, sheetState = rememberModalBottomSheetState(), containerColor = t.bg, contentColor = t.fg, dragHandle = null) {
            Column(Modifier.fillMaxWidth().heightIn(min = 360.dp)) {
                Row(Modifier.fillMaxWidth().background(t.panel).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.grep), style = mono(13), color = t.fgDim)
                    TerminalTextField(searchQuery, { searchQuery = it }, Modifier.weight(1f), size = 13)
                    Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, label = "Close search") { showSearch = false; searchQuery = "" }
                }
                if (matches.isEmpty()) {
                    Text(
                        stringResource(if (searchQuery.isEmpty()) R.string.chat_search_hint else R.string.grep_no_match),
                        style = mono(12), color = t.fgDim, modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(matches.reversed(), key = { it.id }) { m ->
                            Column(
                                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    showSearch = false; searchQuery = ""; jumpTarget = m.id
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("[${Format.clock(m.timestamp)}] ${tag(m).first}", style = mono(10), color = t.fgDim)
                                Text(m.text, style = mono(12), maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }

    detailsTarget?.let { m ->
        DetailsSheet(
            lines = MessageDetails.render(
                DetailsInput(
                    message = m,
                    frame = backend.capturedFrame(m.id),
                    scrub = backend.scrubReceipt(m.id),
                    group = group,
                    former = former,
                    contacts = contacts,
                    peerName = name,
                    myHash = backend.myHash,
                    showRead = settings.readReceiptsOn,
                ),
            ),
            onClose = { detailsTarget = null },
        )
    }

    // §B3: "Tapping it opens the same list." — literally the same renderer, the same words
    scrubSheet?.let { receipt ->
        DetailsSheet(lines = MessageDetails.removedSection(receipt), onClose = { scrubSheet = null })
    }

    if (showVerify) {
        VerifySheet(backend, hash, onClose = { showVerify = false })
    }
    if (group != null && showMembers) {
        MembersSheet(backend, group, contacts, onClose = { showMembers = false }, onVerify = { verifyHash = it }, onFlash = ::flash)
    }
    verifyHash?.let { h ->
        VerifySheet(backend, h, onClose = { verifyHash = null }, fallbackName = group?.member(h)?.name)
    }
}

/**
 * docs/MESSAGE-DETAILS.md §2 — the details sheet. Terminal styling, the app's dividers, and not
 * one word of its own: every line comes from [MessageDetails.render], which is a pure function and
 * the only thing under test. The sheet knows nothing about keys, sessions or message text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(lines: List<DetailLine>, onClose: () -> Unit) {
    val t = LocalTerminal.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.bg, contentColor = t.fg, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.details_title), style = mono(14, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, color = t.fgDim, label = "Close", onClick = onClose)
            }
            lines.forEach { line ->
                when (line) {
                    is DetailLine.Heading -> {
                        Spacer(Modifier.height(7.dp))
                        Text(line.text, style = mono(12, FontWeight.SemiBold), color = t.fgDim)
                        Spacer(Modifier.height(1.dp))
                    }
                    is DetailLine.Field -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // the 14-wide label column is monospace, so its box scales with the type (docs/TEXT-SIZE.md)
                        Text(line.label, style = mono(12), color = t.fgDim, modifier = Modifier.width((88 * t.textScale).dp))
                        Text(line.value, style = mono(12), modifier = Modifier.weight(1f))
                    }
                    is DetailLine.Member -> Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            line.name, style = mono(12, FontWeight.SemiBold),
                            color = line.rgb?.let { Color(0xFF000000L or it.toLong()) } ?: t.fg,
                            // a long member name pushes the value along instead of wrapping under itself
                            modifier = Modifier.widthIn(min = (76 * t.textScale).dp),
                        )
                        Text(line.value, style = mono(12), color = t.fgDim, modifier = Modifier.weight(1f))
                    }
                    is DetailLine.Frame -> Text(line.text, style = mono(11), color = t.flame, modifier = Modifier.fillMaxWidth())
                    is DetailLine.Text -> Text(line.text, style = mono(11), color = t.fgDim, modifier = Modifier.fillMaxWidth())
                    is DetailLine.Footer -> {
                        Spacer(Modifier.height(10.dp))
                        TerminalDivider(0.4f)
                        Spacer(Modifier.height(8.dp))
                        Text(line.text, style = mono(10), color = t.fgDim, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * GROUPS.md §7 members sheet: `── members ──`, rows `<alice>` in her colour + `verified ✓` /
 * `[safety number]`, `joined 12:04`, `[remove]` for the creator, `[ add member ]`,
 * `[ leave group ]`, policy + burn default rows for the creator, `[ rename ]`, and — creator only,
 * below a divider — `[ BURN GROUP ]` with the §6b confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(
    backend: Backend,
    group: UiGroup,
    contacts: List<com.pyrochat.app.ui.Contact>,
    onClose: () -> Unit,
    onVerify: (String) -> Unit,
    onFlash: (String) -> Unit,
) {
    val t = LocalTerminal.current
    val scope = rememberCoroutineScope()
    val gone by backend.goneMembers.collectAsStateWithLifecycle()
    val isCreator = group.creator == backend.myHash && !group.left && group.isMember(backend.myHash)
    val canAct = !group.left && group.isMember(backend.myHash)
    var confirmBurn by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(group.name) }
    var policyMenu by remember { mutableStateOf(false) }
    var burnMenu by remember { mutableStateOf(false) }
    suspend fun attempt(action: suspend () -> Unit) {
        try { action() } catch (e: BackendException) { onFlash(e.message ?: "ERR") }
    }
    val ordered = remember(group) { group.members.sortedWith(compareBy<UiMember> { it.joinedAt }.thenBy { it.hash }) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.bg, contentColor = t.fg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.members_title), style = mono(15, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Text(group.title, style = mono(12), color = t.flame)
                Spacer(Modifier.width(10.dp))
                Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, color = t.fgDim, label = "Close", onClick = onClose)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(ordered, key = { it.hash }) { m ->
                    val me = m.hash == backend.myHash
                    val rgb = GroupUi.colour(group, m.hash)
                    val colour = if (me) t.flame else rgb?.let { Color(0xFF000000L or it.toLong()) } ?: t.fg
                    val isGone = gone[group.id]?.contains(m.hash) == true
                    val verified = contacts.firstOrNull { it.hash == m.hash }?.verified == true
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // `[remove]` is the unweighted child, so it is measured first and keeps its
                            // whole label; the name column carries the weight and elides instead (TEXT-SIZE.md)
                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (me) "<you>" else "<${m.name}>", style = mono(13, FontWeight.SemiBold),
                                    color = if (isGone) t.fgDim else colour,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                                if (m.hash == group.creator) Text(stringResource(R.string.members_creator), style = mono(10), color = t.fgDim)
                                if (isGone) Text(stringResource(R.string.members_gone), style = mono(10), color = t.fgDim)
                                if (!me) {
                                    if (verified) Text(stringResource(R.string.members_verified), style = mono(11), color = t.fg)
                                    Glyph(stringResource(R.string.members_safety), 11, color = t.fgDim, singleLine = true, label = "Safety number for ${m.name}") { onVerify(m.hash) }
                                }
                            }
                            if (isCreator && !me) Glyph(stringResource(R.string.members_remove), 11, color = t.alert, singleLine = true, label = "Remove ${m.name}") {
                                scope.launch { attempt { backend.removeMember(group.id, m.hash) } }
                            }
                        }
                        Text(GroupUi.joinedLabel(m), style = mono(10), color = t.fgDim)
                    }
                    TerminalDivider(0.3f)
                }
            }
            if (canAct) {
                // two whole bracket labels: past `large` they no longer share a line, so the second
                // drops to its own instead of wrapping its `]` (docs/TEXT-SIZE.md)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Glyph(stringResource(R.string.btn_add_member), 13, singleLine = true, label = "Add member") { showAdd = true }
                    Glyph(stringResource(R.string.btn_leave_group), 13, color = t.fgDim, singleLine = true, label = "Leave group") { confirmLeave = true }
                }
            }
            if (isCreator) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.members_policy), style = mono(12), color = t.fgDim)
                    Box {
                        Glyph(GroupUi.policyLabel(group.policy) + " ▾", 12, label = "Screenshot policy") { policyMenu = true }
                        DropdownMenu(expanded = policyMenu, onDismissRequest = { policyMenu = false }, containerColor = t.panel) {
                            GroupUi.POLICY_LABELS.forEach { label ->
                                MenuRow(label, checked = GroupUi.policyLabel(group.policy) == label) {
                                    policyMenu = false
                                    scope.launch { attempt { backend.setGroupPolicy(group.id, GroupUi.policyWire(label), group.burnSeconds) } }
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.members_burn_default), style = mono(12), color = t.fgDim)
                    Box {
                        Glyph((if (group.burnSeconds == null) stringResource(R.string.ttl_global_default) else Format.burnLabel(group.burnSeconds.takeIf { it != 0L })) + " ▾", 12, label = "Burn default") { burnMenu = true }
                        DropdownMenu(expanded = burnMenu, onDismissRequest = { burnMenu = false }, containerColor = t.panel) {
                            listOf(stringResource(R.string.ttl_global_default) to null, stringResource(R.string.ttl_off) to 0L, "5s" to 5L, "1m" to 60L, "1h" to 3600L, "1d" to 86400L).forEach { (label, secs) ->
                                MenuRow(label, checked = group.burnSeconds == secs) {
                                    burnMenu = false
                                    scope.launch { attempt { backend.setGroupPolicy(group.id, group.policy, secs) } }
                                }
                            }
                        }
                    }
                }
                Glyph(stringResource(R.string.btn_rename_group), 13, singleLine = true, label = "Rename group") { renameText = group.name; showRename = true }
                TerminalDivider(0.6f)
                Glyph(stringResource(R.string.btn_burn_group), 14, weight = FontWeight.Bold, color = t.alert, singleLine = true, label = "Burn group") { confirmBurn = true }
                Text(GroupUi.HONEST_CLIENT_NOTE, style = mono(10), color = t.fgDim)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmBurn) {
        TerminalAlert(
            title = GroupUi.burnTitle(group), message = GroupUi.BURN_MESSAGE,
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_burn_confirm), destructive = true) { scope.launch { attempt { backend.burnGroup(group.id) }; onClose() } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmBurn = false },
        )
    }
    if (confirmLeave) {
        TerminalAlert(
            title = stringResource(R.string.dlg_leave_group), message = stringResource(R.string.dlg_leave_group_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_leave), destructive = true) { scope.launch { attempt { backend.leaveGroup(group.id) }; onClose() } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { confirmLeave = false },
        )
    }
    if (showRename) {
        TerminalAlert(
            title = stringResource(R.string.dlg_rename_group), message = stringResource(R.string.dlg_rename_group_msg),
            buttons = listOf(
                DialogButton(stringResource(R.string.btn_mv)) { scope.launch { attempt { backend.renameGroup(group.id, renameText) } } },
                DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            ),
            onDismiss = { showRename = false },
        ) {
            AlertField(renameText, { renameText = it }, stringResource(R.string.dlg_mv_name_field))
        }
    }
    if (showAdd) {
        val candidates = contacts.filterNot { group.isMember(it.hash) }
        TerminalConfirmSheet(
            title = stringResource(R.string.dlg_add_member),
            message = if (candidates.isEmpty()) stringResource(R.string.dlg_add_member_none) else stringResource(R.string.dlg_add_member_msg),
            buttons = candidates.map { c ->
                DialogButton("<${c.name}>") { scope.launch { attempt { backend.addMember(group.id, c.hash) } } }
            } + DialogButton(stringResource(R.string.btn_abort), cancel = true) {},
            onDismiss = { showAdd = false },
        )
    }
}

/**
 * `TerminalLogView` — windowed paging (60 / +40 / trim at 120), terminal
 * follow (own sends and tailing readers pin to the bottom), `[▼ N NEW]` chip
 * when scrolled up, header line when history is exhausted.
 */
@Composable
private fun TerminalLog(
    log: List<ChatMessage>,
    emptyHeader: String,
    modifier: Modifier,
    jumpTarget: String?,
    onJumped: () -> Unit,
    footer: @Composable () -> Unit,
    row: @Composable (ChatMessage) -> Unit,
) {
    val t = LocalTerminal.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var windowCount by remember { mutableIntStateOf(TerminalLogMath.INITIAL_WINDOW) }
    var unseen by remember { mutableIntStateOf(0) }
    var lastCount by remember { mutableIntStateOf(log.size) }
    var lastId by remember { mutableStateOf(log.lastOrNull()?.id) }
    val window = log.takeLast(windowCount)
    val historyExhausted = windowCount >= log.size
    val isAtBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 40
        }
    }
    val bottomIndex = window.size + 2   // header/trigger + rows + footer → sentinel

    suspend fun scrollToBottom() = state.scrollToItem(bottomIndex)

    LaunchedEffect(Unit) { scrollToBottom() }
    LaunchedEffect(log.size, log.lastOrNull()?.id) {
        val newCount = log.size
        if (newCount == 0) { windowCount = TerminalLogMath.INITIAL_WINDOW; unseen = 0 }
        else if (newCount > lastCount && log.last().id != lastId) {
            val n = newCount - lastCount
            if (log.last().direction == Direction.outgoing || isAtBottom) { unseen = 0; scrollToBottom() }
            else { windowCount = TerminalLogMath.appended(windowCount, n, atBottom = false); unseen += n }
        } else if (newCount < lastCount) {
            windowCount = minOf(windowCount, maxOf(newCount, TerminalLogMath.INITIAL_WINDOW))
            if (isAtBottom) scrollToBottom()
        }
        lastCount = newCount; lastId = log.lastOrNull()?.id
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            unseen = 0
            if (windowCount > TerminalLogMath.TRIM_THRESHOLD) { windowCount = TerminalLogMath.INITIAL_WINDOW; scrollToBottom() }
        }
    }
    // history trigger: the head item (index 0) is on screen while older pages remain → grow the
    // window. Item 0 is the header itself, so Compose keeps the viewport pinned to it after the
    // prepend (the new oldest rows would show, and the index could never "reach 0" again). So:
    // remember the row that was at the top, restore the viewport to it once the page has landed,
    // and re-arm — the same "prepend keeps the same start row" rule as iOS TerminalLogView.
    var pageAnchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.firstVisibleItemIndex, historyExhausted) {
        if (!historyExhausted && state.firstVisibleItemIndex == 0 && pageAnchor == null) {
            pageAnchor = window.firstOrNull()?.id
            windowCount = TerminalLogMath.grown(windowCount, log.size)
        }
    }
    LaunchedEffect(windowCount) {
        val anchor = pageAnchor ?: return@LaunchedEffect
        val idx = log.takeLast(windowCount).indexOfFirst { it.id == anchor }
        if (idx >= 0) state.scrollToItem(idx + 1)   // +1: the head item precedes the rows
        pageAnchor = null
    }
    LaunchedEffect(jumpTarget) {
        val target = jumpTarget ?: return@LaunchedEffect
        val idx = log.indexOfFirst { it.id == target }
        if (idx >= 0) {
            val needed = log.size - idx
            if (needed > windowCount) windowCount = needed + 5
            delay(50)
            val i = log.takeLast(windowCount).indexOfFirst { it.id == target }
            if (i >= 0) state.animateScrollToItem(i + 1)
        }
        onJumped()
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().onSizeChanged { if (isAtBottom) scope.launch { scrollToBottom() } },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "__head__") {
                if (historyExhausted) {
                    Text(
                        if (log.isEmpty()) emptyHeader else stringResource(R.string.chat_header_beginning),
                        style = mono(11), color = t.fgDim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
            items(window, key = { it.id }) { row(it) }
            item(key = "__footer__") { Column { footer() } }
            item(key = "__bottom__") { Box(Modifier.fillMaxWidth().height(1.dp)) }
        }
        if (unseen > 0 && !isAtBottom) {
            Text(
                "[▼ $unseen NEW]",
                style = mono(11, FontWeight.Bold), color = t.fg,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { unseen = 0; scope.launch { state.animateScrollToItem(bottomIndex) } }
                    .background(t.panel).border(1.dp, t.fgDim).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun TrailingStatus(msg: ChatMessage, progress: Float?, showRead: Boolean, now: Long, word: String? = null, onRetry: () -> Unit) {
    val t = LocalTerminal.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (msg.direction == Direction.outgoing) {
            when {
                progress != null -> Text("TX ${(progress * 100).toInt()}%", style = mono(9), color = t.fgDim)
                msg.status == Status.failed -> Glyph(stringResource(R.string.chat_failed_retry), 9, weight = FontWeight.Bold, color = t.alert, onClick = onRetry)
                else -> Text(word ?: Format.statusWord(msg.status, showRead), style = mono(9), color = t.fgDim)
            }
        }
        val deadline = msg.burnDeadline
        if (deadline != null) {
            Text(Format.burnCountdown(deadline, now), style = mono(10), color = t.alert, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.defaultMinSize(minWidth = 34.dp))
        } else if (msg.burnAfter != null) {
            Text(Format.BURN_ARMED, style = mono(10), color = t.fgDim, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.defaultMinSize(minWidth = 34.dp))
        }
    }
}

@Composable
private fun VoiceLine(msg: ChatMessage, tag: String, tagColour: Color, playing: Boolean, progress: Float, bars: FloatArray, onToggle: () -> Unit) {
    val t = LocalTerminal.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tag, style = mono(13, FontWeight.SemiBold), color = tagColour)
        Glyph(if (playing) "[■]" else "[▶]", 13, enabled = msg.hasVoice, label = if (playing) "Stop voice note" else "Play voice note", onClick = onToggle)
        Row(Modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
            bars.forEachIndexed { i, b ->
                val played = playing && i.toFloat() / bars.size <= progress
                Box(Modifier.width(2.dp).height(maxOf(3f, b * 18f).dp).clip(RoundedCornerShape(50)).background(if (played) t.flame else t.fgDim))
            }
        }
        Text("${(msg.voiceDuration ?: 0.0).toInt()}s", style = mono(11), color = t.fgDim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageLine(msg: ChatMessage, tag: String, tagColour: Color, bytes: ByteArray?, onOpen: () -> Unit, onExport: () -> Unit) {
    val t = LocalTerminal.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = tagColour)) { append(tag) }
                append(" [IMG ${msg.imageWidth ?: 0}x${msg.imageHeight ?: 0}]")
            },
            style = mono(13, FontWeight.SemiBold),
        )
        val w = (msg.imageWidth ?: 4).toFloat()
        val h = (msg.imageHeight ?: 3).toFloat().coerceAtLeast(1f)
        val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        Box(
            Modifier.heightIn(max = 220.dp).aspectRatio(w / h).wrapContentWidth()
                .border(1.dp, t.fg.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                .combinedClickable(onClick = onOpen, onLongClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap, contentDescription = "Image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(stringResource(R.string.img_missing), style = mono(11), color = t.fgDim)
        }
    }
}

@Composable
private fun FileLine(msg: ChatMessage, tag: String, tagColour: Color, onExport: () -> Unit) {
    val t = LocalTerminal.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = tagColour)) { append(tag) }
                append(" [FILE] ${msg.fileName ?: "file"} · ${Format.byteLabel(msg.fileBytes)}")
            },
            style = mono(13),
            modifier = Modifier.weight(1f, fill = false),
        )
        Glyph(stringResource(R.string.chat_export_glyph), 10, color = t.fgDim, onClick = onExport)
    }
}

/** `TerminalImageViewer` — black ground, `[CLOSE]` / `[EXPORT]` (pinch-zoom lands with the media milestone). */
@Composable
private fun ImageViewer(bytes: ByteArray?, onClose: () -> Unit, onExport: () -> Unit) {
    val t = LocalTerminal.current
    // swipe-down-to-close: the picture follows a downward pull, the ground fades, and past the
    // threshold (or on a fling) the viewer closes; a short pull springs back (same on iOS)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 120.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var pull by remember { mutableFloatStateOf(0f) }
    val shown by androidx.compose.animation.core.animateFloatAsState(if (dragging) pull else 0f, label = "viewerPull")
    val y = if (dragging) pull else shown
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - (y / 1500f).coerceIn(0f, 0.6f)))) {
            val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (bitmap != null) Image(
                bitmap, contentDescription = "Image", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { translationY = y; val k = 1f - (y / 6000f).coerceIn(0f, 0.15f); scaleX = k; scaleY = k }
                    .pointerInput(Unit) {
                        var velocity = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; pull = 0f; velocity = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); velocity = dy; pull = (pull + dy).coerceAtLeast(0f) },
                            onDragEnd = { if (pull > threshold || velocity > 40f) onClose() else { dragging = false } },
                            onDragCancel = { dragging = false },
                        )
                    },
            )
            else Text(stringResource(R.string.img_missing), style = mono(13), color = t.fgDim, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(16.dp)) {
                Glyph(stringResource(R.string.viewer_close), 13, weight = FontWeight.Bold, color = t.fg, onClick = onClose)
                Spacer(Modifier.weight(1f))
                Glyph(stringResource(R.string.viewer_export), 13, weight = FontWeight.Bold, color = t.fgDim, onClick = onExport)
            }
        }
    }
}

/** `VerifyView` sheet — 60 digits in 12×5 groups + the QR of the scannable fingerprint (`VerifyView.swift`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifySheet(backend: Backend, hash: String, onClose: () -> Unit, fallbackName: String? = null) {
    val t = LocalTerminal.current
    val contacts by backend.contacts.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { it.hash == hash }
    val name = contact?.name ?: fallbackName ?: "peer"
    val sn = backend.safetyNumber(hash)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.bg, contentColor = t.fg, dragHandle = null) {
        // scrolls: at a large `text size` the 60 digits, the QR and the two notes outrun the sheet
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.verify_title), style = mono(16, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Glyph(stringResource(R.string.x_close), 13, weight = FontWeight.Bold, color = t.fgDim, label = "Close", onClick = onClose)
            }
            Text("<$name>", style = mono(14, FontWeight.SemiBold), color = t.flame)
            if (sn != null) {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, t.fg.copy(alpha = 0.35f), RoundedCornerShape(2.dp)).padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // docs/TEXT-SIZE.md §3: 12 groups of 5 — scales, capped at `large`, letter spacing with it
                    val snStyle = monoFixed(18, FontWeight.Bold)
                    Format.safetyNumberLines(sn).forEach {
                        Text(it, style = snStyle.copy(letterSpacing = LocalTerminal.current.fixedWidthScale.sp))
                    }
                }
                // `qrBlock(sn.scannable)`: 180×180, white quiet pad, centred — the raw libsignal scannable bytes
                remember(hash, sn) { backend.safetyNumberQR(hash) }?.let { scannable ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrImage(scannable, Modifier.size(180.dp), contentDescription = stringResource(R.string.verify_qr_label))
                    }
                }
                Text(stringResource(R.string.verify_body, name), style = mono(12), color = t.fgDim)
                Text(stringResource(R.string.verify_qr_note), style = mono(11), color = t.fgDim)
                if (contact?.verified == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.verify_status_verified), style = mono(13, FontWeight.Bold))
                        Glyph(stringResource(R.string.verify_unverify), 12, color = t.fgDim, label = "Remove verification") { backend.setVerified(false, hash) }
                    }
                } else {
                    Glyph(stringResource(R.string.verify_mark), 14, weight = FontWeight.Bold, label = "Mark safety number verified") { backend.setVerified(true, hash) }
                }
            } else {
                Text(stringResource(R.string.verify_no_key, name), style = mono(12), color = t.alert)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Message text copied on the user's explicit `copy`, flagged sensitive: Android 13+ then hides it
 * from the clipboard overlay and keeps it out of clipboard history / cross-device sync.
 */
internal fun sensitiveClip(text: String): ClipData = ClipData.newPlainText("", text).apply {
    if (Build.VERSION.SDK_INT >= 33) {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
}
