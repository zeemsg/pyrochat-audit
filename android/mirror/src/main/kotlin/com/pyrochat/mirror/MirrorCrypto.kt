package com.pyrochat.mirror

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Any failure of the pair-channel crypto (GCM tag, short record, degenerate X25519 result). */
class MirrorCryptoException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * The pair-channel record crypto — port of iOS `MirrorCrypto` (`ios/PyroChat/Core/MirrorLink.swift`),
 * constants FROZEN by `docs/MIRROR-PROTOCOL.md` §3–4. Three implementations must agree byte-for-byte:
 * this one, CryptoKit on iOS, and WebCrypto in `relay/src/main/resources/web/index.html`.
 *
 * Keys on the wire are RAW 32-byte X25519 points (no libsignal `0x05` prefix anywhere): the QR
 * payload, the 0x01 record prefix, and both `b64url(pPub)|b64url(bPub)` KDF info strings.
 */
object MirrorCrypto {
    /** iOS `MirrorCrypto.Keys`: `p2b` phone→browser, `b2p` browser→phone, `sas` 6 digits shown on both ends. */
    class Keys(val p2b: ByteArray, val b2p: ByteArray, val sas: String)

    const val TYPE_HELLO: Int = 0x01
    const val TYPE_HELLO_ACK: Int = 0x02
    const val TYPE_DATA: Int = 0x03
    const val DIR_P2B = "p2b"
    const val DIR_B2P = "b2p"
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    /** type(1) ‖ seq(8) */
    const val HEADER_BYTES = 9

    private val random = SecureRandom()

    // ---- base64url (no padding) — iOS `MirrorCrypto.b64url` / `Data(base64urlNoPad:)` ----

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * iOS `Data(base64urlNoPad:)`: map `-`→`+`, `_`→`/`, pad with `=` to a multiple of 4, then decode
     * as standard base64 (so a padded/standard string decodes too). Null on anything invalid.
     */
    fun b64urlDecode(s: String): ByteArray? {
        val sb = StringBuilder(s.replace('-', '+').replace('_', '/'))
        while (sb.length % 4 != 0) sb.append('=')
        return try {
            Base64.getDecoder().decode(sb.toString())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ---- X25519 (raw 32-byte keys) — iOS `Curve25519.KeyAgreement` ----

    /** A fresh random private scalar (clamping happens inside the scalar multiplication, like CryptoKit). */
    fun randomPrivateKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Raw public point for a raw private scalar — iOS `PrivateKey.publicKey.rawRepresentation`. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "X25519 private key must be 32 bytes" }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /**
     * `ss = X25519(ownPriv, peerPub)` — iOS `sharedSecretFromKeyAgreement`. Throws
     * [MirrorCryptoException] on a malformed key or an all-zero result (MIRROR-PROTOCOL.md §3 "reject all-zero").
     */
    fun agree(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        if (privateKey.size != KEY_BYTES || peerPublic.size != KEY_BYTES) {
            throw MirrorCryptoException("X25519 keys must be 32 bytes")
        }
        val out = ByteArray(KEY_BYTES)
        try {
            X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle refuses an all-zero agreement (low-order peer point) with this exception
            throw MirrorCryptoException("X25519 agreement failed (degenerate peer key)", e)
        }
        if (out.all { it == 0.toByte() }) throw MirrorCryptoException("X25519 agreement is all-zero")
        return out
    }

    // ---- HKDF-SHA256 key schedule — iOS `MirrorCrypto.deriveKeys` ----

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    /**
     * ```
     * salt    = UTF8("pyro-link-v1|" + pairId)
     * infoP2B = UTF8("pyro-link-v1|p2b|" + b64url(pPub) + "|" + b64url(bPub))    → k_p2b (32 B)
     * infoB2P = UTF8("pyro-link-v1|b2p|" + …)                                     → k_b2p (32 B)
     * infoSAS = UTF8("pyro-link-v1|sas|" + …)                                     → 4 B, UInt32BE mod 1e6, "%06d"
     * ```
     */
    fun deriveKeys(sharedSecret: ByteArray, pairId: String, phonePub: ByteArray, browserPub: ByteArray): Keys {
        val salt = "pyro-link-v1|$pairId".toByteArray(Charsets.UTF_8)
        val suffix = "${b64url(phonePub)}|${b64url(browserPub)}"
        val p2b = hkdfSha256(sharedSecret, salt, "pyro-link-v1|p2b|$suffix".toByteArray(Charsets.UTF_8), KEY_BYTES)
        val b2p = hkdfSha256(sharedSecret, salt, "pyro-link-v1|b2p|$suffix".toByteArray(Charsets.UTF_8), KEY_BYTES)
        val sasBytes = hkdfSha256(sharedSecret, salt, "pyro-link-v1|sas|$suffix".toByteArray(Charsets.UTF_8), 4)
        val n = (ByteBuffer.wrap(sasBytes).int.toLong() and 0xFFFF_FFFFL) % 1_000_000L
        // Locale.ROOT: Android's Formatter localises %d (ar/fa/bn/ne/mr/my phones would render
        // Arabic-Indic or Devanagari digits), and the browser compares the SAS byte-for-byte.
        return Keys(p2b, b2p, String.format(Locale.ROOT, "%06d", n))
    }

    // ---- record framing — iOS `MirrorCrypto.nonce/aad/seal/open` ----

    /** 12 bytes: ASCII dir (`p2b`/`b2p`) ‖ `0x00` ‖ seq (8 B big-endian). */
    fun nonce(dir: String, seq: Long): ByteArray =
        ByteBuffer.allocate(12).put(dir.toByteArray(Charsets.US_ASCII)).put(0.toByte()).putLong(seq).array()

    /** `UTF8(dir + "|" + pairId + "|") ‖ type`. */
    fun aad(dir: String, pairId: String, type: Int): ByteArray {
        val head = "$dir|$pairId|".toByteArray(Charsets.UTF_8)
        return head.copyOf(head.size + 1).also { it[head.size] = type.toByte() }
    }

    /** seq (8 B big-endian) as carried in the record header. */
    fun seqBytes(seq: Long): ByteArray = ByteBuffer.allocate(8).putLong(seq).array()

    /** Parse the 8-byte big-endian seq at `record[1..8]`. */
    fun seqOf(record: ByteArray): Long = ByteBuffer.wrap(record, 1, 8).long

    /**
     * Build a full record: `type ‖ seq ‖ prefix ‖ GCM ciphertext ‖ tag`. For type 0x01 the caller
     * passes the raw phone pub as [prefix] (it is NOT authenticated by the GCM — same as iOS).
     */
    fun seal(
        key: ByteArray, dir: String, pairId: String, type: Int, seq: Long,
        plaintext: ByteArray, prefix: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(dir, seq)))
        cipher.updateAAD(aad(dir, pairId, type))
        val box = cipher.doFinal(plaintext) // ciphertext ‖ tag, exactly CryptoKit's box.ciphertext + box.tag
        return ByteBuffer.allocate(HEADER_BYTES + prefix.size + box.size)
            .put(type.toByte()).putLong(seq).put(prefix).put(box).array()
    }

    /**
     * Open a record's encrypted portion (the caller has already split off type/seq and any prefix).
     * Throws [MirrorCryptoException] on a short input or a failed tag — the caller must tear the link down.
     */
    fun open(key: ByteArray, dir: String, pairId: String, type: Int, seq: Long, sealed: ByteArray): ByteArray {
        if (sealed.size < TAG_BYTES) throw MirrorCryptoException("record too short")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(dir, seq)))
            cipher.updateAAD(aad(dir, pairId, type))
            cipher.doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            throw MirrorCryptoException("record authentication failed", e)
        }
    }
}
