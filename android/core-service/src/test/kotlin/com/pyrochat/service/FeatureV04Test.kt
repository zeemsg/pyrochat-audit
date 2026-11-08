package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `ios/Tests/FeatureV04Tests.swift` (M1 subset): delete-for-both, account kill, add-by-QR, per-contact burn. */
class FeatureV04Test {

    @Test
    fun `delete for both removes on both devices but never the peer's own messages`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()

            alice.svc.sendText("to be retracted", bobContact)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text == "to be retracted" } == true }, "message must arrive first")
            val msgId = alice.svc.messages.value[bobContact.hash]!!.first().id

            assertTrue(alice.svc.deleteForBoth(msgId, bobContact.hash), "author's retraction is accepted by the relay")
            assertNull(alice.svc.messages.value[bobContact.hash]?.firstOrNull { it.id == msgId }, "gone locally")
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.id == msgId } != true }, "gone on the peer")

            // AUTH BOUNDARY: a crafted control from bob naming ALICE's message id must not touch alice's outgoing rows
            val aliceContact = bob.svc.contacts.value.first { it.hash == alice.hash }
            bob.svc.sendText("bobs message", aliceContact)
            assertTrue(waitUntil { alice.svc.messages.value[bobContact.hash]?.any { it.text == "bobs message" } == true })
            alice.svc.sendText("alices own", bobContact)
            val aliceOwn = alice.svc.messages.value[bobContact.hash]!!.first { it.text == "alices own" }.id
            assertFalse(bob.svc.deleteForBoth(aliceOwn, alice.hash), "not bob's message: no-op") // iOS returns false (not outgoing)
            delay(2_000)
            assertTrue(alice.svc.messages.value[bobContact.hash]?.any { it.id == aliceOwn } == true, "peer must never delete MY messages on my device")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `delete for both refuses when offline`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()
            alice.svc.sendText("kept", bobContact)
            val id = alice.svc.messages.value[bobContact.hash]!!.first().id

            alice.svc.goOffline()
            assertFalse(alice.svc.deleteForBoth(id, bobContact.hash), "offline: must refuse rather than silently delete-for-me")
            assertTrue(alice.svc.messages.value[bobContact.hash]?.any { it.id == id } == true, "row untouched when the retraction can't be delivered")
            assertNotNull(alice.svc.lastError.value)
            bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `deregister makes the account unreachable, tells the peer, and a fresh add throws without hanging`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        val carol = Live.makeService("ca")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()
            alice.svc.sendText("before", bobContact)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            bob.svc.deregister() // bob kills his account
            delay(1_000)

            alice.svc.sendText("after", bobContact)
            assertTrue(waitUntil { alice.svc.messages.value[bobContact.hash]?.firstOrNull { it.text == "after" }?.status == Status.FAILED },
                "sending to a killed account must fail, never sit at SENT")
            assertTrue(waitUntil {
                alice.svc.messages.value[bobContact.hash]?.any { it.kind == Kind.SYSTEM && it.text.contains("deleted their account") } == true
            }, "the sender is told the account is gone")
            alice.svc.sendText("after again", bobContact)
            delay(1_500)
            assertEquals(1, alice.svc.messages.value[bobContact.hash]!!.count { it.kind == Kind.SYSTEM && it.text.contains("deleted their account") },
                "the system row is never duplicated")

            // and a fresh contact of that name fails cleanly — and promptly (the 15 s bundle deadline is a backstop, not the path)
            carol.svc.register(carol.username)
            val started = System.currentTimeMillis()
            val e = assertFailsWith<ServiceError> { carol.svc.addContact(bob.username) }
            assertTrue(e.toString().contains("unknown_user"), "$e")
            assertTrue(System.currentTimeMillis() - started < 5_000, "unknown_user must resolve the fetch immediately, not by timeout")
            alice.svc.goOffline(); carol.svc.goOffline()
        } finally {
            alice.close(); bob.close(); carol.close()
        }
    }

    @Test
    fun `add by QR adds and verifies in one step`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        val carol = Live.makeService("ca")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            val payload = assertNotNull(bob.svc.myAddQRPayload())
            val parts = payload.split(":")
            assertEquals("pyro-add", parts[0]); assertEquals("v1", parts[1]); assertEquals(bob.username, parts[2])
            val key = Base64.getUrlDecoder().decode(parts[3])
            assertEquals(33, key.size, "IdentityKey.serialize() — 0x05 ‖ pub")

            assertTrue(alice.svc.addContactViaQR(bob.username, key))
            assertTrue(alice.svc.contacts.value.first { it.name == bob.username }.verified, "one scan = added AND verified")

            // wrong key (MITM simulation) must throw loudly, not quietly verify
            carol.svc.register(carol.username)
            val e = assertFailsWith<ServiceError> { alice.svc.addContactViaQR(carol.username, ByteArray(33) { 9 }) }
            assertEquals("identity_mismatch", e.code)
            assertFalse(alice.svc.contacts.value.any { it.name == carol.username }, "a MITM-suspect contact created by the scan must be rolled back")
            alice.svc.goOffline(); bob.svc.goOffline(); carol.svc.goOffline()
        } finally {
            alice.close(); bob.close(); carol.close()
        }
    }

    @Test
    fun `a QR cannot demote a known verified contact`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.setVerified(true, bob.hash)

            val e = assertFailsWith<ServiceError> { alice.svc.addContactViaQR(bob.username, ByteArray(33) { 7 }) }
            assertEquals("qr_key_mismatch", e.code)
            assertTrue(alice.svc.contacts.value.first { it.hash == bob.hash }.verified, "verified badge untouched by a foreign QR")

            // the genuine code still confirms
            val real = Base64.getUrlDecoder().decode(bob.svc.myAddQRPayload()!!.split(":")[3])
            assertTrue(alice.svc.addContactViaQR(bob.username, real))
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `QR rejects usernames outside the registration charset`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        try {
            for (bad in listOf("alice​", "al", "alice\nbob", "a‮lice", "x".repeat(25))) {
                val e = assertFailsWith<ServiceError> { alice.svc.addContactViaQR(bad, ByteArray(33) { 1 }) }
                assertEquals("bad_username", e.code, bad)
            }
        } finally {
            alice.close()
        }
    }

    @Test
    fun `delete for both honors a relay refusal`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()
            alice.svc.sendText("target", bobContact)
            val id = alice.svc.messages.value[bobContact.hash]!!.first().id
            // burn the relay's 30-per-10s send budget
            repeat(34) { alice.svc.sendText("burst $it", bobContact) }
            assertFalse(alice.svc.deleteForBoth(id, bobContact.hash), "relay refused the control (rate_limited) — must report failure")
            assertTrue(alice.svc.messages.value[bobContact.hash]?.any { it.id == id } == true, "row must survive a refused retraction")
            assertTrue(waitUntil { alice.svc.lastError.value == "delete for both refused by relay — try again" })
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `per-contact burn overrides the global default`(): Unit = runBlocking {
        val hash = "peer-hash-1"
        val alice = Live.makeService {
            it.copy(settings = it.settings.copy(defaultBurnSeconds = 3600.0), contacts = listOf(Contact(hash, "p", Instant.now())))
        }
        try {
            assertEquals(3600.0, alice.svc.burnDefault(hash), "falls back to global")
            alice.svc.setBurnDefault(60.0, hash)
            assertEquals(60.0, alice.svc.burnDefault(hash), "override wins")
            alice.svc.setBurnDefault(0.0, hash)
            assertNull(alice.svc.burnDefault(hash), "0 = explicit off beats global")
            alice.svc.setBurnDefault(null, hash)
            assertEquals(3600.0, alice.svc.burnDefault(hash), "nil = back to global")
            assertEquals(null, alice.svc.store.blob.contacts.first().burnSeconds, "persisted")
        } finally {
            alice.close()
        }
    }

    @Test
    fun `per-contact burn default lands on sent rows and rides the wire`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.setBurnDefault(60.0, bob.hash)
            alice.svc.sendText("timed", alice.svc.contacts.value.first())
            assertEquals(60.0, alice.svc.messages.value[bob.hash]!!.first().burnAfter)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.firstOrNull()?.burnAfter == 60.0 }, "peer's copy carries the same burnAfter")
            assertNull(bob.svc.messages.value[alice.hash]!!.first().burnStartedAt)
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
