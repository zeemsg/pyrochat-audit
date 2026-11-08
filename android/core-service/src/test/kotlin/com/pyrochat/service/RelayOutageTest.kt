package com.pyrochat.service

import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Retry / reconnect against a PRIVATE relay that the test can kill: the M1 port of the iOS
 * auto-retry behaviour (`retryRecentFailures` on every successful auth, backoff `min(2^n, 30)`,
 * `noteForegrounded`). Never touches the 7444/7445 harness relays.
 */
class RelayOutageTest {
    @BeforeEach
    fun requireJar() = Assumptions.assumeTrue(
        ThrowawayRelay.available(),
        "SKIPPED: relay/build/libs/pyrochat-relay.jar not built — `cd relay && ./gradlew jar` (never rebuild while a harness relay runs)",
    )

    @Test
    fun `a text that failed during a relay outage is re-sent automatically on reconnect`(): Unit = runBlocking {
        val relay = ThrowawayRelay.start()
        val alice = Live.makeService("al", relayUrl = relay.url)
        val bob = Live.makeService("bo", relayUrl = relay.url)
        val states = CopyOnWriteArrayList<ConnState>()
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()
            alice.svc.sendText("before the outage", bobContact)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text == "before the outage" } == true })
            delay(3_000) // the relay persists its registry every 2 s — both accounts must survive the restart

            // --- outage: SIGKILL the relay ---
            relay.kill()
            assertTrue(waitUntil { alice.svc.connState.value == ConnState.OFFLINE && bob.svc.connState.value == ConnState.OFFLINE },
                "both links must notice the dead socket")
            states += ConnState.OFFLINE
            alice.svc.sendText("during the outage", bobContact)
            assertTrue(waitUntil {
                alice.svc.messages.value[bobContact.hash]?.firstOrNull { it.text == "during the outage" }?.status == Status.FAILED
            }, "a send with the relay gone must FAIL, never sit at pending")
            val failedId = alice.svc.messages.value[bobContact.hash]!!.first { it.text == "during the outage" }.id

            // --- recovery: same port, same registry, EMPTY queue (RAM-only by design) ---
            relay.restart()
            assertTrue(waitUntil(20_000) { alice.svc.connState.value == ConnState.ONLINE }, "backoff loop (1,2,4,… s) must bring alice back")
            assertTrue(waitUntil(20_000) { bob.svc.connState.value == ConnState.ONLINE }, "…and bob")
            states += ConnState.ONLINE
            // retryRecentFailures() re-sent the FAILED row under the SAME id — bob gets exactly one copy
            assertTrue(waitUntil(15_000) { bob.svc.messages.value[alice.hash]?.any { it.id == failedId && it.text == "during the outage" } == true },
                "the failed row must be re-sent automatically after the link comes back")
            assertTrue(waitUntil { alice.svc.messages.value[bobContact.hash]!!.first { it.id == failedId }.status.receiptRank >= Status.SENT.receiptRank })
            assertEquals(1, bob.svc.messages.value[alice.hash]!!.count { it.text == "during the outage" })
            assertEquals(listOf(ConnState.OFFLINE, ConnState.ONLINE), states)
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close(); relay.close()
        }
    }

    @Test
    fun `noteForegrounded pings when online and reconnects immediately when offline`(): Unit = runBlocking {
        val relay = ThrowawayRelay.start()
        val alice = Live.makeService("al", relayUrl = relay.url)
        val sent = CopyOnWriteArrayList<Wire.Out>()
        try {
            alice.svc.wireTap = { sent += it }
            alice.svc.register(alice.username)
            delay(3_000)
            sent.clear()
            alice.svc.noteForegrounded()
            assertTrue(waitUntil { sent.any { it is Wire.Out.Ping } }, "online: a foreground note pings to surface a dead suspended socket")

            relay.kill()
            assertTrue(waitUntil { alice.svc.connState.value == ConnState.OFFLINE })
            // let the backoff climb (1 s, 2 s, 4 s fail against the dead port) so the reset is observable
            delay(6_000)
            relay.restart()
            alice.svc.noteForegrounded() // offline: skip the wait, reconnect NOW with the backoff reset
            assertTrue(waitUntil(5_000) { alice.svc.connState.value == ConnState.ONLINE }, "foreground must force the link back up without waiting out the backoff")
            alice.svc.goOffline()
            assertEquals(ConnState.OFFLINE, alice.svc.connState.value)
            delay(2_000)
            assertEquals(ConnState.OFFLINE, alice.svc.connState.value, "an explicit goOffline never reconnects")
        } finally {
            alice.close(); relay.close()
        }
    }
}
