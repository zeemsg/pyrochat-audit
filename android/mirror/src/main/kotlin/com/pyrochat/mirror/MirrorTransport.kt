package com.pyrochat.mirror

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine's view of its second relay socket: an ordered stream of text frames in, text frames
 * out. iOS uses a second `RelayClient`; here the OkHttp implementation is [OkHttpMirrorTransport]
 * and tests substitute an in-memory pipe. Binary frames are ignored (the pair channel is text-only).
 */
interface MirrorTransport {
    sealed interface Event {
        /** Socket open — iOS `RelayClient.Event.connected` (the engine answers with `pair_join`). */
        data object Connected : Event
        /** One relay text frame, undecoded. */
        data class Text(val text: String) : Event
        /** The socket died or the relay closed it. Never emitted after an explicit [close]. */
        data class Disconnected(val cause: Throwable?) : Event
    }

    /** Ordered inbound events, buffered so nothing yielded before the consumer starts is lost. */
    val events: ReceiveChannel<Event>

    /** Open the socket; returns once the WebSocket handshake completed, throws if it failed. */
    suspend fun connect()

    /** Enqueue one text frame (ordered, non-blocking). False once the socket is gone. */
    fun send(text: String): Boolean

    /** Normal closure; yields no [Event.Disconnected]. */
    fun close()
}

/** Factory the engine uses to dial `relayUrl` under the user's proxy policy. */
fun interface MirrorTransportFactory {
    fun create(relayUrl: String, socksProxy: String?): MirrorTransport
}

/**
 * OkHttp WebSocket transport (the same shape as core-service's `RelayClient`). One instance per
 * connection: after [close] or a [MirrorTransport.Event.Disconnected] the channel is closed for good.
 */
class OkHttpMirrorTransport(private val url: String, private val http: OkHttpClient) : MirrorTransport {
    private val channel = Channel<MirrorTransport.Event>(Channel.UNLIMITED)
    private val socket = AtomicReference<WebSocket?>(null)
    private val closedByUs = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    override val events: ReceiveChannel<MirrorTransport.Event> get() = channel

    override suspend fun connect() {
        check(socket.get() == null) { "connect() called twice" }
        val opened = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                channel.trySend(MirrorTransport.Event.Connected)
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                channel.trySend(MirrorTransport.Event.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(MirrorTransport.Event.Disconnected(null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                finish(MirrorTransport.Event.Disconnected(t))
            }
        })
        socket.set(ws)
        try {
            opened.await()
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
    }

    override fun send(text: String): Boolean = socket.get()?.send(text) ?: false

    override fun close() {
        closedByUs.set(true)
        socket.getAndSet(null)?.close(1000, null)
        channel.close()
    }

    private fun finish(event: MirrorTransport.Event.Disconnected) {
        if (!finished.compareAndSet(false, true)) return
        if (!closedByUs.get()) channel.trySend(event)
        channel.close()
        socket.set(null)
    }

    companion object : MirrorTransportFactory {
        /** Default factory: `MirrorHttp.makeClient(socksProxy)` per link (a fresh client honours a changed proxy setting). */
        override fun create(relayUrl: String, socksProxy: String?): MirrorTransport =
            OkHttpMirrorTransport(relayUrl, MirrorHttp.makeClient(socksProxy))
    }
}
