package com.pyrochat.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * Relay login proof — iOS `CryptoEngine.signChallenge` (CryptoKit `Curve25519.Signing`).
 *
 * This is **pure Ed25519 (RFC 8032)** over the raw 32-byte nonce with the 32-byte seed stored as
 * `authPrivateKey`; the relay verifies with the JDK `Signature.getInstance("Ed25519")`
 * (relay `Ed25519Verifier`). It is NOT libsignal's `ECPrivateKey.calculateSignature` — that is
 * XEdDSA over an X25519 key and the relay answers `bad_sig` (proved by the gate test).
 * The auth key is an independent random Ed25519 key, not derived from the identity key.
 *
 * BouncyCastle is used so seed-based signing works on every Android API level (JCA "Ed25519"
 * only exists on API 33+).
 */
object RelayAuth {
    const val SEED_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** iOS `Curve25519.Signing.PrivateKey().rawRepresentation` — 32 random bytes. */
    fun generateSeed(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SEED_BYTES).also(random::nextBytes)

    /** `auth_pub`: the raw 32-byte RFC 8032 public key (`publicKey.rawRepresentation` on iOS). */
    fun publicKey(seed: ByteArray): ByteArray = privateKey(seed).generatePublicKey().encoded

    /** 64-byte signature over [message] (the DECODED nonce bytes, not the base64 text). */
    fun sign(message: ByteArray, seed: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey(seed))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun sign(message: ByteArray, identity: Identity): ByteArray = sign(message, identity.authPrivateKey)

    /** `auth.sig`: base64 of [sign] over the base64-decoded `challenge.nonce`. */
    fun signChallenge(nonceB64: String, seed: ByteArray): String =
        Base64.getEncoder().encodeToString(sign(Base64.getDecoder().decode(nonceB64), seed))

    /** Local self-check with the same algorithm the relay uses. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    private fun privateKey(seed: ByteArray): Ed25519PrivateKeyParameters {
        require(seed.size == SEED_BYTES) { "Ed25519 seed must be $SEED_BYTES bytes, got ${seed.size}" }
        return Ed25519PrivateKeyParameters(seed, 0)
    }
}
