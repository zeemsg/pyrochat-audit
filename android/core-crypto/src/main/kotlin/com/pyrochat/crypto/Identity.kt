package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.SecureRandom

/**
 * One account's long-term secrets, generated exactly like iOS `CryptoEngine.generateIdentity`
 * and `bot/index.mjs ensureIdentity()`:
 *  - [identityKeyPair]: libsignal `IdentityKeyPair.generate()` (X25519, XEdDSA-capable);
 *  - [registrationId]: uniform in `1...16380`;
 *  - [authPrivateKey]: an INDEPENDENT Ed25519 key — 32 random seed bytes (CryptoKit
 *    `Curve25519.Signing.PrivateKey().rawRepresentation`). It is not derived from the identity key.
 */
class Identity(
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
    /** The 32-byte RFC 8032 seed the relay-auth signature uses (iOS `Blob.authPrivateKey`). */
    val authPrivateKey: ByteArray,
) {
    init {
        require(authPrivateKey.size == RelayAuth.SEED_BYTES) { "auth seed must be ${RelayAuth.SEED_BYTES} bytes" }
        require(registrationId in Protocol.REGISTRATION_ID_MIN..Protocol.REGISTRATION_ID_MAX) {
            "registration id $registrationId outside ${Protocol.REGISTRATION_ID_MIN}..${Protocol.REGISTRATION_ID_MAX}"
        }
    }

    val identityKey: IdentityKey get() = identityKeyPair.publicKey

    /** `identity_key` on the wire and in `pyro-add:` QR codes: `IdentityKey.serialize()`, 33 bytes (`0x05` ‖ pub). */
    fun identityKeySerialized(): ByteArray = identityKeyPair.publicKey.serialize()

    /** `auth_pub`: raw 32-byte Ed25519 public key. */
    fun authPublicKey(): ByteArray = RelayAuth.publicKey(authPrivateKey)

    /** Pure-Ed25519 proof over the raw nonce — see [RelayAuth]. */
    fun signChallenge(nonce: ByteArray): ByteArray = RelayAuth.sign(nonce, authPrivateKey)

    /** Write the secrets into a [SignalState] (iOS `store.update { identityKeyPair / registrationId / authPrivateKey }`). */
    fun writeTo(state: SignalState) {
        state.identityKeyPair = identityKeyPair.serialize()
        state.registrationId = registrationId
        state.authPrivateKey = authPrivateKey.copyOf()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): Identity = Identity(
            identityKeyPair = IdentityKeyPair.generate(),
            registrationId = Protocol.REGISTRATION_ID_MIN + random.nextInt(Protocol.REGISTRATION_ID_MAX - Protocol.REGISTRATION_ID_MIN + 1),
            authPrivateKey = RelayAuth.generateSeed(random),
        )

        fun fromState(state: SignalState): Identity = Identity(
            identityKeyPair = IdentityKeyPair(state.identityKeyPair),
            registrationId = state.registrationId,
            authPrivateKey = state.authPrivateKey.copyOf(),
        )
    }
}
