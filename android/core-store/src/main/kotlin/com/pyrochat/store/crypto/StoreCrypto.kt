package com.pyrochat.store.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based key derivation as `SecureStore.deriveKey` uses it (`SecureStore.swift:65-80`).
 * The JVM implementation is [Pbkdf2HmacSha256]; the Android app plugs in the NDK one from
 * `kdf-native` behind the same signature.
 */
fun interface Kdf {
    /** PBKDF2-HMAC-SHA256 over the RAW password bytes (no Unicode normalisation, no trimming). */
    fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray
}

/** Pure `javax.crypto.Mac` PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) — ~0.5 s for 600 000 iterations. */
object Pbkdf2HmacSha256 : Kdf {
    private const val ALGORITHM = "HmacSHA256"

    override fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        require(iterations >= 1) { "iterations must be positive" }
        require(keyLen >= 1) { "keyLen must be positive" }
        val mac = Mac.getInstance(ALGORITHM)
        // HMAC zero-pads keys shorter than the block size, so an empty password keys identically to
        // one zero byte — and SecretKeySpec refuses an empty array.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, ALGORITHM))
        val hLen = mac.macLength
        val out = ByteArray(keyLen)
        var offset = 0
        var block = 1
        while (offset < keyLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, keyLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }
}

/**
 * The primitives shared by the store file, every attachment sidecar and the backup
 * (SPEC.md §1-2, §7-8): `key = PBKDF2-HMAC-SHA256(utf8(password), salt, 600 000, 32)` and
 * `sealed = nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16)` with no AAD, no header — CryptoKit's
 * `AES.GCM.SealedBox.combined`.
 */
object StoreCrypto {
    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** `SecureStore.deriveKey(password:salt:)` — password as raw UTF-8. */
    fun deriveKey(kdf: Kdf, password: String, salt: ByteArray): ByteArray =
        kdf.derive(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERATIONS, KEY_BYTES)

    fun randomBytes(count: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(count).also(random::nextBytes)

    /** `AES.GCM.seal(plaintext, using: key).combined` with a fresh random nonce. */
    fun seal(key: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key must be $KEY_BYTES bytes" }
        val nonce = randomBytes(NONCE_BYTES, random)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /**
     * `AES.GCM.open(SealedBox(combined:))`. Throws [GeneralSecurityException] (an
     * [AEADBadTagException] for a wrong key or tampered bytes) — the caller decides whether that is
     * "bad password" or "unreadable sidecar".
     */
    fun open(key: ByteArray, combined: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key must be $KEY_BYTES bytes" }
        if (combined.size < NONCE_BYTES + TAG_BYTES) throw AEADBadTagException("sealed box too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val nonce = combined.copyOfRange(0, NONCE_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        return cipher.doFinal(combined, NONCE_BYTES, combined.size - NONCE_BYTES)
    }
}

/**
 * Duress PIN verifier (`SecureStore.swift:90-101`): the same PBKDF2 output under the same Keychain
 * salt, stored instead of the PIN and compared before any unlock attempt. iOS compares with plain
 * `==`; this compares in constant time.
 */
object DuressVerifier {
    fun compute(kdf: Kdf, pin: String, salt: ByteArray): ByteArray = StoreCrypto.deriveKey(kdf, pin, salt)

    fun matches(kdf: Kdf, candidate: String, salt: ByteArray, stored: ByteArray): Boolean =
        MessageDigest.isEqual(compute(kdf, candidate, salt), stored)
}
