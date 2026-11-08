package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import com.pyrochat.protocol.usernameHash
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAndPreKeysTest {
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun identityGenerationMatchesIosShapes() {
        repeat(200) {
            val id = Identity.generate()
            assertTrue(id.registrationId in 1..16380, "reg id ${id.registrationId}")
            val ik = id.identityKeySerialized()
            assertEquals(33, ik.size)
            assertEquals(0x05, ik[0].toInt())
            assertEquals(32, id.authPublicKey().size)
            assertEquals(32, id.authPrivateKey.size)
        }
    }

    @Test
    fun identityRoundTripsThroughState() {
        val id = Identity.generate()
        val state = SignalState()
        id.writeTo(state)
        val back = Identity.fromState(state)
        assertContentEquals(id.identityKeySerialized(), back.identityKeySerialized())
        assertContentEquals(id.identityKeyPair.serialize(), back.identityKeyPair.serialize())
        assertEquals(id.registrationId, back.registrationId)
        assertContentEquals(id.authPrivateKey, back.authPrivateKey)
        assertContentEquals(id.authPublicKey(), back.authPublicKey())
    }

    @Test
    fun generateIdentityProducesTheExactRegisterFrame() {
        val state = SignalState()
        val engine = CryptoEngine(state, clock = { 1_700_000_000_000L })
        val reg = engine.generateIdentity("Alice")
        assertEquals(usernameHash("alice"), state.usernameHash)

        val frame = engine.registerFrame(reg)
        assertEquals(state.usernameHash, frame.user)
        assertEquals(32, unb64(frame.authPub).size)
        assertEquals(state.registrationId, frame.regId)
        assertTrue(frame.regId in 1..16380)
        val identityKey = unb64(frame.identityKey)
        assertEquals(33, identityKey.size)
        assertEquals(0x05, identityKey[0].toInt())
        assertContentEquals(IdentityKeyPair(state.identityKeyPair).publicKey.serialize(), identityKey)

        // signed prekey: id 1, XEdDSA signature by the identity key over the 33-byte serialized pub
        val identityPub = IdentityKey(identityKey).publicKey
        assertEquals(Protocol.SIGNED_PRE_KEY_ID, frame.signedPreKey.id)
        assertEquals(33, unb64(frame.signedPreKey.pub).size)
        assertEquals(64, unb64(assertNotNull(frame.signedPreKey.sig)).size)
        assertTrue(identityPub.verifySignature(unb64(frame.signedPreKey.pub), unb64(frame.signedPreKey.sig!!)))
        // kyber-1024 last-resort prekey: id 1, serialized public = 1 type byte + 1568
        assertEquals(Protocol.KYBER_PRE_KEY_ID, frame.kyberPreKey.id)
        assertEquals(1 + 1568, unb64(frame.kyberPreKey.pub).size)
        assertTrue(identityPub.verifySignature(unb64(frame.kyberPreKey.pub), unb64(assertNotNull(frame.kyberPreKey.sig))))
        // 32 one-time prekeys, ids 1..32, no signature
        assertEquals((1..32).toList(), frame.oneTimePreKeys.map { it.id })
        assertTrue(frame.oneTimePreKeys.all { it.sig == null && unb64(it.pub).size == 33 })
        assertEquals(33, state.nextPreKeyId)

        // private halves landed in the state under the same ids
        assertEquals(setOf(1), state.signedPreKeys.keys)
        assertEquals(setOf(1), state.kyberPreKeys.keys)
        assertEquals((1..32).toSet(), state.preKeys.keys)
        assertEquals(1_700_000_000_000L, engine.stores.loadSignedPreKey(1).timestamp)
        assertEquals(1_700_000_000_000L, engine.stores.loadKyberPreKey(1).timestamp)

        // the encoded frame carries no `sig` for one-time keys and exact names
        val json = Wire.encode(frame)
        assertTrue(json.startsWith("""{"t":"register","user":"${state.usernameHash}","auth_pub":"""))
        assertTrue(json.contains(""""one_time_prekeys":[{"id":1,"pub":""""))
        assertTrue(!json.contains("null"))

        // top-up continues the counter
        val more = engine.generateOneTimePreKeys(32)
        assertEquals((33..64).toList(), more.map { it.id })
        assertEquals(65, state.nextPreKeyId)
        assertEquals(64, state.preKeys.size)
        val upload = Wire.encode(PreKeys.uploadFrame(more))
        assertTrue(upload.startsWith("""{"t":"prekey_upload","one_time_prekeys":[{"id":33,"pub":"${more[0].pub}"},{"id":34,"""), upload)
        assertTrue(!upload.contains("sig"))
    }

    @Test
    fun bundleParsingMirrorsIosProcessBundle() {
        val engine = CryptoEngine(SignalState())
        val frame = engine.registerFrame(engine.generateIdentity("bob"))
        val full = PreKeys.parseBundle(PreKeys.bundleFromRegister(frame))
        assertEquals(frame.regId, full.registrationId)
        assertEquals(1, full.deviceId)
        assertEquals(1, full.preKeyId)
        assertNotNull(full.preKey)
        assertEquals(1, full.signedPreKeyId)
        assertEquals(1, full.kyberPreKeyId)
        assertContentEquals(unb64(frame.identityKey), full.identityKey.serialize())

        val noOtk = PreKeys.parseBundle(PreKeys.bundleFromRegister(frame, oneTimePreKey = null))
        assertEquals(PreKeyBundle.NULL_PRE_KEY_ID, noOtk.preKeyId)
        assertNull(noOtk.preKey)

        assertFailsWith<PreKeys.IncompleteBundle> {
            PreKeys.parseBundle(PreKeys.bundleFromRegister(frame).copy(kyberPreKey = null))
        }
        assertFailsWith<PreKeys.IncompleteBundle> {
            PreKeys.parseBundle(PreKeys.bundleFromRegister(frame).copy(signedPreKey = frame.signedPreKey.copy(sig = null)))
        }
        assertFailsWith<PreKeys.IncompleteBundle> {
            PreKeys.parseBundle(PreKeys.bundleFromRegister(frame).copy(identityKey = "not base64!"))
        }
    }

    @Test
    fun storeSemanticsMatchSignalStoreSwift() {
        val state = SignalState()
        val stores = InMemorySignalStores(state)
        val replaced = mutableListOf<String>()
        stores.onIdentityReplaced = { replaced += it }
        val peer = SignalProtocolAddress("peer", 1)
        val k1 = IdentityKeyPair.generate().publicKey
        val k2 = IdentityKeyPair.generate().publicKey

        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, stores.saveIdentity(peer, k1))
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, stores.saveIdentity(peer, k1))
        assertTrue(replaced.isEmpty())
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, stores.saveIdentity(peer, k2))
        assertEquals(listOf("peer"), replaced)
        assertEquals(k2, stores.getIdentity(peer))
        assertContentEquals(k2.serialize(), state.knownIdentities["peer"])
        assertEquals(33, state.knownIdentities["peer"]!!.size)
        // accept-and-warn: trusted regardless of what is on record
        assertTrue(stores.isTrustedIdentity(peer, k1, IdentityKeyStore.Direction.SENDING))
        assertTrue(stores.isTrustedIdentity(peer, k1, IdentityKeyStore.Direction.RECEIVING))
        assertNull(stores.getIdentity(SignalProtocolAddress("nobody", 1)))

        assertFailsWith<InvalidKeyIdException> { stores.loadPreKey(99) }
        assertFailsWith<InvalidKeyIdException> { stores.loadSignedPreKey(99) }
        assertFailsWith<InvalidKeyIdException> { stores.loadKyberPreKey(99) }
        assertNull(stores.loadSession(peer))
        assertEquals(emptyList(), stores.loadExistingSessions(listOf(peer)))
        assertEquals(emptyList(), stores.getSubDeviceSessions("peer"))
    }

    @Test
    fun envelopeConstantsMatchLibsignal() {
        assertEquals(org.signal.libsignal.protocol.message.CiphertextMessage.WHISPER_TYPE, Protocol.CIPHERTEXT_WHISPER)
        assertEquals(org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE, Protocol.CIPHERTEXT_PREKEY)
        assertIs<Wire.Envelope>(Wire.Envelope.of(2, ByteArray(1)))
    }
}
