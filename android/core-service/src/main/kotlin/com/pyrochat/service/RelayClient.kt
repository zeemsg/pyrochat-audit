package com.pyrochat.service

import com.pyrochat.protocol.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What a [RelayClient] emits — iOS `RelayClient.Event` plus an explicit decode-failure event. */
sealed class RelayEvent {
    data object Connected : RelayEvent()
    data class Frame(val frame: Wire.In) : RelayEvent()
    data class Binary(val bytes: ByteArray) : RelayEvent()
    /** A text frame that failed `Wire.decodeIn` — dropped, like iOS `try? JSONDecoder().decode`. */
    data class Undecodable(val text: String, val error: Throwable) : RelayEvent()
    /** The socket died or the relay closed it. Never emitted for an explicit [RelayClient.close]. */
    data class Disconnected(val cause: Throwable?, val code: Int?, val reason: String?) : RelayEvent()
}

/**
 * What [RelayClient.barrier] counts: the pings written to one socket and the pongs its consumer
 * has routed. The relay takes a connection's frames in the order they were written and answers
 * every ping with one pong (docs/PROTOCOL.md §6), so the pong to the nth ping is its word on
 * everything written before that ping. iOS `PingLedger`.
 */
class PingLedger {
    /** The ping was written and nothing came back in time. */
    class NoAnswer : IOException("no answer from the relay")

    private val lock = Any()
    private var written = 0L
    private var routed = 0L
    private var closed = false
    private val waiters = ArrayList<Pair<Long, CompletableDeferred<Unit>>>()

    /** A ping was written: its number, counted from 1. */
    fun written(): Long = synchronized(lock) { ++written }

    /** One pong has been routed, and with it everything that arrived before it. */
    fun routed() {
        val ready = synchronized(lock) {
            routed += 1
            val done = waiters.filter { it.first <= routed }
            waiters.removeAll { it.first <= routed }
            done
        }
        for ((_, waiter) in ready) waiter.complete(Unit)
    }

    /** Completes once pong number [number] has been routed; fails when the socket ends first. */
    fun waiter(number: Long): CompletableDeferred<Unit> = synchronized(lock) {
        val waiter = CompletableDeferred<Unit>()
        when {
            routed >= number -> waiter.complete(Unit)
            closed -> waiter.completeExceptionally(IOException("relay socket is closed"))
            else -> waiters += number to waiter
        }
        waiter
    }

    /** [waiter] is no longer waited on. */
    fun forget(waiter: CompletableDeferred<Unit>) {
        synchronized(lock) { waiters.removeAll { it.second === waiter } }
    }

    /** The socket is gone: nothing more will be answered on it. */
    fun close() {
        val lost = synchronized(lock) {
            closed = true
            val all = waiters.toList()
            waiters.clear()
            all
        }
        for ((_, waiter) in lost) waiter.completeExceptionally(IOException("relay socket is closed"))
    }
}

/**
 * Minimal WebSocket transport to the relay on OkHttp — one socket, one ordered event channel
 * (iOS `RelayClient.swift`). Reconnect/backoff/keepalive/`noteForegrounded` live one layer up in
 * [RelayLink], which owns successive instances of this class. Use one instance per connection:
 * after [close] or a [RelayEvent.Disconnected] the channel is closed for good.
 */
class RelayClient(val url: String, private val http: OkHttpClient) {
    private val channel = Channel<RelayEvent>(Channel.UNLIMITED)
    private val socket = AtomicReference<WebSocket?>(null)
    private val closedByUs = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val received = AtomicLong(0)

    /** Pings written to this socket and pongs routed from it (see [barrier]). */
    private val pings = PingLedger()
    private val writeLock = Any()

    /**
     * Frames this socket has received, counted as they arrive and not as the event loop gets to
     * them: a reply queued behind a slow decrypt still proves the link is alive.
     */
    val framesReceived: Long get() = received.get()

    /** Ordered inbound events. Buffered, so nothing yielded before the consumer starts is lost. */
    val events: ReceiveChannel<RelayEvent> get() = channel

    /** Open the socket; returns once the WebSocket handshake completed, throws if it failed. */
    suspend fun connect() {
        check(socket.get() == null) { "RelayClient.connect() called twice" }
        val opened = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                channel.trySend(RelayEvent.Connected)
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.incrementAndGet()
                val frame = try {
                    Wire.decodeIn(text)
                } catch (e: Exception) {
                    channel.trySend(RelayEvent.Undecodable(text, e))
                    return
                }
                channel.trySend(RelayEvent.Frame(frame))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.incrementAndGet()
                channel.trySend(RelayEvent.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason) // complete the close handshake
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(RelayEvent.Disconnected(null, code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                finish(RelayEvent.Disconnected(t, null, null))
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

    /** False once the handshake failed or the socket died. */
    val isOpen: Boolean get() = socket.get() != null

    /** Enqueue one JSON frame (OkHttp buffers until open). False once the socket is gone. */
    fun send(frame: Wire.Out): Boolean {
        if (frame !is Wire.Out.Ping) return socket.get()?.send(Wire.encode(frame)) ?: false
        // every ping is counted, in the order written: `barrier` waits by number
        synchronized(writeLock) {
            val ok = socket.get()?.send(Wire.encode(frame)) ?: false
            if (ok) pings.written()
            return ok
        }
    }

    /**
     * Whoever consumes [events] calls this as it reaches a pong, after it has routed every frame
     * that arrived before it.
     */
    fun pongRouted() = pings.routed()

    /**
     * Suspends until the relay has handled everything written to this socket before the call:
     * taken it, or refused it with an `error` that the consumer of [events] has routed by then. A
     * ping is written now, and its own pong is waited for (docs/PROTOCOL.md §6). Throws when the
     * socket ends first, or [PingLedger.NoAnswer] when [timeoutMs] pass with nothing moving: bytes
     * still leaving the queue ahead of the ping are not silence.
     */
    suspend fun barrier(timeoutMs: Long) {
        val answered = synchronized(writeLock) {
            val ws = socket.get() ?: throw IOException("relay socket is closed")
            if (!ws.send(Wire.encode(Wire.Out.Ping))) throw IOException("relay socket is closed")
            pings.waiter(pings.written())
        }
        try {
            var queued = queuedBytes()
            var quietSince = System.nanoTime()
            while (true) {
                if (withTimeoutOrNull(BARRIER_POLL_MS) { answered.await() } != null) return
                val now = System.nanoTime()
                val left = queuedBytes()
                if (left != queued) {
                    queued = left
                    quietSince = now
                }
                if (now - quietSince >= timeoutMs * 1_000_000L) throw PingLedger.NoAnswer()
            }
        } finally {
            pings.forget(answered)
        }
    }

    /** Enqueue one binary frame (blob chunks, PROTOCOL.md §6). */
    fun sendBinary(bytes: ByteArray): Boolean = socket.get()?.send(bytes.toByteString()) ?: false

    /**
     * Bytes enqueued but not yet written to the wire (OkHttp `queueSize()`). OkHttp's `send` only
     * enqueues — and refuses once 16 MiB are queued — whereas iOS `task.send` awaits the write;
     * blob uploads poll this to apply the same backpressure. 0 once the socket is gone.
     */
    fun queuedBytes(): Long = socket.get()?.queueSize() ?: 0L

    /** Normal closure. Like iOS `disconnect()`, yields no `Disconnected`; the channel just ends. */
    fun close() {
        closedByUs.set(true)
        socket.getAndSet(null)?.close(1000, null)
        channel.close()
        pings.close()
    }

    private fun finish(event: RelayEvent.Disconnected) {
        if (!finished.compareAndSet(false, true)) return
        if (!closedByUs.get()) channel.trySend(event)
        channel.close()
        socket.set(null)
        pings.close()
    }

    private companion object {
        const val BARRIER_POLL_MS = 25L
    }
}
