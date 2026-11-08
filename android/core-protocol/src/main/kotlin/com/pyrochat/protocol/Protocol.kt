package com.pyrochat.protocol

/** Constants shared with the iOS app and the relay — see docs/PROTOCOL.md and docs/android/inventory-protocol.md. */
object Protocol {
    /** Envelope version field (`v`). REQUIRED on every envelope — omitting it wedges iOS sessions. */
    const val ENVELOPE_VERSION = 1
    /** libsignal CiphertextMessage types as carried in the envelope `type` field. */
    const val CIPHERTEXT_WHISPER = 2
    const val CIPHERTEXT_PREKEY = 3
    /** Every PyroChat client is device 1 of its account (`CryptoEngine.deviceId`). */
    const val DEVICE_ID = 1

    /** iOS `UInt32.random(in: 1...16380)`; bot `1 + randomInt(16380)`. */
    const val REGISTRATION_ID_MIN = 1
    const val REGISTRATION_ID_MAX = 16380

    /** Signed prekey and last-resort Kyber prekey both use id 1 on every client. */
    const val SIGNED_PRE_KEY_ID = 1
    const val KYBER_PRE_KEY_ID = 1
    /** `nextPreKeyId` default — one-time prekey ids start at 1. */
    const val FIRST_ONE_TIME_PRE_KEY_ID = 1
    /** One-time prekeys generated at signup and per top-up. */
    const val ONE_TIME_PRE_KEY_BATCH = 32
    /** Client tops up when the relay's `prekey_count.n` drops below this. */
    const val ONE_TIME_PRE_KEY_TOP_UP_BELOW = 10

    /** Relay-facing sizes (RelayHandler / Ed25519Verifier). */
    const val AUTH_PUBLIC_KEY_BYTES = 32
    const val CHALLENGE_NONCE_BYTES = 32
    const val AUTH_SIGNATURE_BYTES = 64
    const val IDENTITY_KEY_BYTES = 33

    /** TTLs (seconds) the iOS client actually sends. */
    const val TTL_MSG = 172_800
    const val TTL_BLOB = 172_800

    const val TTL_CONTROL = 86_400
    const val TTL_TYPING = 60

    /** `{"t":"ping"}` cadence once online; relay idle-closes after 90 s of silence. */
    const val PING_INTERVAL_SECONDS = 30
    const val RELAY_IDLE_CLOSE_SECONDS = 90
    /** Largest text frame the relay accepts (UTF-16 units). */
    const val MAX_TEXT_FRAME = 65_536

    const val DEFAULT_RELAY_URL = "wss://relay.pyrochat.ca/ws"
}
