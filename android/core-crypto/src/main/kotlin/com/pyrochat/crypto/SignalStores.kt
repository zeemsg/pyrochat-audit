package com.pyrochat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.util.UUID

/**
 * libsignal protocol stores over a [SignalState] — the port of iOS `SignalStore.swift`.
 * Records round-trip through `serialize()` / `Record(bytes)` on every access, exactly like iOS,
 * so the state is always plain bytes. Stores are keyed by `address.name` (the username hash)
 * only; deviceId is always 1 and is not part of any key.
 *
 * It is a full [SignalProtocolStore] because libsignal's `SealedSessionCipher` asks for one
 * (docs/SEALED-SENDER.md §2). The sender-key half of that interface belongs to Signal's own group
 * messaging, which PyroChat does not use: nothing ever calls it.
 */
class InMemorySignalStores(val state: SignalState) :
    IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore, SignalProtocolStore {

    // ---- SenderKeyStore: required by SignalProtocolStore, never used (groups here are pairwise fan-out)

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        throw UnsupportedOperationException("sender keys are not used")

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

    /**
     * iOS `SignalStore.onIdentityReplaced`: fired synchronously on the caller's thread when
     * [saveIdentity] replaces a DIFFERENT key for a peer (ChatService clears `Contact.verified`
     * and posts the "identity key changed" system row).
     */
    var onIdentityReplaced: ((peerHash: String) -> Unit)? = null

    // ---- IdentityKeyStore (iOS: identityKeyPair / localRegistrationId / saveIdentity / isTrustedIdentity / identity)

    override fun getIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair(state.identityKeyPair)

    override fun getLocalRegistrationId(): Int = state.registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = address.name
        val serialized = identityKey.serialize()
        val previous = state.knownIdentities[key]
        state.knownIdentities[key] = serialized
        if (previous != null && !previous.contentEquals(serialized)) {
            onIdentityReplaced?.invoke(key)
            return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    /**
     * ACCEPT-AND-WARN, verbatim from iOS: always true. First contact is trusted on first use and a
     * changed key is still accepted — `saveIdentity` records it and fires [onIdentityReplaced] so
     * the UI warns and clears `verified`. Returning false would make every message after a peer
     * reinstall throw `UntrustedIdentityException` and wedge auto-heal (see THREAT_MODEL.md).
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        state.knownIdentities[address.name]?.let { IdentityKey(it) }

    // ---- PreKeyStore (iOS: loadPreKey / storePreKey / removePreKey)

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        state.preKeys[preKeyId]?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        state.preKeys[preKeyId] = record.serialize()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = state.preKeys.containsKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        state.preKeys.remove(preKeyId)
    }

    // ---- SignedPreKeyStore (iOS: loadSignedPreKey / storeSignedPreKey)

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        state.signedPreKeys[signedPreKeyId]?.let { SignedPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = state.signedPreKeys.values.map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        state.signedPreKeys[signedPreKeyId] = record.serialize()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = state.signedPreKeys.containsKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        state.signedPreKeys.remove(signedPreKeyId)
    }

    // ---- KyberPreKeyStore (iOS: loadKyberPreKey / storeKyberPreKey / markKyberPreKeyUsed)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        state.kyberPreKeys[kyberPreKeyId]?.let { KyberPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = state.kyberPreKeys.values.map { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        state.kyberPreKeys[kyberPreKeyId] = record.serialize()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = state.kyberPreKeys.containsKey(kyberPreKeyId)

    /** The last-resort Kyber prekey is reusable: a no-op, like iOS. (One-time Kyber prekeys are not used in v1.) */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {}

    // ---- SessionStore (iOS: loadSession / loadExistingSessions / storeSession)

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        state.sessions[address.name]?.let { SessionRecord(it) }

    /** iOS `compactMap`: missing sessions are skipped rather than throwing (never called in v1 flows). */
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.mapNotNull { loadSession(it) }

    /** Every peer is deviceId 1; there are no sub-devices. */
    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        state.sessions[address.name] = record.serialize()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = state.sessions.containsKey(address.name)

    override fun deleteSession(address: SignalProtocolAddress) {
        state.sessions.remove(address.name)
    }

    override fun deleteAllSessions(name: String) {
        state.sessions.remove(name)
    }
}
