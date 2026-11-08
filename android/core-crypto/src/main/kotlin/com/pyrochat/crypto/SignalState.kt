package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol

/**
 * Everything the Signal-protocol stores need, as plain bytes — no libsignal handles — so
 * `core-store` can put it inside the Swift-compatible `pyro.store` blob later. Field for field this
 * is the crypto slice of iOS `SecureStore.Blob` (ios/PyroChat/Core/SecureStore.swift) that
 * `SignalStore.swift` and `CryptoEngine.swift` read and write. Persisting it is NOT this module's
 * job: the iOS JSON shape (`[UInt32: Data]` as a flat array, base64 `Data`, …) lives in core-store.
 *
 * Every mutation made by libsignal callbacks lands here synchronously on the calling thread,
 * exactly as `store.update { … }` does on iOS.
 */
class SignalState(
    /** iOS `Blob.usernameHash` — the `ProtocolAddress.name` of this device (deviceId 1). */
    var usernameHash: String = "",
    /** iOS `Blob.identityKeyPair` = `IdentityKeyPair.serialize()` (libsignal protobuf key pair). */
    var identityKeyPair: ByteArray = ByteArray(0),
    /** iOS `Blob.registrationId` — `UInt32.random(in: 1...16380)`. */
    var registrationId: Int = 0,
    /** iOS `Blob.authPrivateKey` — the Ed25519 `rawRepresentation`: the 32-byte RFC 8032 seed. */
    var authPrivateKey: ByteArray = ByteArray(0),
    /** iOS `Blob.sessions` — `{peerHash: SessionRecord.serialize()}`. */
    val sessions: MutableMap<String, ByteArray> = LinkedHashMap(),
    /** iOS `Blob.preKeys` — `[UInt32: Data]` of `PreKeyRecord.serialize()`. */
    val preKeys: MutableMap<Int, ByteArray> = LinkedHashMap(),
    /** iOS `Blob.signedPreKeys` — `[UInt32: Data]` of `SignedPreKeyRecord.serialize()` (id 1 only). */
    val signedPreKeys: MutableMap<Int, ByteArray> = LinkedHashMap(),
    /** iOS `Blob.kyberPreKeys` — `[UInt32: Data]` of `KyberPreKeyRecord.serialize()` (id 1 only, never consumed). */
    val kyberPreKeys: MutableMap<Int, ByteArray> = LinkedHashMap(),
    /** iOS `Blob.knownIdentities` — `{peerHash: IdentityKey.serialize()}` (33 bytes, `0x05` ‖ pub). */
    val knownIdentities: MutableMap<String, ByteArray> = LinkedHashMap(),
    /** iOS `Blob.nextPreKeyId` — default 1; advanced by every one-time prekey generated. */
    var nextPreKeyId: Int = Protocol.FIRST_ONE_TIME_PRE_KEY_ID,
) {
    val hasIdentity: Boolean get() = identityKeyPair.isNotEmpty() && usernameHash.isNotEmpty()
}
