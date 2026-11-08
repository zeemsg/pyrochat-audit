package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of `ios/Tests/ChatServiceTests.swift` (text subset): two complete ChatServices against the
 * live relay — registration, contact add, send/ack/receive, auto-created contact, self-destruct.
 */
class ChatServiceLifecycleTest {
    @BeforeEach
    fun requireRelay() = Live.requireRelay()

    @Test
    fun `full conversation lifecycle`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username)
            bob.svc.register(bob.username)
            assertEquals(ConnState.ONLINE, alice.svc.connState.value)
            assertEquals(ConnState.ONLINE, bob.svc.connState.value)

            // alice opens the channel (prekey fetch + PQXDH bootstrap)
            alice.svc.addContact(bob.username)
            val contact = alice.svc.contacts.value.first()
            assertEquals(bob.hash, contact.hash)

            // alice -> bob
            alice.svc.sendText("first contact 🕯", contact)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text.startsWith("first contact") } == true },
                "bob must receive and decrypt alice's message")

            // bob auto-created a contact for the unknown sender
            val bobsContactForAlice = bob.svc.contacts.value.firstOrNull { it.hash == alice.hash }
            assertNotNull(bobsContactForAlice)

            // bob -> alice over the established ratchet
            bob.svc.sendText("channel confirmed", bobsContactForAlice)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.text == "channel confirmed" } == true },
                "alice must receive bob's reply")

            // sender-side status advanced to at least SENT (delivered receipts may race past it)
            assertTrue(waitUntil {
                alice.svc.messages.value[bob.hash]?.any {
                    it.direction == Direction.OUTGOING && it.status.receiptRank >= Status.SENT.receiptRank
                } == true
            })
            assertEquals(1, alice.svc.messages.value[bob.hash]!!.count { it.direction == Direction.OUTGOING })
            assertEquals(1, bob.svc.messages.value[alice.hash]!!.count { it.direction == Direction.INCOMING })

            alice.svc.goOffline()
            bob.svc.goOffline()
            assertEquals(ConnState.OFFLINE, alice.svc.connState.value)
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `burn-after timer destroys the message on both sides after it is read`(): Unit = runBlocking {
        val alice = Live.makeService("al") { it.copy(settings = it.settings.copy(defaultBurnSeconds = 2.0)) }
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username)
            bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("this message will burn", alice.svc.contacts.value.first())

            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text.contains("will burn") } == true },
                "message must arrive before it burns")
            val row = bob.svc.messages.value[alice.hash]!!.first { it.text.contains("will burn") }
            assertEquals(2.0, row.burnAfter)
            assertEquals(null, row.burnStartedAt, "synced burn: countdown starts on read, not on arrival")

            // SYNCED BURN: the countdown starts when the recipient READS — simulate the row becoming visible
            bob.svc.noteRowVisible(row.id, alice.hash)

            assertTrue(waitUntil(6_000) {
                val bobGone = bob.svc.messages.value[alice.hash]?.any { it.text.contains("will burn") } != true
                val aliceGone = alice.svc.messages.value[bob.hash]?.any { it.text.contains("will burn") } != true
                bobGone && aliceGone
            }, "burn timer must wipe the message on both devices")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
