package com.pyrochat.service

import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Push probe: registers a throwaway sender on the relay named by `PYRO_PUSH_RELAY`
 * (default production) and sends one text to `PYRO_PUSH_TARGET`, whose app must be
 * closed at that moment — the relay then has to wake it with a push. Skipped unless
 * the target is given. Run:
 *   PYRO_PUSH_TARGET=<user> ./gradlew :core-service:test --tests '*ProdPushProbeTest*'
 */
class ProdPushProbeTest {
    private val target: String? = System.getenv("PYRO_PUSH_TARGET")?.takeIf { it.isNotBlank() }
    /** `delivered` waits for the DELIVERED receipt (proves the target's background wake drained its queue). */
    private val expect: String = System.getenv("PYRO_PUSH_EXPECT")?.takeIf { it.isNotBlank() } ?: "sent"
    private val relay: String = System.getenv("PYRO_PUSH_RELAY")?.takeIf { it.isNotBlank() } ?: "wss://relay.pyrochat.ca/ws"

    @Test fun sendsOneTextToTheTarget(): Unit = runBlocking {
        Assumptions.assumeTrue(target != null, "SKIPPED: set PYRO_PUSH_TARGET=<username>")
        val me = Live.makeService("ps", relayUrl = relay)
        try {
            me.svc.register(me.username)
            println("PUSH PROBE: <${me.username}> registered on $relay")
            me.svc.addContact(target!!)
            val peer = me.svc.contacts.value.first { it.name == target }
            val text = "wake probe ${System.currentTimeMillis() / 1000}"
            me.svc.sendText(text, peer)
            assertTrue(Live.waitUntil(if (expect == "delivered") 45_000 else 20_000) {
                val s = me.svc.messages.value[peer.hash]?.firstOrNull { it.text == text }?.status
                if (expect == "delivered") s == Status.DELIVERED || s == Status.READ
                else s == Status.SENT || s == Status.DELIVERED || s == Status.READ
            }, if (expect == "delivered") "the target must wake in the background and send DELIVERED" else "the relay must accept the message")
            println("PUSH PROBE: '$text' → ${me.svc.messages.value[peer.hash]!!.first { it.text == text }.status} at ${java.time.LocalTime.now()}")
        } finally {
            runCatching { me.svc.deregister() }   // leave nothing behind on the relay
            me.close()
        }
    }
}
