package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.protocol.Protocol
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.SecureStore
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store is written at the iOS write sites (`android/fixtures/ios-store/SPEC.md` §3.1). Every
 * assertion cold-opens the file from disk after the 250 ms debounce — nothing is flushed by hand,
 * so a missing `store.update` would show up as stale bytes.
 */
class PersistenceTest {
    private suspend fun settle() = delay(SecureStore.DEBOUNCE_MS + 200)

    @Test
    fun `libsignal callbacks write through to the store`(): Unit = runBlocking {
        val u = Live.makeService()
        try {
            val engine = CryptoEngine(StoreSignalState.bind(u.svc.store))
            engine.generateIdentity("write_through")
            StoreSignalState.persistScalars(u.svc.store, engine.state, "write_through")
            settle()
            val cold = u.reopen()
            assertEquals("write_through", cold.usernameDisplay)
            assertEquals(engine.state.usernameHash, cold.usernameHash)
            assertEquals(Protocol.ONE_TIME_PRE_KEY_BATCH, cold.preKeys.size)
            assertEquals(1, cold.signedPreKeys.size); assertEquals(1, cold.kyberPreKeys.size)
            assertEquals(Protocol.ONE_TIME_PRE_KEY_BATCH + 1, cold.nextPreKeyId)
            assertEquals(32, cold.authPrivateKey.size)
            assertTrue(cold.registrationId in Protocol.REGISTRATION_ID_MIN..Protocol.REGISTRATION_ID_MAX)
            // and the map reads the live blob back (no shadow copy)
            u.svc.store.update { it.copy(preKeys = emptyMap()) }
            assertEquals(0, engine.state.preKeys.size)
        } finally {
            u.close()
        }
    }

    @Test
    fun `identity, contacts, sessions, messages, receipts, drafts, notes and settings persist at the iOS moments`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val bob = Live.makeService("bo") { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        try {
            // create account: identity → store → register → login
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            settle()
            alice.reopen().let { cold ->
                assertEquals(alice.username, cold.usernameDisplay)
                assertEquals(alice.hash, cold.usernameHash)
                assertEquals(32, cold.preKeys.size); assertEquals(33, cold.nextPreKeyId)
                assertTrue(cold.identityKeyPair.size > 32); assertEquals(32, cold.authPrivateKey.size)
            }

            // addContact: contact row + PQXDH session + peer identity
            alice.svc.addContact(bob.username)
            settle()
            alice.reopen().let { cold ->
                assertEquals(listOf(bob.hash), cold.contacts.map { it.hash })
                assertEquals(bob.username, cold.contacts.first().name)
                assertNotNull(cold.sessions[bob.hash], "session record persisted by storeSession")
                assertNotNull(cold.knownIdentities[bob.hash], "peer identity persisted by saveIdentity")
                assertEquals(33, cold.knownIdentities[bob.hash]!!.size)
            }

            // send + receive + delivered receipt
            alice.svc.sendText("persist me", alice.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.firstOrNull()?.status == Status.DELIVERED })
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })
            settle()
            alice.reopen().let { cold ->
                val row = cold.messages[bob.hash]!!.single()
                assertEquals("persist me", row.text); assertEquals(Status.DELIVERED, row.status); assertEquals(Direction.OUTGOING, row.direction)
                assertNull(cold.receiptOutbox, "flushed receipts leave no outbox behind")
            }
            bob.reopen().let { cold ->
                val row = cold.messages[alice.hash]!!.single()
                assertEquals("persist me", row.text); assertEquals(Status.DELIVERED, row.status); assertEquals(Direction.INCOMING, row.direction)
                assertEquals(alice.username, cold.contacts.single { it.hash == alice.hash }.name, "auto-created contact persisted")
                assertNotNull(cold.sessions[alice.hash])
            }

            // read: status + burn start persisted on the reader, READ on the sender
            val incoming = bob.svc.messages.value[alice.hash]!!.first()
            bob.svc.noteRowVisible(incoming.id, alice.hash)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.first()?.status == Status.READ })
            settle()
            assertEquals(Status.READ, bob.reopen().messages[alice.hash]!!.single().status)
            assertEquals(Status.READ, alice.reopen().messages[bob.hash]!!.single().status)

            // drafts, notes, settings, block list
            alice.svc.setDraft("unsent", bob.hash)
            val note = alice.svc.createNote()
            alice.svc.updateNoteText(note.id, "ops checklist")
            alice.svc.togglePin(note.id)
            alice.svc.store.update { it.copy(settings = it.settings.copy(theme = "amber", defaultBurnSeconds = 60.0)) }
            alice.svc.blockContact("f".repeat(64))
            settle()
            alice.reopen().let { cold ->
                assertEquals("unsent", cold.drafts?.get(bob.hash))
                assertEquals("ops checklist", cold.notes?.single()?.text)
                assertEquals(true, cold.notes?.single()?.pinned)
                assertEquals("amber", cold.settings.theme); assertEquals(60.0, cold.settings.defaultBurnSeconds)
                assertEquals(listOf("f".repeat(64)), cold.blockedHashes)
            }

            // receipts queued while offline stay in the persisted outbox until the link is back
            bob.svc.goOffline()
            alice.svc.goOffline()
            delay(300)
            alice.svc.sendText("queued for bob", alice.svc.contacts.value.first()) // fails: no socket
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.text == "queued for bob" && it.status == Status.FAILED } == true })
            settle()
            assertEquals(Status.FAILED, alice.reopen().messages[bob.hash]!!.first { it.text == "queued for bob" }.status)
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `shutdown flips pending rows to failed and flushes synchronously`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("acked", alice.svc.contacts.value.first())
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.first()?.status?.receiptRank!! >= Status.SENT.receiptRank })
            alice.svc.shutdown()
            assertEquals(ConnState.OFFLINE, alice.svc.connState.value)
            // synchronous flush: the file is current with no debounce wait
            val cold = alice.reopen()
            assertEquals(1, cold.messages[bob.hash]?.size)
            assertTrue(cold.messages[bob.hash]!!.none { it.status == Status.PENDING })
            bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
