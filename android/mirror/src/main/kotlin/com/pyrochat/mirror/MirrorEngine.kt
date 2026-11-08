package com.pyrochat.mirror

import com.pyrochat.protocol.Wire
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.text.BreakIterator
import java.util.Base64
import java.util.Locale

/**
 * The phone side of the desktop link — port of iOS `MirrorEngine` (`ios/PyroChat/Core/MirrorEngine.swift`).
 *
 * Owns a SECOND, unauthenticated relay socket, the pair-channel crypto, and a shadow copy of what the
 * browser has seen. Everything is RAM-only: auto-lock, backgrounding, unlink and wipe all kill the
 * link; relinking means rescanning a fresh QR.
 *
 * Shape: one long-lived engine per ChatService (iOS keeps `ChatService.mirror: MirrorEngine?` and
 * builds a new engine per scan; here [start] does that internally and [state] is [State.Idle] while
 * nothing is linked — so `Backend.mirror` in `app` can be bound to [state] directly). Each
 * [start] creates a per-link [Session], which is the 1:1 port of the Swift class body.
 *
 * Threading: iOS is `@MainActor`; here every session mutation runs on one serial dispatcher.
 * [start]/[stop] are safe from any thread. Android must call `stop("background")` from its lifecycle
 * observer where iOS listens for `didEnterBackgroundNotification`.
 */
class MirrorEngine(
    private val scope: CoroutineScope,
    private val options: Options = Options(),
    private val transports: MirrorTransportFactory = OkHttpMirrorTransport,
) {
    /** iOS `MirrorEngine.State` (+ `Idle` = `ChatService.mirror == nil`). `sas` is known from the scan on. */
    sealed interface State {
        data object Idle : State
        data class Joining(val sas: String) : State
        /** `since` epoch ms; `browserName` from `hello_ack` (null until the ack, or unusable label). */
        data class Linked(val since: Long, val sas: String, val browserName: String?) : State
        data class Failed(val message: String, val sas: String) : State
        data class Closed(val reason: String, val sas: String) : State
    }

    /** Knobs the tests turn down; production keeps the iOS values. */
    data class Options(
        /** `objectWillChange.debounce(400 ms)` */
        val debounceMs: Long = 400,
        /** `{"t":"ping"}` cadence on the pair socket (relay idle-closes at 90 s). */
        val pingIntervalMs: Long = 30_000,
        /** Epoch-ms clock (tempId stamps, rate limiting, `Linked.since`). */
        val clock: () -> Long = System::currentTimeMillis,
        /** Deterministic phone key for tests; null = fresh random X25519 key per link (iOS `Curve25519.KeyAgreement.PrivateKey()`). */
        val privateKey: (() -> ByteArray)? = null,
    )

    companion object {
        const val MAX_THREADS = 40
        const val ROWS_PER_THREAD = 50
        const val PLAINTEXT_CAP = 32_768
        const val THUMB_BUDGET = 2_000_000
        const val THUMB_MAX_BYTES = 24_000
        const val SEND_TEXT_MAX_CHARS = 4000
        const val MARK_READ_MAX_IDS = 50
        const val SEND_RATE_MAX = 10
        const val SEND_RATE_WINDOW_MS = 10_000L
        /** `unknown_pair` on join: the QR was already claimed — possible hijack. */
        const val HIJACK_MESSAGE = "code already claimed — reload the browser page and rescan"

        /**
         * iOS `MirrorEngine.sanitizeBrowserName`: keep letters/numbers/spaces (Swift `Character.isLetter`
         * = Unicode Alphabetic, `isNumber` = Nd/Nl/No, tested on a grapheme's first scalar), trim
         * spaces, cap at 32 characters (grapheme clusters); null when nothing printable remains.
         */
        fun sanitizeBrowserName(raw: String): String? {
            val kept = ArrayList<String>()
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(raw)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                val cluster = raw.substring(start, end)
                val cp = cluster.codePointAt(0)
                if (cp == ' '.code || UnicodeText.isLetter(cp) || UnicodeText.isNumber(cp)) kept.add(cluster)
                start = end
                end = it.next()
            }
            while (kept.isNotEmpty() && kept.first() == " ") kept.removeAt(0)
            while (kept.isNotEmpty() && kept.last() == " ") kept.removeAt(kept.size - 1)
            if (kept.isEmpty()) return null
            return kept.take(32).joinToString("")
        }

        /** Swift `String.count` — grapheme clusters, not UTF-16 units. */
        internal fun graphemeCount(s: String): Int {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(s)
            var n = 0
            while (it.next() != BreakIterator.DONE) n++
            return n
        }

        private val json = Json

        /** `GroupRules.anonName` — the label for a sender in neither the roster nor the contacts. */
        internal fun anonName(hash: String): String = "anon-" + hash.take(8)

        /**
         * iOS `GroupMath.rowStatus` for a mirrored OUTGOING group row: the aggregate word. Receipts
         * only ever upgrade a settled row — `read` once EVERY recipient read it, `delivered` once
         * every recipient reported, else `sent`; `pending` / `failed` (the fan-out verdict) stay.
         * [recipients] is what the phone fans out to — the roster minus me and the `gone` members
         * (iOS `recipients(of:)`, Android `UiGroup.recipients`) — NOT the `receipts.total` of the
         * row, which stays members − 1 (MIRROR-GROUPS.md §1).
         */
        internal fun aggregateStatus(status: MirrorStatus, receipts: Map<String, String>?, recipients: Int): MirrorStatus {
            if (status == MirrorStatus.pending || status == MirrorStatus.failed) return status
            val map = receipts ?: emptyMap()
            val fromReceipts = when {
                recipients <= 0 || map.size < recipients -> MirrorStatus.sent
                map.values.all { it == "read" } -> MirrorStatus.read
                else -> MirrorStatus.delivered
            }
            return if (fromReceipts.ordinal >= status.ordinal) fromReceipts else status
        }

        /**
         * `who` for a group typing event (iOS `typingNames(in:)` + `maxTypingNames`): the members
         * typing right now in join order (`(joinedAt, hash)`), at most three names (MIRROR-GROUPS.md §1).
         */
        internal fun whoTyping(group: MirrorGroup, typing: Set<String>): List<String> =
            group.members.sortedWith(compareBy<MirrorMember> { it.joinedAt }.thenBy { it.hash })
                .filter { it.hash in typing }.map { it.name }.take(3)

        private fun isUsableRelayUrl(url: String): Boolean = try {
            val u = URI(url)
            u.host != null && u.scheme?.lowercase(Locale.ROOT) in setOf("ws", "wss", "http", "https")
        } catch (_: URISyntaxException) {
            false
        }
    }

    private val serial: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow<State>(State.Idle)

    /** `MirrorEngine.state` + `browserName` + `sas`, folded for the UI. */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var current: Session? = null

    /** The live session's SAS (the 6 digits the phone shows), null while idle. */
    val sas: String? get() = current?.sas

    /** iOS `browserName` — sanitized `hello_ack.browser`, null until the ack. */
    val browserName: String? get() = current?.browserName

    /** Test hook: the session behind [state]. */
    internal val sessionForTest: Session? get() = current

    /**
     * iOS `ChatService.startMirror(_:)`: stop any live link with reason `replaced`, build the
     * ephemeral X25519 key + key schedule (so the SAS is available immediately, like the LINKED pane
     * showing `code <sas>` at scan time), dial the relay and join the pair. Returns the SAS.
     *
     * @throws MirrorCryptoException when the browser key in the QR is unusable (iOS `init` throws).
     */
    fun start(link: MirrorLink, source: MirrorSource): String {
        val session = Session(link, source)
        val old = current
        current = session
        _state.value = State.Joining(session.sas)
        old?.stop("replaced")
        session.start()
        return session.sas
    }

    /** iOS `ChatService.stopMirror()` (`stop(reason: "unlink")` + `mirror = nil` → [State.Idle]). */
    fun stop(reason: String = "unlink") {
        val session = current ?: return
        current = null
        session.stop(reason)
        _state.value = State.Idle
    }

    // -------------------------------------------------------------------------------------------------
    // One link = one session: the 1:1 port of the Swift `MirrorEngine` body.
    // -------------------------------------------------------------------------------------------------

    /** `shadow` entry — iOS `MirrorRowStamp` (+ the receipts map: a group row re-mirrors when a member's receipt lands). */
    private data class RowStamp(val id: String, val status: MirrorStatus, val isBurn: Boolean, val receipts: Map<String, String>? = null)

    /** What a `group` event carries, minus `lastTs`: any change here re-emits the Group. */
    private data class GroupStamp(val name: String, val members: List<MirrorMember>, val policy: String, val left: Boolean)

    private class Outgoing(val obj: JsonObject, val evenIfStopped: Boolean)

    internal inner class Session(val link: MirrorLink, val source: MirrorSource) {
        private val privateKey: ByteArray = options.privateKey?.invoke() ?: MirrorCrypto.randomPrivateKey()
        val phonePub: ByteArray = MirrorCrypto.publicKey(privateKey)
        private val keys: MirrorCrypto.Keys = MirrorCrypto.deriveKeys(
            MirrorCrypto.agree(privateKey, link.browserPub), link.pairId, phonePub, link.browserPub,
        )
        val sas: String = keys.sas

        @Volatile
        var browserName: String? = null
            private set

        private var transport: MirrorTransport? = null
        private var rxJob: Job? = null
        private var pingJob: Job? = null
        private var diffJob: Job? = null
        private var senderJob: Job? = null
        /** iOS `sendChain`: seq assignment AND the socket send happen inside this one ordered consumer. */
        private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
        private var txSeq = 0L
        private var rxSeq = 0L
        private var gotAck = false
        /**
         * MIRROR-GROUPS.md §5: the threads this link session has disclosed itself to, in
         * announcement order (RAM, per link). Nothing at link time; `web_link {linkUp:false}` on
         * stop goes ONLY here.
         */
        private val announced = LinkedHashSet<String>()
        private var linkedSince: Long? = null
        private val shadow = HashMap<String, List<RowStamp>>()             // thread -> mirrored window
        private val shadowContacts = HashMap<String, Pair<String, Boolean>>() // hash -> (name, verified)
        private val shadowGroups = HashMap<String, GroupStamp>()               // group id -> mirrored roster
        private var shadowTyping: Set<String> = emptySet()
        private val shadowGroupTyping = HashMap<String, List<String>>()        // group id -> `who` last sent
        private var shadowConn = false
        private val thumbSent = HashSet<String>()
        private var thumbBudget = THUMB_BUDGET
        /**
         * thread -> browser tempIds FIFO, each stamped with when the command arrived: a tempId may
         * only attach to a row CREATED after it, so an older row sliding into the window (after a
         * deletion) can't steal it.
         */
        private val pendingTempIds = HashMap<String, ArrayDeque<Pair<String, Long>>>()
        private val cmdStamps = ArrayDeque<Long>()
        @Volatile
        private var stopped = false

        val isStopped: Boolean get() = stopped

        // MARK: lifecycle

        fun start() {
            scope.launch(serial) { startOnSerial() }
        }

        private suspend fun startOnSerial() {
            if (stopped) return
            val url = source.relayUrl
            if (!isUsableRelayUrl(url)) {
                setState(State.Failed("bad relay url", sas))
                return
            }
            // the mirror's second socket must follow the same proxy policy —
            // a direct connection here would leak the user's IP to the relay
            val t = transports.create(url, source.socksProxy)
            transport = t
            senderJob = scope.launch(serial) {
                for (o in outbox) {
                    if (!gotAck) continue
                    if (stopped && !o.evenIfStopped) continue
                    sendRecord(o.obj)
                }
            }
            rxJob = scope.launch(serial) {
                for (event in t.events) handle(event)
            }
            try {
                t.connect()
            } catch (_: Exception) {
                // the transport also yields Disconnected; either path ends in relay_gone exactly once
                if (!stopped) stopOnSerial("relay_gone")
            }
        }

        /** Safe from any thread; iOS `stop(reason:)`. */
        fun stop(reason: String) {
            scope.launch(serial) { stopOnSerial(reason) }
        }

        private fun stopOnSerial(reason: String) {
            if (stopped) return
            stopped = true
            // best-effort goodbye so the browser shows LINK LOST immediately
            if (linkedSince != null) {
                emit(buildJsonObject { put("e", "bye"); put("reason", reason) }, evenIfStopped = true)
            }
            // only the conversations the browser actually touched hear about the unlink (§5)
            if (announced.isNotEmpty()) {
                val threads = announced.toList()
                announced.clear()
                for (t in threads) source.sendWebLink(t, false, null)
            }
            rxJob?.cancel(); rxJob = null
            pingJob?.cancel(); pingJob = null
            diffJob?.cancel(); diffJob = null
            outbox.close()
            val t = transport
            val sender = senderJob
            scope.launch(serial) {
                sender?.join()   // let the bye drain first
                t?.send(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("t", "pair_close"); put("pair", link.pairId) }))
                t?.close()
            }
            // DELIBERATE DEVIATION from MirrorEngine.swift, which unconditionally sets `.closed(reason)`
            // here and thereby hides the `.failed("code already claimed…")` text it set a line earlier
            // (docs/android/inventory-critique.md, UI §1: "the .failed text is never displayed").
            // Keeping `Failed` lets the Android LinkDesktop screen show the hijack warning.
            if (current === this && _state.value is State.Failed) return
            setState(State.Closed(reason, sas))
        }

        private fun setState(s: State) {
            if (current === this) _state.value = s
        }

        private suspend fun handle(event: MirrorTransport.Event) {
            when (event) {
                MirrorTransport.Event.Connected ->
                    transport?.send(Wire.encode(Wire.Out.PairJoin(link.pairId)))
                is MirrorTransport.Event.Disconnected ->
                    if (!stopped) stopOnSerial("relay_gone")
                is MirrorTransport.Event.Text -> {
                    val frame = try {
                        Wire.decodeIn(event.text)
                    } catch (_: Exception) {
                        return // iOS `try? JSONDecoder().decode` — undecodable frames are dropped
                    }
                    route(frame)
                }
            }
        }

        private fun route(f: Wire.In) {
            when (f) {
                is Wire.In.PairJoined -> {
                    linkedSince = options.clock()
                    setState(State.Linked(linkedSince!!, sas, browserName))
                    startPing()
                    sendHello()
                }
                is Wire.In.PairMsg -> handleRecord(f.body)
                is Wire.In.PairClosed -> if (!stopped) stopOnSerial("browser_gone")
                is Wire.In.Error -> if (f.code == Wire.ErrorCode.UNKNOWN_PAIR && !stopped) {
                    // QR already claimed — possible hijack; surface loudly
                    setState(State.Failed(HIJACK_MESSAGE, sas))
                    stopOnSerial("hijack_guard")
                }
                else -> {}
            }
        }

        private fun startPing() {
            pingJob = scope.launch(serial) {
                while (isActive) {
                    delay(options.pingIntervalMs)
                    if (stopped) return@launch
                    transport?.send(Wire.encode(Wire.Out.Ping))
                }
            }
        }

        // MARK: outbound records

        private fun sendHello() {
            // 0x01: pPub ‖ GCM(hello). MUST await hello_ack before anything else.
            val hello = buildJsonObject {
                put("v", 1)
                put("name", source.myUsername)
                put("showRead", source.showRead)
                put("sas", sas)
            }
            val record = try {
                MirrorCrypto.seal(
                    keys.p2b, MirrorCrypto.DIR_P2B, link.pairId, MirrorCrypto.TYPE_HELLO, 0,
                    json.encodeToString(JsonObject.serializer(), hello).toByteArray(Charsets.UTF_8), prefix = phonePub,
                )
            } catch (_: Exception) {
                stopOnSerial("seal_failed")
                return
            }
            transport?.send(Wire.encode(Wire.Out.PairMsg(link.pairId, Base64.getEncoder().encodeToString(record))))
        }

        /**
         * FIFO emit for 0x03 data records. Both the seq assignment AND the socket send happen inside
         * the single ordered consumer — a detached send per record would race on the wire and trip
         * the browser's strict seq check.
         */
        internal fun emit(obj: JsonObject, evenIfStopped: Boolean = false) {
            outbox.trySend(Outgoing(obj, evenIfStopped))
        }

        private fun sendRecord(obj: JsonObject) {
            val plain = json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)
            txSeq += 1
            val record = try {
                MirrorCrypto.seal(keys.p2b, MirrorCrypto.DIR_P2B, link.pairId, MirrorCrypto.TYPE_DATA, txSeq, plain)
            } catch (_: Exception) {
                stopOnSerial("seal_failed")
                return
            }
            transport?.send(Wire.encode(Wire.Out.PairMsg(link.pairId, Base64.getEncoder().encodeToString(record))))
        }

        // MARK: inbound records

        private fun handleRecord(b64: String) {
            val record = try {
                Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (record == null || record.size <= MirrorCrypto.HEADER_BYTES) return protoFail()
            val type = record[0].toInt() and 0xff
            val seq = MirrorCrypto.seqOf(record)
            val sealed = record.copyOfRange(MirrorCrypto.HEADER_BYTES, record.size)

            if (type == MirrorCrypto.TYPE_HELLO_ACK) {
                if (gotAck || seq != 0L) return protoFail()
                val ack = openObject(MirrorCrypto.TYPE_HELLO_ACK, 0, sealed) ?: return protoFail()
                ack.string("browser")?.let { browserName = sanitizeBrowserName(it) }
                gotAck = true
                linkedSince?.let { setState(State.Linked(it, sas, browserName)) }
                // NOTHING is announced at link time (MIRROR-GROUPS.md §5): a conversation learns
                // about the browser the first time the browser touches it — see [announce]
                attachDiffing()
                sendSnapshot()
                return
            }
            if (type != MirrorCrypto.TYPE_DATA || !gotAck || seq != rxSeq + 1) return protoFail()
            val obj = openObject(MirrorCrypto.TYPE_DATA, seq, sealed) ?: return protoFail()
            rxSeq = seq
            handleCommand(obj)
        }

        private fun openObject(type: Int, seq: Long, sealed: ByteArray): JsonObject? = try {
            val plain = MirrorCrypto.open(keys.b2p, MirrorCrypto.DIR_B2P, link.pairId, type, seq, sealed)
            json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }

        private fun protoFail() {
            // any gap, replay, or GCM failure = tampering or a broken peer
            stopOnSerial("proto")
        }

        // MARK: commands from the browser

        /**
         * MIRROR-GROUPS.md §5, contextual disclosure: the first `view` / `send_text` / `typing` /
         * `mark_read` for a thread sends `web_link {linkUp:true, browser}` to THAT conversation
         * only — the peer of a 1:1 thread, or every member of a group with `group:{id,epoch}` —
         * once per link session. Never for an unknown or blocked contact, nor a group this phone
         * has left (the page never sends `view` for those; an old page may still mark them). Not
         * recorded while the phone is offline: a control that cannot leave the phone would
         * otherwise count as "announced", so the next touch retries instead.
         */
        private fun announce(thread: String) {
            if (thread in announced || !isDisclosable(thread) || !source.isOnline()) return
            announced.add(thread)
            source.sendWebLink(thread, true, browserName)
        }

        /** A conversation the browser may legitimately show: a non-blocked contact, or a group this phone is still in. */
        private fun isDisclosable(thread: String): Boolean {
            source.groups().firstOrNull { it.id == thread }?.let { return !it.left }
            return source.contacts().any { it.hash == thread } && !source.isBlocked(thread)
        }

        /** Test hook: the threads announced so far, in order. */
        internal fun announcedForTest(): List<String> = announced.toList()

        private fun handleCommand(obj: JsonObject) {
            val tempId = obj.string("tempId") ?: ""
            fun err(code: String) {
                emit(buildJsonObject { put("e", "cmd_err"); put("tempId", tempId); put("code", code) })
            }
            when (obj.string("c")) {
                // the page shows a thread (activation, first open after the snapshot, relink's reopen)
                "view" -> obj.string("thread")?.let { announce(it) }
                "send_text" -> {
                    val thread = obj.string("thread") ?: return err("bad_cmd")
                    val text = obj.string("text") ?: return err("bad_cmd")
                    // a group id as thread (MIRROR-GROUPS.md §1) takes the existing fan-out send;
                    // the checks run in the iOS order: unknown_thread, left, blocked, offline, too_long, rate_limited
                    val group = source.groups().firstOrNull { it.id == thread }
                    val contact = if (group == null) source.contacts().firstOrNull { it.hash == thread } else null
                    if (group == null && contact == null) return err("unknown_thread")
                    if (group != null && group.left) return err("left")
                    if (contact != null && source.isBlocked(thread)) return err("blocked")
                    if (!source.isOnline()) return err("offline")
                    if (graphemeCount(text) > SEND_TEXT_MAX_CHARS) return err("too_long")
                    val now = options.clock()
                    cmdStamps.removeAll { now - it > SEND_RATE_WINDOW_MS }
                    if (cmdStamps.size >= SEND_RATE_MAX) return err("rate_limited")
                    cmdStamps.addLast(now)
                    if (tempId.isNotEmpty()) {
                        pendingTempIds.getOrPut(thread) { ArrayDeque() }.addLast(tempId to options.clock())
                    }
                    announce(thread) // the disclosure is enqueued ahead of the text
                    scope.launch { source.sendText(text, thread) }
                }
                "mark_read" -> {
                    val thread = obj.string("thread") ?: return
                    val ids = obj.stringList("ids") ?: return
                    if (ids.size > MARK_READ_MAX_IDS) return
                    announce(thread)
                    val mirrored = (shadow[thread] ?: emptyList()).filter { !it.isBurn }.map { it.id }.toSet()
                    for (id in ids) if (id in mirrored) {
                        // burn rows are never in `mirrored`, so a desktop read can
                        // never start a burn timer or leak a burn receipt
                        source.noteRowVisible(id, thread)
                    }
                }
                "typing" -> obj.string("thread")?.let {
                    announce(it)
                    source.noteComposerActivity(it)
                }
                else -> err("bad_cmd")
            }
        }

        // MARK: snapshot + diffing

        private fun lastTs(c: MirrorContact): Long = source.messages(c.hash).lastOrNull()?.timestamp ?: c.addedAt

        private fun lastTs(g: MirrorGroup): Long = source.messages(g.id).lastOrNull()?.timestamp ?: g.createdAt

        private fun mirrorableContacts(): List<MirrorContact> =
            source.contacts().filter { !source.isBlocked(it.hash) }.sortedByDescending { lastTs(it) }

        /** iOS `mirrorableGroups()`: not left (a burned group is already gone from the list). */
        private fun mirrorableGroups(): List<MirrorGroup> =
            source.groups().filter { !it.left }.sortedByDescending { lastTs(it) }

        /**
         * Row → browser JSON. [group] is the thread's group for group rows: incoming rows gain
         * `sender` (hash → member name + colour), outgoing rows `receipts` (aggregated, total =
         * members − 1) and the aggregate `status` word (MIRROR-GROUPS.md §1).
         */
        private fun row(m: MirrorMessage, group: MirrorGroup? = null): JsonObject = buildJsonObject {
            put("id", m.id)
            put("dir", m.direction.wire)
            // FLAGSHIP GUARANTEE: burn-after content never crosses to a browser
            if (m.isBurn) {
                put("kind", "burn")
                put("ts", m.timestamp)
                put("status", m.status.name)
                return@buildJsonObject
            }
            put("kind", m.kind.name)
            put("ts", m.timestamp)
            val total = if (group != null) maxOf(group.members.size - 1, 0) else 0
            // the aggregate word counts the members the fan-out reached for — never the `gone` ones
            val recipients = if (group != null) maxOf(group.members.count { !it.gone } - 1, 0) else 0
            val status = if (group != null && m.direction == MirrorDirection.outgoing) aggregateStatus(m.status, m.receipts, recipients) else m.status
            put("status", status.name)
            if (m.kind == MirrorKind.text || m.kind == MirrorKind.system) put("text", m.text)
            if (m.viaWeb) put("via", "web")
            m.imageWidth?.let { put("w", it) }
            m.imageHeight?.let { put("h", it) }
            if (m.kind == MirrorKind.image) put("th", if (m.id in thumbSent) "sent" else "pending")
            m.fileName?.let { put("fileName", it) }
            m.fileBytes?.let { put("fileBytes", it) }
            m.fileMime?.let { put("mime", it) }
            m.voiceDuration?.let { put("duration", it) }
            if (group == null) return@buildJsonObject
            val sender = m.senderHash
            if (m.direction == MirrorDirection.incoming && sender != null) {
                // iOS `displayName(for:in:)`: roster or former name, then the contact's, then `anon-<hash>`;
                // `Groups.colour(of:in:)` is nil for a hash that never joined: no colour, the page renders dim
                val member = group.member(sender)
                put("sender", buildJsonObject {
                    put("hash", sender)
                    put("name", member?.name ?: source.contacts().firstOrNull { it.hash == sender }?.name ?: anonName(sender))
                    member?.let { put("color", it.color) }
                })
            } else if (m.direction == MirrorDirection.outgoing && m.kind != MirrorKind.system) {
                // every receipt entry means delivered (a `read` implies it), like iOS `receipts.count`
                val map = m.receipts ?: emptyMap()
                put("receipts", buildJsonObject {
                    put("delivered", map.size)
                    put("read", map.values.count { it == "read" })
                    put("total", total)
                })
            }
        }

        private fun stamp(m: MirrorMessage) = RowStamp(m.id, m.status, m.isBurn, m.receipts)

        private fun stamp(g: MirrorGroup) = GroupStamp(g.name, g.members, g.policy, g.left)

        /** `Group` (MIRROR-GROUPS.md §1) — `left`/`gone` only when true, like every optional flag. */
        private fun groupJson(g: MirrorGroup): JsonObject = buildJsonObject {
            put("id", g.id)
            put("name", g.name)
            put("members", buildJsonArray {
                for (m in g.members) add(buildJsonObject {
                    put("hash", m.hash); put("name", m.name); put("color", m.color)
                    if (m.gone) put("gone", true)
                })
            })
            put("lastTs", lastTs(g))
            if (g.left) put("left", true)
            put("policy", g.policy)
        }

        private fun groupEvent(g: MirrorGroup): JsonObject = JsonObject(mapOf("e" to JsonPrimitive("group")) + groupJson(g))

        private fun window(thread: String): List<MirrorMessage> = source.messages(thread).takeLast(ROWS_PER_THREAD)

        private fun contactEvent(e: String, c: MirrorContact): JsonObject = buildJsonObject {
            put("e", e); put("hash", c.hash); put("name", c.name); put("verified", c.verified); put("lastTs", lastTs(c))
        }

        private fun sendSnapshot() {
            val contacts = mirrorableContacts()
            val groups = mirrorableGroups()
            emit(buildJsonObject {
                put("e", "snapshot_begin")
                put("contacts", buildJsonArray {
                    for (c in contacts) add(buildJsonObject {
                        put("hash", c.hash); put("name", c.name); put("verified", c.verified); put("lastTs", lastTs(c))
                    })
                })
                put("groups", buildJsonArray { for (g in groups) add(groupJson(g)) })
            })
            for (c in contacts.take(MAX_THREADS)) {
                emitThreadBatches(c.hash, window(c.hash).map { row(it) })
                shadow[c.hash] = window(c.hash).map { stamp(it) }
                shadowContacts[c.hash] = c.name to c.verified
            }
            for (c in contacts.drop(MAX_THREADS)) {
                shadowContacts[c.hash] = c.name to c.verified
            }
            for (g in groups.take(MAX_THREADS)) {
                emitThreadBatches(g.id, window(g.id).map { row(it, g) })
                shadow[g.id] = window(g.id).map { stamp(it) }
                shadowGroups[g.id] = stamp(g)
            }
            for (g in groups.drop(MAX_THREADS)) {
                shadowGroups[g.id] = stamp(g)
            }
            shadowConn = source.isOnline()
            emit(buildJsonObject { put("e", "conn"); put("up", shadowConn) })
            emit(buildJsonObject { put("e", "snapshot_end") })
            sendThumbs()
        }

        /** One or more `thread` events, packed under the plaintext cap. */
        private fun emitThreadBatches(thread: String, rows: List<JsonObject>) {
            var batch = ArrayList<JsonObject>()
            var size = 0
            for (r in rows) {
                val rSize = json.encodeToString(JsonObject.serializer(), r).toByteArray(Charsets.UTF_8).size
                if (size + rSize > PLAINTEXT_CAP - 200 && batch.isNotEmpty()) {
                    emit(threadEvent(thread, batch))
                    batch = ArrayList(); size = 0
                }
                batch.add(r); size += rSize
            }
            emit(threadEvent(thread, batch))
        }

        private fun threadEvent(thread: String, rows: List<JsonObject>): JsonObject = buildJsonObject {
            put("e", "thread"); put("thread", thread); put("rows", JsonArray(rows))
        }

        /** Thumbs for contact threads, then group threads (MIRROR-GROUPS.md §3: as for contacts). */
        private fun sendThumbs() {
            val threads = mirrorableContacts().take(MAX_THREADS).map { it.hash } + mirrorableGroups().take(MAX_THREADS).map { it.id }
            for (thread in threads) {
                for (m in window(thread).takeLast(20)) {
                    if (m.kind != MirrorKind.image || m.isBurn || m.id in thumbSent) continue
                    if (thumbBudget <= 0) return
                    thumbSent.add(m.id)
                    val jpeg = source.thumbnailJpeg(m) ?: continue
                    if (jpeg.size > THUMB_MAX_BYTES) continue
                    thumbBudget -= jpeg.size
                    emit(buildJsonObject {
                        put("e", "thumb"); put("thread", thread); put("id", m.id)
                        put("jpeg", Base64.getEncoder().encodeToString(jpeg))
                    })
                }
            }
        }

        @OptIn(FlowPreview::class)
        private fun attachDiffing() {
            diffJob = scope.launch(serial) {
                source.changes.debounce(options.debounceMs).collect { tick() }
            }
        }

        /** Test hook: run one diff pass now (what the debounced `changes` collector does). */
        internal suspend fun tickNow() {
            kotlinx.coroutines.withContext(serial) { tick() }
        }

        private fun tick() {
            if (stopped || !gotAck) return
            // contacts diff
            val current = mirrorableContacts()
            val currentMap = current.associate { it.hash to (it.name to it.verified) }
            for (hash in shadowContacts.keys.toList()) if (currentMap[hash] == null) {
                emit(buildJsonObject { put("e", "contact_rm"); put("hash", hash) })
                emit(buildJsonObject { put("e", "thread_rm"); put("thread", hash) })
                shadowContacts.remove(hash)
                shadow.remove(hash)
                announced.remove(hash) // removed or blocked: no unlink notice either
            }
            for (c in current) {
                val prev = shadowContacts[c.hash]
                if (prev == null || prev != (c.name to c.verified)) {
                    emit(contactEvent("contact", c))
                    shadowContacts[c.hash] = c.name to c.verified
                }
            }
            // groups diff (MIRROR-GROUPS.md §3): left / removed / burned → group_rm (the page drops
            // the thread too); create, rename, roster or policy change → group (upsert by id)
            val groups = mirrorableGroups()
            val groupsById = groups.associateBy { it.id }
            for (id in shadowGroups.keys.toList()) if (groupsById[id] == null) {
                emit(buildJsonObject { put("e", "group_rm"); put("id", id) })
                shadowGroups.remove(id)
                shadow.remove(id)
                shadowGroupTyping.remove(id)
                pendingTempIds.remove(id)
                announced.remove(id) // left, removed or burned: nothing more can be fanned out there
            }
            for (g in groups) {
                val s = stamp(g)
                if (shadowGroups[g.id] != s) {
                    emit(groupEvent(g))
                    shadowGroups[g.id] = s
                }
            }
            // per-thread row diff (only threads we snapshotted or that got rows)
            for (c in current.take(MAX_THREADS)) diffThread(c.hash, null)
            for (g in groups.take(MAX_THREADS)) diffThread(g.id, g)
            // typing diff
            val typingNow = source.typingPeers()
            for (t in typingNow - shadowTyping) {
                emit(buildJsonObject { put("e", "typing"); put("thread", t); put("on", true) })
            }
            for (t in shadowTyping - typingNow) {
                emit(buildJsonObject { put("e", "typing"); put("thread", t); put("on", false) })
            }
            shadowTyping = typingNow
            // group typing diff: `who` (≤3 names, join order) rides on every group typing event —
            // `on` is simply "someone is typing"; any change to the names re-emits (iOS `tick()`)
            val groupTypingNow = source.groupTyping()
            for (g in groups) {
                val who = whoTyping(g, groupTypingNow[g.id] ?: emptySet())
                val prev = shadowGroupTyping[g.id] ?: emptyList()
                if (who == prev) continue
                emit(buildJsonObject {
                    put("e", "typing"); put("thread", g.id); put("on", who.isNotEmpty())
                    put("who", buildJsonArray { for (n in who) add(JsonPrimitive(n)) })
                })
                if (who.isEmpty()) shadowGroupTyping.remove(g.id) else shadowGroupTyping[g.id] = who
            }
            // conn diff
            val up = source.isOnline()
            if (up != shadowConn) {
                shadowConn = up
                emit(buildJsonObject { put("e", "conn"); put("up", up) })
            }
            sendThumbs()
        }

        /** Row diff for one thread against its shadow: `thread_rm` / `msg_rm` / `msg` (with the browser's tempId on the first matching new outgoing row). */
        private fun diffThread(thread: String, group: MirrorGroup?) {
            val now = window(thread)
            val nowStamps = now.map { stamp(it) }
            val old = shadow[thread] ?: emptyList()
            if (old == nowStamps) return
            val oldIds = old.map { it.id }.toSet()
            val nowIds = nowStamps.map { it.id }.toSet()
            if (now.isEmpty() && old.isNotEmpty()) {
                emit(buildJsonObject { put("e", "thread_rm"); put("thread", thread) })
            } else {
                for (s in old) if (s.id !in nowIds) {
                    emit(buildJsonObject { put("e", "msg_rm"); put("thread", thread); put("id", s.id) })
                }
                val oldById = old.associateBy { it.id }
                for (m in now) {
                    val s = stamp(m)
                    if (m.id !in oldIds) {
                        var r = row(m, group)
                        val temps = pendingTempIds[thread]
                        if (m.direction == MirrorDirection.outgoing && temps != null && temps.isNotEmpty() &&
                            m.timestamp >= temps.first().second - 1000
                        ) {
                            val tempId = temps.removeFirst().first
                            r = JsonObject(r + ("tempId" to JsonPrimitive(tempId)))
                        }
                        emit(buildJsonObject { put("e", "msg"); put("thread", thread); put("row", r) })
                    } else if (oldById[m.id] != s) {
                        emit(buildJsonObject { put("e", "msg"); put("thread", thread); put("row", row(m, group)) })
                    }
                }
            }
            shadow[thread] = nowStamps
        }
    }
}

// Swift `obj["x"] as? String` / `as? [String]` on a decoded JSON object.
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringList(key: String): List<String>? {
    val arr = this[key] as? JsonArray ?: return null
    val out = ArrayList<String>(arr.size)
    for (e in arr) out.add((e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null)
    return out
}
