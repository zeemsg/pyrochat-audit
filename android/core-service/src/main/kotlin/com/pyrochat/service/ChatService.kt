package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.PreKeys
import com.pyrochat.crypto.Sealed
import com.pyrochat.crypto.SealedTrust
import com.pyrochat.protocol.GroupControl
import com.pyrochat.protocol.GroupMember
import com.pyrochat.protocol.GroupRef
import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import com.pyrochat.protocol.usernameHash
import com.pyrochat.store.Durability
import com.pyrochat.store.SecureStore
import com.pyrochat.store.StoreError
import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.model.AccessToken
import com.pyrochat.store.model.AppSettings
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.Group
import com.pyrochat.store.model.GroupPolicy
import com.pyrochat.store.model.Member
import com.pyrochat.store.model.Note
import com.pyrochat.store.model.PeerKey
import com.pyrochat.store.model.PeerKeys
import com.pyrochat.store.model.PeerKeys.Lane
import com.pyrochat.store.model.ReceiptOutbox
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.BreakIterator
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/** iOS `ChatService.ServiceError.relay(code)` — `description` is the bare code. */
class ServiceError(val code: String) : Exception(code)

/** iOS `SoundPlayer.Tone` — the service asks the app shell to blip (`tx.wav` / `rx.wav`). */
enum class Tone { TX, RX }

/**
 * Port of `ios/PyroChat/Core/ChatService.swift` (milestone 1: the text-messaging core). Owns the
 * store, the crypto engine and the relay link; drives registration/login, the send/receive
 * pipeline, receipts, synced burn, trust (safety numbers, key-change warnings, session heal),
 * drafts and notes. The UI observes the [StateFlow]s.
 *
 * ## Concurrency — one actor, like `@MainActor`
 * Every member runs on ONE single-threaded dispatcher (`pyrochat.chat-service`): public suspend
 * functions hop onto it with [actor], background work (`scope.launch`) is confined to it, and the
 * relay event loop consumes frames on it. Between suspension points the state is therefore
 * touched by exactly one coroutine — the same guarantee Swift's main actor gives the original —
 * and awaiting a relay reply (bundle fetch, control ack) yields the thread so the event loop can
 * resolve it, exactly like actor reentrancy. Reads of the flows and of `store.blob` are safe from
 * any thread.
 *
 * ## Persistence
 * The store is written at the same moments as iOS (`android/fixtures/ios-store/SPEC.md` §3.1):
 * libsignal callbacks write through [StoreSignalState]; every message/contact/receipt/draft/note
 * mutation calls `store.update`; `shutdown` flushes synchronously.
 *
 * Deliberate Android-side deviations (each listed in the milestone report):
 *  - the register/challenge/ready waits carry a [HANDSHAKE_TIMEOUT_MS] deadline (iOS awaits
 *    forever; an `error` during login also resolves the pending challenge/ready waiters here);
 *  - a send with no socket at all fails the row (iOS leaves it `pending` when `relay == nil`);
 *  - two concurrent bundle fetches for one peer share one relay round trip (iOS overwrites the
 *    first continuation, which then never resumes).
 *
 * ## Blob transfers (milestone 2, `ChatService.swift:1323-1541`)
 * Voice / image / file content rides the SAME libsignal session as texts, as a raw binary envelope
 * (`[type][ciphertext]`, [BlobCrypto]) split into 64 KiB binary WebSocket frames ([BlobFrames])
 * announced by an encrypted `blob_manifest` payload. The receiver acks the manifest ONLY after every
 * chunk has been reassembled and decrypted (an early ack makes the relay drop the chunk route).
 * Voice, image and file bytes all live in the encrypted
 * `attachments/<uuid>` sidecar, deleted on delete / burn / purge together with the row.
 *
 * TODO(M5): `redeemAccessKey` (`ChatService.swift:431-445`) over `RelayHttp.makeClient(settings.socksProxy)`.
 * TODO(mirror): `startMirror` / `stopMirror` are owned by the `mirror` module.
 */
class ChatService(
    val store: SecureStore,
    private val clock: () -> Instant = Instant::now,
    /** iOS `PushDelegate.currentToken` — the app's FCM registration token (null until known). */
    private val pushTokenProvider: () -> String? = { null },
    /** Operator notices between launches — outside the encrypted store (see [NoticeStore]). */
    private val noticeStore: NoticeStore = InMemoryNoticeStore(),
    /**
     * The running build number — Android `versionCode`, injected by the app from
     * `BuildConfig.VERSION_CODE` (core-service has none). The `versions` frame shows the update
     * banner only for a strictly greater build ([UpdateRules]); 0 means "every advertised build is newer".
     */
    private val versionCode: Int = 0,
    private val linkTiming: RelayLink.Timing = RelayLink.Timing(),
    /**
     * Sealed sender trust roots handed in by a debug build or a test, base64
     * (docs/SEALED-SENDER.md §7). They count only for a relay on this machine: the throwaway
     * root's private half is in the repository. A release app passes none.
     */
    private val sealedTestRoots: List<String> = emptyList(),
    /** Milliseconds on a clock that keeps counting while the phone sleeps (the app passes `SystemClock.elapsedRealtime`). */
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Whether the app is in front. Android can keep its main link up in the background; an
     * anonymous connection is kept alive only while the app is in front (docs/SEALED-SENDER.md §5.5).
     */
    private val inFront: () -> Boolean = { true },
) : AutoCloseable {

    /** iOS `ChatService.ReceiptKind` (raw values are the wire strings). */
    enum class ReceiptKind(val wire: String) {
        DELIVERED("delivered"), READ("read"), BURN_START("burn_start");

        companion object {
            fun fromWire(raw: String): ReceiptKind? = entries.firstOrNull { it.wire == raw }
        }
    }

    // ---------------------------------------------------------------- actor

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pyrochat.chat-service").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var actorThread: Thread? = null

    /** Hop onto the service thread — the `@MainActor` boundary. */
    private suspend fun <T> actor(block: suspend () -> T): T = withContext(dispatcher) { block() }

    // ---------------------------------------------------------------- observable state (iOS @Published)

    private val _contacts = MutableStateFlow(store.blob.contacts)
    private val _messages = MutableStateFlow(store.blob.messages)
    private val _notes = MutableStateFlow(store.blob.notes ?: emptyList())
    private val _lastError = MutableStateFlow<String?>(null)
    private val _blockedHashes = MutableStateFlow((store.blob.blockedHashes ?: emptyList()).toSet())
    private val _typingPeers = MutableStateFlow<Map<String, Instant>>(emptyMap())

    /** `@Published var contacts` — insertion order, blocked ones included. */
    val contacts: StateFlow<List<Contact>> get() = _contacts

    /** `@Published var messages` — thread hash → rows. */
    val messages: StateFlow<Map<String, List<ChatMessage>>> get() = _messages

    /** `@Published var notes` */
    val notes: StateFlow<List<Note>> get() = _notes

    /** `@Published var lastError` */
    val lastError: StateFlow<String?> get() = _lastError

    /** `@Published private(set) var blockedHashes` */
    val blockedHashes: StateFlow<Set<String>> get() = _blockedHashes

    /** `@Published private(set) var typingPeers` — peer hash → last "typing" signal; rows expire after 6 s. */
    val typingPeers: StateFlow<Map<String, Instant>> get() = _typingPeers

    // ---------------------------------------------------------------- group channels (docs/GROUPS.md)

    private val _groups = MutableStateFlow(store.blob.groups ?: emptyList())
    private val _groupTyping = MutableStateFlow<Map<String, Map<String, Instant>>>(emptyMap())
    private val _goneMembers = MutableStateFlow(goneByGroup(_groups.value))
    private val _formerMembers = MutableStateFlow(formerByGroup(_groups.value))
    private val _flashes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Tests: the offline roster state machine runs with the link-up rule switched off. */
    internal var requireLinkForRosterOps = true

    /** `Blob.groups` — every group this phone knows, `left` ones included (read-only threads). */
    val groups: StateFlow<List<Group>> get() = _groups

    /** group id → member hash → last typing signal (§1: per member, expires after 6 s). */
    val groupTyping: StateFlow<Map<String, Map<String, Instant>>> get() = _groupTyping

    /**
     * group id → members the relay reported `unknown_user` for (§4: they deleted their account) —
     * `Member.gone` in the roster: they keep their colour slot but no fan-out addresses them.
     */
    val goneMembers: StateFlow<Map<String, Set<String>>> get() = _goneMembers

    /** group id → `Group.former`: removed/left members with their original `joinedAt` (§5 colour history). */
    val formerMembers: StateFlow<Map<String, List<Member>>> get() = _formerMembers

    /** Transient home-screen flashes (§6b: `#name was burned by the creator`). */
    val flashes: SharedFlow<String> get() = _flashes.asSharedFlow()

    /** DEBUG builds log an ignored non-creator removal as a system row (§1); release builds stay silent. */
    @Volatile
    var debugRows: Boolean = false

    /** fan-out per outgoing group row: message id → verdict tracker (transient) */
    private val groupOutbound = HashMap<String, GroupFanOut>()

    private data class GroupSendRef(val messageId: String, val groupId: String, val recipient: String)

    /** relay send id → the group row + recipient it carried (a receipt may name either id) */
    private val groupSendIds = HashMap<String, GroupSendRef>()

    /** message id → recipient → the relay id used, for retracting on iOS receivers that keep relay ids */
    private val groupRelayIds = HashMap<String, HashMap<String, String>>()

    /** `"$messageId:$recipient"` already re-sent once after `rate_limited` / `queue_full` */
    private val groupRecipientRetried = HashSet<String>()

    private data class GroupReceipt(val peer: String, val groupId: String, val kind: ReceiptKind, val ids: List<String>)

    /** receipts for group rows: sent with `group`, outside the persisted 1:1 outbox (no group lane exists there) */
    private val groupReceiptQueue = ArrayList<GroupReceipt>()

    private class PendingControl(
        val peer: String,
        val payload: Wire.Payload,
        val maxAttempts: Int,
        /** delete-for-all: told once per control whether the relay accepted it */
        val onVerdict: ((Boolean) -> Unit)?,
        var attempts: Int = 0,
    )

    /** roster controls awaiting a link / a relay verdict — retried until each `sent` lands (§6b) */
    private val groupControlQueue = ArrayDeque<PendingControl>()
    private val inflightGroupControls = HashMap<String, PendingControl>()
    private var controlFlushJob: Job? = null

    /** last applied `(epoch, senderHash)` per group + member, for the last-writer rule on add/remove/leave (§2; RAM-only) */
    private val memberStamps = HashMap<String, Pair<Int, String>>()

    /** iOS `ChatService.RxTransfer` — an incoming blob still being reassembled. */
    data class RxTransfer(val thread: String, val subtype: String, val received: Int, val total: Int)

    private val _blobProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val _rxTransfers = MutableStateFlow<Map<String, RxTransfer>>(emptyMap())

    /** `@Published var blobProgress` — outgoing chunk-upload progress, message id → 0…1 (transient; gone when done). */
    val blobProgress: StateFlow<Map<String, Double>> get() = _blobProgress

    /** `@Published var rxTransfers` — incoming reassembly progress, message id → state (transient). */
    val rxTransfers: StateFlow<Map<String, RxTransfer>> get() = _rxTransfers

    /**
     * An operator notice (docs/PROTOCOL.md §"Operator notices"): the relay's own announcement,
     * shown as the pinned `<pyrochat>` channel. No sender, no session, nobody can reply.
     */
    data class Notice(val id: String, val text: String, val url: String?, val ts: Instant, val expires: Instant)

    /** Ids the user has opened (`[N]` counts the rest). Actor-confined; persisted with every change. */
    private val seenNoticeIds: MutableSet<String> = noticeStore.getSeen().toMutableSet()
    private val _notices = MutableStateFlow(
        NoticeCodec.decode(noticeStore.getNotices()).filter { it.expires.isAfter(clock()) }.sortedBy { it.ts },
    )
    private val _unseenNoticeCount = MutableStateFlow(_notices.value.count { it.id !in seenNoticeIds })

    /** Active (unexpired) operator notices, oldest first — restored from [NoticeStore] before the first login. */
    val notices: StateFlow<List<Notice>> get() = _notices

    /** How many of [notices] the user has not opened yet — the `<pyrochat>` row's `[N]` marker. */
    val unseenNoticeCount: StateFlow<Int> get() = _unseenNoticeCount

    private val _updateAvailable = MutableStateFlow(UpdateRules.update(VersionCodec.decode(noticeStore.getVersions()), versionCode))

    /**
     * The newer build the relay advertises for this platform (docs/PROTOCOL.md §"Latest versions"),
     * null when this install is current — the home screen's `▲ update available` row. Restored from
     * [NoticeStore] before the first login (so it shows offline), replaced by every `versions` frame.
     */
    val updateAvailable: StateFlow<LatestVersion?> get() = _updateAvailable

    // ---------------------------------------------------------------- engine room

    private val signalState = StoreSignalState.bind(store)
    private val crypto = CryptoEngine(signalState)
    private val link = RelayLink(
        scope = scope,
        endpoint = { RelayLink.Endpoint(settings.relayURL, settings.socksProxy) },
        onEvent = ::handleEvent,
        reconnect = { goOnline() },
        timing = linkTiming,
    )

    /** `@Published var connState` */
    val connState: StateFlow<ConnState> get() = link.state

    /** Continuations for request/response frames (iOS `pendingBundleFetches` etc.). */
    private class BundleFetch {
        val reply = CompletableDeferred<Wire.In>()
        var processed = false
    }

    private val pendingBundleFetches = HashMap<String, BundleFetch>()

    /** control-send id → waiter for the relay's `sent` / `error` verdict (delete-for-both). */
    private val pendingControlAcks = HashMap<String, CompletableDeferred<Boolean>>()
    private var pendingRegister: CompletableDeferred<Wire.In>? = null
    private var pendingChallenge: CompletableDeferred<Wire.In>? = null
    private var pendingReady: CompletableDeferred<Wire.In>? = null
    /** True while [register] runs: a network change must not restart that attempt, because its failure rolls the new identity back. */
    private var registering = false
    private var burnJob: Job? = null

    /** Thread whose chat screen is currently on screen (null = none). */
    var activeThread: String? = null
        private set

    private data class InflightReceipt(val peer: String, val kind: ReceiptKind, val ids: List<String>)

    /** control-send id → receipts in flight, awaiting the relay `sent` ack. */
    private val inflightReceipts = HashMap<String, InflightReceipt>()
    private var receiptFlushJob: Job? = null

    /** Session-scoped read-visibility dedupe (never persisted; the status field is the durable record). */
    private val visibleReported = HashSet<String>()
    private val lastTypingSent = HashMap<String, Instant>()

    /** message ids already auto-retried once after `rate_limited`. */
    private val rateLimitRetried = HashSet<String>()

    // trust & heal bookkeeping (transient — deliberately NOT persisted)
    private val identityRowAt = HashMap<String, Instant>()
    private val decryptFailures = HashMap<String, Int>()
    private val lastHealAt = HashMap<String, Instant>()
    private val accountGoneNoted = HashSet<String>()

    // blob transfers (iOS `IncomingBlob` / `blobIncoming` / `attachmentCache`)
    private class IncomingBlob(
        val from: String,
        val senderName: String?,
        val total: Int,
        val chunks: HashMap<Long, ByteArray>,
        val subtype: String,
        val burnAfter: Double?,
        val duration: Double?,
        val filename: String?,
        val mime: String?,
        val width: Int?,
        val height: Int?,
        /** TOTAL ciphertext bytes announced by the manifest */
        val bytes: Int,
        val startedAt: Instant,
        /** group thread the blob belongs to (GROUPS.md §4), else null */
        val groupId: String? = null,
        /** the sender's row id for a group blob or a sealed one (its `refId`); the relay id names only this copy */
        val logicalId: String? = null,
        /**
         * Set when the manifest came sealed: the relay's timestamp on that frame, which the blob's
         * certificate is judged at (docs/SEALED-SENDER.md §5.6). null = the manifest named its
         * sender, and so must the blob.
         */
        val sealedTs: Long? = null,
    )

    private val blobIncoming = HashMap<String, IncomingBlob>()
    private val attachmentCache = AttachmentCache(ATTACHMENT_CACHE_BYTES)

    /** Observation tap on every binary chunk frame handed to the socket (tests: framing). Never on iOS. */
    var binaryTap: ((ByteArray) -> Unit)?
        get() = link.binaryTap
        set(value) {
            link.binaryTap = value
        }

    /** iOS `SoundPlayer.play(_:settings:)` — installed by the app shell; honours `settings.soundsOn` here. */
    @Volatile
    var soundHook: ((Tone) -> Unit)? = null

    /**
     * docs/MESSAGE-DETAILS.md §3 — the envelopes this session put on / took off the wire, in RAM
     * only, bounded, keyed by row id. This service is its single owner: it lives and dies with the
     * unlocked session and is emptied by [shutdown] (lock, auto-lock, wipe and app exit all route
     * through it), so nothing survives a restart. Read it through [capturedFrame].
     */
    private val frames = FrameCapture()

    /** §3 — the envelope row [messageId] crossed the wire in, or null when nothing was captured. */
    fun capturedFrame(messageId: String): CapturedFrame? = frames[messageId]

    /** Tests only: file [frame] against [messageId] as if the wire had just carried it. */
    internal fun captureForTest(messageId: String, frame: CapturedFrame) = frames.put(messageId, frame)

    /** Tests only: how many envelopes this session is holding. */
    internal fun capturedCountForTest(): Int = frames.size()

    /** Observation tap on every frame handed to the socket (tests: envelope types, pings). Never on iOS. */
    var wireTap: ((Wire.Out) -> Unit)?
        get() = link.tap
        set(value) {
            link.tap = value
        }

    val myUsername: String get() = store.blob.usernameDisplay
    val myHash: String get() = store.blob.usernameHash
    private val settings: AppSettings get() = store.blob.settings

    init {
        executor.execute { actorThread = Thread.currentThread() }
        startBurnSweeper()
        crypto.onIdentityReplaced = { peer ->
            // Invariant: CryptoEngine is only ever driven from the actor thread, so this runs there.
            handleIdentityChange(peer)
        }
    }

    // ---------------------------------------------------------------- relay connection

    private fun sendFrame(frame: Wire.Out) = link.send(frame)

    // ---------------------------------------------------------------- outbound choke point (docs/SEALED-SENDER.md §5.3)

    /**
     * One message, encrypted and addressed, ready to leave: a `send` frame for the logged-in
     * socket, or a `deliver` frame for the anonymous one.
     */
    internal class Outbound(
        val frame: Wire.Out,
        val lane: Lane,
        /** What the caller passed, kept so a refusal can take the message one step down: a retry always encrypts and seals again. */
        val payload: Wire.Payload,
        val peer: String,
        val kind: String,
        val ttl: Int,
        /**
         * The caller's id: the row, the control or the group envelope this is. On the legacy path
         * it is the relay id too. On a sealed lane the relay sees a fresh id for every attempt, so
         * that no id ever appears on both sockets.
         */
        val callerId: String,
        /** The delivery key a key-lane attempt carried. */
        val access: ByteArray?,
        /** 1 for the first try; a message makes at most [PeerKeys.MAX_ATTEMPTS]. */
        val attempt: Int,
    ) {
        /** The envelope as the details sheet shows it. A sealed frame's `access` is never kept. */
        fun captured(): CapturedFrame = when (frame) {
            is Wire.Out.Deliver -> CapturedFrame.of(frame)
            is Wire.Out.Send -> CapturedFrame.of(frame)
            else -> error("not a message frame")
        }
    }

    /**
     * THE one place a payload becomes a relay frame. The ratchet step happens here, in the caller's
     * order; the write is a separate step ([dispatch]), as it was at every call site. It picks the
     * path for this peer (§5.2), adds the delivery-key fields, and seals when the path is not the
     * legacy one.
     */
    private fun prepare(payload: Wire.Payload, to: String, kind: String, ttl: Int, id: String): Outbound =
        prepare(payload, to, kind, ttl, id, null, 1)

    /** [forced] names the path: the legacy one after a refusal, or the one an attachment's blob was already encrypted for. */
    private fun prepare(payload: Wire.Payload, to: String, kind: String, ttl: Int, id: String, forced: Lane?, attempt: Int): Outbound {
        val lane = forced ?: sealedLane(to)
        val own = sealedOwn
        var out = payload
        // `dk` until the peer echoes my key, `dkh` while I hold theirs: on every path, the legacy
        // one included, for as long as this login has sealed sender
        val mine = store.blob.deliveryKey
        if (own != null && mine != null) {
            val fields = PeerKeys.outgoingFields(peerKey(to), mine.bytes)
            out = out.copy(dk = fields.dk, dkh = fields.dkh)
        }
        if (lane == Lane.LEGACY || lane == Lane.FETCH || own == null) {
            return Outbound(Wire.Out.Send(id, to, ttl, kind, crypto.encrypt(out, to)), Lane.LEGACY, payload, to, kind, ttl, id, null, attempt)
        }
        if (lane == Lane.FIRST) {
            // typing would spend the recipient's first-contact allowance and crowd out the message
            // itself; an attachment waits until the first text is delivered (§5.2). Both are refused
            // here, before the ratchet moves.
            if (out.control == "typing") throw ServiceError("first_contact_typing")
            if (kind == "blob") throw ServiceError(Sealed.firstContactRefusal(peerName(to)))
        }
        // a message that has a row carries its id inside: the id the relay sees is new for every
        // attempt (group messages already carry theirs)
        if (out.refId == null && (out.kind == "text" || out.kind == "blob_manifest")) out = out.copy(refId = id)
        val body = crypto.seal(out, to, own.own)
        if (lane == Lane.FIRST && body.length > PeerKeys.FIRST_CONTACT_MAX_BODY) throw ServiceError(Sealed.firstContactTooLong(peerName(to)))
        val access = if (lane == Lane.KEY) peerKey(to)?.dk?.bytes else null
        val frame = Wire.Out.Deliver(newMessageId(), to, ttl, kind, body, access?.let { b64(it) })
        return Outbound(frame, lane, payload, to, kind, ttl, id, access, attempt)
    }

    /** Attachment bytes for a peer: what goes out as chunks after their manifest. The blob takes the same path as its manifest. */
    private fun prepareBlob(content: ByteArray, to: String, lane: Lane): ByteArray {
        val own = sealedOwn
        return if (lane == Lane.LEGACY || own == null) BlobCrypto.encrypt(crypto, content, to) else crypto.sealBlob(content, to, own.own)
    }

    /**
     * THE one place a prepared message is written. On the legacy path it throws when there is no
     * socket, as before. A sealed message goes to the anonymous connection, opened on first need;
     * what becomes of it arrives later as a verdict, mapped back to the caller's id.
     */
    private fun dispatch(out: Outbound) {
        if (out.lane == Lane.LEGACY) {
            sendFrame(out.frame)
            return
        }
        scope.launch { dispatchSealed(out) }
    }

    /** File [out] as the envelope of row [rowId] on the details sheet; a later attempt for the same message replaces it there. */
    private fun capture(rowId: String, out: Outbound, ifAbsent: Boolean = false) {
        if (capturedRows.size > 200) capturedRows.clear() // bookkeeping only; never grows without bound
        capturedRows[out.callerId] = rowId
        if (ifAbsent) frames.putIfAbsent(rowId, out.captured()) else frames.put(rowId, out.captured())
    }

    private fun recapture(out: Outbound) {
        capturedRows[out.callerId]?.let { frames.put(it, out.captured()) }
    }

    // ---------------------------------------------------------------- sealed sender (docs/SEALED-SENDER.md)

    private val _sealedStatus = MutableStateFlow(SealedStatus.NOT_OFFERED)

    /** What the status page says about it (§5.8). */
    val sealedStatus: StateFlow<SealedStatus> get() = _sealedStatus

    /** Tests only: be, on the wire, a build from before sealed sender. Takes effect at the next login. */
    @Volatile
    internal var legacyWireOnly = false

    /** Tests only: where the anonymous connection dials instead of the relay, to see what happens when it cannot be opened. */
    @Volatile
    internal var anonRelayUrlForTest: String? = null

    /** Observation tap on every frame handed to an anonymous connection (tests). Never on iOS. */
    @Volatile
    var anonWireTap: ((Wire.Out) -> Unit)? = null

    /** The same for every attachment chunk written to one (tests: framing). */
    @Volatile
    var anonBinaryTap: ((ByteArray) -> Unit)? = null

    private fun anonSend(link: AnonLink, frame: Wire.Out): Boolean {
        val ok = link.client.send(frame)
        if (ok) anonWireTap?.invoke(frame)
        return ok
    }

    /** The roots a certificate from this login's relay may chain to. Empty: this build does not offer sealed sender there. */
    private var sealedRoots: List<ECPublicKey> = emptyList()

    /** My certificate and binding for this login (RAM only); null = the relay offered none that passes its check. */
    private var sealedOwn: SealedCertificate? = null
    private var certJob: Job? = null

    /** The anonymous connection new work goes out on. */
    private var anonLink: AnonLink? = null

    /** Every anonymous connection still open: the current one and any that is retiring. */
    private val anonLinks = ArrayList<AnonLink>()
    private var anonOpening: Deferred<AnonLink>? = null

    /** The opening in flight is the engine's own, to send lost work again: its failure settles nothing. */
    private var anonOpeningForLostWork = false

    /** Two attempts to open one failed: the legacy path until the next login (§5.5). */
    private var anonFailed = false

    /** An anonymous connection ended with messages in flight: they go again when the next opens. */
    private var anonLostWork = false

    /** Connections opened in a row because one died with work on it, with no verdict in between. */
    private var anonReopens = 0

    /**
     * Relay ids of the attachments whose chunks are going out, and what the relay answered for one
     * of them when it refused a chunk (it drops the whole attachment then).
     */
    private val uploading = HashSet<String>()
    private val uploadRefused = HashMap<String, String>()

    /**
     * How long the relay may stay silent after the ping written behind an attachment's last chunk
     * (§5.6). Its answer is what makes the upload complete. Tests shorten it.
     */
    @Volatile
    internal var uploadAnswerTimeoutMs = UPLOAD_ANSWER_TIMEOUT_MS

    /** peer hash → the bundle lookup in flight after a `denied` (one per peer at a time). */
    private val bundleRefresh = HashMap<String, Deferred<Unit>>()

    /** caller id → the row its frame is filed under. */
    private val capturedRows = HashMap<String, String>()

    private fun refreshSealedRoots() {
        sealedRoots = if (legacyWireOnly) emptyList() else SealedTrust.roots(settings.relayURL, sealedTestRoots)
    }

    /**
     * What `register` and `auth` offer the relay: the hash of my delivery key. null when this build
     * trusts no root for this relay, and then nothing about sealed sender is ever sent.
     */
    private fun sealedDeliveryOffer(): String? {
        if (sealedRoots.isEmpty()) return null
        if (store.blob.deliveryKey == null) {
            val key = Bytes(PeerKeys.newDeliveryKey())
            store.update { it.copy(deliveryKey = key) }
        }
        return store.blob.deliveryKey?.let { b64(PeerKeys.deliveryHash(it.bytes)) }
    }

    /** `ready` arrived: take the certificate it carries, or note that this login has none. */
    private fun sealedLoggedIn(ready: Wire.In.Ready) {
        anonFailed = false
        anonReopens = 0
        if (ready.sealed == true && sealedRoots.isNotEmpty()) takeCertificate(ready.cert, ready.bind, ready.certTtl) else dropCertificate()
    }

    /**
     * A certificate and binding from `ready` or `cert`. They are checked the way a recipient will
     * check them; one that fails leaves sealed sender off rather than sending messages nobody
     * could verify.
     */
    private fun takeCertificate(cert: String?, bind: String?, ttlSeconds: Int?) {
        certJob?.cancel()
        certJob = null
        val hash = Sealed.hashBytes(myHash)
        val own = if (sealedRoots.isEmpty() || cert == null || bind == null || hash == null) null else runCatching {
            Sealed.takeOwnCertificate(cert, bind, hash, crypto.myIdentityPublic(), sealedRoots, SealedTrust.MIN_SERVER_KEY_ID)
        }.getOrNull()
        if (own == null) {
            dropCertificate()
            return
        }
        val lifeMs = maxOf(1, ttlSeconds ?: 0) * 1000L
        // nothing is sealed in the last tenth of its life, a minute at most
        sealedOwn = SealedCertificate(own, elapsedRealtimeMs() + lifeMs - minOf(60_000L, lifeMs / 10))
        // renewed at half its life, and asked for again every eighth of it until the relay answers
        certJob = scope.launch {
            delay(lifeMs / 2)
            while (isActive && connState.value == ConnState.ONLINE) {
                runCatching { sendFrame(Wire.Out.Cert) }
                delay(lifeMs / 8)
            }
        }
        refreshSealedStatus()
    }

    private fun dropCertificate() {
        certJob?.cancel()
        certJob = null
        sealedOwn = null
        refreshSealedStatus()
    }

    /** The main link is gone, or is being closed: an anonymous connection never outlives it. */
    private fun sealedLinkDown() {
        dropCertificate() // first: a connection closed below must not be opened again
        closeAnonLinks()
    }

    private fun refreshSealedStatus() {
        _sealedStatus.value = when {
            sealedOwn == null -> SealedStatus.NOT_OFFERED
            anonFailed -> SealedStatus.NO_PRIVATE_CONNECTION
            else -> SealedStatus.ON
        }
    }

    /**
     * True while a message may be sealed: the relay offered it at this login, my certificate passed
     * its own check and has not run out, and the anonymous connection can be had.
     */
    private val sealedUsable: Boolean
        get() {
            val own = sealedOwn ?: return false
            return !anonFailed && connState.value == ConnState.ONLINE && elapsedRealtimeMs() < own.untilMs
        }

    // ---- what I know about each peer (§5.1)

    private fun peerKey(peer: String): PeerKey? = store.blob.peerKeys?.get(peer)

    /** Store an entry; one that says nothing is not kept. */
    private fun setPeerKey(peer: String, entry: PeerKey?) {
        val next = entry?.takeIf { !it.isEmpty }
        if (next == peerKey(peer)) return
        store.update { blob ->
            val all = (blob.peerKeys ?: emptyMap()).toMutableMap()
            if (next == null) all.remove(peer) else all[peer] = next
            blob.copy(peerKeys = all.ifEmpty { null })
        }
    }

    /** Every payload received from a peer, on any path, and every sealed message that passed its checks. */
    private fun learnKeys(peer: String, payload: Wire.Payload, sealed: Boolean) {
        if (legacyWireOnly) return
        val before = peerKey(peer)
        val entry = PeerKeys.payloadReceived(if (sealed) PeerKeys.sealedReceived(before) else before, payload.dk, payload.dkh)
        setPeerKey(peer, entry)
        // Their key just arrived: what could not go without it can go now. An attachment sent
        // before the first text was delivered (§5.2) sends itself, nobody has to tap [RETRY].
        if (before?.dk == null && entry.dk != null && connState.value == ConnState.ONLINE) retryRecentFailures()
    }

    /** The delivery key a roster may carry for a member: my own for me, the one I hold for anyone else. Only while this login has sealed sender. */
    private fun rosterDeliveryKey(hash: String): String? {
        if (sealedOwn == null) return null
        val key = if (hash == myHash) store.blob.deliveryKey else peerKey(hash)?.dk
        return key?.let { b64(it.bytes) }
    }

    /** A delivery key that arrived in a group roster (§5.1: taken only when none is held). */
    private fun learnRosterKey(dk: String?, hash: String) {
        if (legacyWireOnly || hash == myHash || dk == null) return
        setPeerKey(hash, PeerKeys.rosterKey(peerKey(hash), dk))
    }

    /** The path for a message to a peer I have a session with (§5.2). */
    private fun sealedLane(peer: String): Lane {
        val lane = PeerKeys.route(peerKey(peer), sealedUsable, hasSession = true)
        return if (lane == Lane.FETCH) Lane.LEGACY else lane
    }

    private fun peerName(hash: String): String = contactName(hash) ?: GroupRules.anonName(hash)

    // ---- the anonymous connection (§5.5)

    /**
     * The connection new work goes out on, opened on first need. Throws when there is none to be
     * had: the main link is down, this login has no certificate, or two attempts to open one failed
     * (then the legacy path is used until the next login).
     */
    private suspend fun ensureAnon(forLostWork: Boolean = false): AnonLink {
        anonLink?.takeIf { !it.ended }?.let { return it }
        if (anonFailed || sealedOwn == null || connState.value != ConnState.ONLINE) throw ServiceError(NO_PRIVATE_CONNECTION)
        anonOpening?.let { running ->
            val own = anonOpeningForLostWork
            try {
                return running.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The attempt this caller joined was the engine's own and it failed. A caller with
                // a message in hand makes the attempt that counts; anyone else takes the answer.
                if (forLostWork || !own) throw e
            }
            return ensureAnon(false)
        }
        if (forLostWork) {
            // counted here, where a connection is opened: one that died is counted once, however
            // many pieces of work noticed it
            if (anonReopens >= MAX_ANON_REOPENS) throw ServiceError(NO_PRIVATE_CONNECTION)
            anonReopens += 1
        }
        anonOpeningForLostWork = forLostWork
        val opening = scope.async {
            try {
                var attempts = 0
                while (attempts++ < 2 && connState.value == ConnState.ONLINE && sealedOwn != null) {
                    val link = openAnonOnce() ?: continue
                    if (connState.value != ConnState.ONLINE || sealedOwn == null) {
                        anonEnd(link) // the main link dropped while this one was opening
                        break
                    }
                    anonLink = link
                    if (anonLostWork) {
                        // what was in flight when the last one ended goes again now (§5.5)
                        anonLostWork = false
                        retryRecentFailures()
                    }
                    return@async link
                }
                // An attempt made only to send lost work again decides nothing: the next message
                // tries once more, and that one settles it.
                if (!forLostWork && connState.value == ConnState.ONLINE && sealedOwn != null) {
                    anonFailed = true
                    refreshSealedStatus()
                    if (anonLostWork) {
                        // no private connection until the next login: what was lost goes the old way
                        anonLostWork = false
                        retryRecentFailures()
                    }
                }
                throw ServiceError(NO_PRIVATE_CONNECTION)
            } finally {
                anonOpening = null
            }
        }
        anonOpening = opening
        return opening.await()
    }

    /** The anonymous connection, or null when there is none to be had. */
    private suspend fun anonOrNull(forLostWork: Boolean = false): AnonLink? {
        val link = runCatching { ensureAnon(forLostWork) }.getOrNull()
        currentCoroutineContext().ensureActive()
        return link
    }

    /**
     * One attempt: connect, send `anon`, wait up to 10 s for `anon_ok`. Its own client, so no
     * connection and no TLS session is shared with the logged-in socket.
     */
    private suspend fun openAnonOnce(): AnonLink? {
        val client = RelayClient(anonRelayUrlForTest ?: settings.relayURL, RelayHttp.makeClient(settings.socksProxy))
        try {
            client.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!client.send(Wire.Out.Anon)) {
            client.close()
            return null
        }
        val accepted = withTimeoutOrNull(ANON_OPEN_TIMEOUT_MS) {
            var ok = false
            for (event in client.events) {
                if (event is RelayEvent.Frame) {
                    ok = event.frame is Wire.In.AnonOk
                    break
                }
                if (event is RelayEvent.Disconnected) break
            }
            ok
        } ?: false
        if (!accepted) {
            client.close()
            return null
        }
        val link = AnonLink(client)
        anonLinks += link
        link.events = scope.launch {
            for (event in client.events) {
                when (event) {
                    is RelayEvent.Frame -> {
                        // counted as it is reached: everything that arrived before it has been routed
                        if (event.frame is Wire.In.Pong) client.pongRouted()
                        routeAnon(event.frame, link)
                    }
                    is RelayEvent.Disconnected -> {
                        anonEnd(link)
                        return@launch
                    }
                    else -> {}
                }
            }
        }
        // kept alive like the main link while the app is in front: the relay closes a silent
        // socket after 90 s. Out of sight it has served what it was opened for (a receipt for a
        // message that arrived in the background), and closes once that is settled.
        link.ping = scope.launch {
            while (isActive && !link.ended) {
                delay(linkTiming.keepaliveMs)
                if (link.ended) break
                if (inFront()) client.send(Wire.Out.Ping) else anonRetire(link)
            }
        }
        return link
    }

    /**
     * The connection is gone or going: nothing still on it will be answered. What was in flight
     * fails as a send does when the link drops, and is retried when a connection is back.
     */
    private fun anonEnd(link: AnonLink) {
        if (link.ended) return
        link.ended = true
        if (anonLink === link) anonLink = null
        anonLinks.remove(link)
        link.ping?.cancel()
        link.client.close()
        val lost = HashMap(link.attempts)
        link.attempts.clear()
        for ((id, out) in lost) {
            val waiter = link.verdictWaiters.remove(id)
            if (waiter != null) waiter.complete(NO_VERDICT) else relayRefused(NO_VERDICT, out.callerId)
        }
        val lostNow = lost.isNotEmpty() || link.fetches.isNotEmpty()
        if (lostNow) anonLostWork = true
        // a bundle asked for here will never come: say so now, not when the fetch times out
        for (peer in link.fetches.toList()) {
            pendingBundleFetches.remove(peer)?.reply?.completeExceptionally(IOException("the private connection closed"))
        }
        link.fetches.clear()
        if (lostNow) lostWithConnection()
    }

    /**
     * Work was lost with an anonymous connection (the rows it belongs to are FAILED by now) while
     * the main link is up: open another and send it again, so nobody has to do anything (§5.5).
     * [ensureAnon] opens two in a row at most for this with no verdict in between: a path that
     * keeps killing the connection must not become a loop, and then the work waits for the next
     * message or the next login.
     */
    private fun lostWithConnection() {
        anonLostWork = true
        if (anonFailed || sealedOwn == null || connState.value != ConnState.ONLINE) return
        scope.launch {
            anonOrNull(forLostWork = true) ?: return@launch
            if (anonLostWork) {
                anonLostWork = false
                retryRecentFailures()
            }
        }
    }

    /** An attachment's chunks were going out on an anonymous connection, and it ended. */
    private class AnonLinkLost : IOException("the private connection closed")

    private fun closeAnonLinks() {
        for (link in anonLinks.toList()) anonEnd(link)
    }

    /**
     * After a fallback to the legacy path (§5.2) new work goes to a fresh connection, so the
     * refused attempt and the legacy send that follows are not on record against the socket used
     * for other people. What is in flight on this one still gets its verdict.
     */
    private fun anonRetire(link: AnonLink) {
        if (anonLink === link) anonLink = null
        link.retiring = true
        anonSettle(link)
    }

    private fun anonSettle(link: AnonLink) {
        if (link.retiring && link.attempts.isEmpty() && link.uploads == 0 && link.fetches.isEmpty()) anonEnd(link)
    }

    private fun armVerdictDeadline(id: String, link: AnonLink) {
        scope.launch {
            delay(VERDICT_DEADLINE_MS)
            // no verdict: this connection is not answering. Everything on it fails, and the retry opens another.
            if (!link.ended && link.attempts.containsKey(id)) anonEnd(link)
        }
    }

    private fun routeAnon(f: Wire.In, link: AnonLink) {
        when (f) {
            is Wire.In.Sent -> link.attempts.remove(f.id)?.let { out ->
                anonReopens = 0 // this path works
                val waiter = link.verdictWaiters.remove(f.id)
                if (waiter != null) waiter.complete(null) else relaySent(out.callerId)
            }
            is Wire.In.PreKeyBundle -> {
                // every bundle reply here says whether that account can receive sealed messages (§5.1)
                if (!legacyWireOnly) setPeerKey(f.user, PeerKeys.bundleSeen(peerKey(f.user), f.sealed == true))
                link.fetches.remove(f.user)
                pendingBundleFetches.remove(f.user)?.reply?.complete(f)
            }
            is Wire.In.Error -> {
                val ref = f.ref
                val out = ref?.let { link.attempts.remove(it) }
                if (out != null) {
                    val waiter = link.verdictWaiters.remove(f.ref)
                    // DEADLOCK GUARD, as for healSession: a refusal may wait for a prekey bundle, and
                    // that bundle arrives through this very loop. Handle it in its own coroutine.
                    if (waiter != null) waiter.complete(f.code) else scope.launch { sealedRefused(out, f.code, link) }
                } else if (ref != null && ref in uploading) {
                    uploadRefused[ref] = f.code // a chunk was refused: the upload ends
                } else {
                    // a refused prekey fetch names the account asked for
                    f.user?.let { user ->
                        link.fetches.remove(user)
                        pendingBundleFetches.remove(user)?.reply?.complete(f)
                    }
                }
            }
            else -> {}
        }
        anonSettle(link)
    }

    /** Tests only: hand a frame to the anonymous receive path as if the relay had sent it. */
    internal suspend fun routeAnonForTest(frame: Wire.In, link: AnonLink): Unit = actor { routeAnon(frame, link) }

    // ---- sending on a sealed lane (§5.2, §5.3)

    /**
     * Write a sealed message to the anonymous connection. With no such connection to be had the
     * message is encrypted again for the legacy path.
     */
    private suspend fun dispatchSealed(out: Outbound) {
        val frame = out.frame as? Wire.Out.Deliver ?: return
        val link = anonOrNull()
        if (link == null) {
            try {
                val again = prepare(out.payload, out.peer, out.kind, out.ttl, out.callerId, Lane.LEGACY, out.attempt + 1)
                recapture(again)
                sendFrame(again.frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                relayRefused(NO_VERDICT, out.callerId)
            }
            return
        }
        link.attempts[frame.id] = out
        armVerdictDeadline(frame.id, link)
        if (!anonSend(link, frame)) anonEnd(link) // fails everything in flight on it, this one included
    }

    private sealed class Verdict {
        /** Taken by the relay, on this connection, under this id. */
        class Sent(val link: AnonLink, val id: String) : Verdict()
        class Refused(val code: String, val link: AnonLink?) : Verdict()
    }

    /**
     * The same write, for a caller that needs the verdict before it goes on: an attachment's
     * manifest, whose chunks follow only once the relay has taken it (§5.6).
     */
    private suspend fun dispatchForVerdict(out: Outbound): Verdict {
        val frame = out.frame as? Wire.Out.Deliver ?: return Verdict.Refused(NO_PRIVATE_CONNECTION, null)
        val link = anonOrNull() ?: return Verdict.Refused(NO_PRIVATE_CONNECTION, null)
        val waiter = CompletableDeferred<String?>()
        link.attempts[frame.id] = out
        link.verdictWaiters[frame.id] = waiter
        armVerdictDeadline(frame.id, link)
        if (!anonSend(link, frame)) anonEnd(link) // answers the waiter with NO_VERDICT
        val code = waiter.await()
        return if (code == null) Verdict.Sent(link, frame.id) else Verdict.Refused(code, link)
    }

    /** The relay refused a sealed message: one step down, never back up (§5.2). */
    private suspend fun sealedRefused(out: Outbound, code: String, link: AnonLink) {
        when (code) {
            Wire.ErrorCode.DENIED -> {
                keyDenied(out)
                reroute(out, null)
            }
            Wire.ErrorCode.UNSUPPORTED -> { // an old build has the account
                setPeerKey(out.peer, PeerKeys.unsupported(peerKey(out.peer)))
                anonRetire(link)
                reroute(out, null)
            }
            Wire.ErrorCode.INTRO_FULL -> {
                anonRetire(link)
                reroute(out, Lane.LEGACY)
            }
            Wire.ErrorCode.INTRO_LIMIT -> {
                _lastError.value = if (out.kind == "blob") Sealed.firstContactRefusal(peerName(out.peer)) else Sealed.firstContactTooLong(peerName(out.peer))
                relayRefused(code, out.callerId)
            }
            else -> {
                _lastError.value = code
                relayRefused(code, out.callerId)
            }
        }
    }

    /**
     * `denied`: the key that attempt carried is no longer theirs. Forget it and look the peer up
     * again; a changed identity key starts a new session, as it does today.
     */
    private suspend fun keyDenied(out: Outbound) {
        val dropped = PeerKeys.denied(peerKey(out.peer), out.access)
        if (dropped != null) {
            setPeerKey(out.peer, dropped)
            refreshFromBundle(out.peer)
        } else {
            bundleRefresh[out.peer]?.let { runCatching { it.await() } } // another refusal is already looking them up
        }
    }

    private suspend fun reroute(out: Outbound, lane: Lane?) {
        if (out.attempt >= PeerKeys.MAX_ATTEMPTS) {
            relayRefused(NO_VERDICT, out.callerId)
            return
        }
        try {
            if (!crypto.hasSession(out.peer)) fetchAndProcessBundle(out.peer)
            val next = prepare(out.payload, out.peer, out.kind, out.ttl, out.callerId, lane, out.attempt + 1)
            recapture(next)
            dispatch(next)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            relayRefused(NO_VERDICT, out.callerId)
        }
    }

    /**
     * One lookup per peer at a time: several messages refused together share it, so one bundle
     * never starts two sessions.
     */
    private suspend fun refreshFromBundle(peer: String) {
        bundleRefresh[peer]?.let {
            runCatching { it.await() }
            return
        }
        val job = scope.async<Unit> {
            try {
                val (fetch, reply) = runCatching { fetchBundle(peer) }.getOrNull() ?: return@async
                val bundle = reply as? Wire.In.PreKeyBundle ?: return@async
                val key = runCatching { Base64.getDecoder().decode(bundle.identityKey) }.getOrNull() ?: return@async
                if (crypto.hasSession(peer) && crypto.knownIdentity(peer)?.contentEquals(key) == true) return@async
                if (fetch.processed) return@async
                fetch.processed = true
                runCatching { crypto.processBundle(bundle, peer) }
                Unit
            } finally {
                bundleRefresh.remove(peer)
            }
        }
        bundleRefresh[peer] = job
        runCatching { job.await() }
    }

    // ---- tests

    /**
     * Tests only: the sealed-sender internals of a service that never connects, the way the iOS
     * tests reach theirs through `@testable`. Used through [sealedForTest], on the service thread.
     */
    internal inner class SealedHooks {
        /** An identity without a relay: what `register` makes before it dials. */
        fun generateIdentity(username: String): PreKeys.Registration =
            crypto.generateIdentity(username).also { StoreSignalState.persistScalars(store, signalState, usernameDisplay = username) }

        fun processBundle(bundle: Wire.In.PreKeyBundle, peer: String) = crypto.processBundle(bundle, peer)
        fun hasSession(peer: String): Boolean = crypto.hasSession(peer)

        var roots: List<ECPublicKey>
            get() = sealedRoots
            set(value) {
                sealedRoots = value
            }

        /** Hold [own] as my certificate, usable for [usableMs] more (negative: it has run out); null takes it away. */
        fun arm(own: Sealed.Own?, usableMs: Long = 3_600_000) {
            sealedOwn = own?.let { SealedCertificate(it, elapsedRealtimeMs() + usableMs) }
        }

        val armed: Boolean get() = sealedOwn != null

        /** How much longer my certificate may be used, in milliseconds. */
        val usableForMs: Long? get() = sealedOwn?.let { it.untilMs - elapsedRealtimeMs() }
        val usable: Boolean get() = sealedUsable

        /** The link counts as up, with no socket behind it. */
        var online: Boolean
            get() = connState.value == ConnState.ONLINE
            set(value) {
                link.connState = if (value) ConnState.ONLINE else ConnState.OFFLINE
            }

        var anonFailed: Boolean
            get() = this@ChatService.anonFailed
            set(value) {
                this@ChatService.anonFailed = value
            }

        val lostWork: Boolean get() = anonLostWork

        /** Connections opened in a row for lost work, with no verdict in between. */
        val reopens: Int get() = anonReopens
        fun lostWithConnection() = this@ChatService.lostWithConnection()

        /** The anonymous connection new work goes out on, when one is open. */
        val anon: AnonLink? get() = anonLink

        fun lane(peer: String): Lane = sealedLane(peer)

        /** When my certificate ends, by its own field (epoch milliseconds). */
        val certExpiresMs: Long? get() = sealedOwn?.own?.cert?.expiration

        /** No more renewals: what a relay that stopped answering `cert` looks like. */
        fun stopRenewal() {
            certJob?.cancel()
            certJob = null
        }

        fun askCertificate() = sendFrame(Wire.Out.Cert)

        fun prepare(payload: Wire.Payload, to: String, kind: String = "msg", ttl: Int = Protocol.TTL_MSG, id: String = newMessageId(), lane: Lane? = null, attempt: Int = 1): Outbound =
            this@ChatService.prepare(payload, to, kind, ttl, id, lane, attempt)

        fun peerKey(peer: String): PeerKey? = this@ChatService.peerKey(peer)
        fun setPeerKey(peer: String, entry: PeerKey?) = this@ChatService.setPeerKey(peer, entry)
        fun offer(): String? = sealedDeliveryOffer()
        fun loggedIn(ready: Wire.In.Ready) = sealedLoggedIn(ready)
        fun takeCertificate(cert: String?, bind: String?, ttlSeconds: Int?) = this@ChatService.takeCertificate(cert, bind, ttlSeconds)
        fun linkDown() = sealedLinkDown()
        fun refreshStatus() = refreshSealedStatus()
        fun rosterDeliveryKey(hash: String): String? = this@ChatService.rosterDeliveryKey(hash)
        fun learnRosterKey(dk: String?, hash: String) = this@ChatService.learnRosterKey(dk, hash)
        fun peerName(hash: String): String = this@ChatService.peerName(hash)
        fun anonEnd(link: AnonLink) = this@ChatService.anonEnd(link)
        fun anonRetire(link: AnonLink) = this@ChatService.anonRetire(link)
        fun capture(rowId: String, out: Outbound) = this@ChatService.capture(rowId, out)
        fun appendMessage(msg: ChatMessage, thread: String) = this@ChatService.appendMessage(msg, thread)
    }

    internal suspend fun <T> sealedForTest(block: suspend SealedHooks.() -> T): T = actor { SealedHooks().block() }

    // ---------------------------------------------------------------- verdicts, from either connection

    /**
     * The relay took a message. [id] is the caller's id for it: the row, the control or the group
     * envelope. On the legacy path that is the relay id too; on a sealed lane the relay saw a fresh
     * id, mapped back before this is called (§5.3).
     */
    private fun relaySent(id: String) {
        pendingControlAcks.remove(id)?.complete(true)
        if (inflightReceipts.remove(id) != null) return // receipt confirmed
        inflightGroupControls.remove(id)?.let { c ->
            c.onVerdict?.invoke(true) // roster control landed
            return
        }
        groupSendIds[id]?.let { ref ->
            groupOutbound[ref.messageId]?.let { t ->
                t.sent(ref.recipient)
                finishGroupSend(t)
            }
            return
        }
        markSent(id)
    }

    private suspend fun handleEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Disconnected -> failAllPending()
            is RelayEvent.Frame -> route(event.frame)
            is RelayEvent.Binary -> receiveBlobChunk(event.bytes)
            is RelayEvent.Undecodable, is RelayEvent.Connected -> {} // dropped like iOS `try? decode`; Connected is RelayLink's
        }
    }

    private suspend fun route(f: Wire.In) {
        // Swift: `case "registered", "error" where pendingRegister != nil` — an error while
        // registering answers the register waiter (and a pending challenge) and NOTHING else.
        // There is exactly ONE general error path below; never add a second.
        if (f is Wire.In.Error && pendingRegister != null) {
            pendingRegister?.complete(f)
            pendingRegister = null
            pendingChallenge?.complete(f)
            pendingChallenge = null
            return
        }
        when (f) {
            is Wire.In.Registered -> {
                pendingRegister?.complete(f)
                pendingRegister = null
            }
            is Wire.In.Challenge -> {
                pendingChallenge?.complete(f)
                pendingChallenge = null
            }
            is Wire.In.Ready -> {
                pendingReady?.complete(f)
                pendingReady = null
            }
            is Wire.In.PreKeyBundle -> pendingBundleFetches.remove(f.user)?.reply?.complete(f)
            is Wire.In.Recv -> receiveMessage(f)
            is Wire.In.Sent -> relaySent(f.id)
            is Wire.In.Cert -> takeCertificate(f.cert, f.bind, f.certTtl)
            is Wire.In.PreKeyCount -> if (f.n < Protocol.ONE_TIME_PRE_KEY_TOP_UP_BELOW) topUpPreKeys()
            is Wire.In.Error -> handleError(f)
            is Wire.In.Notice -> receiveNotice(f)
            is Wire.In.NoticeGone -> removeNotice(f.id)
            is Wire.In.Versions -> receiveVersions(f)
            else -> {}
        }
    }

    /** Tests only: push one relay→client frame through the router on the actor, no socket involved. */
    internal suspend fun routeForTest(frame: Wire.In): Unit = actor { route(frame) }

    // ---------------------------------------------------------------- operator notices

    /**
     * `notice`: upsert by id (the relay replays every active notice at each login, so a repeat
     * is normal, not news); malformed or already-expired frames are ignored. A notice this device
     * has never held before blips like an incoming message.
     */
    private fun receiveNotice(f: Wire.In.Notice) {
        val notice = NoticeRules.fromFrame(f, clock()) ?: return
        val current = _notices.value
        val known = current.any { it.id == notice.id }
        setNotices((current.filterNot { it.id == notice.id } + notice).sortedBy { it.ts })
        if (!known) play(Tone.RX)
    }

    /** `notice_gone`: the operator deleted it — drop it everywhere, seen or not. */
    private fun removeNotice(id: String) {
        val current = _notices.value
        if (current.none { it.id == id }) return
        setNotices(current.filterNot { it.id == id })
    }

    /** Opening the `<pyrochat>` channel: every active notice counts as read from now on. */
    suspend fun markNoticesSeen(): Unit = actor {
        val ids = _notices.value.map { it.id }
        if (seenNoticeIds.containsAll(ids)) {
            _unseenNoticeCount.value = 0
            return@actor
        }
        seenNoticeIds.addAll(ids)
        _unseenNoticeCount.value = 0
        noticeStore.putSeen(seenNoticeIds.toSet())
    }

    /** Publish + persist; the seen set is pruned to ids that still exist so it can never grow unbounded. */
    private fun setNotices(next: List<Notice>) {
        _notices.value = next
        val ids = next.map { it.id }.toSet()
        if (seenNoticeIds.retainAll(ids)) noticeStore.putSeen(seenNoticeIds.toSet())
        _unseenNoticeCount.value = next.count { it.id !in seenNoticeIds }
        noticeStore.putNotices(NoticeCodec.encode(next))
    }

    private fun sweepNotices(now: Instant) {
        val current = _notices.value
        val kept = current.filter { it.expires.isAfter(now) }
        if (kept.size != current.size) setNotices(kept)
    }

    // ---------------------------------------------------------------- latest versions (update banner)

    /**
     * `versions`: the SAME frame for everyone, sent after the notice replay at every login and live
     * when the operator changes it. Our platform's entry is remembered as the last accepted one
     * (null when absent) and the banner recomputed against [versionCode]; a malformed frame is
     * ignored as a whole and changes nothing. No blip: it is one row, never a notice, never a push.
     */
    private fun receiveVersions(f: Wire.In.Versions) {
        when (val verdict = UpdateRules.fromFrame(f, versionCode)) {
            UpdateRules.Verdict.Malformed -> return
            is UpdateRules.Verdict.Accepted -> {
                noticeStore.putVersions(verdict.latest?.let(VersionCodec::encode))
                if (_updateAvailable.value != verdict.update) _updateAvailable.value = verdict.update
            }
        }
    }

    private fun handleError(f: Wire.In.Error) {
        _lastError.value = f.code
        // (Android) a login-stage error — unknown_user after a deregister elsewhere, bad_sig —
        // resolves the handshake instead of leaving goOnline() awaiting forever
        pendingChallenge?.let { it.complete(f); pendingChallenge = null }
        pendingReady?.let { it.complete(f); pendingReady = null }
        // an error naming a user answers that user's bundle fetch (unknown / deregistered
        // account) instead of leaving it waiting
        f.user?.let { user -> pendingBundleFetches.remove(user)?.reply?.complete(f) }
        val ref = f.ref ?: return
        if (ref in uploading) uploadRefused[ref] = f.code // its upload hears of it too
        relayRefused(f.code, ref)
    }

    /**
     * The relay refused a message, or a sealed one never got a verdict ([NO_VERDICT]). [ref] is the
     * caller's id, as in [relaySent].
     */
    private fun relayRefused(code: String, ref: String) {
        pendingControlAcks.remove(ref)?.let { it.complete(false); return }
        if (inflightReceipts.containsKey(ref)) {
            // transient -> retry later; permanent -> drop
            if (code == Wire.ErrorCode.RATE_LIMITED || code == Wire.ErrorCode.QUEUE_FULL || code == NO_VERDICT) {
                requeueInflightReceipt(ref)
                scheduleReceiptFlush(5.0)
            } else {
                inflightReceipts.remove(ref)
            }
            return
        }
        inflightGroupControls.remove(ref)?.let { c ->
            when (code) {
                Wire.ErrorCode.RATE_LIMITED, Wire.ErrorCode.QUEUE_FULL, NO_VERDICT -> {
                    if (++c.attempts < c.maxAttempts) {
                        groupControlQueue.addLast(c)
                        scheduleControlFlush(5_000)
                    } else c.onVerdict?.invoke(false)
                }
                Wire.ErrorCode.UNKNOWN_USER -> {
                    c.payload.group?.let { markMemberGone(it.id, c.peer) }
                    c.onVerdict?.invoke(false)
                }
                else -> c.onVerdict?.invoke(false)
            }
            return
        }
        groupSendIds[ref]?.let { sendRef ->
            val t = groupOutbound[sendRef.messageId]
            when (code) {
                Wire.ErrorCode.UNKNOWN_USER -> {
                    markMemberGone(sendRef.groupId, sendRef.recipient)
                    t?.gone(sendRef.recipient)
                }
                Wire.ErrorCode.RATE_LIMITED, Wire.ErrorCode.QUEUE_FULL -> {
                    // one silent re-send to THIS recipient after the limiter window, like the 1:1 path
                    val key = "${sendRef.messageId}:${sendRef.recipient}"
                    if (t != null && groupRecipientRetried.add(key)) {
                        scope.launch {
                            delay(5_000)
                            resendGroupRecipient(sendRef.messageId, sendRef.groupId, sendRef.recipient)
                        }
                        return
                    }
                    t?.failed(sendRef.recipient)
                }
                else -> t?.failed(sendRef.recipient)
            }
            t?.let { finishGroupSend(it) }
            return
        }
        val found = findMessage(ref)
        if (code == Wire.ErrorCode.RATE_LIMITED && ref !in rateLimitRetried &&
            found != null && found.second.kind == Kind.TEXT && found.second.direction == Direction.OUTGOING
        ) {
            // one silent retry after the limiter window clears
            rateLimitRetried += ref
            val thread = found.first
            scope.launch {
                delay(5_000)
                resendUnderSameId(ref, thread)
            }
        } else if (code == Wire.ErrorCode.UNKNOWN_USER && found != null) {
            markFailed(ref)
            noteAccountGone(found.first)
        } else {
            markFailed(ref)
        }
    }

    /**
     * The peer deleted their account: relay forgot their keys. Leave one system row so the
     * sender stops staring at endless SENT, and never duplicate it.
     */
    private fun noteAccountGone(thread: String) {
        if (!accountGoneNoted.add(thread)) return
        val name = _contacts.value.firstOrNull { it.hash == thread }?.name ?: "this contact"
        appendMessage(systemRow("⚠ <$name> deleted their account — messages can no longer be delivered."), thread)
    }

    /**
     * Account kill from THIS device: tell the relay to forget our keys, drop our queued messages,
     * and stop waking us. Best-effort — the caller wipes the local store right after regardless.
     * Only possible while authenticated (an unlocked, online session).
     */
    suspend fun deregister(): Unit = actor {
        if (connState.value != ConnState.ONLINE || link.socket == null) return@actor
        runCatching { sendFrame(Wire.Out.Deregister) }
        // give the frame time to flush before the socket is torn down
        delay(300)
    }

    private fun failAllPending() {
        sealedLinkDown() // an anonymous connection never outlives the main link
        val err = IOException("network connection lost")
        pendingRegister?.completeExceptionally(err)
        pendingRegister = null
        pendingChallenge?.completeExceptionally(err)
        pendingChallenge = null
        pendingReady?.completeExceptionally(err)
        pendingReady = null
        for (fetch in pendingBundleFetches.values) fetch.reply.completeExceptionally(err)
        pendingBundleFetches.clear()
        for (id in inflightReceipts.keys.toList()) requeueInflightReceipt(id)
        // roster controls go back to the queue; group rows still awaiting a verdict fail now so
        // the reconnect auto-retry re-sends them to exactly those recipients
        for (c in inflightGroupControls.values) groupControlQueue.addLast(c)
        inflightGroupControls.clear()
        for (t in groupOutbound.values.toList()) {
            if (t.done) continue
            for (r in t.pending.toList()) t.failed(r)
            finishGroupSend(t)
        }
    }

    // ---------------------------------------------------------------- registration & login

    /** Full signup: generate identity, register with relay, then authenticate. */
    suspend fun register(username: String): Unit = actor {
        registering = true
        try {
            link.wantOnline = true
            link.connState = ConnState.CONNECTING
            val registration = crypto.generateIdentity(username) // records land in the store as they are made
            StoreSignalState.persistScalars(store, signalState, usernameDisplay = username)
            refreshSealedRoots()
            val attempt = link.open()
            val waiter = CompletableDeferred<Wire.In>()
            pendingRegister = waiter
            val delivery = sealedDeliveryOffer()
            runCatching { sendFrame(crypto.registerFrame(registration).copy(delivery = delivery)) }
            val reply = awaitReply(waiter)
            if (reply !is Wire.In.Registered) {
                link.wantOnline = false
                link.connState = ConnState.OFFLINE
                throw ServiceError((reply as? Wire.In.Error)?.code ?: "register_failed")
            }
            authenticateOrRetry(attempt)
        } finally {
            registering = false
        }
    }

    /** Login on an existing identity (app relaunch / reconnect). */
    suspend fun goOnline(): Unit = actor {
        if (store.blob.usernameHash.isEmpty()) return@actor
        link.wantOnline = true
        link.connState = ConnState.CONNECTING
        refreshSealedRoots()
        val attempt = link.open()
        if (link.socket !== attempt) return@actor // replaced while it was connecting
        if (!attempt.isOpen) throw IOException("network connection lost") // RelayLink is already retrying
        authenticateOrRetry(attempt)
    }

    suspend fun goOffline(): Unit = actor {
        sealedLinkDown()
        link.disconnect()
    }

    /**
     * A login that got no reply was started on a network that is failing, and its socket can hang
     * for minutes without reporting anything. Close it and keep retrying.
     */
    private suspend fun authenticateOrRetry(attempt: RelayClient) {
        try {
            authenticate()
        } catch (e: ServiceError) {
            if (e.code == "timeout") link.dropLink(attempt)
            throw e
        }
    }

    private suspend fun authenticate() {
        link.connState = ConnState.AUTHENTICATING
        val challengeWaiter = CompletableDeferred<Wire.In>()
        pendingChallenge = challengeWaiter
        runCatching { sendFrame(Wire.Out.Login(myHash)) }
        val challenge = awaitReply(challengeWaiter)
        val nonce = (challenge as? Wire.In.Challenge)?.let { runCatching { Base64.getDecoder().decode(it.nonce) }.getOrNull() }
        if (nonce == null) {
            link.connState = ConnState.OFFLINE
            throw ServiceError((challenge as? Wire.In.Error)?.code ?: "login_failed")
        }
        val sig = crypto.signChallenge(nonce)
        val token = currentAccessToken()
        val pushToken = if (settings.pushEnabled) pushTokenProvider() else ""
        val readyWaiter = CompletableDeferred<Wire.In>()
        pendingReady = readyWaiter
        // a sealed-capable build offers the hash of its delivery key at EVERY login: a login
        // without it tells the relay an old build has the account (docs/SEALED-SENDER.md §4.1)
        val delivery = sealedDeliveryOffer()
        runCatching {
            sendFrame(
                Wire.Out.Auth(
                    sig = b64(sig),
                    token = token?.let { b64(it.token.bytes) },
                    tokenSig = token?.let { b64(it.signature.bytes) },
                    pushToken = pushToken,
                    platform = if (pushToken.isNullOrEmpty()) null else PUSH_PLATFORM,
                    delivery = delivery,
                ),
            )
        }
        val ready = awaitReply(readyWaiter)
        if (ready !is Wire.In.Ready) {
            link.connState = ConnState.OFFLINE
            throw ServiceError((ready as? Wire.In.Error)?.code ?: "auth_failed")
        }
        link.connState = ConnState.ONLINE
        sealedLoggedIn(ready) // my certificate for this login, or sealed sender off
        scheduleReceiptFlush() // drain anything accumulated offline
        scheduleControlFlush() // roster controls that waited for the link
        retryRecentFailures() // auto-resend what failed while unreachable
        link.noteLoggedIn()
    }

    /** A handshake reply, or `ServiceError("timeout")` — every await on a relay reply has a deadline. */
    private suspend fun awaitReply(waiter: CompletableDeferred<Wire.In>): Wire.In =
        withTimeoutOrNull(linkTiming.handshakeMs) { waiter.await() } ?: run {
            if (pendingRegister === waiter) pendingRegister = null
            if (pendingChallenge === waiter) pendingChallenge = null
            if (pendingReady === waiter) pendingReady = null
            throw ServiceError("timeout")
        }

    /**
     * Push the wake-push token to the relay while online (RAM-only there; an empty token
     * unregisters). Called when the platform delivers/rotates the token and when the wake-push
     * toggle changes.
     */
    suspend fun sendPushToken(token: String?): Unit = actor {
        if (connState.value != ConnState.ONLINE) return@actor // login re-sends it anyway
        val tok = if (settings.pushEnabled) (token ?: pushTokenProvider() ?: "") else ""
        runCatching { sendFrame(Wire.Out.PushToken(tok, platform = if (tok.isEmpty()) null else PUSH_PLATFORM)) }
    }

    /**
     * Call when the app comes to the foreground: the OS suspends sockets in the background, so
     * the backoff loop may still be asleep and a dead socket can go undetected.
     */
    suspend fun noteForegrounded(): Unit = actor { link.noteForegrounded() }

    /** The phone's network came back or switched: recover now instead of waiting out the backoff. */
    suspend fun noteNetworkChanged(): Unit = actor {
        if (registering) return@actor
        closeAnonLinks() // it may have stayed on the old network; the next message opens another
        link.noteNetworkChanged()
    }

    /**
     * The app left the foreground, where the OS may suspend its sockets. The anonymous connection
     * takes nothing new from here and closes once what is in flight has its verdict; the next
     * message opens another (docs/SEALED-SENDER.md §5.5).
     */
    suspend fun noteBackgrounded(): Unit = actor {
        for (link in anonLinks.toList()) anonRetire(link)
    }

    /**
     * Auto-retry: outgoing rows that FAILED while the relay was unreachable re-send themselves
     * when the link comes back. Only recent failures (15 min) retry on their own. Same message id
     * both times, and the receiver dedupes by id, so a peer can never see a duplicate.
     */
    private fun retryRecentFailures() {
        val cutoff = clock().minusSeconds(15 * 60)
        for ((thread, log) in _messages.value) {
            val group = group(thread)
            val contact = if (group == null) _contacts.value.firstOrNull { it.hash == thread } ?: continue else null
            if (thread in _blockedHashes.value) continue
            if (group != null && group.isLeft) continue
            for (msg in log) {
                if (msg.direction != Direction.OUTGOING || msg.status != Status.FAILED || !msg.timestamp.isAfter(cutoff)) continue
                when (msg.kind) {
                    Kind.TEXT -> scope.launch { if (group != null) retryGroupTextNow(msg.id, thread) else resendFailedText(msg, msg.text, contact!!) }
                    Kind.VOICE, Kind.IMAGE, Kind.FILE -> scope.launch { if (group != null) retryGroupBlobNow(msg.id, thread) else retryBlobNow(msg.id, thread) }
                    Kind.SYSTEM -> {}
                }
            }
        }
    }

    /** Manual retry from the FAILED [RETRY] tap on a text row. */
    suspend fun retryText(messageId: String, thread: String): Unit = actor {
        if (isGroup(thread)) {
            retryGroupTextNow(messageId, thread)
            return@actor
        }
        val contact = _contacts.value.firstOrNull { it.hash == thread } ?: return@actor
        val msg = _messages.value[thread]?.firstOrNull { it.id == messageId } ?: return@actor
        if (msg.status != Status.FAILED || msg.kind != Kind.TEXT) return@actor
        resendFailedText(msg, msg.text, contact)
    }

    /** iOS `resendText(_:text:to:)` — the FAILED path: bundle refetch if needed, `via` preserved. */
    private suspend fun resendFailedText(msg: ChatMessage, text: String, contact: Contact) {
        setStatus(msg.id, Status.PENDING) { it == Status.FAILED }
        try {
            if (!crypto.hasSession(contact.hash)) fetchAndProcessBundle(contact.hash)
            val payload = Wire.Payload(
                kind = "text", text = text, senderName = myUsername, burnAfter = msg.burnAfter,
                via = if (msg.viaWeb) "web" else null,
            )
            val out = prepare(payload, contact.hash, "msg", Protocol.TTL_MSG, msg.id)
            capture(msg.id, out) // §3: the sheet shows the envelope that went out last, as on iOS (it may have changed path)
            dispatch(out)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setStatus(msg.id, Status.FAILED) { it == Status.PENDING }
        }
    }

    /** Days of paid access remaining (0 = none / dev relay). */
    val accessDaysRemaining: Int
        get() {
            val today = todayEpochDay()
            return store.blob.accessTokens.count { it.epochDay >= today }
        }

    private fun todayEpochDay(): Long = clock().epochSecond / 86_400

    private fun currentAccessToken(): AccessToken? {
        val today = todayEpochDay()
        return store.blob.accessTokens.firstOrNull { it.epochDay == today }
    }

    // ---------------------------------------------------------------- contacts

    suspend fun addContact(username: String): Unit = actor {
        val hash = usernameHash(username)
        if (hash == myHash) throw ServiceError("cannot_add_self")
        // if they messaged us first, an auto-created placeholder exists — adopt the real
        // username instead of keeping "anon-…"
        if (_contacts.value.any { it.hash == hash }) {
            setContacts(_contacts.value.map { if (it.hash == hash) it.copy(name = username) else it })
            return@actor
        }
        // establish the session up front so the first send is instant
        if (!crypto.hasSession(hash)) fetchAndProcessBundle(hash)
        setContacts(_contacts.value + Contact(hash = hash, name = username, addedAt = clock()))
    }

    /**
     * Add via a scanned "pyro-add" QR: adds the contact AND — because the QR carries the owner's
     * identity key — verifies the safety number in the same step. Returns true when the key
     * matched (verified badge). A MISMATCH is a loud failure: someone may sit between you.
     */
    suspend fun addContactViaQR(username: String, identityKey: ByteArray): Boolean = actor {
        // the username lands in a trust-bearing label and is hashed as-is — enforce the
        // registration charset (no format/control characters)
        if (!isValidUsername(username)) throw ServiceError("bad_username")
        if (identityKey.size < 32) throw ServiceError("bad_key")
        val hash = usernameHash(username)
        if (hash == myHash) throw ServiceError("cannot_add_self")

        // KNOWN contact with a key on record: a QR is an untrusted input and may only CONFIRM
        // what we hold — it can never demote a verified badge or reset a working session
        if (_contacts.value.any { it.hash == hash }) {
            val known = crypto.knownIdentity(hash)
            if (known != null) {
                if (known.contentEquals(identityKey)) {
                    setVerifiedNow(true, hash)
                    return@actor true
                }
                throw ServiceError("qr_key_mismatch")
            }
        }

        val wasKnown = _contacts.value.any { it.hash == hash }
        addContact(username)
        val known = crypto.knownIdentity(hash) ?: return@actor false
        if (known.contentEquals(identityKey)) {
            setVerifiedNow(true, hash)
            return@actor true
        }
        // MITM-suspect on a FRESH channel: never leave it messageable. Roll back the contact
        // this scan created and archive the suspect session so a later add refetches from scratch.
        if (!wasKnown) setContacts(_contacts.value.filter { it.hash != hash })
        crypto.archiveSession(hash)
        throw ServiceError("identity_mismatch")
    }

    /** My add-me QR payload: username + serialized identity public key (base64url, no padding). */
    fun myAddQRPayload(): String? {
        val key = runCatching { crypto.myIdentityPublic() }.getOrNull() ?: return null
        return "pyro-add:v1:$myUsername:" + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }

    /**
     * Auto-create a contact for a first-contact sender. If the sender declared a name inside the
     * ciphertext, adopt it (sanitized) — a self-chosen label, like a caller ID; the cryptographic
     * identity is the hash + safety number, never the name.
     */
    private fun ensureContact(from: String, suggestedName: String? = null) {
        val clean = sanitizeDisplayName(suggestedName)
        val existing = _contacts.value.firstOrNull { it.hash == from }
        if (existing != null) {
            // upgrade only auto-generated placeholders — never overwrite a name the user typed
            if (clean != null && existing.name.startsWith("anon-")) {
                setContacts(_contacts.value.map { if (it.hash == from) it.copy(name = clean) else it })
            }
            return
        }
        val name = clean ?: ("anon-" + from.take(8))
        setContacts(_contacts.value + Contact(hash = from, name = name, addedAt = clock()))
    }

    // ---------------------------------------------------------------- safety — block & report

    fun isBlocked(hash: String): Boolean = hash in _blockedHashes.value

    /**
     * Block: every future frame from this hash is silently dropped (acked so the relay wipes its
     * copy). History is kept; the thread is hidden from the session list until unblocked.
     */
    suspend fun blockContact(hash: String): Unit = actor { blockContactNow(hash) }

    suspend fun unblockContact(hash: String): Unit = actor {
        _blockedHashes.value = _blockedHashes.value - hash
        store.update { it.copy(blockedHashes = _blockedHashes.value.toList()) }
    }

    private fun blockContactNow(hash: String) {
        _blockedHashes.value = _blockedHashes.value + hash
        store.update { it.copy(blockedHashes = _blockedHashes.value.toList()) }
    }

    // ---------------------------------------------------------------- safety numbers / verification

    /** null until the peer's identity key is on record (first bundle fetch or first received message). */
    fun safetyNumber(contact: Contact): SafetyNumber? = safetyNumber(contact.hash)

    fun safetyNumber(hash: String): SafetyNumber? {
        val peer = crypto.knownIdentity(hash) ?: return null
        return runCatching { SafetyNumbers.compute(myHash, signalState.identityKeyPair, hash, peer) }.getOrNull()
    }

    suspend fun setVerified(verified: Boolean, hash: String): Unit = actor { setVerifiedNow(verified, hash) }

    private fun setVerifiedNow(verified: Boolean, hash: String) {
        val current = _contacts.value.firstOrNull { it.hash == hash } ?: return
        if (current.verified == verified) return
        setContacts(_contacts.value.map { if (it.hash == hash) it.copy(verified = verified) else it })
    }

    // ---------------------------------------------------------------- identity-change warning

    private fun handleIdentityChange(peer: String) {
        // verified clears ALWAYS (idempotent)…
        if (_contacts.value.firstOrNull { it.hash == peer }?.verified == true) {
            setContacts(_contacts.value.map { if (it.hash == peer) it.copy(verified = false) else it })
        }
        // …the loud row is deduped: one per peer per 60 s (a reinstall storm — failed decrypts +
        // heal + refetch — collapses to one warning)
        val now = clock()
        identityRowAt[peer]?.let { if (now.epochSecond - it.epochSecond < HEAL_THROTTLE_SECONDS) return }
        identityRowAt[peer] = now
        ensureContact(peer)
        val name = _contacts.value.firstOrNull { it.hash == peer }?.name ?: "peer"
        appendSystem("⚠ <$name>'s identity key changed — re-verify before trusting", peer)
    }

    private fun appendSystem(text: String, thread: String) = appendMessage(systemRow(text), thread)

    /** Local-only system rows use an UPPERCASE uuid (iOS `UUID().uuidString`); they are never sent. */
    private fun systemRow(text: String) = ChatMessage(
        id = UUID.randomUUID().toString().uppercase(), direction = Direction.INCOMING, kind = Kind.SYSTEM,
        text = text, timestamp = clock(), status = Status.DELIVERED,
    )

    /**
     * Report abuse + block + burn the local thread. E2E-honest: the ONLY thing sent to the
     * operator is the reported routing hash.
     */
    suspend fun reportAndBlock(contact: Contact): Unit = actor {
        runCatching { sendFrame(Wire.Out.Report(contact.hash)) }
        purgeAttachments(_messages.value[contact.hash] ?: emptyList())
        setMessages(_messages.value + (contact.hash to emptyList()))
        blockContactNow(contact.hash)
    }

    suspend fun renameContact(hash: String, name: String): Unit = actor {
        val clean = name.trim { it == ' ' || it == '\t' } // Swift `.whitespaces`
        if (clean.isEmpty() || _contacts.value.none { it.hash == hash }) return@actor
        setContacts(_contacts.value.map { if (it.hash == hash) it.copy(name = clean) else it })
    }

    /**
     * Remove a contact and everything local about the conversation (messages, attachments,
     * draft). The Signal session is kept so a future re-add resumes cleanly.
     */
    suspend fun deleteContact(hash: String): Unit = actor {
        purgeAttachments(_messages.value[hash] ?: emptyList())
        _messages.value = _messages.value - hash
        _contacts.value = _contacts.value.filter { it.hash != hash }
        store.update {
            it.copy(messages = _messages.value, contacts = _contacts.value, drafts = it.drafts?.let { d -> d - hash })
        }
    }

    /**
     * Delete one of MY messages on BOTH devices: local removal plus an E2E control telling the
     * peer's app to drop it too. Only outgoing messages qualify. Returns false (and touches
     * nothing) when the retraction cannot be delivered right now.
     */
    suspend fun deleteForBoth(id: String, thread: String): Boolean = actor {
        val msg = _messages.value[thread]?.firstOrNull { it.id == id } ?: return@actor false
        if (msg.direction != Direction.OUTGOING) return@actor false
        group(thread)?.let { g -> return@actor deleteForAllNow(msg, g) }
        if (connState.value != ConnState.ONLINE || link.socket == null) {
            _lastError.value = "offline — delete for both needs the link up"
            return@actor false
        }
        try {
            if (!crypto.hasSession(thread)) fetchAndProcessBundle(thread)
            val payload = Wire.Payload(kind = "control", control = "delete_msg", refIds = listOf(id))
            val controlId = newMessageId()
            val out = prepare(payload, thread, "control", Protocol.TTL_MSG, controlId)
            // a socket write is not delivery: wait for the relay's `sent` (queued for the peer)
            // or `error` (rate_limited / queue_full / unknown_user) before touching the local row
            val verdict = CompletableDeferred<Boolean>()
            pendingControlAcks[controlId] = verdict
            try {
                dispatch(out)
                scope.launch {
                    delay(CONTROL_ACK_TIMEOUT_MS)
                    pendingControlAcks.remove(controlId)?.complete(false) // no verdict = not delivered
                }
            } catch (e: IOException) {
                pendingControlAcks.remove(controlId)?.complete(false)
            }
            if (!verdict.await()) {
                _lastError.value = "delete for both refused by relay — try again"
                return@actor false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _lastError.value = "delete for both failed — try again"
            return@actor false
        }
        deleteMessageNow(id, thread)
        true
    }

    /** Delete ONE message locally ("delete for me"). */
    suspend fun deleteMessage(id: String, thread: String): Unit = actor { deleteMessageNow(id, thread) }

    private fun deleteMessageNow(id: String, thread: String) {
        val log = _messages.value[thread] ?: return
        log.firstOrNull { it.id == id }?.let { purgeAttachments(listOf(it)) }
        setMessages(_messages.value + (thread to log.filter { it.id != id }))
    }

    // ---------------------------------------------------------------- drafts

    fun draft(thread: String): String = store.blob.drafts?.get(thread) ?: ""

    /**
     * docs/DECOY-ACCOUNT.md §1 — a draft is [Durability.LAZY] and a keystroke therefore never
     * reaches the disk. The text is current in memory from the first character, so whatever
     * flushes next — the composer losing focus, leaving the screen, backgrounding, locking, the
     * idle timer — writes exactly what was typed, once, instead of 2 MiB per keystroke.
     */
    suspend fun setDraft(text: String, thread: String): Unit = actor {
        store.update(Durability.LAZY) { blob ->
            val d = (blob.drafts ?: emptyMap()).toMutableMap()
            if (text.isBlank()) d.remove(thread) else d[thread] = text
            blob.copy(drafts = if (d.isEmpty()) null else d)
        }
    }

    // ---------------------------------------------------------------- typing indicators (E2E control)

    /** Called on every composer keystroke; throttled to one signal per 4 s. */
    suspend fun noteComposerActivity(thread: String): Unit = actor {
        if (!settings.typingOn || connState.value != ConnState.ONLINE) return@actor
        val now = clock()
        group(thread)?.let { g ->
            // §1: per member — one throttled `typing` + group to every member we share a session with
            if (g.isLeft) return@actor
            lastTypingSent[thread]?.let { if (now.toEpochMilli() - it.toEpochMilli() < 4_000) return@actor }
            lastTypingSent[thread] = now
            val payload = Wire.Payload(kind = "control", control = "typing", group = GroupRef(g.id, g.epoch))
            for (peer in recipientsOf(g)) {
                if (!crypto.hasSession(peer)) continue
                val out = runCatching { prepare(payload, peer, "control", Protocol.TTL_TYPING, newMessageId()) }.getOrNull() ?: continue
                runCatching { dispatch(out) }
            }
            return@actor
        }
        if (!crypto.hasSession(thread) || thread in _blockedHashes.value) return@actor
        lastTypingSent[thread]?.let { if (now.toEpochMilli() - it.toEpochMilli() < 4_000) return@actor }
        lastTypingSent[thread] = now
        val out = runCatching {
            prepare(Wire.Payload(kind = "control", control = "typing"), thread, "control", Protocol.TTL_TYPING, newMessageId())
        }.getOrNull() ?: return@actor
        runCatching { dispatch(out) }
    }

    /** Incoming rows not yet read, for the home list [N] chip. */
    fun unreadCount(hash: String): Int = (_messages.value[hash] ?: emptyList()).count {
        it.direction == Direction.INCOMING && it.kind != Kind.SYSTEM && it.status != Status.READ
    }

    /** Badge source: unread across every conversation (blocked threads don't count). */
    val totalUnread: Int
        get() = _messages.value.keys.filter { it !in _blockedHashes.value }.sumOf { unreadCount(it) }

    private suspend fun fetchAndProcessBundle(hash: String) {
        val (fetch, bundle) = fetchBundle(hash)
        if (bundle !is Wire.In.PreKeyBundle) throw ServiceError((bundle as? Wire.In.Error)?.code ?: Wire.ErrorCode.UNKNOWN_USER)
        if (fetch.processed) return // a concurrent caller already built the session from this reply
        fetch.processed = true
        crypto.processBundle(bundle, hash)
    }

    /**
     * One prekey bundle, or the relay's refusal. Whenever this login has sealed sender the
     * question goes out on the anonymous connection, so the relay is not told who is about to
     * write to whom (docs/SEALED-SENDER.md §5.2); the answer then also says whether that account
     * can receive sealed messages.
     */
    private suspend fun fetchBundle(hash: String): Pair<BundleFetch, Wire.In> {
        if (link.socket == null) throw IOException("not connected to relay")
        val anon = if (sealedOwn != null && !anonFailed) anonOrNull() else null
        if (link.socket == null) throw IOException("not connected to relay")
        val fetch = pendingBundleFetches[hash] ?: BundleFetch().also { fresh ->
            pendingBundleFetches[hash] = fresh
            if (anon != null) {
                // the answer is owed on this connection: it stays open for it, even while retiring
                anon.fetches += hash
                if (!anonSend(anon, Wire.Out.PreKeyFetch(hash))) anonEnd(anon) // fails this fetch with everything else on it
            } else {
                runCatching { sendFrame(Wire.Out.PreKeyFetch(hash)) }
            }
            // hard deadline: a lost reply must never hang an add forever
            scope.launch {
                delay(BUNDLE_FETCH_TIMEOUT_MS)
                if (pendingBundleFetches[hash] === fresh) {
                    pendingBundleFetches.remove(hash)
                    fresh.reply.completeExceptionally(ServiceError("timeout"))
                    anon?.let {
                        it.fetches.remove(hash)
                        anonSettle(it)
                    }
                }
            }
        }
        return fetch to fetch.reply.await()
    }

    // ---------------------------------------------------------------- text messaging

    /** Self-destruct default for a thread: per-contact override, else global. */
    fun burnDefault(hash: String): Double? {
        _contacts.value.firstOrNull { it.hash == hash }?.burnSeconds?.let { s ->
            return if (s == 0.0) null else s // 0 = explicit "off" override
        }
        return settings.defaultBurnSeconds
    }

    suspend fun setBurnDefault(seconds: Double?, hash: String): Unit = actor {
        if (_contacts.value.none { it.hash == hash }) return@actor
        setContacts(_contacts.value.map { if (it.hash == hash) it.copy(burnSeconds = seconds) else it })
    }

    suspend fun sendText(text: String, to: Contact, viaWeb: Boolean = false): Unit = sendText(text, to.hash, viaWeb)

    /**
     * `sendText(text, group)` (§4): the thread key may be a group id — the row fans out over every
     * member's session. A linked browser may compose it too (MIRROR-GROUPS.md §3): the row and every
     * fanned-out payload carry `via:"web"` exactly like a 1:1 row.
     */
    suspend fun sendText(text: String, toHash: String, viaWeb: Boolean = false): Unit = actor {
        if (isGroup(toHash)) {
            sendGroupTextNow(text, toHash, viaWeb)
            return@actor
        }
        val id = newMessageId()
        val burn = burnDefault(toHash)
        // SYNCED BURN: burnStartedAt stays null until the peer's burn_start control arrives —
        // both devices then count down together.
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = Kind.TEXT, text = text,
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            viaWeb = viaWeb,
        )
        appendMessage(msg, toHash)
        play(Tone.TX)
        try {
            if (!crypto.hasSession(toHash)) fetchAndProcessBundle(toHash)
            val payload = Wire.Payload(
                kind = "text", text = text, senderName = myUsername, burnAfter = burn,
                via = if (viaWeb) "web" else null,
            )
            val out = prepare(payload, toHash, "msg", Protocol.TTL_MSG, id)
            capture(id, out) // §3: capture as it is sent, never re-encrypt later
            dispatch(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            replaceMessage(msg.copy(status = Status.FAILED), toHash)
            _lastError.value = (e as? ServiceError)?.code ?: (e.message ?: e.toString())
        }
    }

    // ---------------------------------------------------------------- receive pipeline

    private suspend fun receiveMessage(f: Wire.In.Recv) {
        val id = f.id
        // WHO IT IS FROM. Today's frame says so, and the relay vouches for it. A sealed frame has
        // no `from`: the sender is inside, behind checks C0–C6 (docs/SEALED-SENDER.md §3). Until
        // those pass there is nobody to answer and nothing to heal, so a failure is acked and
        // dropped in silence. A frame without `from` must carry type 6, and a frame with `from`
        // must not.
        val type = crypto.envelopeType(f.body)
        val named = f.from
        val from: String
        var opened: CryptoEngine.Opened? = null
        if (named != null) {
            if (type == Sealed.ENVELOPE_TYPE) {
                ack(id)
                return
            }
            from = named
        } else {
            val ts = f.ts
            val o = if (type == Sealed.ENVELOPE_TYPE && ts != null && ts > 0) {
                runCatching { crypto.openSealedBody(f.body, ts, sealedRoots, SealedTrust.MIN_SERVER_KEY_ID) }.getOrNull()
            } else null
            if (o == null) {
                ack(id)
                return
            }
            from = o.sender
            opened = o
        }
        // blocked sender: ack (relay wipes) and drop without decrypting — skipped ciphertexts
        // are safe, the ratchet fast-forwards on unblock. For a sealed frame this is before C7
        // and before the ratchet runs.
        if (from in _blockedHashes.value) {
            ack(id)
            return
        }
        // dedupe (relay redelivers until acked)
        if (_messages.value[from]?.any { it.id == id } == true) {
            ack(id)
            return
        }
        try {
            val payload = if (opened != null) crypto.decryptSealed(opened) else crypto.decryptPayload(f.body, from)
            learnKeys(from, payload, opened != null)
            dispatchPayload(id, from, payload, f.ts, f, sealed = opened != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Sealed.Rejected) {
            ack(id)
            if (e.heal) {
                // a sealed Whisper whose sender's key is not the one on record (C7): the sender is
                // certified, so it heals as a failed decrypt always has
                _lastError.value = "decrypt_failed"
                noteDecryptFailure(from)
            }
            // otherwise C7 with a PreKey message inside: someone else's message in this sender's
            // envelope. Nothing was decrypted and there is nothing to heal.
        } catch (_: DuplicateMessageException) {
            // a redelivered control (lost ack) re-decrypts as a DUPLICATE — that is a healthy
            // ratchet doing its job, never heal on it. A redelivered blob MANIFEST whose chunks
            // are still arriving (socket drop mid-transfer: the relay replays manifest + chunks
            // on reconnect) must NOT be acked — the relay drops the chunk route on ack, chunks
            // and all. assembleBlob acks it once the last chunk lands.
            if (!blobIncoming.containsKey(id)) ack(id)
        } catch (_: Exception) {
            // undecryptable — never store ciphertext we can't read; ack so the relay wipes it
            ack(id)
            _lastError.value = "decrypt_failed"
            noteDecryptFailure(from)
        }
    }

    /** Everything after a successful decrypt — the relay id [id], the session identity [from], the plaintext. */
    private fun dispatchPayload(id: String, from: String, payload: Wire.Payload, relayTs: Long?, rx: Wire.In.Recv? = null, sealed: Boolean = false) {
        val gref = payload.group
        // THE ROW THIS BECOMES. On the legacy path a 1:1 row's id is the relay id. On a sealed lane
        // the relay id is new for every attempt and the row's id rides inside as `refId`
        // (docs/SEALED-SENDER.md §5.3) — so a retry of something already here is recognised only
        // now, after decrypting.
        val rowId = if (sealed) payload.refId?.takeIf { it.isNotBlank() } ?: id else id
        if (sealed && gref == null && (payload.kind == "text" || payload.kind == "blob_manifest") &&
            _messages.value[from]?.any { it.id == rowId } == true
        ) {
            ack(id)
            return
        }
        // blob manifests are acked by assembleBlob() once every chunk has arrived — acking
        // now would tear down the relay's chunk route
        if (payload.kind != "blob_manifest") ack(id)

        when (payload.kind) {
            "text" -> {
                if (gref != null) {
                    receiveGroupText(id, from, payload, gref, rx)
                    return
                }
                _typingPeers.value = _typingPeers.value - from
                ensureContact(from, payload.senderName)
                val msg = ChatMessage(
                    id = rowId, direction = Direction.INCOMING, kind = Kind.TEXT,
                    text = payload.text ?: "", timestamp = clock(), status = Status.DELIVERED,
                    burnAfter = payload.burnAfter, burnStartedAt = null, // synced burn: starts on read
                    viaWeb = payload.via == "web",
                )
                appendMessage(msg, from)
                rx?.let { frames.put(rowId, CapturedFrame.of(it)) } // §3: the envelope as it arrived
                enqueueReceipt(ReceiptKind.DELIVERED, listOf(rowId), from)
                play(Tone.RX)
            }
            "blob_manifest" -> {
                val chunks = payload.chunks
                val bytes = payload.bytes
                val sub = payload.subtype
                if (chunks == null || chunks !in 1..MAX_BLOB_CHUNKS || bytes == null || bytes > MAX_BLOB_CIPHERTEXT_BYTES ||
                    sub == null || BlobSubtype.fromWire(sub) == null
                ) {
                    ack(id) // junk: wipe
                    return
                }
                var groupId: String? = null
                var logicalId: String? = null
                if (gref != null) {
                    // a blob for a group we never joined, or one we already hold under its logical id: wipe
                    val g = group(gref.id)
                    if (g == null) {
                        ack(id)
                        return
                    }
                    logicalId = payload.refId?.takeIf { it.isNotBlank() } ?: id
                    if (_messages.value[g.id]?.any { it.id == logicalId } == true) {
                        ack(id)
                        return
                    }
                    groupId = g.id
                } else if (sealed) {
                    logicalId = rowId // a sealed 1:1 blob lands under the sender's row id too
                }
                // The sender is trying again: a new relay id for the same row (a group copy always,
                // a sealed 1:1 one too). The attempt it replaces will never finish. Let it go here
                // and at the relay, or it would sit there "receiving" for two days.
                if (logicalId != null) {
                    for ((old, entry) in blobIncoming.entries.toList()) {
                        if (old != id && entry.from == from && entry.logicalId == logicalId && entry.groupId == groupId) {
                            blobIncoming.remove(old)
                            dropRxTransfer(old)
                            ack(old)
                        }
                    }
                }
                // a re-delivered manifest intentionally resets a partial entry (the relay replays
                // manifest + all chunks on reconnect)
                blobIncoming[id] = IncomingBlob(
                    from = from, senderName = payload.senderName, total = chunks, chunks = HashMap(), subtype = sub,
                    burnAfter = payload.burnAfter, duration = payload.duration, filename = payload.filename,
                    mime = payload.mime, width = payload.width, height = payload.height, bytes = bytes, startedAt = clock(),
                    groupId = groupId, logicalId = logicalId, sealedTs = if (sealed) relayTs else null,
                )
                _rxTransfers.value = _rxTransfers.value + (id to RxTransfer(thread = groupId ?: from, subtype = sub, received = 0, total = chunks))
                // §3: the manifest envelope stands for the row; assembleBlob re-keys it if the
                // row lands under the sender's logical id
                rx?.let { frames.put(id, CapturedFrame.of(it)) }
            }
            "control" -> if (gref != null) handleGroupControl(payload, from, relayTs, gref) else handleControl(payload, from, relayTs)
            else -> {} // unknown kind from a newer client: ignore
        }
    }

    /** Tests only: hand a decrypted payload to the receive pipeline as if session [from] had delivered it. */
    internal suspend fun receivePayloadForTest(from: String, payload: Wire.Payload, id: String = newMessageId(), relayTs: Long? = null): Unit =
        actor { dispatchPayload(id, from, payload, relayTs) }

    private fun ack(id: String) {
        runCatching { sendFrame(Wire.Out.Ack(id)) }
    }

    private fun handleControl(payload: Wire.Payload, from: String, relayTs: Long?) {
        when (payload.control) {
            "receipt" -> {
                val kind = payload.receipt?.let { ReceiptKind.fromWire(it) } ?: return
                if (kind == ReceiptKind.BURN_START) return
                val refIds = payload.refIds ?: return
                applyReceipt(kind, refIds, from)
                // a member that omitted `group` (older client): the ids still resolve to our group rows
                applyGroupReceipt(kind, refIds, from, null)
            }
            "burn_start" -> {
                val refIds = payload.refIds ?: return
                applyBurnStart(refIds, from)
                applyGroupBurnStart(refIds, from, null)
            }
            "session_reset" -> scope.launch { adoptSessionReset(from) }
            "typing" -> {
                if (_contacts.value.none { it.hash == from }) return
                // a typing signal is only meaningful live; one replayed from the relay queue after
                // a reconnect must not show a phantom "typing…"
                if (relayTs != null && clock().toEpochMilli() - relayTs > TYPING_STALE_MS) return
                _typingPeers.value = _typingPeers.value + (from to clock())
            }
            "burn_thread" -> {
                purgeAttachments(_messages.value[from] ?: emptyList())
                // the trace note only for peers we know — a stranger's control must never
                // conjure a phantom thread
                if (_contacts.value.any { it.hash == from }) {
                    setMessages(_messages.value + (from to listOf(systemRow("🔥 thread burned by peer"))))
                } else {
                    setMessages(_messages.value + (from to emptyList()))
                }
            }
            "screenshot", "screen_record" -> {
                // an alert from a stranger we share no thread with is meaningless
                if (_contacts.value.none { it.hash == from }) return
                val note = systemRow(
                    if (payload.control == "screenshot") "⚠ peer took a screenshot"
                    else "⚠ peer may be recording the screen",
                )
                if (settings.burnOnScreenshot) {
                    purgeAttachments(_messages.value[from] ?: emptyList())
                    setMessages(_messages.value + (from to listOf(note)))
                    // burn the OTHER side too — the screenshotter's copy wipes as well
                    runCatching { prepare(Wire.Payload(kind = "control", control = "burn_thread"), from, "control", Protocol.TTL_CONTROL, newMessageId()) }
                        .getOrNull()?.let { out ->
                            runCatching { dispatch(out) }
                        }
                } else {
                    appendMessage(note, from)
                }
            }
            "delete_msg" -> {
                val refIds = payload.refIds ?: return
                if (refIds.size > 50) return
                val log = _messages.value[from] ?: return
                // AUTH BOUNDARY: the sender may retract only messages THEY authored — incoming
                // rows in this thread. My own messages are untouchable by any remote control.
                val victims = log.filter { it.id in refIds && it.direction == Direction.INCOMING }
                if (victims.isEmpty()) return
                purgeAttachments(victims)
                val ids = victims.map { it.id }.toSet()
                setMessages(_messages.value + (from to log.filter { it.id !in ids }))
            }
            "web_link" -> {
                if (_contacts.value.none { it.hash == from }) return
                val browser = payload.browser?.let { sanitizeBrowserName(it) }
                val text = if (payload.linkUp == true) {
                    "⚠ peer linked a desktop browser" + (browser?.let { " ($it)" } ?: "") +
                        " — this conversation may be viewed there"
                } else {
                    "peer unlinked the desktop browser"
                }
                appendMessage(systemRow(text), from)
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- session auto-heal

    private fun noteDecryptFailure(peer: String) {
        decryptFailures[peer] = (decryptFailures[peer] ?: 0) + 1
        // archive IMMEDIATELY (independent of the online guard below): with the session archived,
        // hasSession is false, so even if the heal task bails the next outbound send self-heals
        crypto.archiveSession(peer)
        // DEADLOCK GUARD: healSession awaits a prekey_bundle frame resolved by the same relay
        // event loop that called us — heal in its own task, never inline.
        scope.launch { healSession(peer) }
    }

    /**
     * Bootstrap a fresh outbound session from a new PQXDH bundle and tell the peer to do the
     * same. Throttled to one attempt per peer per 60 s.
     */
    private suspend fun healSession(peer: String) {
        if (connState.value != ConnState.ONLINE) return // archived already; next send refetches
        val now = clock()
        lastHealAt[peer]?.let { if (now.epochSecond - it.epochSecond < HEAL_THROTTLE_SECONDS) return }
        lastHealAt[peer] = now // set BEFORE first await
        val lost = maxOf(decryptFailures[peer] ?: 0, 1)
        decryptFailures[peer] = 0
        try {
            fetchAndProcessBundle(peer) // may fire onIdentityReplaced
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _lastError.value = "heal_failed"
            return // session stays archived => every send path refetches
        }
        // cosmetics + the reset control only for peers we actually know
        if (_contacts.value.none { it.hash == peer }) return
        runCatching {
            prepare(Wire.Payload(kind = "control", control = "session_reset"), peer, "control", Protocol.TTL_CONTROL, newMessageId())
        }.getOrNull()?.let { out ->
            runCatching { dispatch(out) }
        }
        val name = _contacts.value.firstOrNull { it.hash == peer }?.name ?: "peer"
        appendSystem(
            "⚠ channel re-keyed — $lost incoming message${if (lost == 1) " was" else "s were"} lost and cannot be recovered. ask <$name> to resend.",
            peer,
        )
    }

    /**
     * Peer told us they rebuilt the channel. Our receiving side is already fixed; we also archive +
     * refetch so OUR outbound direction rides a brand-new handshake. LOOP GUARD: never sends
     * session_reset back, and shares the 60 s stamp with healSession.
     */
    private suspend fun adoptSessionReset(peer: String) {
        val now = clock()
        lastHealAt[peer]?.let { if (now.epochSecond - it.epochSecond < HEAL_THROTTLE_SECONDS) return }
        lastHealAt[peer] = now
        crypto.archiveSession(peer)
        runCatching { fetchAndProcessBundle(peer) } // best-effort
        if (_contacts.value.none { it.hash == peer }) return
        val name = _contacts.value.firstOrNull { it.hash == peer }?.name ?: "peer"
        appendSystem("⚠ <$name> re-keyed the channel — messages you sent recently may not have arrived. resend anything unanswered.", peer)
    }

    /**
     * AUTO-LOCK teardown: after this returns the instance owns no socket and no live task.
     * Idempotent. The relay sees only a normal socket close — it learns nothing.
     */
    suspend fun shutdown(): Unit = actor {
        // TODO(mirror): stopMirror() — auto-lock / wipe must never leave a live browser mirror
        link.close() // stop consuming relay events FIRST, then drop the socket; wantOnline = false
        // explicit disconnect never yields Disconnected — resolve waiters + requeue in-flight
        // receipts into the PERSISTED outbox ourselves
        failAllPending()
        receiptFlushJob?.cancel()
        receiptFlushJob = null
        burnJob?.cancel()
        burnJob = null
        // a still-pending outgoing row never got its relay ack — surface as failed (receiptRank
        // ranks failed with sent, so a receipt arriving next session still upgrades it)
        var changed = false
        val next = _messages.value.mapValues { (_, log) ->
            log.map {
                if (it.direction == Direction.OUTGOING && it.status == Status.PENDING) {
                    changed = true
                    it.copy(status = Status.FAILED)
                } else it
            }
        }
        if (changed) setMessages(next)
        _blobProgress.value = emptyMap()
        _rxTransfers.value = emptyMap()
        blobIncoming.clear()
        frames.clear() // §3: dropped on lock, auto-lock, wipe and app exit
        _typingPeers.value = emptyMap()
        _groupTyping.value = emptyMap()
        lastTypingSent.clear()
        controlFlushJob?.cancel()
        controlFlushJob = null
        inflightReceipts.clear() // all requeued by failAllPending
        attachmentCache.clear() // decrypted bytes out of RAM
        store.flush()
    }

    // ---------------------------------------------------------------- receipts + burn sync

    /**
     * Apply an incoming receipt. Containment: only the sender's own thread, only OUTGOING
     * non-system rows, upgrades only (rank-monotone), unknown ids ignored. Idempotent.
     */
    private fun applyReceipt(kind: ReceiptKind, refIds: List<String>, peer: String) {
        val log = _messages.value[peer] ?: return
        val target = if (kind == ReceiptKind.DELIVERED) Status.DELIVERED else Status.READ
        var changed = false
        val idSet = refIds.toSet()
        val next = log.map { m ->
            if (m.id in idSet && m.direction == Direction.OUTGOING && m.kind != Kind.SYSTEM &&
                target.receiptRank > m.status.receiptRank
            ) {
                changed = true
                clearBlobProgress(m.id)
                m.copy(status = target)
            } else m
        }
        if (!changed) return
        setMessages(_messages.value + (peer to next), Durability.LAZY)   // §1: read state
    }

    /**
     * SYNCED BURN, sender side: the reader's device announced (always-on, independent of the
     * read-receipt toggle) that these messages became visible — start our copies' countdowns now.
     */
    private fun applyBurnStart(refIds: List<String>, peer: String) {
        val log = _messages.value[peer] ?: return
        var changed = false
        val idSet = refIds.toSet()
        val now = clock()
        val next = log.map { m ->
            if (m.id in idSet && m.direction == Direction.OUTGOING && m.burnAfter != null && m.burnStartedAt == null) {
                changed = true
                m.copy(burnStartedAt = now)
            } else m
        }
        if (!changed) return
        setMessages(_messages.value + (peer to next))
    }

    /** True while a row should carry a visibility reporter. */
    fun needsVisibilityReport(msg: ChatMessage): Boolean {
        if (msg.direction != Direction.INCOMING || msg.kind == Kind.SYSTEM || msg.id in visibleReported) return false
        val unread = msg.status != Status.READ
        val burnPending = msg.burnAfter != null && msg.burnStartedAt == null
        return unread || burnPending
    }

    /**
     * THE read trigger: called by the chat screen after the row has been continuously on-screen
     * >= 0.5 s with the chat open and the app active. Marks the row read locally, starts its burn
     * countdown, and enqueues the E2E controls (read receipt only if the reciprocity toggle is on;
     * burn_start always).
     */
    suspend fun noteRowVisible(messageId: String, thread: String): Unit = actor {
        if (messageId in visibleReported) return@actor
        val log = _messages.value[thread] ?: return@actor
        val i = log.indexOfFirst { it.id == messageId && it.direction == Direction.INCOMING }
        if (i < 0) return@actor
        visibleReported += messageId
        var row = log[i]
        var changed = false
        val group = group(thread)
        // a group row's receipts go to its SENDER (the thread key is the group), with `group` attached
        val receiptPeer = if (group != null) row.sender else thread
        fun receipt(kind: ReceiptKind) {
            if (receiptPeer == null) return
            if (group != null) enqueueGroupReceipt(kind, listOf(messageId), receiptPeer, group.id)
            else enqueueReceipt(kind, listOf(messageId), receiptPeer)
        }
        if (row.status != Status.READ) {
            row = row.copy(status = Status.READ)
            changed = true
            if (settings.readReceiptsOn && row.kind != Kind.SYSTEM) receipt(ReceiptKind.READ)
        }
        if (row.burnAfter != null && row.burnStartedAt == null && row.kind != Kind.SYSTEM) {
            row = row.copy(burnStartedAt = clock())
            changed = true
            receipt(ReceiptKind.BURN_START)
        }
        if (!changed) return@actor
        // §1: a row becoming READ, and its countdown starting, is read state — lazy
        setMessages(_messages.value + (thread to log.toMutableList().also { it[i] = row }), Durability.LAZY)
    }

    /**
     * Chat screen enter / leave. Leaving is one of docs/DECOY-ACCOUNT.md §1's lazy write moments,
     * so everything deferred while the screen was open — the draft, the rows read on it — reaches
     * the disk here, in ONE save.
     */
    suspend fun setActiveThread(thread: String?): Unit = actor {
        val leaving = activeThread != null && thread == null
        activeThread = thread
        if (leaving) persistDeferred()
    }

    /**
     * §1: write whatever [Durability.LAZY] has been holding. Called when the composer loses focus,
     * when the chat screen is left, when the app backgrounds and when the session ends. A store
     * with nothing pending writes nothing at all.
     */
    fun persistDeferred() {
        runCatching { store.flush() }
    }

    private fun enqueueReceipt(kind: ReceiptKind, ids: List<String>, peer: String) {
        store.update(Durability.LAZY) { blob ->   // §1: the outbox is read state
            val box = blob.receiptOutbox ?: ReceiptOutbox()
            fun add(lane: Map<String, List<String>>): Map<String, List<String>> {
                val existing = (lane[peer] ?: emptyList())
                return lane + (peer to existing + ids.filter { it !in existing })
            }
            val next = when (kind) {
                ReceiptKind.DELIVERED -> box.copy(delivered = add(box.delivered))
                ReceiptKind.READ -> {
                    // read implies delivered on the sender side
                    var delivered = box.delivered
                    delivered[peer]?.let { pending ->
                        val rest = pending.filter { it !in ids }
                        delivered = if (rest.isEmpty()) delivered - peer else delivered + (peer to rest)
                    }
                    box.copy(delivered = delivered, read = add(box.read))
                }
                ReceiptKind.BURN_START -> box.copy(burnStart = add(box.burnStart))
            }
            blob.copy(receiptOutbox = next)
        }
        scheduleReceiptFlush()
    }

    /**
     * Debounce 300 ms so a burst of stored/read messages becomes one control frame per peer per
     * lane — 40 unread on chat open never trips the relay's 30-sends/10 s limiter.
     */
    private fun scheduleReceiptFlush(afterSeconds: Double = 0.3) {
        receiptFlushJob?.cancel()
        receiptFlushJob = scope.launch {
            delay((afterSeconds * 1000).toLong())
            if (!isActive) return@launch
            flushReceipts()
        }
    }

    private fun flushReceipts() {
        if (connState.value != ConnState.ONLINE) return
        flushGroupReceipts()
        val snapshot = store.blob.receiptOutbox ?: return
        if (snapshot.isEmpty) return
        for (kind in ReceiptKind.entries) {
            val map = when (kind) {
                ReceiptKind.DELIVERED -> snapshot.delivered
                ReceiptKind.READ -> snapshot.read
                ReceiptKind.BURN_START -> snapshot.burnStart
            }
            for ((peer, ids) in map) {
                if (ids.isEmpty()) continue
                if (!crypto.hasSession(peer)) continue // leave in outbox
                val payload = if (kind == ReceiptKind.BURN_START) {
                    Wire.Payload(kind = "control", control = "burn_start", refIds = ids)
                } else {
                    Wire.Payload(kind = "control", control = "receipt", receipt = kind.wire, refIds = ids)
                }
                val sendId = newMessageId()
                val out = runCatching { prepare(payload, peer, "control", Protocol.TTL_CONTROL, sendId) }.getOrNull() ?: continue
                inflightReceipts[sendId] = InflightReceipt(peer, kind, ids)
                // remove ONLY the dispatched ids, BEFORE the send — a receipt enqueued meanwhile
                // must survive (a whole-lane overwrite would silently drop it)
                store.update(Durability.LAZY) { blob ->
                    val live = blob.receiptOutbox ?: return@update blob
                    fun strip(lane: Map<String, List<String>>): Map<String, List<String>> {
                        val rest = lane[peer]?.filter { it !in ids } ?: return lane
                        return if (rest.isEmpty()) lane - peer else lane + (peer to rest)
                    }
                    val next = when (kind) {
                        ReceiptKind.DELIVERED -> live.copy(delivered = strip(live.delivered))
                        ReceiptKind.READ -> live.copy(read = strip(live.read))
                        ReceiptKind.BURN_START -> live.copy(burnStart = strip(live.burnStart))
                    }
                    blob.copy(receiptOutbox = if (next.isEmpty) null else next)
                }
                runCatching { dispatch(out) }
            }
        }
    }

    /** Return in-flight receipts to the outbox (disconnect / transient error). */
    private fun requeueInflightReceipt(id: String) {
        val inflight = inflightReceipts.remove(id) ?: return
        enqueueReceipt(inflight.kind, inflight.ids, inflight.peer)
    }

    private fun markSent(id: String) = setStatus(id, Status.SENT) { it == Status.PENDING }

    private fun markFailed(id: String) {
        clearBlobProgress(id)
        setStatus(id, Status.FAILED) { it == Status.PENDING || it == Status.SENT }
    }

    private fun setStatus(id: String, status: Status, allowed: (Status) -> Boolean) {
        for ((thread, log) in _messages.value) {
            val i = log.indexOfFirst { it.id == id }
            if (i < 0) continue
            if (!allowed(log[i].status)) return
            setMessages(_messages.value + (thread to log.toMutableList().also { it[i] = it[i].copy(status = status) }))
            return
        }
    }

    // ---------------------------------------------------------------- blob transfers (voice / image / file)

    /** UI hands in an ALREADY normalized JPEG (≤ 2048 px, EXIF stripped — the app's ImageNormalizer). */
    suspend fun sendImage(jpeg: ByteArray, width: Int, height: Int, to: Contact): Unit = sendImage(jpeg, width, height, to.hash)

    suspend fun sendImage(jpeg: ByteArray, width: Int, height: Int, toHash: String): Unit = actor {
        if (isGroup(toHash)) {
            sendGroupMediaNow(toHash, Kind.IMAGE, jpeg, "[IMG]", mime = "image/jpeg", width = width, height = height)
            return@actor
        }
        val id = newMessageId()
        val attId = newMessageId()
        if (!writeAttachmentOrNote(attId, jpeg)) return@actor
        val burn = burnDefault(toHash)
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = Kind.IMAGE, text = "[IMG]",
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            attachmentId = attId, fileMime = "image/jpeg", fileBytes = jpeg.size, imageWidth = width, imageHeight = height,
        )
        appendMessage(msg, toHash)
        play(Tone.TX)
        sendBlob(id, jpeg, BlobSubtype.IMAGE, mime = "image/jpeg", width = width, height = height, toHash = toHash)
    }

    suspend fun sendFile(data: ByteArray, filename: String, mime: String, to: Contact): Unit = sendFile(data, filename, mime, to.hash)

    /** Refusals are `lastError` codes exactly like iOS: `empty_file`, `file_too_large` (> 12 MiB plaintext), `store_io`. */
    suspend fun sendFile(data: ByteArray, filename: String, mime: String, toHash: String): Unit = actor {
        if (data.isEmpty()) {
            _lastError.value = "empty_file"
            return@actor
        }
        if (data.size > MAX_FILE_PLAINTEXT_BYTES) {
            _lastError.value = "file_too_large"
            return@actor
        }
        val name = sanitizeFilename(filename)
        if (isGroup(toHash)) {
            sendGroupMediaNow(toHash, Kind.FILE, data, "[FILE] $name", filename = name, mime = mime)
            return@actor
        }
        val id = newMessageId()
        val attId = newMessageId()
        if (!writeAttachmentOrNote(attId, data)) return@actor
        val burn = burnDefault(toHash)
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = Kind.FILE, text = "[FILE] $name",
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            attachmentId = attId, fileName = name, fileMime = mime, fileBytes = data.size,
        )
        appendMessage(msg, toHash)
        play(Tone.TX)
        sendBlob(id, data, BlobSubtype.FILE, filename = name, mime = mime, toHash = toHash)
    }

    suspend fun sendVoice(audio: ByteArray, duration: Double, to: Contact): Unit = sendVoice(audio, duration, to.hash)

    /**
     * `audio` = the recorder's AAC `.m4a` bytes, sent unchanged and kept in a SIDECAR beside the
     * store, like an image or a file (docs/DECOY-ACCOUNT.md §1a). `voiceData` — audio inline in the
     * blob — is never written any more: twenty notes would overflow a 4 MiB container slot, and
     * every save re-encrypted every note ever recorded.
     */
    suspend fun sendVoice(audio: ByteArray, duration: Double, toHash: String): Unit = actor {
        if (isGroup(toHash)) {
            sendGroupMediaNow(toHash, Kind.VOICE, audio, voicePlaceholder(duration), duration = duration)
            return@actor
        }
        val id = newMessageId()
        val attId = newMessageId()
        if (!writeAttachmentOrNote(attId, audio)) return@actor
        val burn = burnDefault(toHash)
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = Kind.VOICE, text = voicePlaceholder(duration),
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            attachmentId = attId, voiceDuration = duration,
        )
        appendMessage(msg, toHash)
        play(Tone.TX)
        sendBlob(id, audio, BlobSubtype.VOICE, duration = duration, toHash = toHash)
    }

    /**
     * Re-run a failed blob send (same message id; full re-encrypt + manifest + chunks — the receiver
     * resets its partial entry on the fresh manifest). The FAILED [RETRY] tap for non-text rows.
     */
    suspend fun retryBlob(messageId: String, thread: String): Unit = actor {
        if (isGroup(thread)) retryGroupBlobNow(messageId, thread) else retryBlobNow(messageId, thread)
    }

    private suspend fun retryBlobNow(messageId: String, thread: String) {
        val contact = _contacts.value.firstOrNull { it.hash == thread } ?: return
        val msg = _messages.value[thread]?.firstOrNull { it.id == messageId } ?: return
        val subtype: BlobSubtype
        val content: ByteArray? = when (msg.kind) {
            Kind.VOICE -> { subtype = BlobSubtype.VOICE; voiceContent(msg) }
            Kind.IMAGE -> { subtype = BlobSubtype.IMAGE; attachmentData(msg) }
            Kind.FILE -> { subtype = BlobSubtype.FILE; attachmentData(msg) }
            Kind.TEXT, Kind.SYSTEM -> return
        }
        if (content == null) return
        setStatus(messageId, Status.PENDING) { it == Status.FAILED }
        sendBlob(
            messageId, content, subtype, duration = msg.voiceDuration, filename = msg.fileName,
            mime = msg.fileMime, width = msg.imageWidth, height = msg.imageHeight, toHash = contact.hash,
        )
    }

    /**
     * iOS `sendBlob` (`ChatService.swift:1400-1444`): session, then the content is encrypted FIRST
     * (raw envelope, one ratchet step) and the manifest SECOND — the receiver decrypts the manifest
     * before the blob, which libsignal serves from its skipped-message keys. Then one binary frame per
     * 64 KiB chunk, `blobProgress` tracking the wire; any failure fails the row (retryable).
     */
    private suspend fun sendBlob(
        id: String,
        content: ByteArray,
        subtype: BlobSubtype,
        duration: Double? = null,
        filename: String? = null,
        mime: String? = null,
        width: Int? = null,
        height: Int? = null,
        toHash: String,
    ) {
        try {
            val burn = _messages.value[toHash]?.firstOrNull { it.id == id }?.burnAfter
            sendBlobFrames(id, id, toHash, content, subtype, burn, duration, filename, mime, width, height, group = null) { setBlobProgress(id, it) }
            clearBlobProgress(id) // done ⇒ status word takes over
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearBlobProgress(id)
            markFailed(id)
            val code = (e as? ServiceError)?.code
            // a sealed manifest's refusal is handed back to this call and never reaches the relay's
            // `error` path, so the row that path leaves for a deleted account is left here
            if (code == Wire.ErrorCode.UNKNOWN_USER) noteAccountGone(toHash)
            _lastError.value = code ?: (e.message ?: e.toString())
            if (e is AnonLinkLost) lostWithConnection() // the row is FAILED: it goes again when a connection is back
        }
    }

    /**
     * One recipient's copy of a blob: session, ciphertext, manifest under [relayId], then the
     * chunk frames. For a group row [relayId] is fresh per recipient (the relay's blob route is
     * keyed by id) and the manifest carries `refId` = [messageId] + `group` so the receiver files
     * the row under the sender's id (§4). Throws on any failure; the caller owns the row status.
     */
    private suspend fun sendBlobFrames(
        relayId: String,
        messageId: String,
        toHash: String,
        content: ByteArray,
        subtype: BlobSubtype,
        burn: Double?,
        duration: Double?,
        filename: String?,
        mime: String?,
        width: Int?,
        height: Int?,
        group: GroupRef?,
        progress: (Double) -> Unit,
    ) {
        if (!crypto.hasSession(toHash)) fetchAndProcessBundle(toHash)
        // On a sealed lane (docs/SEALED-SENDER.md §5.6) the blob is sealed like its manifest, the
        // chunks wait for the relay to take the manifest and follow it on the same anonymous
        // connection under the id the relay saw, and a refusal that changes the path starts over:
        // blob and manifest are encrypted again for it.
        var attempt = 1
        while (true) {
            val lane = sealedLane(toHash)
            // the first message to someone new is text only: refused before anything is encrypted
            if (lane == Lane.FIRST) throw ServiceError(Sealed.firstContactRefusal(peerName(toHash)))
            val blob = prepareBlob(content, toHash, lane)
            if (blob.size > MAX_BLOB_CIPHERTEXT_BYTES) throw ServiceError("file_too_large")
            val chunkCount = (blob.size + BLOB_CHUNK_SIZE - 1) / BLOB_CHUNK_SIZE
            val manifest = Wire.Payload(
                kind = "blob_manifest", senderName = myUsername, burnAfter = burn,
                subtype = subtype.wire, chunks = chunkCount, bytes = blob.size, duration = duration,
                filename = filename, mime = mime, width = width, height = height,
                refId = if (group != null) messageId else null, group = group,
            )
            val out = prepare(manifest, toHash, "blob", Protocol.TTL_BLOB, relayId, lane, attempt)
            // the id the chunks carry, and the connection they go out on: one connection for the
            // manifest, every chunk and the ping after them, whose answer speaks for all of it
            var chunkId = relayId
            var anon: AnonLink? = null
            var main: RelayClient? = null
            if (out.lane == Lane.LEGACY) {
                // §3: the manifest IS the envelope for this row; a group row keeps the first member's copy
                capture(messageId, out, ifAbsent = group != null)
                main = link.socket ?: throw IOException("not connected to relay")
                dispatch(out)
            } else {
                when (val verdict = dispatchForVerdict(out)) {
                    is Verdict.Sent -> {
                        anon = verdict.link
                        chunkId = verdict.id
                        capture(messageId, out, ifAbsent = group != null)
                        relaySent(relayId)
                    }
                    is Verdict.Refused -> {
                        attempt += 1
                        if (attempt > PeerKeys.MAX_ATTEMPTS) throw ServiceError(verdict.code)
                        when (verdict.code) {
                            Wire.ErrorCode.DENIED -> keyDenied(out)
                            Wire.ErrorCode.UNSUPPORTED -> {
                                setPeerKey(toHash, PeerKeys.unsupported(peerKey(toHash)))
                                verdict.link?.let { anonRetire(it) }
                            }
                            NO_PRIVATE_CONNECTION -> {} // the path is the legacy one now, or the link is down
                            else -> throw ServiceError(verdict.code)
                        }
                        continue
                    }
                }
            }
            val header16 = BlobFrames.header16(chunkId) ?: return
            progress(0.0)
            anon?.let { it.uploads += 1 }
            uploading += chunkId
            /** The relay refused a chunk and has dropped the attachment: the upload ends there. */
            fun refusal() {
                uploadRefused[chunkId]?.let { throw ServiceError(it) }
            }
            try {
                for (i in 0 until chunkCount) {
                    val lo = i * BLOB_CHUNK_SIZE
                    val hi = minOf(lo + BLOB_CHUNK_SIZE, blob.size)
                    val frame = BlobFrames.chunk(header16, i, chunkCount, blob, lo, hi)
                    if (anon != null) sendAnonChunk(anon, frame) else link.sendBinaryChunk(frame, main!!)
                    refusal()
                    progress((i + 1).toDouble() / chunkCount)
                }
                // The chunks were handed to the socket's queue, not to the relay. It has them all
                // once it answers a ping written after the last one (§5.6). Until then the upload is
                // in flight: a connection that ends first takes it along, like a message without its
                // verdict, and it is sent again. (The wait also covers the queue: 100 % is on the wire.)
                if (anon != null) {
                    try {
                        anon.client.barrier(uploadAnswerTimeoutMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        throw AnonLinkLost()
                    }
                } else {
                    link.barrier(main!!, uploadAnswerTimeoutMs)
                }
                refusal()
            } catch (e: AnonLinkLost) {
                anon?.let { anonEnd(it) } // so the retry that follows opens another
                throw e
            } finally {
                uploading -= chunkId
                uploadRefused -= chunkId
                anon?.let {
                    it.uploads -= 1
                    anonSettle(it) // it may have been waiting for these chunks to retire
                }
            }
            return
        }
    }

    /** One chunk on the anonymous connection, with the same backpressure as [RelayLink.sendBinaryChunk]. */
    private suspend fun sendAnonChunk(anon: AnonLink, bytes: ByteArray) {
        while (true) {
            if (anon.ended) throw AnonLinkLost()
            if (anon.client.queuedBytes() <= RelayLink.MAX_INFLIGHT_BYTES) break
            delay(RelayLink.QUEUE_POLL_MS)
        }
        if (!anon.client.sendBinary(bytes)) throw AnonLinkLost()
        anonBinaryTap?.invoke(bytes)
    }

    private fun setBlobProgress(id: String, fraction: Double) {
        _blobProgress.value = _blobProgress.value + (id to fraction)
    }

    private fun clearBlobProgress(id: String) {
        if (id in _blobProgress.value) _blobProgress.value = _blobProgress.value - id
    }

    /** iOS `receiveBlobChunk` (`ChatService.swift:1446-1458`): frames for unknown ids are ignored. */
    private suspend fun receiveBlobChunk(frame: ByteArray) {
        if (frame.size <= BlobFrames.HEADER_BYTES) return
        val id = BlobFrames.messageId(frame)
        val seq = BlobFrames.seq(frame)
        val entry = blobIncoming[id] ?: return
        entry.chunks[seq] = BlobFrames.payload(frame)
        _rxTransfers.value[id]?.let { _rxTransfers.value = _rxTransfers.value + (id to it.copy(received = entry.chunks.size)) }
        if (entry.chunks.size == entry.total) assembleBlob(id)
    }

    /** Only called once `chunks.size == total` (`ChatService.swift:1461-1530`). */
    private suspend fun assembleBlob(id: String) {
        val entry = blobIncoming.remove(id) ?: return
        val joined = ByteArrayOutputStream(maxOf(entry.bytes, 0))
        for (i in 0 until entry.total) {
            val c = entry.chunks[i.toLong()] ?: run {
                // gap ⇒ duplicate seq overwrote another: unrecoverable
                dropRxTransfer(id)
                ack(id)
                _lastError.value = "decrypt_failed"
                return
            }
            joined.write(c)
        }
        val blob = joined.toByteArray()
        if (blob.size != entry.bytes) {
            // transport damage (lost/duplicated chunks) — NOT ratchet damage
            dropRxTransfer(id)
            ack(id)
            _lastError.value = "decrypt_failed"
            return
        }
        val plain = try {
            decryptIncoming(blob, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Sealed.Rejected) {
            dropRxTransfer(id)
            ack(id)
            if (e.heal) {
                _lastError.value = "decrypt_failed"
                noteDecryptFailure(entry.from)
            }
            // otherwise: not the manifest's sender, or not sealed the way its manifest was.
            // Nobody to answer, nothing to heal.
            return
        } catch (_: Exception) {
            dropRxTransfer(id)
            ack(id) // permanent: wipe queue
            _lastError.value = "decrypt_failed"
            noteDecryptFailure(entry.from)
            return
        }

        // a group blob lands in the group thread under the sender's row id; a 1:1 one under the
        // relay id, unless it came sealed: then the sender's row id rode inside (SEALED-SENDER.md §5.3)
        val gid = entry.groupId
        val rowId = entry.logicalId ?: id
        if (gid == null && entry.sealedTs != null && _messages.value[entry.from]?.any { it.id == rowId } == true) {
            dropRxTransfer(id) // a retry of one already here
            ack(id)
            return
        }
        if (gid != null) {
            if (group(gid) == null || _messages.value[gid]?.any { it.id == rowId } == true) {
                dropRxTransfer(id)
                ack(id)
                return
            }
        } else {
            ensureContact(entry.from, entry.senderName)
        }
        val base: ChatMessage = when (entry.subtype) {
            BlobSubtype.VOICE.wire -> {
                // §1a: an arriving note is spilled on receipt; nothing goes inline into the blob
                val attId = newMessageId()
                if (!writeIncomingAttachment(id, attId, plain)) return
                ChatMessage(
                    id = rowId, direction = Direction.INCOMING, kind = Kind.VOICE, text = voicePlaceholder(entry.duration ?: 0.0),
                    timestamp = clock(), status = Status.DELIVERED, burnAfter = entry.burnAfter, burnStartedAt = null,
                    attachmentId = attId, voiceDuration = entry.duration ?: 0.0,
                )
            }
            BlobSubtype.IMAGE.wire -> {
                val attId = newMessageId()
                if (!writeIncomingAttachment(id, attId, plain)) return
                ChatMessage(
                    id = rowId, direction = Direction.INCOMING, kind = Kind.IMAGE, text = "[IMG]",
                    timestamp = clock(), status = Status.DELIVERED, burnAfter = entry.burnAfter, burnStartedAt = null,
                    attachmentId = attId, fileMime = entry.mime ?: "image/jpeg", fileBytes = plain.size,
                    imageWidth = entry.width, imageHeight = entry.height,
                )
            }
            else -> { // "file"
                val name = sanitizeFilename(entry.filename ?: "file")
                val attId = newMessageId()
                if (!writeIncomingAttachment(id, attId, plain)) return
                ChatMessage(
                    id = rowId, direction = Direction.INCOMING, kind = Kind.FILE, text = "[FILE] $name",
                    timestamp = clock(), status = Status.DELIVERED, burnAfter = entry.burnAfter, burnStartedAt = null,
                    attachmentId = attId, fileName = name, fileMime = entry.mime, fileBytes = plain.size,
                )
            }
        }
        val msg = if (gid != null) base.copy(group = gid, sender = entry.from) else base
        appendMessage(msg, gid ?: entry.from)
        frames.rekey(id, rowId) // §3: a group blob lands under the sender's row id
        dropRxTransfer(id)
        ack(id) // wipes relay queue — ONLY now, after full reassembly
        if (gid != null) enqueueGroupReceipt(ReceiptKind.DELIVERED, listOf(rowId), entry.from, gid)
        else enqueueReceipt(ReceiptKind.DELIVERED, listOf(rowId), entry.from)
        play(Tone.RX)
    }

    /**
     * A blob takes the path its manifest took: sealed by the same sender when the manifest was,
     * and judged at the manifest's timestamp; named otherwise.
     */
    private fun decryptIncoming(blob: ByteArray, entry: IncomingBlob): ByteArray {
        val sealedBlob = blob.isNotEmpty() && blob[0].toInt() == Sealed.ENVELOPE_TYPE
        val ts = entry.sealedTs
        if (ts == null) {
            if (sealedBlob) throw Sealed.Rejected("C0")
            return BlobCrypto.decrypt(crypto, blob, entry.from)
        }
        if (!sealedBlob || ts <= 0) throw Sealed.Rejected("C0")
        return crypto.decryptSealedBlob(blob, entry.from, ts, sealedRoots, SealedTrust.MIN_SERVER_KEY_ID)
    }

    /** iOS: a sidecar write failure surfaces `store_io` and does NOT ack — the relay redelivers later. */
    private fun writeIncomingAttachment(messageId: String, attId: String, plain: ByteArray): Boolean {
        return try {
            store.writeAttachment(attId, plain)
            true
        } catch (_: StoreError) {
            dropRxTransfer(messageId)
            _lastError.value = "store_io"
            false
        }
    }

    /** Outgoing: `guard (try? store.writeAttachment(…)) != nil else { lastError = "store_io"; return }`. */
    private fun writeAttachmentOrNote(attId: String, data: ByteArray): Boolean {
        return try {
            store.writeAttachment(attId, data)
            true
        } catch (_: StoreError) {
            _lastError.value = "store_io"
            false
        }
    }

    private fun dropRxTransfer(id: String) {
        if (id in _rxTransfers.value) _rxTransfers.value = _rxTransfers.value - id
    }

    // ---------------------------------------------------------------- attachments (sidecars)

    /**
     * Decrypt-on-demand with a 64 MiB LRU cache (iOS `NSCache`); null if the file is gone
     * (burned/wiped) or the row has no sidecar. Voice notes are sidecars too since
     * docs/DECOY-ACCOUNT.md §1a.
     */
    fun attachmentData(message: ChatMessage): ByteArray? {
        val attId = message.attachmentId ?: return null
        attachmentCache.get(attId)?.let { return it }
        val data = store.readAttachment(attId) ?: return null
        attachmentCache.put(attId, data)
        return data
    }

    /**
     * A voice row's audio: its sidecar, or the inline `voiceData` of a row recorded before
     * docs/DECOY-ACCOUNT.md §1a and restored from a backup made back then.
     */
    fun voiceContent(message: ChatMessage): ByteArray? = attachmentData(message) ?: message.voiceData?.bytes

    private fun purgeAttachments(rows: List<ChatMessage>) {
        for (m in rows) {
            frames.remove(m.id) // §3: a row that is gone keeps no envelope either
            m.attachmentId?.let {
                store.deleteAttachment(it)
                attachmentCache.remove(it)
            }
        }
    }

    // ---------------------------------------------------------------- burn actions

    /** Manual thread burn: wipes local log and tells the peer to do the same. */
    suspend fun burnThread(contact: Contact): Unit = actor {
        purgeAttachments(_messages.value[contact.hash] ?: emptyList())
        setMessages(_messages.value + (contact.hash to emptyList()))
        runCatching {
            prepare(Wire.Payload(kind = "control", control = "burn_thread"), contact.hash, "control", Protocol.TTL_CONTROL, newMessageId())
        }.getOrNull()?.let { out ->
            runCatching { dispatch(out) }
        }
    }

    /**
     * MIRROR-GROUPS.md §5 — contextual disclosure of the desktop link: the E2E `web_link` control
     * to ONE conversation the linked browser touched (`up` = linked, with the sanitized browser
     * label; `false` = unlinked). Nothing is broadcast at link time any more; the mirror engine
     * calls this the first time the browser shows or writes a thread, and on stop only for those.
     *
     * A group id fans out to every recipient exactly like a roster control — `group:{id,epoch}`,
     * retried until the relay's `sent`, `unknown_user` marks the member gone (§6b) — so each
     * member files it in the group thread naming this phone. A contact hash goes to that peer over
     * its session (a bundle is fetched for a contact never messaged before: the browser can view
     * and write that thread too). A blocked contact, an unknown thread and a group this phone has
     * left are no-ops.
     */
    suspend fun sendWebLink(thread: String, up: Boolean, browser: String?): Unit = actor {
        // like iOS `webLinkFrames`: the label rides only on the link-up notice, never on the retraction
        val payload = Wire.Payload(kind = "control", control = "web_link", linkUp = up, browser = if (up) browser else null)
        group(thread)?.let { g ->
            if (g.isLeft || !g.isMember(myHash)) return@actor
            val stamped = payload.copy(group = GroupRef(g.id, g.epoch))
            for (peer in recipientsOf(g)) enqueueGroupControl(peer, stamped)
            return@actor
        }
        if (_contacts.value.none { it.hash == thread } || thread in _blockedHashes.value) return@actor
        try {
            if (!crypto.hasSession(thread)) fetchAndProcessBundle(thread)
            dispatch(prepare(payload, thread, "control", Protocol.TTL_CONTROL, newMessageId()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // offline or an unreachable peer: best-effort, like every other 1:1 control
        }
    }

    /** Screenshot or screen-recording detected locally: alert the peer. `control` is "screenshot" or "screen_record". */
    suspend fun reportCapture(control: String, contact: Contact): Unit = actor { reportCaptureNow(control, contact.hash) }

    /** The open thread's capture report: a group applies its screenshot policy (§6), a 1:1 thread alerts the peer. */
    suspend fun reportCapture(control: String, thread: String): Unit = actor {
        val g = group(thread)
        if (g != null) reportGroupCapture(g, control)
        else if (_contacts.value.any { it.hash == thread }) reportCaptureNow(control, thread)
    }

    private fun reportCaptureNow(control: String, peer: String) {
        runCatching {
            prepare(Wire.Payload(kind = "control", control = control), peer, "control", Protocol.TTL_CONTROL, newMessageId())
        }.getOrNull()?.let { out ->
            runCatching { dispatch(out) }
        }
    }

    private fun topUpPreKeys() {
        val fresh = runCatching { crypto.generateOneTimePreKeys(Protocol.ONE_TIME_PRE_KEY_BATCH) }.getOrNull() ?: return
        StoreSignalState.persistScalars(store, signalState) // nextPreKeyId advanced 32×
        runCatching { sendFrame(PreKeys.uploadFrame(fresh)) }
    }

    // ---------------------------------------------------------------- burn sweeper

    private fun startBurnSweeper() {
        burnJob = scope.launch {
            while (isActive) {
                delay(1_000)
                sweepBurned()
            }
        }
    }

    private fun sweepBurned() {
        val now = clock()
        var changed = false
        var msgs = _messages.value
        for ((thread, log) in msgs) {
            val expired = log.filter { m -> m.burnDeadline?.let { !it.isAfter(now) } == true }
            if (expired.isNotEmpty()) {
                purgeAttachments(expired)
                msgs = msgs + (thread to log.filter { m -> m.burnDeadline?.isAfter(now) ?: true })
                changed = true
            }
        }
        // thread-level auto-burn after N days of silence
        settings.threadAutoBurnDays?.let { days ->
            val cutoff = now.minusSeconds(days * 86_400L)
            for ((thread, log) in msgs) {
                val last = log.maxOfOrNull { it.timestamp } ?: continue
                if (last.isBefore(cutoff) && log.isNotEmpty()) {
                    purgeAttachments(log)
                    msgs = msgs + (thread to emptyList())
                    changed = true
                }
            }
        }
        // typing indicators expire after 6 s of silence
        val typingCutoff = now.minusSeconds(TYPING_EXPIRY_SECONDS)
        val typing = _typingPeers.value.filterValues { !it.isBefore(typingCutoff) }
        if (typing.size != _typingPeers.value.size) _typingPeers.value = typing
        val groupTyping = _groupTyping.value
            .mapValues { (_, m) -> m.filterValues { !it.isBefore(typingCutoff) } }
            .filterValues { it.isNotEmpty() }
        if (groupTyping != _groupTyping.value) _groupTyping.value = groupTyping
        // stale partial transfers (sender vanished mid-blob)
        val staleCutoff = now.minusSeconds(STALE_BLOB_SECONDS)
        for ((id, entry) in blobIncoming.entries.toList()) {
            if (entry.startedAt.isBefore(staleCutoff)) {
                blobIncoming.remove(id)
                dropRxTransfer(id)
            }
        }
        // operator notices past their `expires`
        sweepNotices(now)
        if (changed) setMessages(msgs)
    }

    // ---------------------------------------------------------------- notes (local-only; never touches the relay)

    suspend fun createNote(): Note = actor {
        val now = clock()
        val n = Note(id = UUID.randomUUID().toString().uppercase(), text = "", createdAt = now, modifiedAt = now, pinned = null)
        _notes.value = _notes.value + n
        persistNotes()
        n
    }

    /** No-ops (and doesn't bump modifiedAt) when text is unchanged. */
    suspend fun updateNoteText(id: String, text: String): Unit = actor {
        val current = _notes.value.firstOrNull { it.id == id } ?: return@actor
        if (current.text == text) return@actor
        _notes.value = _notes.value.map { if (it.id == id) it.copy(text = text, modifiedAt = clock()) else it }
        persistNotes()
    }

    suspend fun togglePin(id: String): Unit = actor {
        if (_notes.value.none { it.id == id }) return@actor
        _notes.value = _notes.value.map { if (it.id == id) it.copy(pinned = !(it.pinned ?: false)) else it }
        persistNotes()
    }

    suspend fun deleteNote(id: String): Unit = actor { deleteNoteNow(id) }

    private fun deleteNoteNow(id: String) {
        _notes.value = _notes.value.filter { it.id != id }
        persistNotes()
    }

    /** Editor-close hook: a brand-new buffer that never received real text is discarded silently. */
    suspend fun finalizeNote(id: String, isNew: Boolean): Unit = actor {
        if (!isNew) return@actor
        val n = _notes.value.firstOrNull { it.id == id } ?: return@actor
        if (n.text.isBlank()) deleteNoteNow(id)
    }

    /** Pinned first; within each group newest-modified first; stable ties. */
    val sortedNotes: List<Note>
        get() = _notes.value.sortedWith { a, b ->
            when {
                a.isPinned != b.isPinned -> if (a.isPinned) -1 else 1
                a.modifiedAt != b.modifiedAt -> b.modifiedAt.compareTo(a.modifiedAt)
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                else -> a.id.compareTo(b.id)
            }
        }

    private fun persistNotes() {
        val snapshot = _notes.value
        store.update { it.copy(notes = snapshot) }
    }

    // ---------------------------------------------------------------- group channels (docs/GROUPS.md)
    //
    // A group is shared state inside every member's store; every group message and control is an
    // ordinary pairwise-encrypted payload carrying `group: {id, epoch}`, fanned out by the sender
    // over the existing Signal sessions (one relay envelope per recipient). Authority is the
    // pairwise session: a control is attributed to `from` — the identity whose session it arrived
    // on — never to a field inside it (§2).

    fun group(id: String): Group? = _groups.value.firstOrNull { it.id == id }

    /** True when [thread] is a group id rather than a peer hash (the two never collide: uuid vs hex-64). */
    fun isGroup(thread: String): Boolean = group(thread) != null

    private fun publishGroups(next: List<Group>) {
        _groups.value = next
        _goneMembers.value = goneByGroup(next)
        _formerMembers.value = formerByGroup(next)
    }

    private fun setGroups(next: List<Group>) {
        publishGroups(next)
        store.update { it.copy(groups = next.ifEmpty { null }) }
    }

    private fun replaceGroup(next: Group) = setGroups(_groups.value.map { if (it.id == next.id) next else it })

    /** Everyone a fan-out addresses: the roster minus me and the members the relay reported gone. */
    private fun recipientsOf(g: Group): List<String> = g.members.filter { it.hash != myHash && !it.isGone }.map { it.hash }

    /** Roster changes need the link up (agreed with iOS). */
    private fun requireLink() {
        if (!requireLinkForRosterOps) return
        if (connState.value != ConnState.ONLINE || link.socket == null) throw ServiceError(GroupRules.ERR_LINK_DOWN)
    }

    /** §1 burn timers: the group default has per-contact semantics (absent = global default, `0` = off). */
    fun groupBurnDefault(g: Group): Double? {
        g.burnSeconds?.let { s -> return if (s == 0.0) null else s }
        return settings.defaultBurnSeconds
    }

    private fun contactName(hash: String): String? = _contacts.value.firstOrNull { it.hash == hash }?.name

    /** A member's label: roster, then the former members, then contacts, then `anon-<hash>`. */
    fun memberName(g: Group, hash: String): String =
        g.member(hash)?.name ?: g.formerMember(hash)?.name ?: contactName(hash) ?: GroupRules.anonName(hash)

    private fun groupSystemRow(text: String, gid: String) = systemRow(text).copy(group = gid)

    private fun appendGroupSystem(text: String, gid: String) = appendMessage(groupSystemRow(text, gid), gid)

    /** §5: a member who leaves keeps their ORIGINAL `joinedAt` in `former` (earliest join per hash). */
    private fun withFormer(g: Group, m: Member): Group {
        val cur = g.former ?: emptyList()
        val earlier = cur.firstOrNull { it.hash == m.hash }?.takeIf { it.joinedAt <= m.joinedAt }
        return g.copy(former = cur.filter { it.hash != m.hash } + (earlier ?: m.copy(gone = null)))
    }

    /** A (re)joining member keeps the earliest join known for the hash and leaves `former`. */
    private fun rejoined(g: Group, hash: String, name: String, joinedAt: Instant): Pair<Group, Member> {
        val earlier = g.formerMember(hash)?.joinedAt
        val m = Member(hash, name, if (earlier != null && earlier < joinedAt) earlier else joinedAt)
        val former = g.former?.filter { it.hash != hash }?.ifEmpty { null }
        return g.copy(members = g.members + m, former = former) to m
    }

    /** §4: the relay answered `unknown_user` for a member — they deleted their account. Once per group + member. */
    private fun markMemberGone(gid: String, hash: String) {
        val g = group(gid) ?: return
        val m = g.member(hash) ?: return
        if (m.isGone) return
        replaceGroup(g.copy(members = g.members.map { if (it.hash == hash) it.copy(gone = true) else it }))
        appendGroupSystem("── <${m.name}> deleted their account ──", gid)
    }

    /**
     * §2 last-writer rule per member for add / remove / leave / screenshot-remove: a change stamped
     * `(epoch, senderHash)` applies unless an already-applied change to that member is newer.
     * RAM-only, like on iOS. Rename and policy are single-writer (creator) and apply unconditionally.
     */
    private fun acceptsMember(gid: String, hash: String, epoch: Int, sender: String): Boolean {
        val last = memberStamps["$gid:$hash"] ?: return true
        return epoch > last.first || (epoch == last.first && sender >= last.second)
    }

    private fun stampMember(gid: String, hash: String, epoch: Int, sender: String) {
        memberStamps["$gid:$hash"] = epoch to sender
    }

    /** `group_create` / `group_invite`: the full roster at the group's current epoch (§2), plus the former members. */
    private fun rosterPayload(g: Group, control: String): Wire.Payload = Wire.Payload(
        kind = "control", control = control, group = GroupRef(g.id, g.epoch), name = g.name, creator = g.creator,
        // with each member's delivery key where this phone holds it (docs/SEALED-SENDER.md §5.7):
        // members who never spoke directly then start without the relay being told who writes
        members = g.members.map { GroupMember(it.hash, it.name, SwiftDate.toSeconds(it.joinedAt), rosterDeliveryKey(it.hash)) },
        policy = g.policy, burnSeconds = g.burnSeconds, createdAt = SwiftDate.toSeconds(g.createdAt),
        former = g.former?.map { GroupMember(it.hash, it.name, SwiftDate.toSeconds(it.joinedAt)) },
    )

    // ---- creator / member actions (§1, §6b)

    /**
     * `createGroup(name, members, policy, burnSeconds)`: [members] are contact hashes (at most 19 —
     * the creator makes 20); every initial member joins at `createdAt`, so join order among them is
     * by hash. Sends `group_create` to each of them (retried until every `sent` lands).
     */
    suspend fun createGroup(
        name: String,
        members: List<String>,
        policy: GroupPolicy = GroupPolicy.DEFAULT,
        burnSeconds: Double? = null,
    ): Group = actor {
        requireLink()
        val clean = GroupRules.normalizeName(name) ?: throw ServiceError(GroupRules.ERR_BAD_NAME)
        val others = members.distinct().filter { it != myHash }
        if (others.size + 1 > GroupRules.MAX_MEMBERS) throw ServiceError(GroupRules.ERR_GROUP_FULL)
        val now = clock()
        val roster = listOf(Member(myHash, myUsername, now)) +
            others.map { Member(it, contactName(it) ?: GroupRules.anonName(it), now) }
        val g = Group(
            id = newMessageId(), name = clean, creator = myHash, createdAt = now, epoch = 0,
            policy = policy.wire, burnSeconds = burnSeconds, members = roster,
        )
        setGroups(_groups.value + g)
        setMessages(_messages.value + (g.id to emptyList()))
        val payload = rosterPayload(g, GroupControl.CREATE)
        for (h in others) enqueueGroupControl(h, payload)
        g
    }

    /** Any member may add a contact: `group_add` to the existing members, `group_invite` (full roster) to the newcomer. */
    suspend fun addMember(groupId: String, hash: String): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (g.isLeft || !g.isMember(myHash)) throw ServiceError(GroupRules.ERR_NOT_IN_GROUP)
        if (hash == myHash || g.isMember(hash)) return@actor
        if (g.members.size >= GroupRules.MAX_MEMBERS) throw ServiceError(GroupRules.ERR_GROUP_FULL)
        requireLink()
        val (joined, m) = rejoined(g, hash, contactName(hash) ?: GroupRules.anonName(hash), clock())
        val next = joined.copy(epoch = g.epoch + 1)
        replaceGroup(next)
        stampMember(groupId, hash, next.epoch, myHash)
        appendGroupSystem("── <${m.name}> joined ──", groupId)
        val add = Wire.Payload(
            kind = "control", control = GroupControl.ADD, group = GroupRef(next.id, next.epoch),
            member = GroupMember(m.hash, m.name, SwiftDate.toSeconds(m.joinedAt), rosterDeliveryKey(m.hash)),
        )
        for (peer in recipientsOf(g)) enqueueGroupControl(peer, add) // the roster BEFORE the add
        enqueueGroupControl(hash, rosterPayload(next, GroupControl.INVITE))
    }

    /** Creator only (§1): `group_remove` to everyone including the removed member. */
    suspend fun removeMember(groupId: String, hash: String): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (g.isLeft || !g.isMember(myHash)) throw ServiceError(GroupRules.ERR_NOT_IN_GROUP)
        if (g.creator != myHash) throw ServiceError(GroupRules.ERR_ONLY_CREATOR)
        if (hash == myHash) return@actor
        val m = g.member(hash) ?: return@actor
        requireLink()
        val next = withFormer(g.copy(members = g.members.filter { it.hash != hash }, epoch = g.epoch + 1), m)
        replaceGroup(next)
        stampMember(groupId, hash, next.epoch, myHash)
        appendGroupSystem("── <${m.name}> was removed ──", groupId)
        val remove = Wire.Payload(kind = "control", control = GroupControl.REMOVE, group = GroupRef(next.id, next.epoch), member = GroupMember(hash))
        for (peer in recipientsOf(g)) enqueueGroupControl(peer, remove) // includes the removed member
    }

    /** Anyone, any time: `group_leave` to everyone; the local copy turns read-only (`left`). */
    suspend fun leaveGroup(groupId: String): Unit = actor { leaveGroupNow(groupId) }

    private fun leaveGroupNow(groupId: String) {
        val g = group(groupId) ?: return
        if (g.isLeft || !g.isMember(myHash)) return
        requireLink()
        val me = g.member(myHash) ?: return
        val leave = Wire.Payload(kind = "control", control = GroupControl.LEAVE, group = GroupRef(g.id, g.epoch + 1))
        for (peer in recipientsOf(g)) enqueueGroupControl(peer, leave)
        val next = withFormer(g.copy(members = g.members.filter { it.hash != myHash }, epoch = g.epoch + 1, left = true), me)
        replaceGroup(next)
        stampMember(groupId, myHash, next.epoch, myHash)
        appendGroupSystem("── <$myUsername> left ──", groupId)
    }

    /** Creator only: `group_rename`; the local row reads `── renamed to #<name> ──`. */
    suspend fun renameGroup(groupId: String, name: String): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (g.isLeft || !g.isMember(myHash)) throw ServiceError(GroupRules.ERR_NOT_IN_GROUP)
        if (g.creator != myHash) throw ServiceError("only_creator")
        val clean = GroupRules.normalizeName(name) ?: throw ServiceError(GroupRules.ERR_BAD_NAME)
        if (clean == g.name) return@actor
        requireLink()
        val next = g.copy(name = clean, epoch = g.epoch + 1)
        replaceGroup(next)
        appendGroupSystem("── renamed to #$clean ──", groupId)
        val rename = Wire.Payload(kind = "control", control = GroupControl.RENAME, group = GroupRef(next.id, next.epoch), name = clean)
        for (peer in recipientsOf(next)) enqueueGroupControl(peer, rename)
    }

    /** Creator only: `group_policy` (policy + burn default); a row only when the policy word changed. */
    suspend fun setGroupPolicy(groupId: String, policy: GroupPolicy, burnSeconds: Double?): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (g.isLeft || !g.isMember(myHash)) throw ServiceError(GroupRules.ERR_NOT_IN_GROUP)
        if (g.creator != myHash) throw ServiceError("only_creator")
        if (policy.wire == g.policy && burnSeconds == g.burnSeconds) return@actor
        requireLink()
        val next = g.copy(policy = policy.wire, burnSeconds = burnSeconds, epoch = g.epoch + 1)
        replaceGroup(next)
        if (policy.wire != g.policy) appendGroupSystem("── screenshot policy: ${policy.wire} ──", groupId)
        val control = Wire.Payload(
            kind = "control", control = GroupControl.POLICY, group = GroupRef(next.id, next.epoch),
            policy = policy.wire, burnSeconds = burnSeconds,
        )
        for (peer in recipientsOf(next)) enqueueGroupControl(peer, control)
    }

    /**
     * §6b: `group_burn` to every member (retried until each `sent` lands), then this phone's copy
     * — rows, sidecars, roster — is destroyed. Creator only.
     */
    suspend fun burnGroup(groupId: String): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (g.isLeft || !g.isMember(myHash)) throw ServiceError(GroupRules.ERR_NOT_IN_GROUP)
        if (g.creator != myHash) throw ServiceError("only_creator")
        requireLink()
        val burn = Wire.Payload(kind = "control", control = GroupControl.BURN, group = GroupRef(g.id, g.epoch))
        // every member, plus the phones removed or left — they still hold a read-only copy to destroy;
        // agreed with iOS: 3 attempts with an 8 s verdict deadline each
        val former = g.former?.map { it.hash } ?: emptyList()
        for (peer in (recipientsOf(g) + former).distinct()) enqueueGroupControl(peer, burn, maxAttempts = BURN_CONTROL_ATTEMPTS)
        destroyGroupLocally(g)
    }

    /** Drop a group from THIS phone: a `left` thread goes silently, a live membership leaves first. */
    suspend fun deleteGroup(groupId: String): Unit = actor {
        val g = group(groupId) ?: return@actor
        if (!g.isLeft && g.isMember(myHash)) leaveGroupNow(groupId) // throws ERR_LINK_DOWN offline
        destroyGroupLocally(group(groupId) ?: g)
    }

    private fun destroyGroupLocally(g: Group) {
        purgeAttachments(_messages.value[g.id] ?: emptyList())
        _messages.value = _messages.value - g.id
        publishGroups(_groups.value.filter { it.id != g.id })
        store.update { blob ->
            blob.copy(
                messages = _messages.value, groups = _groups.value.ifEmpty { null },
                drafts = blob.drafts?.let { d -> (d - g.id).ifEmpty { null } },
            )
        }
        _groupTyping.value = _groupTyping.value - g.id
        groupOutbound.values.removeIf { it.groupId == g.id }
        lastTypingSent.remove(g.id)
        if (activeThread == g.id) activeThread = null
    }

    /**
     * §6, the offender: `screenshot` + `group` to every member (as today), then the local policy —
     * `alert` nothing, `burn` wipe the thread, `burn_remove` wipe + mark `left`. A screen recording
     * only alerts.
     */
    private fun reportGroupCapture(g: Group, control: String) {
        if (g.isLeft || !g.isMember(myHash)) return
        if (control != "screenshot") return // agreed with iOS: a screen recording is not fanned out to a group
        val payload = Wire.Payload(kind = "control", control = control, group = GroupRef(g.id, g.epoch))
        for (peer in recipientsOf(g)) enqueueGroupControl(peer, payload)
        when (g.policyKind) {
            GroupPolicy.ALERT -> {}
            GroupPolicy.BURN, GroupPolicy.BURN_REMOVE -> {
                purgeAttachments(_messages.value[g.id] ?: emptyList())
                setMessages(_messages.value + (g.id to listOf(groupSystemRow("── this channel burned: you took a screenshot ──", g.id))))
                if (g.policyKind == GroupPolicy.BURN_REMOVE) replaceGroup(g.copy(left = true))
            }
        }
    }

    // ---- fan-out (§4)

    private suspend fun sendGroupTextNow(text: String, groupId: String, viaWeb: Boolean = false) {
        val g = group(groupId) ?: return
        if (g.isLeft || !g.isMember(myHash)) {
            _lastError.value = GroupRules.ERR_NOT_IN_GROUP
            return
        }
        val id = newMessageId()
        val burn = groupBurnDefault(g)
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = Kind.TEXT, text = text,
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            group = groupId, receipts = emptyMap(), viaWeb = viaWeb,
        )
        appendMessage(msg, groupId)
        play(Tone.TX)
        fanOutText(msg, g, recipientsOf(g))
    }

    /** Media: the row (and its ONE sidecar) once, then the blob pipeline per recipient with the same plaintext. */
    private suspend fun sendGroupMediaNow(
        groupId: String,
        kind: Kind,
        content: ByteArray,
        text: String,
        filename: String? = null,
        mime: String? = null,
        width: Int? = null,
        height: Int? = null,
        duration: Double? = null,
    ) {
        val g = group(groupId) ?: return
        if (g.isLeft || !g.isMember(myHash)) {
            _lastError.value = GroupRules.ERR_NOT_IN_GROUP
            return
        }
        val id = newMessageId()
        val burn = groupBurnDefault(g)
        // §1a: voice goes to a sidecar exactly like an image or a file
        val attId = newMessageId()
        if (!writeAttachmentOrNote(attId, content)) return
        val msg = ChatMessage(
            id = id, direction = Direction.OUTGOING, kind = kind, text = text,
            timestamp = clock(), status = Status.PENDING, burnAfter = burn, burnStartedAt = null,
            voiceDuration = duration,
            attachmentId = attId, fileName = filename, fileMime = mime,
            fileBytes = if (kind == Kind.VOICE) null else content.size, imageWidth = width, imageHeight = height,
            group = groupId, receipts = emptyMap(),
        )
        appendMessage(msg, groupId)
        play(Tone.TX)
        fanOutBlob(msg, g, recipientsOf(g))
    }

    /** The SAME payload for every recipient; `refId` lets the receiver file the row under the sender's id. */
    private fun groupTextPayload(row: ChatMessage, g: Group) = Wire.Payload(
        kind = "text", text = row.text, senderName = myUsername, burnAfter = row.burnAfter,
        via = if (row.viaWeb) "web" else null, refId = row.id, group = GroupRef(g.id, g.epoch),
    )

    private suspend fun fanOutText(row: ChatMessage, g: Group, targets: List<String>) {
        val t = groupOutbound.getOrPut(row.id) { GroupFanOut(row.id, g.id, emptyList()) }
        t.pending += targets
        for (r in targets) sendGroupTextTo(row, g, r, t)
        finishGroupSend(t)
    }

    private suspend fun sendGroupTextTo(row: ChatMessage, g: Group, recipient: String, t: GroupFanOut) {
        try {
            if (link.socket == null) throw IOException("not connected to relay")
            if (!crypto.hasSession(recipient)) fetchAndProcessBundle(recipient)
            val relayId = newMessageId()
            val out = prepare(groupTextPayload(row, g), recipient, "msg", Protocol.TTL_MSG, relayId)
            groupSendIds[relayId] = GroupSendRef(row.id, g.id, recipient)
            groupRelayIds.getOrPut(row.id) { HashMap() }[recipient] = relayId
            capture(row.id, out, ifAbsent = true) // §3: one envelope of the fan-out
            dispatch(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteRecipientFailure(t, g.id, recipient, e)
        }
    }

    private suspend fun fanOutBlob(row: ChatMessage, g: Group, targets: List<String>) {
        val t = groupOutbound.getOrPut(row.id) { GroupFanOut(row.id, g.id, emptyList()) }
        t.pending += targets
        val n = targets.size
        var lost = false
        for ((i, r) in targets.withIndex()) {
            lost = sendGroupBlobTo(row, g, r, t) { frac -> setBlobProgress(row.id, (i + frac) / n) } || lost
        }
        clearBlobProgress(row.id)
        finishGroupSend(t)
        if (lost) lostWithConnection() // after the row has its status: the retry looks for FAILED rows
    }

    /** One recipient's copy. True when it was lost with the anonymous connection it was going out on. */
    private suspend fun sendGroupBlobTo(row: ChatMessage, g: Group, recipient: String, t: GroupFanOut, progress: (Double) -> Unit): Boolean {
        val subtype: BlobSubtype
        val content: ByteArray? = when (row.kind) {
            Kind.VOICE -> { subtype = BlobSubtype.VOICE; voiceContent(row) }
            Kind.IMAGE -> { subtype = BlobSubtype.IMAGE; attachmentData(row) }
            Kind.FILE -> { subtype = BlobSubtype.FILE; attachmentData(row) }
            Kind.TEXT, Kind.SYSTEM -> { t.failed(recipient); return false }
        }
        if (content == null) {
            t.failed(recipient)
            return false
        }
        try {
            if (link.socket == null) throw IOException("not connected to relay")
            val relayId = newMessageId()
            groupSendIds[relayId] = GroupSendRef(row.id, g.id, recipient)
            groupRelayIds.getOrPut(row.id) { HashMap() }[recipient] = relayId
            sendBlobFrames(
                relayId, row.id, recipient, content, subtype, row.burnAfter, row.voiceDuration, row.fileName,
                row.fileMime, row.imageWidth, row.imageHeight, GroupRef(g.id, g.epoch), progress,
            )
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteRecipientFailure(t, g.id, recipient, e)
            return e is AnonLinkLost
        }
    }

    private fun noteRecipientFailure(t: GroupFanOut, gid: String, recipient: String, e: Exception) {
        if ((e as? ServiceError)?.code == Wire.ErrorCode.UNKNOWN_USER) {
            markMemberGone(gid, recipient)
            t.gone(recipient)
        } else {
            t.failed(recipient)
            _lastError.value = (e as? ServiceError)?.code ?: (e.message ?: e.toString())
        }
    }

    /** Once every recipient has a verdict: `sent` when all landed, `failed` if any errored (§4). */
    private fun finishGroupSend(t: GroupFanOut) {
        if (!t.done) return
        val status = t.status
        setStatus(t.messageId, status) { it == Status.PENDING }
        if (status == Status.SENT) groupOutbound.remove(t.messageId)
    }

    /** The one silent re-send to a rate-limited recipient (mirrors the 1:1 path). */
    private suspend fun resendGroupRecipient(messageId: String, groupId: String, recipient: String) {
        val g = group(groupId) ?: return
        val t = groupOutbound[messageId] ?: return
        val row = _messages.value[groupId]?.firstOrNull { it.id == messageId } ?: return
        if (recipient !in t.pending) return
        if (row.kind == Kind.TEXT) sendGroupTextTo(row, g, recipient, t)
        else sendGroupBlobTo(row, g, recipient, t) {}
        finishGroupSend(t)
    }

    /** §4 retry: only the recipients that failed; after a relaunch (no tracker) everyone without a receipt yet. */
    private fun retryTargets(row: ChatMessage, g: Group): List<String> {
        groupOutbound[row.id]?.let { return it.retry().toList() }
        val done = row.receipts?.keys ?: emptySet()
        return recipientsOf(g).filter { it !in done }
    }

    private suspend fun retryGroupTextNow(messageId: String, groupId: String) {
        val g = group(groupId) ?: return
        if (g.isLeft || !g.isMember(myHash)) {
            _lastError.value = GroupRules.ERR_NOT_IN_GROUP
            return
        }
        val row = _messages.value[groupId]?.firstOrNull { it.id == messageId } ?: return
        if (row.status != Status.FAILED || row.kind != Kind.TEXT) return
        val targets = retryTargets(row, g)
        setStatus(messageId, Status.PENDING) { it == Status.FAILED }
        fanOutText(row, g, targets)
    }

    private suspend fun retryGroupBlobNow(messageId: String, groupId: String) {
        val g = group(groupId) ?: return
        if (g.isLeft || !g.isMember(myHash)) {
            _lastError.value = GroupRules.ERR_NOT_IN_GROUP
            return
        }
        val row = _messages.value[groupId]?.firstOrNull { it.id == messageId } ?: return
        if (row.status != Status.FAILED || row.kind == Kind.TEXT || row.kind == Kind.SYSTEM) return
        val targets = retryTargets(row, g)
        setStatus(messageId, Status.PENDING) { it == Status.FAILED }
        fanOutBlob(row, g, targets)
    }

    /**
     * §1 delete for all — author only: the existing `delete` control + `group` to every member;
     * the local row goes once at least one member's verdict was `sent` (agreed with iOS), the rest
     * keep retrying in the outbox.
     */
    private suspend fun deleteForAllNow(msg: ChatMessage, g: Group): Boolean {
        if (g.isLeft || !g.isMember(myHash)) {
            _lastError.value = GroupRules.ERR_NOT_IN_GROUP
            return false
        }
        if (connState.value != ConnState.ONLINE || link.socket == null) {
            _lastError.value = "offline — delete for both needs the link up"
            return false
        }
        val relayIds = groupRelayIds[msg.id] ?: emptyMap()
        val peers = recipientsOf(g)
        if (peers.isNotEmpty()) {
            val first = CompletableDeferred<Boolean>()
            var refused = 0
            for (peer in peers) {
                // both ids: the sender's row id (how this app files it) and the relay id of that member's copy
                val refs = listOfNotNull(msg.id, relayIds[peer]).distinct()
                enqueueGroupControl(peer, Wire.Payload(kind = "control", control = "delete_msg", refIds = refs, group = GroupRef(g.id, g.epoch))) { ok ->
                    if (ok) first.complete(true) else if (++refused == peers.size) first.complete(false)
                }
            }
            val ok = withTimeoutOrNull(CONTROL_ACK_TIMEOUT_MS) { first.await() } ?: false
            if (!ok) {
                _lastError.value = "delete for both refused by relay — try again"
                return false
            }
        }
        deleteMessageNow(msg.id, g.id)
        groupOutbound.remove(msg.id)
        groupRelayIds.remove(msg.id)
        return true
    }

    // ---- inbound (§2)

    private fun receiveGroupText(relayId: String, from: String, payload: Wire.Payload, gref: GroupRef, rx: Wire.In.Recv? = null) {
        val g = group(gref.id) ?: return // a group this phone never joined: dropped (already acked)
        val rowId = payload.refId?.takeIf { it.isNotBlank() } ?: relayId
        if (_messages.value[g.id]?.any { it.id == rowId } == true) return
        _groupTyping.value[g.id]?.let { m -> if (from in m) _groupTyping.value = _groupTyping.value + (g.id to (m - from)) }
        // a sender outside the local roster is still shown (`<name>?` in the UI), never dropped (§2)
        val msg = ChatMessage(
            id = rowId, direction = Direction.INCOMING, kind = Kind.TEXT,
            text = payload.text ?: "", timestamp = clock(), status = Status.DELIVERED,
            burnAfter = payload.burnAfter, burnStartedAt = null, viaWeb = payload.via == "web",
            group = g.id, sender = from,
        )
        appendMessage(msg, g.id)
        rx?.let { frames.put(rowId, CapturedFrame.of(it)) } // §3: filed under the row id, not the relay id
        enqueueGroupReceipt(ReceiptKind.DELIVERED, listOf(rowId), from, g.id)
        play(Tone.RX)
    }

    private fun handleGroupControl(payload: Wire.Payload, from: String, relayTs: Long?, gref: GroupRef) {
        val gid = gref.id
        when (payload.control) {
            GroupControl.CREATE, GroupControl.INVITE -> receiveRoster(payload, from, gref)
            GroupControl.ADD -> {
                val g = group(gid) ?: return
                if (g.isLeft || !g.isMember(from)) return
                val wm = payload.member ?: return
                if (wm.hash.length != 64 || g.isMember(wm.hash) || g.members.size >= GroupRules.MAX_MEMBERS) return
                if (!acceptsMember(gid, wm.hash, gref.epoch, from)) return
                val (joined, m) = rejoined(g, wm.hash, GroupRules.sanitizeMemberName(wm.name) ?: GroupRules.anonName(wm.hash), wm.joinedAt?.let(SwiftDate::toInstant) ?: clock())
                replaceGroup(joined.copy(epoch = maxOf(g.epoch + 1, gref.epoch)))
                stampMember(gid, wm.hash, gref.epoch, from)
                learnRosterKey(wm.dk, wm.hash) // taken only if this phone holds no key for them
                appendGroupSystem("── <${m.name}> joined ──", gid)
            }
            GroupControl.REMOVE -> {
                val g = group(gid) ?: return
                if (g.isLeft) return
                val hash = payload.member?.hash ?: return
                if (from != g.creator || !g.isMember(from)) {
                    if (debugRows) appendGroupSystem("── ignored a removal from <${memberName(g, from)}> (not the creator) ──", gid)
                    return
                }
                val m = g.member(hash) ?: return
                if (!acceptsMember(gid, hash, gref.epoch, from)) return
                val next = withFormer(
                    g.copy(
                        members = g.members.filter { it.hash != hash }, epoch = maxOf(g.epoch + 1, gref.epoch),
                        left = if (hash == myHash) true else g.left,
                    ),
                    m,
                )
                replaceGroup(next)
                stampMember(gid, hash, gref.epoch, from)
                appendGroupSystem("── <${m.name}> was removed ──", gid)
            }
            GroupControl.LEAVE -> {
                val g = group(gid) ?: return
                if (g.isLeft) return
                val m = g.member(from) ?: return
                if (!acceptsMember(gid, from, gref.epoch, from)) return
                replaceGroup(withFormer(g.copy(members = g.members.filter { it.hash != from }, epoch = maxOf(g.epoch + 1, gref.epoch)), m))
                stampMember(gid, from, gref.epoch, from)
                appendGroupSystem("── <${m.name}> left ──", gid)
            }
            GroupControl.RENAME -> {
                val g = group(gid) ?: return
                if (g.isLeft || from != g.creator || !g.isMember(from)) return
                val name = GroupRules.normalizeName(payload.name ?: return) ?: return
                if (name == g.name) return // single-writer (the creator): applied unconditionally
                replaceGroup(g.copy(name = name, epoch = maxOf(g.epoch + 1, gref.epoch)))
                appendGroupSystem("── renamed to #$name ──", gid)
            }
            GroupControl.POLICY -> {
                val g = group(gid) ?: return
                if (g.isLeft || from != g.creator || !g.isMember(from)) return
                val policy = GroupPolicy.fromWire(payload.policy) ?: GroupPolicy.ALERT // unknown word ⇒ alert (agreed)
                if (policy.wire == g.policy && payload.burnSeconds == g.burnSeconds) return
                replaceGroup(g.copy(policy = policy.wire, burnSeconds = payload.burnSeconds, epoch = maxOf(g.epoch + 1, gref.epoch)))
                if (policy.wire != g.policy) appendGroupSystem("── screenshot policy: ${policy.wire} ──", gid)
            }
            GroupControl.BURN -> {
                val g = group(gid) ?: return
                if (from != g.creator || !g.isMember(from)) return // §6b: anyone but the creator is ignored
                val name = g.name
                destroyGroupLocally(g)
                _flashes.tryEmit("#$name was burned by the creator")
                play(Tone.RX)
            }
            "screenshot", "screen_record" -> receiveGroupCapture(payload.control, from, gid)
            "web_link" -> {
                // MIRROR-GROUPS.md §5: a member's linked browser shows this channel — the notice
                // lands in the group thread and names the member (the 1:1 text is unchanged)
                val g = group(gid) ?: return
                if (g.isLeft || !g.isMember(from)) return
                val browser = payload.browser?.let { sanitizeBrowserName(it) }
                val who = memberName(g, from)
                appendGroupSystem(
                    if (payload.linkUp == true) {
                        "── <$who> linked a desktop browser" + (browser?.let { " ($it)" } ?: "") + " — this channel may be viewed there ──"
                    } else {
                        "── <$who> unlinked the desktop browser ──"
                    },
                    gid,
                )
            }
            "typing" -> {
                val g = group(gid) ?: return
                if (!g.isMember(from)) return
                if (relayTs != null && clock().toEpochMilli() - relayTs > TYPING_STALE_MS) return
                val cur = _groupTyping.value[gid] ?: emptyMap()
                _groupTyping.value = _groupTyping.value + (gid to cur + (from to clock()))
            }
            "receipt" -> {
                val kind = payload.receipt?.let { ReceiptKind.fromWire(it) } ?: return
                applyGroupReceipt(kind, payload.refIds ?: return, from, gid)
            }
            "burn_start" -> applyGroupBurnStart(payload.refIds ?: return, from, gid)
            "delete_msg" -> {
                val g = group(gid) ?: return
                val refIds = payload.refIds ?: return
                if (refIds.size > 50) return
                val log = _messages.value[g.id] ?: return
                // AUTH BOUNDARY: the author may retract only rows THEY sent into this group
                val victims = log.filter { it.id in refIds && it.direction == Direction.INCOMING && it.sender == from }
                if (victims.isEmpty()) return
                purgeAttachments(victims)
                val ids = victims.map { it.id }.toSet()
                setMessages(_messages.value + (g.id to log.filter { it.id !in ids }))
            }
            else -> {}
        }
    }

    /**
     * `group_create` (from its creator, to an initial member) / `group_invite` (from the adder,
     * to the new member): create the group locally at that epoch, idempotent by id. A create for
     * a group we hold is ignored; an invite refreshes only a copy we had `left` (re-added) — an
     * existing member never lets another member rewrite the roster through an invite.
     */
    private fun receiveRoster(payload: Wire.Payload, from: String, gref: GroupRef) {
        val control = payload.control
        val name = GroupRules.normalizeName(payload.name ?: return) ?: return
        val creator = payload.creator ?: return
        val wire = payload.members ?: return
        if (wire.isEmpty() || wire.size > GroupRules.MAX_MEMBERS) return
        val members = wire.filter { it.hash.length == 64 }.distinctBy { it.hash }.map {
            Member(it.hash, GroupRules.sanitizeMemberName(it.name) ?: GroupRules.anonName(it.hash), it.joinedAt?.let(SwiftDate::toInstant) ?: clock())
        }
        if (members.none { it.hash == myHash }) return // a roster without me is not mine
        when (control) {
            GroupControl.CREATE -> if (creator != from || members.none { it.hash == creator }) return
            GroupControl.INVITE -> if (members.none { it.hash == from }) return
            else -> return
        }
        // an authentic roster may carry members' delivery keys; one is taken only for a member
        // this phone holds no key for (docs/SEALED-SENDER.md §5.1)
        for (m in wire) if (m.hash.length == 64) learnRosterKey(m.dk, m.hash)
        val policy = GroupPolicy.fromWire(payload.policy) ?: GroupPolicy.ALERT // unknown word ⇒ alert (agreed)
        val carriedFormer = payload.former?.filter { it.hash.length == 64 }?.distinctBy { it.hash }?.map {
            Member(it.hash, GroupRules.sanitizeMemberName(it.name) ?: GroupRules.anonName(it.hash), it.joinedAt?.let(SwiftDate::toInstant) ?: clock())
        }
        val existing = group(gref.id)
        if (existing != null) {
            if (control != GroupControl.INVITE || !existing.isLeft) return
            // a refreshed copy keeps the colour history it already had, merged with what the invite carries
            val former = ((carriedFormer ?: emptyList()) + (existing.former ?: emptyList()))
                .filter { f -> members.none { it.hash == f.hash } }
                .groupBy { it.hash }.map { (_, v) -> v.minBy { it.joinedAt } }
            replaceGroup(
                existing.copy(
                    name = name, creator = creator, epoch = gref.epoch, policy = policy.wire,
                    burnSeconds = payload.burnSeconds, members = members, left = null, former = former.ifEmpty { null },
                ),
            )
            return
        }
        val g = Group(
            id = gref.id, name = name, creator = creator, createdAt = payload.createdAt?.let(SwiftDate::toInstant) ?: clock(),
            epoch = gref.epoch, policy = policy.wire, burnSeconds = payload.burnSeconds, members = members,
            former = carriedFormer?.filter { f -> members.none { it.hash == f.hash } }?.ifEmpty { null },
        )
        setGroups(_groups.value + g)
        if (_messages.value[g.id] == null) setMessages(_messages.value + (g.id to emptyList()))
        play(Tone.RX)
    }

    /** §6 on every other phone: the alert row (counts as unread, RX tone); `burn_remove` also drops M from the roster. */
    private fun receiveGroupCapture(control: String?, from: String, gid: String) {
        val g = group(gid) ?: return
        if (!g.isMember(from)) return
        if (control != "screenshot") return // agreed with iOS: only `screenshot` has group semantics
        val name = memberName(g, from)
        appendGroupSystem("⚠ <$name> took a screenshot", gid)
        play(Tone.RX)
        if (g.policyKind == GroupPolicy.BURN_REMOVE) {
            val m = g.member(from) ?: return
            val epoch = g.epoch + 1
            if (!acceptsMember(gid, from, epoch, from)) return
            replaceGroup(withFormer(g.copy(members = g.members.filter { it.hash != from }, epoch = epoch), m))
            stampMember(gid, from, epoch, from)
            appendGroupSystem("── <$name> was removed for taking a screenshot ──", gid)
        }
    }

    // ---- receipts (§1: per member, aggregated)

    /** Which group row a receipt id names: the relay id used for that recipient, or a row id in [gid] / any group the sender is (or was) in. */
    private fun resolveGroupRef(ref: String, gid: String?, from: String): Pair<String, String>? {
        groupSendIds[ref]?.let { r -> if (r.recipient == from) return r.groupId to r.messageId }
        fun knows(g: Group) = g.isMember(from) || g.formerMember(from) != null
        if (gid != null) {
            val g = group(gid) ?: return null
            if (!knows(g)) return null // a stranger's receipt touches nothing
            return if (_messages.value[gid]?.any { it.id == ref } == true) gid to ref else null
        }
        for (g in _groups.value) {
            if (!knows(g)) continue
            if (_messages.value[g.id]?.any { it.id == ref } == true) return g.id to ref
        }
        return null
    }

    private fun applyGroupReceipt(kind: ReceiptKind, refIds: List<String>, from: String, gid: String?) {
        if (kind == ReceiptKind.BURN_START) return
        var msgs = _messages.value
        var changed = false
        for (ref in refIds) {
            val (g, msgId) = resolveGroupRef(ref, gid, from) ?: continue
            val log = msgs[g] ?: continue
            val i = log.indexOfFirst { it.id == msgId }
            if (i < 0) continue
            val row = log[i]
            if (row.direction != Direction.OUTGOING || row.kind == Kind.SYSTEM) continue
            val next = GroupReceipts.merge(row.receipts, from, kind.wire)
            if (next == row.receipts) continue
            msgs = msgs + (g to log.toMutableList().also { it[i] = row.copy(receipts = next) })
            changed = true
        }
        if (changed) setMessages(msgs)
    }

    /** The first member to read starts the sender's own countdown (synced burn, as in 1:1). */
    private fun applyGroupBurnStart(refIds: List<String>, from: String, gid: String?) {
        var msgs = _messages.value
        var changed = false
        val now = clock()
        for (ref in refIds) {
            val (g, msgId) = resolveGroupRef(ref, gid, from) ?: continue
            val log = msgs[g] ?: continue
            val i = log.indexOfFirst { it.id == msgId }
            if (i < 0) continue
            val row = log[i]
            if (row.direction != Direction.OUTGOING || row.burnAfter == null || row.burnStartedAt != null) continue
            msgs = msgs + (g to log.toMutableList().also { it[i] = row.copy(burnStartedAt = now) })
            changed = true
        }
        if (changed) setMessages(msgs)
    }

    private fun enqueueGroupReceipt(kind: ReceiptKind, ids: List<String>, peer: String, gid: String) {
        groupReceiptQueue += GroupReceipt(peer, gid, kind, ids)
        scheduleReceiptFlush()
    }

    /** One control per (member, group, lane), carrying `group` so the sender routes it to the group thread. */
    private fun flushGroupReceipts() {
        if (groupReceiptQueue.isEmpty()) return
        val batch = groupReceiptQueue.toList()
        groupReceiptQueue.clear()
        for ((key, items) in batch.groupBy { Triple(it.peer, it.groupId, it.kind) }) {
            val (peer, gid, kind) = key
            val g = group(gid) ?: continue
            if (!crypto.hasSession(peer)) continue
            val ids = items.flatMap { it.ids }.distinct()
            val payload = if (kind == ReceiptKind.BURN_START) {
                Wire.Payload(kind = "control", control = "burn_start", refIds = ids, group = GroupRef(gid, g.epoch))
            } else {
                Wire.Payload(kind = "control", control = "receipt", receipt = kind.wire, refIds = ids, group = GroupRef(gid, g.epoch))
            }
            val out = runCatching { prepare(payload, peer, "control", Protocol.TTL_CONTROL, newMessageId()) }.getOrNull() ?: continue
            runCatching { dispatch(out) }
        }
    }

    // ---- roster controls: retried until each `sent` lands (§6b)

    private fun enqueueGroupControl(peer: String, payload: Wire.Payload, maxAttempts: Int = MAX_CONTROL_ATTEMPTS, onVerdict: ((Boolean) -> Unit)? = null) {
        groupControlQueue.addLast(PendingControl(peer, payload, maxAttempts, onVerdict))
        scheduleControlFlush()
    }

    /**
     * One drain loop at a time: a running flush is never cancelled (it would drop the control it
     * is awaiting a bundle for) — it keeps looping over whatever is enqueued meanwhile.
     */
    private fun scheduleControlFlush(afterMs: Long = 0) {
        controlFlushJob?.let { if (it.isActive) return }
        controlFlushJob = scope.launch {
            if (afterMs > 0) delay(afterMs)
            flushGroupControls()
        }
    }

    private suspend fun flushGroupControls() {
        while (groupControlQueue.isNotEmpty()) {
            if (connState.value != ConnState.ONLINE || link.socket == null) return // login re-schedules
            val c = groupControlQueue.removeFirst()
            try {
                if (!crypto.hasSession(c.peer)) fetchAndProcessBundle(c.peer)
                val relayId = newMessageId()
                val out = prepare(c.payload, c.peer, "control", Protocol.TTL_CONTROL, relayId)
                inflightGroupControls[relayId] = c
                dispatch(out)
                // a socket write is not delivery: without a verdict within the deadline the control is re-sent
                scope.launch {
                    delay(CONTROL_ACK_TIMEOUT_MS)
                    val stale = inflightGroupControls[relayId] ?: return@launch
                    if (stale !== c) return@launch
                    inflightGroupControls.remove(relayId)
                    if (++c.attempts < c.maxAttempts) {
                        groupControlQueue.addLast(c)
                        scheduleControlFlush()
                    } else c.onVerdict?.invoke(false)
                }
            } catch (e: CancellationException) {
                groupControlQueue.addFirst(c) // shutdown mid-await: nothing is lost
                throw e
            } catch (e: Exception) {
                if ((e as? ServiceError)?.code == Wire.ErrorCode.UNKNOWN_USER) {
                    c.payload.group?.let { markMemberGone(it.id, c.peer) }
                    c.onVerdict?.invoke(false)
                    continue
                }
                if (++c.attempts >= c.maxAttempts) { // give up on this one
                    c.onVerdict?.invoke(false)
                    continue
                }
                groupControlQueue.addLast(c)
                delay(5_000) // transient (no bundle, socket hiccup): pause, then keep draining
            }
        }
    }

    /** Tests: roster controls not yet confirmed by the relay (queued + in flight). */
    internal fun pendingGroupControlCount(): Int = groupControlQueue.size + inflightGroupControls.size

    /** Tests: the same controls as (peer, payload), queued ones first. */
    internal fun pendingGroupControlsForTest(): List<Pair<String, Wire.Payload>> =
        groupControlQueue.map { it.peer to it.payload } + inflightGroupControls.values.map { it.peer to it.payload }

    /** Tests only: encrypt + send one raw control to [peer] as this identity (a forged non-creator `group_remove`, say). */
    internal suspend fun sendControlForTest(peer: String, payload: Wire.Payload): Unit = actor {
        if (!crypto.hasSession(peer)) fetchAndProcessBundle(peer)
        dispatch(prepare(payload, peer, "control", Protocol.TTL_CONTROL, newMessageId()))
    }

    // ---------------------------------------------------------------- helpers

    private fun findMessage(id: String): Pair<String, ChatMessage>? {
        for ((thread, log) in _messages.value) log.firstOrNull { it.id == id }?.let { return thread to it }
        return null
    }

    /**
     * iOS `resendText(id:thread:)` — the rate-limit path: re-encrypt and re-send under the
     * ORIGINAL id (the receiver dedupes by id). No `via`, no bundle fetch — verbatim.
     */
    private fun resendUnderSameId(id: String, thread: String) {
        val msg = _messages.value[thread]?.firstOrNull { it.id == id } ?: return
        if (msg.status != Status.PENDING && msg.status != Status.SENT) return
        val out = runCatching {
            prepare(Wire.Payload(kind = "text", text = msg.text, senderName = myUsername, burnAfter = msg.burnAfter), thread, "msg", Protocol.TTL_MSG, id)
        }.getOrNull() ?: run { markFailed(id); return }
        capture(id, out)
        runCatching { dispatch(out) }
    }

    private fun appendMessage(msg: ChatMessage, thread: String) {
        setMessages(_messages.value + (thread to (_messages.value[thread] ?: emptyList()) + msg))
    }

    private fun replaceMessage(msg: ChatMessage, thread: String) {
        val log = _messages.value[thread] ?: return
        val i = log.indexOfFirst { it.id == msg.id }
        if (i < 0) return
        setMessages(_messages.value + (thread to log.toMutableList().also { it[i] = msg }))
    }

    /**
     * iOS `messages[…] = …; store.update { $0.messages = snapshot }`.
     *
     * [durability] is docs/DECOY-ACCOUNT.md §1's split: a sent or received message is
     * [Durability.PROMPT], because losing it to a crash is real damage; a row that merely changed
     * READ state is [Durability.LAZY], because it is not.
     */
    private fun setMessages(next: Map<String, List<ChatMessage>>, durability: Durability = Durability.PROMPT) {
        _messages.value = next
        store.update(durability) { it.copy(messages = next) }
    }

    /** iOS `contacts = …; store.update { $0.contacts = self.contacts }` */
    private fun setContacts(next: List<Contact>) {
        _contacts.value = next
        store.update { it.copy(contacts = next) }
    }

    private fun play(tone: Tone) {
        if (settings.soundsOn) soundHook?.invoke(tone)
    }

    /**
     * Test/lifecycle hygiene: `shutdown()` then release the actor thread. Never call from the
     * service thread itself.
     */
    override fun close() {
        check(Thread.currentThread() !== actorThread) { "ChatService.close() must not run on the service thread" }
        runBlocking { shutdown() }
        scope.cancel()
        executor.shutdown()
    }

    companion object {
        /** iOS `healThrottle` */
        const val HEAL_THROTTLE_SECONDS = 60L

        /** `ChatService.swift:798` — every bundle fetch has a 15 s deadline. */
        const val BUNDLE_FETCH_TIMEOUT_MS = 15_000L

        /** A `deliver` got no verdict: 15 s passed, or its connection ended first (docs/SEALED-SENDER.md §5.5). */
        const val NO_VERDICT = "no_verdict"
        const val NO_PRIVATE_CONNECTION = "no_private_connection"
        const val VERDICT_DEADLINE_MS = 15_000L
        const val MAX_ANON_REOPENS = 2
        const val UPLOAD_ANSWER_TIMEOUT_MS = 60_000L
        const val ANON_OPEN_TIMEOUT_MS = 10_000L

        /** `ChatService.swift:709` — delete-for-both waits 8 s for the relay verdict. */
        const val CONTROL_ACK_TIMEOUT_MS = 8_000L

        /** Android addition: the register / challenge / ready waits never hang an unlock. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        /** `ChatService.swift:945` — a typing signal older than this (relay clock) is a replay. */
        const val TYPING_STALE_MS = 10_000L

        /** `ChatService.swift:1638` */
        const val TYPING_EXPIRY_SECONDS = 6L

        /** `platform` on `auth` / `push_token` (PROTOCOL.md §11); iOS sends none (= apns). */
        const val PUSH_PLATFORM = "fcm"

        // blob transfers (`ChatService.swift:58-62`)
        /** `blobChunkSize` — one binary frame carries at most this much ciphertext. */
        const val BLOB_CHUNK_SIZE = 64 * 1024
        /** `maxBlobCiphertextBytes` — mirrors the relay's `limit.maxBlobBytes` (per frame AND cumulative per id). */
        const val MAX_BLOB_CIPHERTEXT_BYTES = 16 * 1024 * 1024
        /** `maxFilePlaintextBytes` — `sendFile` refuses larger data with `file_too_large`. */
        const val MAX_FILE_PLAINTEXT_BYTES = 12 * 1024 * 1024
        /** `maxBlobChunks` — a manifest announcing more is junk. */
        const val MAX_BLOB_CHUNKS = 257
        /** `ChatService.swift:1643` — partial transfers older than this are discarded by the sweeper. */
        const val STALE_BLOB_SECONDS = 48L * 3600
        /** `attachmentCache.totalCostLimit` */
        const val ATTACHMENT_CACHE_BYTES = 64L * 1024 * 1024
        /** A roster control (GROUPS.md §6b) is re-sent this many times across outages before it is dropped. */
        const val MAX_CONTROL_ATTEMPTS = 40
        /** `group_burn`: 3 attempts, each with the 8 s verdict deadline (agreed with iOS). */
        const val BURN_CONTROL_ATTEMPTS = 3

        private fun goneByGroup(groups: List<Group>): Map<String, Set<String>> =
            groups.associate { g -> g.id to g.members.filter { it.isGone }.map { it.hash }.toSet() }.filterValues { it.isNotEmpty() }

        private fun formerByGroup(groups: List<Group>): Map<String, List<Member>> =
            groups.associate { g -> g.id to (g.former ?: emptyList()) }.filterValues { it.isNotEmpty() }

        /** iOS `"[voice \(Int(duration))s]"` — truncates, never rounds. */
        fun voicePlaceholder(duration: Double): String = "[voice ${duration.toInt()}s]"

        /** Wire message ids: lowercase UUID strings (`UUID().uuidString.lowercased()`). */
        fun newMessageId(): String = UUID.randomUUID().toString().lowercase()

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        /**
         * Registration rule (mirrors the auth screen): letters/digits/underscore, 3–24 —
         * counted and classified per grapheme cluster like Swift `String.count` /
         * `Character.isLetter || isNumber`.
         */
        fun isValidUsername(u: String): Boolean {
            val clusters = graphemes(u)
            return clusters.size in 3..24 && clusters.all(::isUsernameCluster)
        }

        /** Same charset the registration screen enforces — a peer cannot smuggle UI-confusing characters into their label. */
        fun sanitizeDisplayName(raw: String?): String? {
            if (raw == null) return null
            val kept = graphemes(raw).filter(::isUsernameCluster).take(24)
            return if (kept.size >= 3) kept.joinToString("") else null
        }

        /** iOS `MirrorEngine.sanitizeBrowserName`: letters/digits/space, trimmed, ≤ 32 clusters, null if empty. */
        fun sanitizeBrowserName(raw: String): String? {
            val kept = graphemes(raw).filter { c ->
                val cp = c.codePointAt(0)
                cp == ' '.code || Character.isAlphabetic(cp) || isNumberCodePoint(cp)
            }.joinToString("").trim(' ')
            val clipped = graphemes(kept).take(32).joinToString("")
            return clipped.ifEmpty { null }
        }

        /** Swift `Character.isLetter || isNumber || == "_"` — the FIRST scalar of the cluster decides. */
        private fun isUsernameCluster(cluster: String): Boolean {
            val cp = cluster.codePointAt(0)
            return cp == '_'.code || Character.isAlphabetic(cp) || isNumberCodePoint(cp)
        }

        /** Swift `Character.isNumber` ≈ Unicode Nd / Nl / No (CJK numerals are Alphabetic anyway). */
        private fun isNumberCodePoint(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }

        /** Grapheme clusters, as Swift `String` counts characters. */
        private fun graphemes(s: String): List<String> {
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val out = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                out += s.substring(start, end)
                start = end
                end = it.next()
            }
            return out
        }
    }
}
