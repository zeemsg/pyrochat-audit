package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `ios/Tests/ReceiptTests.swift`: receipts + synced burn timers against the live relay. */
class ReceiptTest {
    @BeforeEach
    fun requireRelay() = Live.requireRelay()

    @Test
    fun `delivered receipt advances the sender to DELIVERED`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("receipt check", alice.svc.contacts.value.first())
            assertTrue(waitUntil {
                alice.svc.messages.value[bob.hash]?.any { it.direction == Direction.OUTGOING && it.status == Status.DELIVERED } == true
            }, "sender must reach DELIVERED without any read")
            // the receipt itself must not create rows on either side
            assertEquals(1, bob.svc.messages.value[alice.hash]?.size)
            assertEquals(1, alice.svc.messages.value[bob.hash]?.size)
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `offline queue delivers and the receipt outbox drains on reconnect`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)

            bob.svc.goOffline()
            delay(300)
            alice.svc.sendText("catch up later", alice.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.status == Status.SENT } == true })

            bob.svc.goOnline()
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.status == Status.DELIVERED } == true },
                "queued message + receipt outbox must deliver after reconnect")
            assertTrue(bob.svc.messages.value[alice.hash]?.any { it.text == "catch up later" } == true)
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `read receipt reciprocity - both toggles on`(): Unit = runBlocking {
        val alice = Live.makeService("al") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val bob = Live.makeService("bo") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("read me", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            // simulate the row becoming visible in bob's open chat
            val msg = bob.svc.messages.value[alice.hash]!!.first()
            bob.svc.noteRowVisible(msg.id, alice.hash)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.status == Status.READ } == true },
                "with both toggles on, READ must reach the sender")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `read receipt is not sent when the toggle is off`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo") // defaults: read receipts OFF
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("private read", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            val msg = bob.svc.messages.value[alice.hash]!!.first()
            bob.svc.noteRowVisible(msg.id, alice.hash)
            delay(2_000)
            val aliceRow = alice.svc.messages.value[bob.hash]!!.first { it.direction == Direction.OUTGOING }
            assertEquals(Status.DELIVERED, aliceRow.status, "toggle off ⇒ sender must never see READ")
            // but bob's local copy IS marked read (state consistency + unread badge)
            assertEquals(Status.READ, bob.svc.messages.value[alice.hash]!!.first().status)
            assertEquals(0, bob.svc.unreadCount(alice.hash))
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `synced burn starts on read, not on arrival`(): Unit = runBlocking {
        val alice = Live.makeService("al") { it.copy(settings = it.settings.copy(defaultBurnSeconds = 2.0)) }
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("synced burn", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            // NOT read yet: the message must survive well past burnAfter
            delay(3_000)
            assertTrue(bob.svc.messages.value[alice.hash]?.any { it.text == "synced burn" } == true, "unread burn message must NOT burn on arrival")
            assertTrue(alice.svc.messages.value[bob.hash]?.any { it.text == "synced burn" } == true, "sender copy must NOT burn before the peer reads")

            // bob reads it -> both countdowns start (burn_start control to alice)
            val msg = bob.svc.messages.value[alice.hash]!!.first()
            assertNull(msg.burnStartedAt)
            bob.svc.noteRowVisible(msg.id, alice.hash)
            assertTrue(waitUntil(8_000) {
                val bobGone = bob.svc.messages.value[alice.hash]?.any { it.text == "synced burn" } != true
                val aliceGone = alice.svc.messages.value[bob.hash]?.any { it.text == "synced burn" } != true
                bobGone && aliceGone
            }, "after read, both copies must burn together")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `no downgrade and unknown ids ignored`(): Unit = runBlocking {
        val alice = Live.makeService("al") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val bob = Live.makeService("bo") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("upgrade only", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })
            val msg = bob.svc.messages.value[alice.hash]!!.first()
            bob.svc.noteRowVisible(msg.id, alice.hash)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.status == Status.READ } == true })

            // a late delivered receipt (relay redelivery) must not downgrade READ
            val before = alice.svc.messages.value[bob.hash]!!
            delay(1_000)
            assertEquals(Status.READ, alice.svc.messages.value[bob.hash]!!.first { it.direction == Direction.OUTGOING }.status,
                "READ is terminal — no downgrade ever")
            assertEquals(before.size, alice.svc.messages.value[bob.hash]!!.size, "receipts never create rows")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `a control from a stranger creates nothing`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val charlie = Live.makeService("ch")
        try {
            alice.svc.register(alice.username); charlie.svc.register(charlie.username)
            // charlie has a session with alice but no conversation
            charlie.svc.addContact(alice.username)
            charlie.svc.burnThread(charlie.svc.contacts.value.first()) // sends control to alice
            delay(2_000)
            assertFalse(alice.svc.contacts.value.any { it.hash == charlie.hash }, "a control from a stranger must not create a contact")
            assertTrue(alice.svc.messages.value[charlie.hash]?.isEmpty() ?: true, "no phantom thread from stranger controls")
            alice.svc.goOffline(); charlie.svc.goOffline()
        } finally {
            alice.close(); charlie.close()
        }
    }

    /**
     * `ChatService.swift:945`: a typing signal replayed from the relay queue after a reconnect
     * (relay `ts` older than 10 s) is dropped; a fresh one is shown.
     */
    @Test
    fun `typing - fresh signal shown, stale replay from the queue dropped`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            bob.svc.addContact(alice.username) // bob must know alice for the typing control to pass the stranger guard
            alice.svc.sendText("open the channel", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            bob.svc.goOffline()
            delay(300)
            alice.svc.noteComposerActivity(bob.hash) // queued for bob with ttl 60
            delay(11_000) // older than the 10 s staleness window by the time bob drains it

            bob.svc.goOnline()
            delay(2_500)
            assertFalse(bob.svc.typingPeers.value.containsKey(alice.hash), "a typing signal replayed from the queue must not show a phantom 'typing…'")

            alice.svc.noteComposerActivity(bob.hash) // > 4 s after the first: passes the throttle
            assertTrue(waitUntil { bob.svc.typingPeers.value.containsKey(alice.hash) }, "a live typing signal must reach the peer")
            assertTrue(waitUntil(10_000) { !bob.svc.typingPeers.value.containsKey(alice.hash) }, "typing row must expire after ~6 s of silence")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
