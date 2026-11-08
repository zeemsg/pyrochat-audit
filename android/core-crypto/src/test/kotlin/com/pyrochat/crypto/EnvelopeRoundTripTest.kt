package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two in-memory identities; B's registration payload is served back as the relay would
 * (`bundleFromRegister`), A processes it, A → B PreKey message (type 3), B → A Whisper (type 2).
 */
class EnvelopeRoundTripTest {
    @Test
    fun preKeyThenWhisperThroughTheEnvelope() {
        val a = CryptoEngine(SignalState())
        val b = CryptoEngine(SignalState())
        val regA = a.generateIdentity("alice")
        val regB = b.generateIdentity("bob")
        val hashA = a.state.usernameHash
        val hashB = b.state.usernameHash
        assertFalse(a.hasSession(hashB))

        // A fetched B's bundle (with B's first one-time prekey, as the relay pops FIFO)
        val bundle = PreKeys.bundleFromRegister(b.registerFrame(regB))
        a.processBundle(bundle, hashB)
        assertTrue(a.hasSession(hashB))
        assertContentEquals(Base64.getDecoder().decode(regB.let { b.registerFrame(it).identityKey }), a.knownIdentity(hashB))

        val hello = Wire.Payload(kind = "text", text = "hello bob", senderName = "alice")
        val body1 = a.encrypt(hello, hashB)

        // the body is base64( {"v":1,"type":3,"data":"..."} ) exactly like bot/index.mjs
        val env1 = Envelope.open(body1)
        assertEquals(Protocol.ENVELOPE_VERSION, env1.v)
        assertEquals(Protocol.CIPHERTEXT_PREKEY, env1.type)
        val rawJson = String(Base64.getDecoder().decode(body1))
        assertTrue(rawJson.startsWith("""{"v":1,"type":3,"data":""""), rawJson)
        assertTrue(rawJson.endsWith(""""}"""))
        assertTrue(Regex("""[A-Za-z0-9+/]+={0,2}""").matches(env1.data))

        // B decrypts the PreKey message: session bootstrapped, one-time prekey 1 consumed, kyber kept
        val got = b.decryptPayload(body1, hashA)
        assertEquals(hello, got)
        assertTrue(b.hasSession(hashA))
        assertFalse(b.stores.containsPreKey(1))
        assertTrue(b.stores.containsPreKey(2))
        assertTrue(b.stores.containsKyberPreKey(1))
        assertContentEquals(Base64.getDecoder().decode(a.registerFrame(regA).identityKey), b.knownIdentity(hashA))

        // B replies with a Whisper (type 2) and A reads it
        val reply = Wire.Payload(kind = "text", text = "hi alice", senderName = "bob", burnAfter = 20.0)
        val body2 = b.encrypt(reply, hashA)
        assertEquals(Protocol.CIPHERTEXT_WHISPER, Envelope.open(body2).type)
        assertEquals(reply, a.decryptPayload(body2, hashB))

        // and A continues on the ratchet
        val body3 = a.encrypt(Wire.Payload(kind = "control", control = "typing"), hashB)
        assertEquals(Protocol.CIPHERTEXT_WHISPER, Envelope.open(body3).type)
        assertEquals("typing", b.decryptPayload(body3, hashA).control)

        // replaying an envelope fails (duplicate message), a missing `v` fails before libsignal
        assertFails { b.decrypt(body1, hashA) }
        val noV = Base64.getEncoder().encodeToString("""{"type":2,"data":"${Envelope.open(body3).data}"}""".toByteArray())
        assertFails { b.decrypt(noV, hashA) }

        // session state is plain bytes in the state class, restorable into a fresh engine
        val bBytes = b.state.sessions[hashA]
        assertNotNull(bBytes)
        val b2 = CryptoEngine(SignalState(usernameHash = hashB, identityKeyPair = b.state.identityKeyPair, registrationId = b.state.registrationId, authPrivateKey = b.state.authPrivateKey).also {
            it.sessions.putAll(b.state.sessions); it.knownIdentities.putAll(b.state.knownIdentities)
            it.preKeys.putAll(b.state.preKeys); it.signedPreKeys.putAll(b.state.signedPreKeys); it.kyberPreKeys.putAll(b.state.kyberPreKeys)
        })
        val body4 = a.encrypt(Wire.Payload(kind = "text", text = "still there?", senderName = "alice"), hashB)
        assertEquals("still there?", b2.decryptPayload(body4, hashA).text)

        // archive: no sending session, receive chains kept
        a.archiveSession(hashB)
        assertFalse(a.hasSession(hashB))
        assertNotNull(a.state.sessions[hashB], "archived record is kept, not deleted")
        assertFails { a.encrypt(Wire.Payload(kind = "text"), hashB) }
        val body5 = b2.encrypt(Wire.Payload(kind = "text", text = "late"), hashA)
        assertEquals("late", a.decryptPayload(body5, hashB).text)
    }

    @Test
    fun bundleWithoutOneTimePreKeyStillBootstraps() {
        val a = CryptoEngine(SignalState())
        val b = CryptoEngine(SignalState())
        a.generateIdentity("a1")
        val regB = b.generateIdentity("b1")
        a.processBundle(PreKeys.bundleFromRegister(b.registerFrame(regB), oneTimePreKey = null), b.state.usernameHash)
        val body = a.encrypt(Wire.Payload(kind = "text", text = "x"), b.state.usernameHash)
        assertEquals(Protocol.CIPHERTEXT_PREKEY, Envelope.open(body).type)
        assertEquals("x", b.decryptPayload(body, a.state.usernameHash).text)
        assertEquals(32, b.state.preKeys.size)
    }
}
