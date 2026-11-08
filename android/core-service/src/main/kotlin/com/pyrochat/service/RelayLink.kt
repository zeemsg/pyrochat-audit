package com.pyrochat.service

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/** iOS `ChatService.ConnState` — the link status the UI renders (LINK DOWN / SYNC / LINK UP). */
enum class ConnState { OFFLINE, CONNECTING, AUTHENTICATING, ONLINE }

/**
 * The relay link with a lifecycle — the transport half of iOS `ChatService` (`openRelay`,
 * `handle(.connected/.disconnected)`, `startKeepalive`, `noteForegrounded`, `goOffline`,
 * `shutdown`) layered over the one-socket [RelayClient]. Owns successive sockets, the connection
 * state, the keepalive ping and the reconnect backoff; the PROTOCOL half (login handshake, frame
 * routing) stays in `ChatService`, which plugs in through [onEvent] and [reconnect].
 *
 * Confinement: every member must be called on the owner's single-threaded [scope] (the
 * `ChatService` actor thread); nothing here locks.
 *
 * Every socket is built from [RelayHttp.makeClient] with the CURRENT `settings.socksProxy`, so a
 * proxy change takes effect on the next (re)connect — exactly `RelayClient(url:socksProxy:)` on iOS.
 */
class RelayLink(
    private val scope: CoroutineScope,
    /** `store.blob.settings.relayURL` / `.socksProxy`, read at every [open]. */
    private val endpoint: () -> Endpoint,
    /** Frames, binary frames, undecodable frames AND the disconnect notification, in order. */
    private val onEvent: suspend (RelayEvent) -> Unit,
    /** iOS `try? await goOnline()` from the backoff loop and from `noteForegrounded`. */
    private val reconnect: suspend () -> Unit,
    private val timing: Timing = Timing(),
) {
    data class Endpoint(val url: String, val socksProxy: String?)

    /** iOS `LinkTiming`: how long the link waits before calling a connection dead. Tests shorten these. */
    data class Timing(
        val keepaliveMs: Long = Protocol.PING_INTERVAL_SECONDS * 1000L,
        /** Long enough for a Tor circuit's round trip. A wrong call only costs a reconnect. */
        val pongTimeoutMs: Long = 15_000L,
        val handshakeMs: Long = ChatService.HANDSHAKE_TIMEOUT_MS,
    )

    private val _state = MutableStateFlow(ConnState.OFFLINE)

    /** iOS `@Published var connState`. */
    val state: StateFlow<ConnState> get() = _state

    var connState: ConnState
        get() = _state.value
        set(value) {
            _state.value = value
        }

    /** iOS `wantOnline`: the user wants the link up; drives reconnects and `noteForegrounded`. */
    var wantOnline: Boolean = false

    /** iOS `reconnectAttempts` — reset by a login and by `noteForegrounded` / `noteNetworkChanged`. */
    var reconnectAttempts: Int = 0
        private set

    /** The current socket (iOS `relay`); null before the first [open] and after [disconnect]/[close]. */
    var socket: RelayClient? = null
        private set

    private var eventJob: Job? = null
    private var keepaliveJob: Job? = null
    private var pongJob: Job? = null
    private var reconnectJob: Job? = null

    /** Observation tap on every frame handed to the socket (tests: envelope types, pings). */
    @Volatile
    var tap: ((Wire.Out) -> Unit)? = null

    /**
     * iOS `openRelay()`: a fresh socket replaces the current one, its event stream is consumed by
     * a new task (the old task is cancelled), and the connection is started. A handshake failure
     * is NOT thrown here — it surfaces as [RelayEvent.Disconnected] through [onEvent], which then
     * fails every pending request and schedules the backoff, exactly like the iOS event path.
     */
    suspend fun open(): RelayClient {
        val ep = endpoint()
        val client = RelayClient(ep.url, RelayHttp.makeClient(ep.socksProxy))
        eventJob?.cancel()
        socket?.close()
        socket = client
        eventJob = scope.launch {
            for (event in client.events) {
                // counted as it is reached: everything that arrived before it has been routed
                if (event is RelayEvent.Frame && event.frame is Wire.In.Pong) client.pongRouted()
                handle(event)
            }
        }
        try {
            client.connect()
        } catch (_: Exception) {
            // the Disconnected event is already queued on the channel
        }
        return client
    }

    private suspend fun handle(event: RelayEvent) {
        when (event) {
            is RelayEvent.Connected -> {} // only a login resets the backoff (noteLoggedIn)
            is RelayEvent.Disconnected -> linkLost(event)
            else -> onEvent(event)
        }
    }

    /** iOS `linkLost()`: the socket is gone, reported or judged dead. Fail what waited on it and retry. */
    private suspend fun linkLost(event: RelayEvent.Disconnected) {
        connState = ConnState.OFFLINE
        stopKeepalive()
        onEvent(event) // ChatService.failAllPending()
        if (!wantOnline) return
        val seconds = min(2.0.pow(reconnectAttempts.toDouble()), MAX_BACKOFF_SECONDS)
        reconnectAttempts += 1
        val lost = socket
        reconnectJob?.cancel()
        // a separate job so that cancelling the old event loop in open() can never cancel the
        // login it is about to run
        reconnectJob = scope.launch {
            delay((seconds * 1000).toLong())
            // a newer attempt (retry tap, foreground, network back) replaced the socket
            if (wantOnline && socket === lost) runCatching { reconnect() }
        }
    }

    /**
     * iOS `authenticateOrRetry`: give up on [client] and take the path of a reported disconnect, so
     * the user never has to tap [retry]. Does nothing once a newer attempt has replaced it.
     */
    suspend fun dropLink(client: RelayClient) {
        if (socket !== client) return
        client.close()
        linkLost(RelayEvent.Disconnected(null, null, "no reply"))
    }

    /** A login completed: the link is proven, so the backoff starts over and the keepalive starts. */
    fun noteLoggedIn() {
        reconnectAttempts = 0
        startKeepalive()
    }

    /** iOS `try await relay.send(frame)`: throws when there is no live socket. */
    fun send(frame: Wire.Out) {
        val s = socket ?: throw IOException("not connected to relay")
        if (!s.send(frame)) throw IOException("relay socket is closed")
        tap?.invoke(frame)
    }

    /** iOS `relay.sendBinary(data)` — blob chunks (M2). */
    fun sendBinary(bytes: ByteArray) {
        val s = socket ?: throw IOException("not connected to relay")
        if (!s.sendBinary(bytes)) throw IOException("relay socket is closed")
        binaryTap?.invoke(bytes)
    }

    /** Observation tap on every binary frame handed to the socket (tests: chunk framing). Never on iOS. */
    @Volatile
    var binaryTap: ((ByteArray) -> Unit)? = null

    /** Bytes enqueued on the current socket but not yet on the wire (0 without a socket). */
    fun queuedBytes(): Long = socket?.queuedBytes() ?: 0L

    /**
     * iOS `try await relay.sendBinary(frame)` for one blob chunk: URLSession's send returns once the
     * frame is written, so the chunk loop (and `blobProgress`) tracks the wire. OkHttp only enqueues,
     * so wait until at most [MAX_INFLIGHT_BYTES] are still queued before handing over the next chunk
     * — the upload can never outrun the socket into OkHttp's 16 MiB hard queue cap.
     */
    suspend fun sendBinaryChunk(bytes: ByteArray) {
        awaitQueueBelow(MAX_INFLIGHT_BYTES)
        sendBinary(bytes)
    }

    /**
     * The same for an upload that began on [client]. It stays on that socket, manifest, chunks and
     * the ping after them, so that the answer to that ping speaks for all of it ([barrier]).
     * Throws once [client] is no longer this link's socket.
     */
    suspend fun sendBinaryChunk(bytes: ByteArray, client: RelayClient) {
        while (true) {
            if (socket !== client) throw IOException("the connection this upload began on is gone")
            if (client.queuedBytes() <= MAX_INFLIGHT_BYTES) break
            delay(QUEUE_POLL_MS)
        }
        if (!client.sendBinary(bytes)) throw IOException("relay socket is closed")
        binaryTap?.invoke(bytes)
    }

    /**
     * The relay's word that it has handled everything [client] wrote so far ([RelayClient.barrier]).
     * No answer is a dead link, as for a keepalive ping: it is dropped, and the login that follows
     * sends again what failed with it.
     */
    suspend fun barrier(client: RelayClient, timeoutMs: Long) {
        if (socket !== client) throw IOException("the connection this upload began on is gone")
        try {
            client.barrier(timeoutMs)
        } catch (e: PingLedger.NoAnswer) {
            dropLink(client)
            throw e
        }
    }

    /** Suspend until the socket's outgoing queue holds at most [limit] bytes; throws once the socket is gone. */
    suspend fun awaitQueueBelow(limit: Long) {
        while (true) {
            val s = socket ?: throw IOException("not connected to relay")
            if (s.queuedBytes() <= limit) return
            delay(QUEUE_POLL_MS)
        }
    }

    /**
     * iOS `startKeepalive()`: the relay idle-closes silent connections after 90 s — a ping every
     * 30 s keeps the link (and any NAT in the path) alive.
     */
    fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(timing.keepaliveMs)
                if (!isActive || connState != ConnState.ONLINE) return@launch
                probe()
            }
        }
    }

    fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        pongJob?.cancel()
        pongJob = null
    }

    /**
     * iOS `probeLink()`: one ping with a deadline. A socket on a dead network can report nothing for
     * minutes while the app says online, so no frame back in time means the link is gone. `send`
     * only enqueues, so blob chunks still queued ahead of the ping hold its reply back: no verdict
     * then. OkHttp's own write timeout catches a socket that stopped taking them.
     */
    private fun probe() {
        if (pongJob?.isActive == true) return // the ping already out keeps its deadline
        val client = socket ?: return
        val seen = client.framesReceived
        runCatching { send(Wire.Out.Ping) }
        pongJob = scope.launch {
            delay(timing.pongTimeoutMs)
            if (socket !== client || connState != ConnState.ONLINE) return@launch
            if (client.framesReceived != seen || client.queuedBytes() > 0) return@launch
            pongJob = null // this job must not cancel itself on the way through linkLost
            dropLink(client)
        }
    }

    /**
     * iOS `noteForegrounded()`: the OS may have suspended the socket while backgrounded, so the
     * backoff loop may still be asleep and a dead socket undetected. Online → a ping either proves
     * the link alive or surfaces the failure (which reconnects); offline → reconnect NOW with the
     * backoff reset.
     */
    fun noteForegrounded() {
        if (!wantOnline) return
        when (connState) {
            ConnState.ONLINE -> probe()
            ConnState.OFFLINE -> {
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = scope.launch { runCatching { reconnect() } }
            }
            else -> {}
        }
    }

    /**
     * iOS `noteNetworkChanged()`: the phone's network came back or switched. Online, the socket may
     * have stayed on the old network, so probe it. Otherwise connect now: an attempt in progress
     * may be hanging on the network that just went away.
     */
    fun noteNetworkChanged() {
        if (!wantOnline) return
        reconnectAttempts = 0
        if (connState == ConnState.ONLINE) {
            probe()
        } else {
            reconnectJob?.cancel()
            reconnectJob = scope.launch { runCatching { reconnect() } }
        }
    }

    /**
     * iOS `goOffline()` transport half (`relay?.disconnect()`): normal closure, no `Disconnected`
     * event, pending backoff cancelled. The event loop ends with the channel.
     */
    fun disconnect() {
        wantOnline = false
        stopKeepalive()
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close()
        socket = null
        connState = ConnState.OFFLINE
    }

    /** iOS `shutdown()` transport half: stop consuming events FIRST, then drop the socket. */
    fun close() {
        eventJob?.cancel()
        eventJob = null
        disconnect()
    }

    companion object {
        /** iOS `min(pow(2, attempts), 30)` seconds. */
        const val MAX_BACKOFF_SECONDS = 30.0

        /** Blob upload backpressure: at most this many bytes queued in OkHttp ahead of the wire (16 chunks). */
        const val MAX_INFLIGHT_BYTES = 1024L * 1024
        const val QUEUE_POLL_MS = 5L
    }
}
