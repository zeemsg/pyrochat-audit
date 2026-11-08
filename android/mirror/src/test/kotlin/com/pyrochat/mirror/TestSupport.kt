package com.pyrochat.mirror

import com.pyrochat.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/** The local dev harness relay (tests only — never compiled into the app). */
const val LOCAL_RELAY_URL = "ws://127.0.0.1:7444/ws"

const val TEST_PAIR_ID = "TESTPAIRID0000000000AA"
const val RELAY_HOST = "127.0.0.1"
const val RELAY_PORT = 7444

val json = Json

fun relayListening(): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), 500); true }
} catch (_: IOException) {
    false
}

fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

// ------------------------------------------------------------------------------------------------
// The browser side of the pair channel — the same crypto as relay/src/main/resources/web/index.html
// (`deriveAll` / `sealRecord` / `openRecord` / `onRecord`), so a test can stand in for the page.
// ------------------------------------------------------------------------------------------------
class TestBrowser(seed: ByteArray = MirrorCrypto.randomPrivateKey()) {
    val priv: ByteArray = seed
    val pub: ByteArray = MirrorCrypto.publicKey(seed)
    var pairId: String = TEST_PAIR_ID
    var keys: MirrorCrypto.Keys? = null
    var phonePub: ByteArray? = null
    var txSeq = 0L
    var rxSeq = 0L
    var gotHello = false
    var hello: JsonObject? = null

    sealed interface Decoded {
        data class Hello(val obj: JsonObject, val plaintext: String) : Decoded
        data class Event(val seq: Long, val obj: JsonObject, val plaintext: String) : Decoded
    }

    /** `renderQR()`: `pyro-link:v1:<pairId>:<b64url(pub)>:<location.host>` */
    fun qrPayload(host: String): String = MirrorLink.encode(pairId, pub, host)

    /** `onRecord()` from the page — strict seq, throws on any integrity failure. */
    fun onRecord(bodyB64: String): Decoded {
        val raw = Base64.getDecoder().decode(bodyB64)
        check(raw.size >= 9) { "short record" }
        val type = raw[0].toInt() and 0xff
        val seq = MirrorCrypto.seqOf(raw)
        val rest = raw.copyOfRange(9, raw.size)
        return when (type) {
            MirrorCrypto.TYPE_HELLO -> {
                check(!gotHello && seq == 0L && rest.size >= 33) { "bad hello framing" }
                val pPub = rest.copyOfRange(0, 32)
                phonePub = pPub
                val ss = MirrorCrypto.agree(priv, pPub)
                val k = MirrorCrypto.deriveKeys(ss, pairId, pPub, pub)
                keys = k
                val pt = MirrorCrypto.open(k.p2b, MirrorCrypto.DIR_P2B, pairId, MirrorCrypto.TYPE_HELLO, 0, rest.copyOfRange(32, rest.size))
                val text = String(pt, Charsets.UTF_8)
                val obj = json.parseToJsonElement(text).jsonObject
                check(obj.str("sas") == k.sas) { "sas mismatch: hello ${obj.str("sas")} vs derived ${k.sas}" }
                gotHello = true
                hello = obj
                Decoded.Hello(obj, text)
            }
            MirrorCrypto.TYPE_DATA -> {
                check(gotHello) { "data before hello" }
                check(seq == rxSeq + 1) { "seq gap: got $seq, expected ${rxSeq + 1}" }
                val pt = MirrorCrypto.open(keys!!.p2b, MirrorCrypto.DIR_P2B, pairId, MirrorCrypto.TYPE_DATA, seq, rest)
                rxSeq = seq
                val text = String(pt, Charsets.UTF_8)
                Decoded.Event(seq, json.parseToJsonElement(text).jsonObject, text)
            }
            else -> error("bad record type $type")
        }
    }

    /** `sealRecord(keys.b2p, pairId, 0x02, 0, {v:1, browser})` — the hello_ack body. */
    fun ackBody(browser: String): String =
        sealBody(MirrorCrypto.TYPE_HELLO_ACK, 0, buildJsonObject { put("v", 1); put("browser", browser) })

    /** `emitCmd(obj)` — next 0x03 record body. */
    fun cmdBody(obj: JsonObject): String {
        txSeq += 1
        return sealBody(MirrorCrypto.TYPE_DATA, txSeq, obj)
    }

    /** A record at an arbitrary type/seq (replay and gap tests). */
    fun sealBody(type: Int, seq: Long, obj: JsonObject): String {
        val plain = json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(
            MirrorCrypto.seal(keys!!.b2p, MirrorCrypto.DIR_B2P, pairId, type, seq, plain),
        )
    }
}

// ------------------------------------------------------------------------------------------------
// A ChatService stand-in: in-memory contacts/messages + channels recording every callback.
// ------------------------------------------------------------------------------------------------
class FakeSource(
    override val myUsername: String = "andro",
    override var relayUrl: String = LOCAL_RELAY_URL,
    override var socksProxy: String? = null,
    override var showRead: Boolean = true,
) : MirrorSource {
    private val lock = Any()
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1024)
    override val changes: Flow<Unit> = flow

    private var contactList: List<MirrorContact> = emptyList()
    private var groupList: List<MirrorGroup> = emptyList()
    private var threads: Map<String, List<MirrorMessage>> = emptyMap()
    private var typing: Set<String> = emptySet()
    private var groupTypingMap: Map<String, Set<String>> = emptyMap()
    private var blocked: Set<String> = emptySet()
    @Volatile
    var online: Boolean = true

    /** (text, thread) for every `sendText`; (id, thread) for every `noteRowVisible`; thread for typing. */
    val sentTexts = Channel<Pair<String, String>>(Channel.UNLIMITED)
    val visible = Channel<Pair<String, String>>(Channel.UNLIMITED)
    val composer = Channel<String>(Channel.UNLIMITED)
    /** (thread, up, browser) for every `sendWebLink` — MIRROR-GROUPS.md §5, one conversation at a time. */
    val webLinks = Channel<Triple<String, Boolean, String?>>(Channel.UNLIMITED)
    var thumbnails: (MirrorMessage) -> ByteArray? = { null }
    val thumbRequests = Channel<String>(Channel.UNLIMITED)

    /** Default `sendText`: append the outgoing row the service would create (pending, viaWeb). */
    var onSendText: suspend (String, String) -> Unit = { text, thread ->
        append(thread, MirrorMessage(
            id = UUID.randomUUID().toString(), direction = MirrorDirection.outgoing, kind = MirrorKind.text,
            text = text, timestamp = System.currentTimeMillis(), status = MirrorStatus.pending, viaWeb = true,
        ))
    }

    fun changed() { flow.tryEmit(Unit) }

    fun addContact(c: MirrorContact) = synchronized(lock) { contactList = contactList + c }.also { changed() }
    fun removeContact(hash: String) = synchronized(lock) {
        contactList = contactList.filter { it.hash != hash }
        threads = threads - hash
    }.also { changed() }
    fun renameContact(hash: String, name: String) = synchronized(lock) {
        contactList = contactList.map { if (it.hash == hash) it.copy(name = name) else it }
    }.also { changed() }
    fun append(thread: String, m: MirrorMessage) = synchronized(lock) {
        threads = threads + (thread to ((threads[thread] ?: emptyList()) + m))
    }.also { changed() }
    fun replace(thread: String, m: MirrorMessage) = synchronized(lock) {
        threads = threads + (thread to (threads[thread] ?: emptyList()).map { if (it.id == m.id) m else it })
    }.also { changed() }
    fun delete(thread: String, id: String) = synchronized(lock) {
        threads = threads + (thread to (threads[thread] ?: emptyList()).filter { it.id != id })
    }.also { changed() }
    fun setTyping(peers: Set<String>) = synchronized(lock) { typing = peers }.also { changed() }
    fun addGroup(g: MirrorGroup) = synchronized(lock) { groupList = groupList + g }.also { changed() }
    /** Upsert by id (rename, roster change, `left`). */
    fun replaceGroup(g: MirrorGroup) = synchronized(lock) { groupList = groupList.map { if (it.id == g.id) g else it } }.also { changed() }
    /** `group_burn` on this phone: rows, roster, everything gone. */
    fun removeGroup(id: String) = synchronized(lock) {
        groupList = groupList.filter { it.id != id }
        threads = threads - id
    }.also { changed() }
    fun setGroupTyping(groupId: String, members: Set<String>) = synchronized(lock) {
        groupTypingMap = if (members.isEmpty()) groupTypingMap - groupId else groupTypingMap + (groupId to members)
    }.also { changed() }
    fun goOnline(up: Boolean) { online = up; changed() }
    fun block(hash: String) = synchronized(lock) { blocked = blocked + hash }.also { changed() }

    override fun contacts(): List<MirrorContact> = synchronized(lock) { contactList }
    override fun messages(thread: String): List<MirrorMessage> = synchronized(lock) { threads[thread] ?: emptyList() }
    override fun typingPeers(): Set<String> = synchronized(lock) { typing }
    override fun groups(): List<MirrorGroup> = synchronized(lock) { groupList }
    override fun groupTyping(): Map<String, Set<String>> = synchronized(lock) { groupTypingMap }
    override fun isOnline(): Boolean = online
    override fun isBlocked(hash: String): Boolean = synchronized(lock) { hash in blocked }
    override suspend fun sendText(text: String, thread: String) {
        sentTexts.trySend(text to thread)
        onSendText(text, thread)
    }
    override fun noteRowVisible(messageId: String, thread: String) { visible.trySend(messageId to thread) }
    override fun noteComposerActivity(thread: String) { composer.trySend(thread) }
    override fun thumbnailJpeg(message: MirrorMessage): ByteArray? {
        thumbRequests.trySend(message.id)
        return thumbnails(message)
    }
    override fun sendWebLink(thread: String, up: Boolean, browser: String?) { webLinks.trySend(Triple(thread, up, browser)) }
}

fun textRow(id: String, dir: MirrorDirection, text: String, ts: Long, status: MirrorStatus = MirrorStatus.delivered, viaWeb: Boolean = false) =
    MirrorMessage(id, dir, MirrorKind.text, text, ts, status, viaWeb = viaWeb)

// ------------------------------------------------------------------------------------------------
// In-memory transport + fake relay: the test plays relay AND browser.
// ------------------------------------------------------------------------------------------------
class MemoryTransport : MirrorTransport {
    private val inbound = Channel<MirrorTransport.Event>(Channel.UNLIMITED)
    val outbound = Channel<String>(Channel.UNLIMITED)
    @Volatile
    var closed = false
    var connectFails = false
    val closedSignal = CompletableDeferred<Unit>()

    override val events: ReceiveChannel<MirrorTransport.Event> get() = inbound
    override suspend fun connect() {
        if (connectFails) throw IOException("connection refused")
        inbound.trySend(MirrorTransport.Event.Connected)
    }
    override fun send(text: String): Boolean = if (closed) false else outbound.trySend(text).isSuccess
    override fun close() { closed = true; inbound.close(); closedSignal.complete(Unit) }

    fun push(text: String) { inbound.trySend(MirrorTransport.Event.Text(text)) }
    fun drop(cause: Throwable? = null) { inbound.trySend(MirrorTransport.Event.Disconnected(cause)) }
}

class Harness(
    val browser: TestBrowser = TestBrowser(),
    debounceMs: Long = 30,
    clock: () -> Long = System::currentTimeMillis,
    /** Share one service stand-in between two harnesses to relink the same phone to a fresh page. */
    val source: FakeSource = FakeSource(),
) {
    val transport = MemoryTransport()
    /** Set before a second `start()` so the replacing session gets its own socket. */
    @Volatile
    var nextTransport: MemoryTransport? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = MirrorEngine(
        scope,
        MirrorEngine.Options(debounceMs = debounceMs, pingIntervalMs = 3_600_000, clock = clock),
        MirrorTransportFactory { _, _ -> nextTransport?.also { nextTransport = null } ?: transport },
    )
    val link: MirrorLink get() = MirrorLink.parse(browser.qrPayload("$RELAY_HOST:$RELAY_PORT"), source.relayUrl)

    suspend fun nextRaw(timeoutMs: Long = 5_000): String = withTimeout(timeoutMs) { transport.outbound.receive() }

    /** Next client→relay frame as a JSON object, asserting its `t`. */
    suspend fun expectOut(t: String, timeoutMs: Long = 5_000): JsonObject {
        val raw = nextRaw(timeoutMs)
        val obj = json.parseToJsonElement(raw).jsonObject
        assertEquals(t, obj.str("t"), "unexpected frame $raw")
        return obj
    }

    /** Next 0x03 record the phone sent, decoded by the browser (throws on seq/GCM failure). */
    suspend fun nextEvent(timeoutMs: Long = 5_000): TestBrowser.Decoded.Event {
        val frame = expectOut("pair_msg", timeoutMs)
        assertEquals(browser.pairId, frame.str("pair"))
        return browser.onRecord(frame.str("body")) as TestBrowser.Decoded.Event
    }

    fun pushRelay(obj: JsonObject) = transport.push(json.encodeToString(JsonObject.serializer(), obj))
    fun pushPairJoined() = pushRelay(buildJsonObject { put("t", "pair_joined"); put("pair", browser.pairId) })
    fun pushPairMsg(body: String) = pushRelay(buildJsonObject { put("t", "pair_msg"); put("pair", browser.pairId); put("body", body) })
    fun pushCmd(obj: JsonObject) = pushPairMsg(browser.cmdBody(obj))

    /** start → pair_join → pair_joined → hello → ack. Returns the hello the browser decrypted. */
    suspend fun handshake(browserLabel: String = "Test on JVM"): TestBrowser.Decoded.Hello {
        val sas = engine.start(link, source)
        val join = expectOut("pair_join")
        assertEquals(browser.pairId, join.str("pair"))
        pushPairJoined()
        val helloFrame = expectOut("pair_msg")
        val hello = browser.onRecord(helloFrame.str("body")) as TestBrowser.Decoded.Hello
        assertEquals(sas, browser.keys!!.sas, "phone SAS must equal the browser's derived SAS")
        pushPairMsg(browser.ackBody(browserLabel))
        return hello
    }

    /** Read events through `snapshot_end`. */
    suspend fun drainSnapshot(): List<TestBrowser.Decoded.Event> {
        val out = ArrayList<TestBrowser.Decoded.Event>()
        while (true) {
            val e = nextEvent()
            out.add(e)
            if (e.obj.str("e") == "snapshot_end") return out
        }
    }

    suspend fun awaitState(timeoutMs: Long = 5_000, pred: (MirrorEngine.State) -> Boolean): MirrorEngine.State =
        withTimeout(timeoutMs) { engine.state.first(pred) }

    /** After a teardown the phone must send exactly `pair_close` (after the bye) and close the socket. */
    suspend fun expectPairCloseAndSocketClose() {
        val close = expectOut("pair_close")
        assertEquals(browser.pairId, close.str("pair"), "pair_close must name the pair like iOS")
        withTimeout(5_000) { transport.closedSignal.await() }
    }

    fun close() { scope.cancel() }
}

// ------------------------------------------------------------------------------------------------
// A raw text WebSocket for the live tests (the browser's or a CDP socket).
// ------------------------------------------------------------------------------------------------
class WsText(private val url: String, private val http: OkHttpClient) {
    val inbound = Channel<String>(Channel.UNLIMITED)
    private var ws: WebSocket? = null
    val closed = CompletableDeferred<Pair<Int?, String?>>()

    suspend fun connect() {
        val opened = CompletableDeferred<Unit>()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { inbound.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(code to reason); inbound.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t); closed.complete(null to t.message); inbound.close()
            }
        })
        withTimeout(10_000) { opened.await() }
    }

    fun send(text: String) { ws!!.send(text) }
    fun send(obj: JsonObject) = send(json.encodeToString(JsonObject.serializer(), obj))
    fun close() { ws?.close(1000, null) }

    suspend fun next(timeoutMs: Long = 10_000): String = withTimeout(timeoutMs) { inbound.receive() }

    /** Next frame with this `t`, skipping `pong`s. */
    suspend fun expect(t: String, timeoutMs: Long = 10_000): JsonObject {
        while (true) {
            val obj = json.parseToJsonElement(next(timeoutMs)).jsonObject
            val got = obj.str("t")
            if (got == "pong") continue
            if (got != t) fail("expected $t, got $obj")
            return obj
        }
    }
}

fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
