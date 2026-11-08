package com.pyrochat.service

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator

/**
 * iOS `ChatService.SafetyNumber`: [digits] are the 60 decimal digits both devices render
 * (`fingerprint.displayable.formatted` ↔ `getDisplayText()`, no separators — the UI groups them);
 * [scannable] is libsignal's scannable encoding, which differs per side by design.
 */
class SafetyNumber(val digits: String, val scannable: ByteArray)

/**
 * iOS `CryptoEngine.fingerprint(with:)` with Signal's production parameters. NEVER change the
 * constants without a coordinated client-wide version bump — a mismatch makes two honest,
 * up-to-date clients display different numbers. Identifiers are the username hashes (UTF-8) —
 * stable and identical on both devices, so the number changes IFF a key changes.
 */
object SafetyNumbers {
    /** `CryptoEngine.fingerprintIterations` */
    const val ITERATIONS = 5200

    /** `CryptoEngine.fingerprintVersion` */
    const val VERSION = 2

    fun compute(
        localHash: String,
        localIdentityKeyPair: ByteArray,
        remoteHash: String,
        remoteIdentityKey: ByteArray,
    ): SafetyNumber {
        val local = IdentityKeyPair(localIdentityKeyPair).publicKey
        val remote = IdentityKey(remoteIdentityKey)
        val fp = NumericFingerprintGenerator(ITERATIONS).createFor(
            VERSION,
            localHash.toByteArray(Charsets.UTF_8),
            local,
            remoteHash.toByteArray(Charsets.UTF_8),
            remote,
        )
        return SafetyNumber(fp.displayableFingerprint.displayText, fp.scannableFingerprint.serialized)
    }
}
