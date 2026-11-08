package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.Contact
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of `ios/Tests/SafetyTests.swift`: block + report abuse against the live relay. */
class SafetyTest {
    @BeforeEach
    fun requireRelay() = Live.requireRelay()

    @Test
    fun `blocked sender is dropped and unblock recovers`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            bob.svc.addContact(alice.username)

            // establish the channel first
            bob.svc.sendText("hello before block", bob.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.size == 1 })

            // alice blocks bob — everything he sends now vanishes silently
            alice.svc.blockContact(bob.hash)
            bob.svc.sendText("you should never see this", bob.svc.contacts.value.first())
            bob.svc.sendText("nor this", bob.svc.contacts.value.first())
            delay(2_000)
            assertEquals(1, alice.svc.messages.value[bob.hash]?.size, "blocked sender's messages must be dropped")
            assertEquals(0, alice.svc.totalUnread, "blocked threads never badge")

            // unblock: NEW messages flow again (ratchet skipped the dropped ones)
            alice.svc.unblockContact(bob.hash)
            bob.svc.sendText("back in the room", bob.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.text == "back in the room" } == true },
                "post-unblock messages must decrypt (ratchet fast-forwards)")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `report and block burns the thread, blocks, and persists across a cold reopen`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            bob.svc.addContact(alice.username)
            bob.svc.sendText("offensive content", bob.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.isNotEmpty() == true })

            alice.svc.reportAndBlock(Contact(bob.hash, "reported", Instant.now()))
            assertTrue(alice.svc.isBlocked(bob.hash))
            assertTrue(alice.svc.messages.value[bob.hash]?.isEmpty() ?: true, "reporting burns the local thread")

            // blocked persists across store reload
            alice.svc.store.flush()
            val cold = alice.reopen()
            assertTrue(cold.blockedHashes?.contains(bob.hash) == true)
            assertEquals(emptyList(), cold.messages[bob.hash])
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
