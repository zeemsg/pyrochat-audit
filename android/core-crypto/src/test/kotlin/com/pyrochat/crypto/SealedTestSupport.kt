package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.metadata.certificate.ServerCertificate
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.Optional
import java.util.UUID

/**
 * A stand-in for the relay's signing side, built with libsignal itself: a trust root, a server
 * certificate under it, and sender certificates and bindings under that.
 */
class SealedTestAuthority(keyId: Int = 1) {
    private val rootPair = ECKeyPair.generate()
    private val serverPair = ECKeyPair.generate()
    val server: ECPrivateKey get() = serverPair.privateKey
    val serverCert = ServerCertificate(rootPair.privateKey, keyId, serverPair.publicKey)
    val roots: List<ECPublicKey> get() = listOf(rootPair.publicKey)

    /** [bindHash] signs the binding for another account than the certificate names. */
    fun issue(hash: ByteArray, identityKey: ECPublicKey, expires: Long, device: Int = 1, bindHash: ByteArray? = null, bindExpires: Long? = null): Sealed.Own {
        val cert = serverCert.issue(server, Sealed.uuid(hash), Optional.empty(), device, identityKey, expires)
        val signed = Sealed.bindingMessage(bindHash ?: hash, identityKey.serialize(), bindExpires ?: expires)
        return Sealed.Own(cert, server.calculateSignature(signed))
    }
}

/** One identity with its own state: the crypto half of a phone. */
class SealedParty(name: String, authority: SealedTestAuthority? = null) {
    val crypto = CryptoEngine(SignalState())
    val reg = crypto.generateIdentity(name + UUID.randomUUID().toString().take(8))
    val hash: String get() = crypto.state.usernameHash
    val hashBytes: ByteArray get() = Sealed.hashBytes(hash)!!
    val identity: ECPublicKey get() = ECPublicKey(reg.identityKey)
    val expires: Long = System.currentTimeMillis() + 86_400_000
    val own: Sealed.Own? = authority?.issue(hashBytes, identity, expires)

    /** My prekey bundle, as the relay would hand it out. */
    fun bundle(asUser: String? = null): Wire.In.PreKeyBundle =
        PreKeys.bundleFromRegister(crypto.registerFrame(reg)).let { if (asUser != null) it.copy(user = asUser) else it }

    private fun address(h: String) = SignalProtocolAddress(h, Protocol.DEVICE_ID)

    /** Today's ciphertext for a peer, as it would cross the relay on the legacy path. */
    fun legacyCiphertext(to: String, plaintext: ByteArray): CiphertextMessage =
        SessionCipher(crypto.stores, crypto.stores, crypto.stores, crypto.stores, crypto.stores, address(hash), address(to)).encrypt(plaintext)

    /** Seal by hand, with any certificate and any `groupId`: what an attacker can build. */
    fun rawSeal(to: String, cert: SenderCertificate, groupId: ByteArray, inner: CiphertextMessage? = null): ByteArray {
        val ciphertext = inner ?: legacyCiphertext(to, "x".toByteArray())
        val content = UnidentifiedSenderMessageContent(ciphertext, cert, UnidentifiedSenderMessageContent.CONTENT_HINT_DEFAULT, Optional.of(groupId))
        return SealedSessionCipher(crypto.stores, UUID(0L, 0L), null, Protocol.DEVICE_ID).encrypt(address(to), content)
    }
}
