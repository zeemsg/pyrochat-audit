package com.pyrochat.service

import com.pyrochat.crypto.InMemorySignalStores
import com.pyrochat.crypto.SignalState
import com.pyrochat.protocol.UsernameHash
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.Bytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of `ios/Tests/TrustTests.swift`: safety-number fingerprints (pure crypto), the key-change
 * bridge (store unit), and session auto-heal / key change (two live ChatServices).
 */
class TrustTest {

    @Test
    fun `fingerprint is deterministic and symmetric with the iOS parameters`() {
        val aliceKey = IdentityKeyPair.generate()
        val bobKey = IdentityKeyPair.generate()
        val aliceId = UsernameHash.of("alice")
        val bobId = UsernameHash.of("bob")
        assertEquals(5200, SafetyNumbers.ITERATIONS); assertEquals(2, SafetyNumbers.VERSION)

        val a1 = SafetyNumbers.compute(aliceId, aliceKey.serialize(), bobId, bobKey.publicKey.serialize())
        val a2 = SafetyNumbers.compute(aliceId, aliceKey.serialize(), bobId, bobKey.publicKey.serialize())
        assertEquals(a1.digits, a2.digits)
        // symmetry: bob computes with roles swapped, sees the SAME digits
        val b = SafetyNumbers.compute(bobId, bobKey.serialize(), aliceId, aliceKey.publicKey.serialize())
        assertEquals(a1.digits, b.digits, "both devices must render identical 60 digits")
        assertEquals(60, a1.digits.length)
        assertTrue(a1.digits.all { it.isDigit() })
        // scannable halves differ per side by design
        assertFalse(a1.scannable.contentEquals(b.scannable))
        // a substituted key must change the number
        val mallory = IdentityKeyPair.generate()
        val forged = SafetyNumbers.compute(aliceId, mallory.serialize(), bobId, bobKey.publicKey.serialize())
        assertNotEquals(forged.digits, a1.digits)
    }

    @Test
    fun `saveIdentity fires the replacement bridge exactly once`() {
        val stores = InMemorySignalStores(SignalState())
        val fired = ArrayList<String>()
        stores.onIdentityReplaced = { fired += it }
        val peer = UsernameHash.of("peer")
        val addr = SignalProtocolAddress(peer, 1)
        val k1 = IdentityKeyPair.generate().publicKey
        val k2 = IdentityKeyPair.generate().publicKey

        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, stores.saveIdentity(addr, k1))
        assertTrue(fired.isEmpty(), "TOFU first sight is not a change")
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, stores.saveIdentity(addr, k1))
        assertTrue(fired.isEmpty())
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, stores.saveIdentity(addr, k2))
        assertEquals(listOf(peer), fired, "replacement fires the bridge exactly once")
        // accept-and-warn: trust stays permissive, new key is recorded
        assertTrue(stores.isTrustedIdentity(addr, k1, IdentityKeyStore.Direction.SENDING))
        assertContentEquals(k2.serialize(), stores.getIdentity(addr)?.serialize())
    }

    @Test
    fun `safety numbers agree across a live channel`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("ping", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            val aliceSN = assertNotNull(alice.svc.safetyNumber(alice.svc.contacts.value.first()))
            val bobSN = assertNotNull(bob.svc.safetyNumber(bob.svc.contacts.value.first { it.hash == alice.hash }))
            assertEquals(aliceSN.digits, bobSN.digits, "both real clients must display the same safety number")
            assertEquals(60, aliceSN.digits.length)

            bob.svc.setVerified(true, alice.hash)
            assertTrue(bob.svc.contacts.value.first { it.hash == alice.hash }.verified)
            assertTrue(bob.svc.store.blob.contacts.first { it.hash == alice.hash }.verified, "persisted")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `session auto-heal after the peer loses its session`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("hello bob", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text == "hello bob" } == true })
            val bobContact = bob.svc.contacts.value.first { it.hash == alice.hash }
            bob.svc.sendText("hello alice", bobContact)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.text == "hello alice" } == true })

            // surgically destroy bob's session record (reinstall-shaped damage)
            bob.svc.store.update { it.copy(sessions = it.sessions - alice.hash) }

            // alice's session is intact -> her next send is a whisper bob can't decrypt
            alice.svc.sendText("you will never read this", alice.svc.contacts.value.first())

            assertTrue(waitUntil(15_000) {
                bob.svc.messages.value[alice.hash]?.any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } == true
            }, "bob must announce the re-key")
            assertFalse(bob.svc.messages.value[alice.hash]!!.any { it.text.contains("never read this") }, "the undecryptable message is lost — and stays lost")
            assertTrue(waitUntil(15_000) {
                alice.svc.messages.value[bob.hash]?.any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } == true
            }, "alice must be told the channel was re-keyed")

            // channel works again in BOTH directions
            alice.svc.sendText("take two", alice.svc.contacts.value.first())
            assertTrue(waitUntil(10_000) { bob.svc.messages.value[alice.hash]?.any { it.text == "take two" } == true }, "alice -> bob flows after heal")
            bob.svc.sendText("read you loud and clear", bobContact)
            assertTrue(waitUntil(10_000) { alice.svc.messages.value[bob.hash]?.any { it.text == "read you loud and clear" } == true }, "bob -> alice flows after heal")

            // noise/loop guard: exactly one re-key row per side
            delay(2_000)
            assertEquals(1, bob.svc.messages.value[alice.hash]!!.count { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") })
            assertEquals(1, alice.svc.messages.value[bob.hash]!!.count { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") })
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }

    @Test
    fun `key change during heal warns once and clears verified`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username); bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            alice.svc.sendText("establish", alice.svc.contacts.value.first())
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.isNotEmpty() == true })

            // bob verified alice, then bob's records go stale
            bob.svc.setVerified(true, alice.hash)
            val wrongKey = IdentityKeyPair.generate().publicKey.serialize()
            bob.svc.store.update {
                it.copy(knownIdentities = it.knownIdentities + (alice.hash to Bytes(wrongKey)), sessions = it.sessions - alice.hash)
            }

            alice.svc.sendText("trigger", alice.svc.contacts.value.first())
            assertTrue(waitUntil(15_000) {
                bob.svc.messages.value[alice.hash]?.any { it.kind == Kind.SYSTEM && it.text.contains("identity key changed") } == true
            }, "identity warning row must appear")
            assertFalse(bob.svc.contacts.value.first { it.hash == alice.hash }.verified, "key change must clear the verified flag")

            waitUntil(10_000) { bob.svc.messages.value[alice.hash]?.any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } == true }
            delay(2_000)
            val rows = bob.svc.messages.value[alice.hash]!!.filter { it.kind == Kind.SYSTEM }
            assertEquals(1, rows.count { it.text.contains("identity key changed") })
            assertEquals(1, rows.count { it.text.contains("re-keyed") })

            // accept-and-warn: flow continues under the (correct) key
            alice.svc.sendText("still flowing", alice.svc.contacts.value.first())
            assertTrue(waitUntil(10_000) { bob.svc.messages.value[alice.hash]?.any { it.text == "still flowing" } == true }, "messages must keep flowing after a key change")
            alice.svc.goOffline(); bob.svc.goOffline()
        } finally {
            alice.close(); bob.close()
        }
    }
}
