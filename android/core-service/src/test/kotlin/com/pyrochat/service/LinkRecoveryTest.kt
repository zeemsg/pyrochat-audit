package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A network that loses the connection without saying so (iOS `ReconnectTests`): the sockets stay
 * open and bytes stop moving, so the OS reports nothing. The app must notice by itself and come
 * back by itself, without a tap on [retry].
 */
class LinkRecoveryTest {
    /** A TCP gate in front of a private relay. */
    private class Gate(private val relayPort: Int) : AutoCloseable {
        enum class Mode {
            OPEN,
            /** TCP connects, then nothing moves in either direction. */
            SILENT,
            /** The WebSocket opens, then the relay's replies are lost. */
            UPGRADE_ONLY,
        }

        @Volatile var mode = Mode.OPEN
        val accepted = AtomicInteger()
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val open = CopyOnWriteArrayList<Socket>()
        val url: String get() = "ws://127.0.0.1:${server.localPort}/ws"

        init {
            thread(isDaemon = true, name = "gate-accept") {
                try {
                    while (true) connect(server.accept())
                } catch (_: IOException) {
                    // closed
                }
            }
        }

        private fun connect(client: Socket) {
            accepted.incrementAndGet()
            val relay = Socket("127.0.0.1", relayPort)
            open += client
            open += relay
            pump(client, relay, fromRelay = false)
            pump(relay, client, fromRelay = true)
        }

        private fun pump(from: Socket, to: Socket, fromRelay: Boolean) = thread(isDaemon = true, name = "gate-pump") {
            val buffer = ByteArray(65_536)
            var chunks = 0
            try {
                while (true) {
                    val n = from.getInputStream().read(buffer)
                    if (n < 0) break
                    chunks++
                    val pass = when (mode) {
                        Mode.OPEN -> true
                        Mode.SILENT -> false
                        Mode.UPGRADE_ONLY -> !fromRelay || chunks == 1
                    }
                    if (pass) to.getOutputStream().run { write(buffer, 0, n); flush() }
                }
            } catch (_: IOException) {
                // either side went away
            }
            if (mode == Mode.OPEN) runCatching { to.close() }
        }

        override fun close() {
            runCatching { server.close() }
            open.forEach { s -> runCatching { s.close() } }
        }
    }

    @BeforeEach
    fun requireJar() = Assumptions.assumeTrue(
        ThrowawayRelay.available(),
        "SKIPPED: relay/build/libs/pyrochat-relay.jar not built — `cd relay && ./gradlew jar` (never rebuild while a harness relay runs)",
    )

    private fun scenario(timing: RelayLink.Timing, body: suspend (TestUser, Gate) -> Unit): Unit = runBlocking {
        val relay = ThrowawayRelay.start()
        val gate = Gate(relay.port)
        val user = Live.makeService("rc", relayUrl = gate.url, linkTiming = timing)
        try {
            user.svc.register(user.username)
            assertEquals(ConnState.ONLINE, user.svc.connState.value)
            body(user, gate)
        } finally {
            user.close()
            gate.close()
            relay.close()
        }
    }

    /** The reported bug: a login with no reply used to end the attempt until the user tapped [retry]. */
    @Test
    fun `a login that gets no reply retries by itself`() = scenario(RelayLink.Timing(handshakeMs = 1_000)) { user, gate ->
        user.svc.goOffline()
        gate.mode = Gate.Mode.UPGRADE_ONLY
        val before = gate.accepted.get()
        val thrown = assertFailsWith<ServiceError> { user.svc.goOnline() }
        assertEquals("timeout", thrown.code)
        assertEquals(ConnState.OFFLINE, user.svc.connState.value)
        assertTrue(waitUntil(6_000) { gate.accepted.get() > before + 1 }, "no further attempt was made without a tap")

        gate.mode = Gate.Mode.OPEN
        assertTrue(waitUntil(20_000) { user.svc.connState.value == ConnState.ONLINE }, "the link never came back by itself")
    }

    /** The app used to sit at LINK UP on a socket nothing was answering. */
    @Test
    fun `a silent socket is noticed and replaced`() =
        scenario(RelayLink.Timing(keepaliveMs = 1_000, pongTimeoutMs = 2_000, handshakeMs = 2_000)) { user, gate ->
            gate.mode = Gate.Mode.SILENT
            assertTrue(waitUntil(8_000) { user.svc.connState.value != ConnState.ONLINE }, "a socket with no replies still reads as online")

            gate.mode = Gate.Mode.OPEN
            assertTrue(waitUntil(30_000) { user.svc.connState.value == ConnState.ONLINE }, "the link never came back by itself")
        }

    @Test
    fun `an answered ping leaves the link alone`() =
        scenario(RelayLink.Timing(keepaliveMs = 1_000, pongTimeoutMs = 1_000)) { user, gate ->
            val before = gate.accepted.get()
            delay(5_000)
            assertEquals(ConnState.ONLINE, user.svc.connState.value)
            assertEquals(before, gate.accepted.get(), "a healthy link was replaced")
        }

    @Test
    fun `the network coming back skips the backoff`() =
        scenario(RelayLink.Timing(keepaliveMs = 1_000, pongTimeoutMs = 1_000, handshakeMs = 1_000)) { user, gate ->
            gate.mode = Gate.Mode.SILENT
            assertTrue(waitUntil(8_000) { user.svc.connState.value != ConnState.ONLINE })
            delay(6_000) // the backoff has climbed

            gate.mode = Gate.Mode.OPEN
            user.svc.noteNetworkChanged()
            assertTrue(waitUntil(3_000) { user.svc.connState.value == ConnState.ONLINE }, "the network coming back must reconnect at once")
        }

    @Test
    fun `going offline stops the retries and a network change does not undo it`() =
        scenario(RelayLink.Timing(handshakeMs = 1_000)) { user, gate ->
            user.svc.goOffline()
            gate.mode = Gate.Mode.UPGRADE_ONLY
            runCatching { user.svc.goOnline() }
            user.svc.goOffline()
            val before = gate.accepted.get()
            delay(4_000)
            assertEquals(before, gate.accepted.get(), "an explicit goOffline must stop retrying")
            assertEquals(ConnState.OFFLINE, user.svc.connState.value)

            user.svc.noteNetworkChanged()
            delay(1_000)
            assertEquals(before, gate.accepted.get(), "a network change must not undo an explicit goOffline")
        }

    @Test
    fun `a login's deadline never fires once it has succeeded`() =
        scenario(RelayLink.Timing(handshakeMs = 1_000)) { user, gate ->
            user.svc.goOffline()
            user.svc.goOnline()
            val current = gate.accepted.get()
            delay(2_500) // past the deadline of every wait that login used
            assertEquals(ConnState.ONLINE, user.svc.connState.value)
            assertEquals(current, gate.accepted.get(), "no attempt may run after the login")
        }
}
