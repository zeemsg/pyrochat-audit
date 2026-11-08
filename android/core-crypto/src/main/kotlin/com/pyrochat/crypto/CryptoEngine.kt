package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import com.pyrochat.protocol.usernameHash
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SessionRecord
import java.security.SecureRandom
import java.util.Optional
import java.util.UUID

/**
 * Port of iOS `CryptoEngine.swift`: identity + PQXDH prekey generation, session bootstrap from a
 * fetched bundle, and per-message encrypt/decrypt through the [Envelope]. All state lives in
 * [state]; libsignal callbacks write to it synchronously through [stores].
 */
class CryptoEngine(
    val state: SignalState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    val stores: InMemorySignalStores = InMemorySignalStores(state)

    /** Bridge for identity replacement events (peer hash) — iOS `CryptoEngine.onIdentityReplaced`. */
    var onIdentityReplaced: ((String) -> Unit)?
        get() = stores.onIdentityReplaced
        set(value) { stores.onIdentityReplaced = value }

    /** `ProtocolAddress(name: usernameHash, deviceId: 1)`. */
    fun myAddress(): SignalProtocolAddress {
        check(state.usernameHash.isNotEmpty()) { "no identity: usernameHash is empty" }
        return SignalProtocolAddress(state.usernameHash, Protocol.DEVICE_ID)
    }

    private fun peerAddress(peerHash: String) = SignalProtocolAddress(peerHash, Protocol.DEVICE_ID)

    // MARK: registration material

    /**
     * iOS `generateIdentity(username:)`: identity key pair, registration id, Ed25519 auth seed,
     * signed prekey (id 1), last-resort Kyber prekey (id 1), 32 one-time prekeys (ids from
     * `nextPreKeyId`). Private halves land in [state]; the returned bundle is the public material.
     */
    fun generateIdentity(username: String): PreKeys.Registration {
        val identity = Identity.generate(random)
        state.usernameHash = usernameHash(username)
        identity.writeTo(state)

        val now = clock()
        val spk = PreKeys.generateSignedPreKey(identity.identityKeyPair, now)
        stores.storeSignedPreKey(spk.id, spk)
        val kyber = PreKeys.generateKyberPreKey(identity.identityKeyPair, now)
        stores.storeKyberPreKey(kyber.id, kyber)
        val otks = PreKeys.generateOneTimePreKeys(state, stores)

        return PreKeys.Registration(
            regId = identity.registrationId,
            identityKey = identity.identityKeySerialized(),
            signedPreKey = PreKeys.json(spk),
            kyberPreKey = PreKeys.json(kyber),
            oneTimePreKeys = otks,
            authPub = identity.authPublicKey(),
        )
    }

    /** The `register` frame for this identity. */
    fun registerFrame(registration: PreKeys.Registration): Wire.Out.Register =
        registration.toRegisterFrame(state.usernameHash)

    fun identity(): Identity = Identity.fromState(state)

    /** Sign a relay login challenge with the Ed25519 auth key — iOS `signChallenge`. */
    fun signChallenge(nonce: ByteArray): ByteArray = RelayAuth.sign(nonce, state.authPrivateKey)

    /** Fresh one-time prekeys for relay top-up — iOS `generateOneTimePreKeys`. */
    fun generateOneTimePreKeys(count: Int = Protocol.ONE_TIME_PRE_KEY_BATCH): List<Wire.PreKeyJson> =
        PreKeys.generateOneTimePreKeys(state, stores, count)

    /** My serialized identity public key (33 bytes) — the `pyro-add` QR payload. */
    fun myIdentityPublic(): ByteArray = IdentityKeyPair(state.identityKeyPair).publicKey.serialize()

    /** The peer identity key currently on record (null until first contact). */
    fun knownIdentity(peerHash: String): ByteArray? = state.knownIdentities[peerHash]

    // MARK: sessions

    /** True only for a session usable for SENDING (`SessionRecord.hasCurrentState` ↔ `hasSenderChain()`). */
    fun hasSession(peerHash: String): Boolean {
        val bytes = state.sessions[peerHash] ?: return false
        return runCatching { SessionRecord(bytes).hasSenderChain() }.getOrDefault(false)
    }

    /** Archive (not delete) the ratchet for a peer; old receive chains are kept. */
    fun archiveSession(peerHash: String) {
        val bytes = state.sessions[peerHash] ?: return
        val record = runCatching { SessionRecord(bytes) }.getOrNull() ?: return
        record.archiveCurrentState()
        state.sessions[peerHash] = record.serialize()
    }

    /** Bootstrap a session from a fetched PQXDH prekey bundle — iOS `processBundle`. */
    fun processBundle(bundle: Wire.In.PreKeyBundle, peerHash: String) {
        val preKeyBundle = PreKeys.parseBundle(bundle)
        SessionBuilder(stores, stores, stores, stores, peerAddress(peerHash), myAddress()).process(preKeyBundle)
    }

    // MARK: encrypt / decrypt

    // libsignal-java 0.102.2: SessionCipher(session, preKey, signedPreKey, kyber, identity, LOCAL, REMOTE)
    // — note the opposite address order from SessionBuilder (verified from the constructor bytecode).
    private fun cipher(peerHash: String) =
        SessionCipher(stores, stores, stores, stores, stores, myAddress(), peerAddress(peerHash))

    /** Encrypt plaintext bytes (a compact-JSON `Wire.Payload`) for a peer → opaque relay `body`. */
    fun encrypt(plaintext: ByteArray, peerHash: String): String = Envelope.seal(cipher(peerHash).encrypt(plaintext))

    fun encrypt(payload: Wire.Payload, peerHash: String): String = encrypt(payload.encode(), peerHash)

    /** Decrypt a relay `body` from a peer → plaintext bytes. Throws on any failure (caller acks + heals). */
    fun decrypt(body: String, peerHash: String): ByteArray {
        val envelope = Envelope.open(body)
        val cipher = cipher(peerHash)
        return if (Envelope.isPreKey(envelope)) {
            cipher.decrypt(PreKeySignalMessage(envelope.ciphertext()))
        } else {
            cipher.decrypt(SignalMessage(envelope.ciphertext()))
        }
    }

    fun decryptPayload(body: String, peerHash: String): Wire.Payload = Wire.Payload.decode(decrypt(body, peerHash))

    // MARK: sealed sender (docs/SEALED-SENDER.md §3, §4.3)

    /**
     * The envelope's type, read without touching the ratchet: 2 or 3 on today's path, 6 for a
     * sealed body. null when [body] is not an envelope at all.
     */
    fun envelopeType(body: String): Int? = runCatching { Envelope.open(body).type }.getOrNull()

    /**
     * Seal bytes for a peer: the Signal ciphertext, wrapped so that only they learn it is from me.
     * The content carries my certificate and, in `groupId`, my full hash, the relay's binding for
     * it, and the account it is meant for (§3).
     */
    private fun sealBytes(plaintext: ByteArray, peerHash: String, own: Sealed.Own): ByteArray {
        // checked before the ratchet moves: a step taken for bytes that cannot be sealed is wasted
        val me = Sealed.hashBytes(state.usernameHash) ?: throw IllegalArgumentException("not a username hash")
        val to = Sealed.hashBytes(peerHash) ?: throw IllegalArgumentException("not a username hash")
        val ciphertext = cipher(peerHash).encrypt(plaintext)
        val content = UnidentifiedSenderMessageContent(
            ciphertext, own.cert, UnidentifiedSenderMessageContent.CONTENT_HINT_DEFAULT, Optional.of(me + own.bind + to),
        )
        // the local UUID and device are used only by the library's own one-step decrypt, which is not used here
        return SealedSessionCipher(stores, UUID(0L, 0L), null, Protocol.DEVICE_ID).encrypt(peerAddress(peerHash), content)
    }

    /** Seal an inner Payload for a peer → relay `body`: today's envelope with type 6. */
    fun seal(payload: Wire.Payload, peerHash: String, own: Sealed.Own): String =
        Wire.Envelope.of(Sealed.ENVELOPE_TYPE, sealBytes(payload.encode(), peerHash, own)).toBody()

    /**
     * Seal attachment bytes for a peer → binary blob: `[0x06][sealed bytes]`. A blob sent before
     * the peer has replied is a PreKey message, which carries my identity key in the clear;
     * sealing it keeps that from the relay too (§5.6).
     */
    fun sealBlob(plaintext: ByteArray, peerHash: String, own: Sealed.Own): ByteArray =
        byteArrayOf(Sealed.ENVELOPE_TYPE.toByte()) + sealBytes(plaintext, peerHash, own)

    /**
     * A sealed message opened as far as its sender: the outer layer and checks C1–C6 have passed.
     * The ratchet has not been touched.
     */
    class Opened internal constructor(
        /** The sender's username hash, as the certificate and binding vouch for it. */
        val sender: String,
        internal val content: UnidentifiedSenderMessageContent,
        internal val certKey: ByteArray,
    )

    /**
     * Checks C0–C6 on sealed bytes. [ts] is the relay's timestamp on the frame that carried them
     * (for a blob, its manifest's): the certificate is judged at the moment the relay took the
     * message, so one queued for two days still verifies. Throws [Sealed.Rejected].
     *
     * libsignal-java has no public call that opens only the outer layer: see [SealedBridge].
     */
    fun openSealed(sealed: ByteArray, ts: Long, roots: List<ECPublicKey>, minKeyId: Int): Opened {
        val content = try {
            SealedBridge.openOuter(sealed, stores)
        } catch (e: Exception) {
            throw Sealed.Rejected("C0")
        }
        val cert = content.senderCertificate
        val gid = content.groupId.orElse(null)
        if (gid == null || gid.size != 128) throw Sealed.Rejected("C4")
        val hash = gid.copyOfRange(0, 32)
        val bind = gid.copyOfRange(32, 96)
        val to = gid.copyOfRange(96, 128)
        Sealed.checkCertificate(cert, bind, hash, roots, minKeyId, ts)              // C1–C4
        val sender = Sealed.hex(hash)
        if (sender == state.usernameHash) throw Sealed.Rejected("C5")
        if (Sealed.hex(to) != state.usernameHash) throw Sealed.Rejected("C6")
        return Opened(sender, content, cert.key.serialize())
    }

    /** The same, from a relay `body`. Anything that is not a type 6 envelope is C0. */
    fun openSealedBody(body: String, ts: Long, roots: List<ECPublicKey>, minKeyId: Int): Opened {
        val envelope = runCatching { Envelope.open(body) }.getOrNull()
        if (envelope == null || envelope.type != Sealed.ENVELOPE_TYPE) throw Sealed.Rejected("C0")
        val bytes = runCatching { envelope.ciphertext() }.getOrNull() ?: throw Sealed.Rejected("C0")
        return openSealed(bytes, ts, roots, minKeyId)
    }

    /**
     * C7, then the ratchet. A PreKey message has to carry the certificate's identity key, or it is
     * someone else's message in this sender's envelope: dropped. A Whisper needs that key to be the
     * one on record for the sender; if it is not (their key changed, or this phone lost the
     * session) the failure is one today's code heals, and says so.
     */
    private fun decryptSealedBytes(opened: Opened): ByteArray {
        val cipher = cipher(opened.sender)
        return when (opened.content.type) {
            CiphertextMessage.PREKEY_TYPE -> {
                val message = runCatching { PreKeySignalMessage(opened.content.content) }.getOrNull() ?: throw Sealed.Rejected("C7")
                if (!message.identityKey.serialize().contentEquals(opened.certKey)) throw Sealed.Rejected("C7")
                cipher.decrypt(message)
            }
            CiphertextMessage.WHISPER_TYPE -> {
                if (state.knownIdentities[opened.sender]?.contentEquals(opened.certKey) != true) throw Sealed.Rejected("C7", heal = true)
                cipher.decrypt(SignalMessage(opened.content.content))
            }
            else -> throw Sealed.Rejected("C7")
        }
    }

    /** An opened sealed message → inner Payload. */
    fun decryptSealed(opened: Opened): Wire.Payload = Wire.Payload.decode(decryptSealedBytes(opened))

    /**
     * A sealed blob (`[0x06][sealed bytes]`) → raw bytes. Its sender must be the one its manifest
     * came from, and its certificate is judged at the manifest's timestamp.
     */
    fun decryptSealedBlob(blob: ByteArray, peerHash: String, ts: Long, roots: List<ECPublicKey>, minKeyId: Int): ByteArray {
        if (blob.size <= 1 || blob[0].toInt() != Sealed.ENVELOPE_TYPE) throw Sealed.Rejected("C0")
        val opened = openSealed(blob.copyOfRange(1, blob.size), ts, roots, minKeyId)
        if (opened.sender != peerHash) throw Sealed.Rejected("C4")
        return decryptSealedBytes(opened)
    }
}
