package com.pyrochat.store

import com.pyrochat.store.codec.SwiftBase64
import com.pyrochat.store.model.Blob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `session-probe.json`: a Double-Ratchet message bob encrypted AFTER `pyro.store` was written.
 * Feeding the persisted `sessions[bob]` bytes to libsignal-java and decrypting it proves the
 * session material iOS persists is directly usable on Android (SPEC.md §9).
 */
class SessionProbeTest {

    /** The iOS `SignalStore` (`SignalStore.swift`) over a decoded [Blob], in memory. */
    private class BlobSignalStore(blob: Blob) :
        SessionStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, IdentityKeyStore {
        private val identity = IdentityKeyPair(blob.identityKeyPair.bytes)
        private val registrationId = blob.registrationId
        val sessions: MutableMap<String, ByteArray> = blob.sessions.mapValues { it.value.bytes }.toMutableMap()
        val identities: MutableMap<String, ByteArray> = blob.knownIdentities.mapValues { it.value.bytes }.toMutableMap()
        private val preKeys = blob.preKeys.mapValues { it.value.bytes }.toMutableMap()
        private val signedPreKeys = blob.signedPreKeys.mapValues { it.value.bytes }.toMutableMap()
        private val kyberPreKeys = blob.kyberPreKeys.mapValues { it.value.bytes }.toMutableMap()

        // IdentityKeyStore (SignalStore.swift:26-63)
        override fun getIdentityKeyPair(): IdentityKeyPair = identity
        override fun getLocalRegistrationId(): Int = registrationId
        override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
            val serialized = identityKey.serialize()
            val previous = identities.put(address.name, serialized)
            return if (previous != null && !previous.contentEquals(serialized)) {
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            } else {
                IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            }
        }
        /** accept-and-warn, never enforce (SignalStore.swift:47-58) */
        override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean = true
        override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identities[address.name]?.let { IdentityKey(it) }

        // SessionStore (SignalStore.swift:116-129)
        override fun loadSession(address: SignalProtocolAddress): SessionRecord? = sessions[address.name]?.let { SessionRecord(it) }
        override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.mapNotNull { loadSession(it) }
        override fun getSubDeviceSessions(name: String): List<Int> = emptyList()
        override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[address.name] = record.serialize() }
        override fun containsSession(address: SignalProtocolAddress): Boolean = address.name in sessions
        override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address.name) }
        override fun deleteAllSessions(name: String) { sessions.remove(name) }

        // PreKeyStore (SignalStore.swift:67-80)
        override fun loadPreKey(id: Int): PreKeyRecord = preKeys[id]?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey $id")
        override fun storePreKey(id: Int, record: PreKeyRecord) { preKeys[id] = record.serialize() }
        override fun containsPreKey(id: Int): Boolean = id in preKeys
        override fun removePreKey(id: Int) { preKeys.remove(id) }

        // SignedPreKeyStore (SignalStore.swift:84-93)
        override fun loadSignedPreKey(id: Int): SignedPreKeyRecord = signedPreKeys[id]?.let { SignedPreKeyRecord(it) } ?: throw InvalidKeyIdException("no signed prekey $id")
        override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = signedPreKeys.values.map { SignedPreKeyRecord(it) }
        override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) { signedPreKeys[id] = record.serialize() }
        override fun containsSignedPreKey(id: Int): Boolean = id in signedPreKeys
        override fun removeSignedPreKey(id: Int) { signedPreKeys.remove(id) }

        // KyberPreKeyStore (SignalStore.swift:97-112)
        override fun loadKyberPreKey(id: Int): KyberPreKeyRecord = kyberPreKeys[id]?.let { KyberPreKeyRecord(it) } ?: throw InvalidKeyIdException("no kyber prekey $id")
        override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyberPreKeys.values.map { KyberPreKeyRecord(it) }
        override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) { kyberPreKeys[id] = record.serialize() }
        override fun containsKyberPreKey(id: Int): Boolean = id in kyberPreKeys
        /** the last-resort kyber prekey is reusable — a no-op on iOS too */
        override fun markKyberPreKeyUsed(id: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {}
    }

    @Test fun thePersistedSessionDecryptsAMessageBobSentAfterTheStoreWasWritten() {
        val probe = Fixtures.json("session-probe.json")
        val from = probe.str("from")
        assertEquals(Fixtures.manifest.obj("peer").str("usernameHash"), from)
        // relay body = base64(JSON {v:1, type, data:b64}); type 2 = SignalMessage (Double Ratchet)
        val envelope = Json.parseToJsonElement(SwiftBase64.decode(probe.str("body")).toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, envelope.int("v"))
        assertEquals(2, envelope.int("type"))
        assertEquals(probe.int("envelopeType"), envelope.int("type"))
        val ciphertext = SwiftBase64.decode(envelope.str("data"))

        val blob = Fixtures.blob
        val store = BlobSignalStore(blob)
        val original = blob.sessions.getValue(from).bytes
        val local = SignalProtocolAddress(blob.usernameHash, 1)      // CryptoEngine.deviceId = 1
        val remote = SignalProtocolAddress(from, 1)
        val cipher = SessionCipher(store, store, store, store, store, local, remote)

        val plain = cipher.decrypt(SignalMessage(ciphertext))
        val payload = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        assertEquals(probe.obj("expectedPayload"), payload, "inner plaintext is the Wire.Payload JSON")
        assertEquals("probe: decrypt me with the fixture session", payload.str("text"))
        assertEquals("bob_fixture", payload.str("senderName"))

        // the ratchet advanced: the record the store would now persist differs, still parses, and keeps the peer
        val updated = store.sessions.getValue(from)
        assertFalse(updated.contentEquals(original))
        val record = SessionRecord(updated)
        assertTrue(record.hasSenderChain())
        assertEquals(Fixtures.manifest.obj("peer").int("registrationId"), record.remoteRegistrationId)
        assertEquals(IdentityKey(blob.knownIdentities.getValue(from).bytes), record.remoteIdentityKey)
        assertContentEquals(blob.knownIdentities.getValue(from).bytes, store.identities.getValue(from), "the known identity is unchanged")
        // a replay of the same envelope is refused
        assertFailsWith<DuplicateMessageException> { cipher.decrypt(SignalMessage(ciphertext)) }
    }
}
