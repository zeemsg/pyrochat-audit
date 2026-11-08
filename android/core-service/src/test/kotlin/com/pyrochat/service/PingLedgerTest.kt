package com.pyrochat.service

import com.pyrochat.protocol.Wire
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The count behind "the relay has all of it" (docs/SEALED-SENDER.md §5.6): an upload is complete
 * when the pong to a ping written after its last chunk has been routed. The first half needs no
 * relay; the second talks to the dev relay on 127.0.0.1:7444 and skips without it.
 *
 * The same cases are in iOS `PingLedgerTests`.
 */
class PingLedgerTest {

    @Test
    fun `a pong answers the ping with its own number, not the one written after it`() {
        val ledger = PingLedger()
        ledger.written() // a keepalive ping, written before the last chunk and not answered yet
        val mine = ledger.written() // the ping written after the last chunk
        val answered = ledger.waiter(mine)
        ledger.routed() // the keepalive's pong
        assertFalse(answered.isCompleted, "the answer to an earlier ping says nothing about what was written after it")
        ledger.routed()
        assertTrue(answered.isCompleted && !answered.isCancelled)
    }

    @Test
    fun `a pong routed before anyone waits for it still counts, and so does a later one`() {
        val ledger = PingLedger()
        val first = ledger.written()
        ledger.routed()
        assertTrue(ledger.waiter(first).isCompleted, "it was answered before the wait began")
        val second = ledger.written()
        ledger.written()
        ledger.routed()
        ledger.routed() // two more: the second one is past what was asked for
        assertTrue(ledger.waiter(second).isCompleted)
    }

    @Test
    fun `when the socket ends, whoever waits fails, and so does whoever asks afterwards`(): Unit = runBlocking {
        val ledger = PingLedger()
        val waiting = ledger.waiter(ledger.written())
        ledger.close()
        assertFailsWith<IOException> { withTimeout(2_000) { waiting.await() } }
        assertFailsWith<IOException> { withTimeout(2_000) { ledger.waiter(ledger.written()).await() } }
    }

    @Test
    fun `an answer that was routed is kept even after the socket ends`(): Unit = runBlocking {
        val ledger = PingLedger()
        val mine = ledger.written()
        ledger.routed()
        ledger.close()
        ledger.waiter(mine).await() // does not throw: the relay had said so
    }

    @Test
    fun `a waiter that was let go is not answered, and the others are`() {
        val ledger = PingLedger()
        val gone = ledger.waiter(ledger.written())
        val kept = ledger.waiter(ledger.written())
        ledger.forget(gone)
        ledger.routed()
        ledger.routed()
        assertFalse(gone.isCompleted)
        assertTrue(kept.isCompleted)
    }

    // ---------------------------------------------------------------- against the real relay

    @Test
    fun `the relay's answer comes back, and only the answer to this ping counts`(): Unit = runBlocking {
        Live.requireRelay()
        val client = RelayClient(LOCAL_RELAY_URL, RelayHttp.makeClient(null))
        client.connect()
        try {
            /** Route what the relay sends, one frame at a time, as a consumer does. */
            suspend fun routeOne(): Wire.In {
                while (true) {
                    val event = withTimeout(5_000) { client.events.receive() }
                    if (event is RelayEvent.Frame) {
                        if (event.frame is Wire.In.Pong) client.pongRouted()
                        return event.frame
                    }
                }
            }
            // a keepalive ping is on its way when the upload ends
            assertTrue(client.send(Wire.Out.Ping))
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { client.barrier(5_000) }
            assertTrue(routeOne() is Wire.In.Pong)
            repeat(20) { yield() }
            delay(100)
            assertFalse(barrier.isCompleted, "the keepalive's pong is not the answer")
            assertTrue(routeOne() is Wire.In.Pong)
            withTimeout(2_000) { barrier.await() }
        } finally {
            client.close()
        }
    }

    @Test
    fun `no answer in time is said, and a socket that ends says so at once`(): Unit = runBlocking {
        Live.requireRelay()
        val silent = RelayClient(LOCAL_RELAY_URL, RelayHttp.makeClient(null))
        silent.connect()
        try {
            // nobody routes its pong: for the one who waits, the relay never answered
            val began = System.nanoTime()
            assertFailsWith<PingLedger.NoAnswer> { silent.barrier(400) }
            val tookMs = (System.nanoTime() - began) / 1_000_000
            assertTrue(tookMs in 350..3_000, "it waited the time it was given ($tookMs ms)")
        } finally {
            silent.close()
        }
        val ended = RelayClient(LOCAL_RELAY_URL, RelayHttp.makeClient(null))
        ended.connect()
        val barrier = async(start = CoroutineStart.UNDISPATCHED) { runCatching { ended.barrier(30_000) } }
        ended.close()
        val result = withTimeout(2_000) { barrier.await() }
        assertTrue(result.exceptionOrNull() is IOException && result.exceptionOrNull() !is PingLedger.NoAnswer, "the socket ended: ${result.exceptionOrNull()}")
        assertFailsWith<IOException> { ended.barrier(1_000) }
    }
}
