package com.pyrochat.store

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cross-platform container test vector of docs/DECOY-ACCOUNT.md §7.
 *
 * A container is 12,582,925 bytes of mostly incompressible padding, so neither platform checks one
 * in. Instead both pin the INPUTS and compare one SHA-256:
 *
 * ```
 * stream(seed, n): out = "" ; ctr = 0
 *                  while len(out) < n: out += SHA-256(seed(32) ‖ ctr(4, big-endian)); ctr += 1
 *                  return out[:n]
 * seed    = SHA-256("pyrochat-decoy-fixture-v1")
 * payload = the checked-in plaintext JSON, sealed VERBATIM — never re-encoded from a Blob
 * half    = 0, seq = 1, slot pinned by the test
 * key     = PBKDF2(fixture password, pinned kdf-salt)
 * ```
 *
 * A platform RNG cannot be the byte source (`java.util.Random` and `SecRandomCopyBytes` are not the
 * same stream); the counter construction is. Sealing the payload verbatim sidesteps JSON key
 * ordering, which is why §7 says byte-identity is unreachable for ordinary saves.
 *
 * **Draw order**, as `android/fixtures/container-vector.json` pins it — ONE stream per container,
 * the counter running on across requests (a request that is not a multiple of 32 keeps the
 * remainder for the next one):
 *
 * 1. the sealed half's padding — `halfCapacity - seq(8) - length(4) - payload`,
 * 2. the sealed half's 12-byte nonce,
 * 3. every remaining half, in increasing FILE OFFSET, [StoreContainer.HALF_BYTES] each.
 */
object ContainerVector {

    const val SEED_LABEL = "pyrochat-decoy-fixture-v1"

    val SEED: ByteArray = sha256(SEED_LABEL.toByteArray(Charsets.US_ASCII))

    /** `SHA-256(seed ‖ ctr(4, big-endian))` blocks, concatenated and cut to [n]. */
    fun stream(seed: ByteArray, n: Int): ByteArray {
        require(seed.size == 32) { "the seed is a SHA-256 digest" }
        require(n >= 0)
        val out = ByteArray(n)
        var off = 0
        var ctr = 0
        while (off < n) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(seed)
            md.update(byteArrayOf((ctr ushr 24).toByte(), (ctr ushr 16).toByte(), (ctr ushr 8).toByte(), ctr.toByte()))
            val block = md.digest()
            val take = minOf(block.size, n - off)
            System.arraycopy(block, 0, out, off, take)
            off += take
            ctr++
        }
        return out
    }

    /**
     * The whole container, determined byte for byte by ([key], [payload], [slot], [seed]).
     * Half A of [slot] holds [payload] at `seq = 1`; everything else is stream bytes.
     */
    fun build(
        key: ByteArray,
        payload: ByteArray,
        slot: Int,
        seed: ByteArray = SEED,
        half: Int = 0,
        seq: Long = StoreContainer.FIRST_SEQ,
    ): ByteArray {
        require(slot in 0 until StoreContainer.SLOT_COUNT)
        require(half in 0 until StoreContainer.HALVES_PER_SLOT)
        val prefix = StoreContainer.SEQ_BYTES + StoreContainer.LENGTH_BYTES
        val padBytes = StoreContainer.HALF_PLAINTEXT_BYTES - prefix - payload.size
        require(padBytes >= 0) { "payload does not fit in a half" }
        val others = StoreContainer.SLOT_COUNT * StoreContainer.HALVES_PER_SLOT - 1
        // ONE stream, drawn in the pinned order; slices of it continue the counter mid-block
        val src = Stream(stream(seed, padBytes + NONCE_BYTES + others * StoreContainer.HALF_BYTES))
        val padding = src.take(padBytes)
        val nonce = src.take(NONCE_BYTES)

        val out = ByteArray(StoreContainer.FILE_BYTES)
        System.arraycopy(StoreContainer.MAGIC, 0, out, 0, StoreContainer.MAGIC.size)
        out[8] = StoreContainer.SLOT_COUNT.toByte()
        StoreContainer.writeInt(out, 9, StoreContainer.SLOT_BYTES)

        val plain = ByteArray(StoreContainer.HALF_PLAINTEXT_BYTES)
        StoreContainer.writeLong(plain, 0, seq)
        StoreContainer.writeInt(plain, StoreContainer.SEQ_BYTES, payload.size)
        System.arraycopy(payload, 0, plain, prefix, payload.size)
        System.arraycopy(padding, 0, plain, prefix + payload.size, padding.size)
        val sealed = seal(key, nonce, plain)
        check(sealed.size == StoreContainer.HALF_BYTES)
        System.arraycopy(sealed, 0, out, StoreContainer.halfRange(slot, half).first, sealed.size)

        for (s in 0 until StoreContainer.SLOT_COUNT) {
            for (h in 0 until StoreContainer.HALVES_PER_SLOT) {
                if (s == slot && h == half) continue                    // increasing file offset
                System.arraycopy(src.take(StoreContainer.HALF_BYTES), 0, out, StoreContainer.halfRange(s, h).first, StoreContainer.HALF_BYTES)
            }
        }
        return out
    }

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    const val NONCE_BYTES = 12

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** AES-256-GCM with an EXPLICIT nonce — test-only, because reusing a nonce in production is fatal. */
    private fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private class Stream(private val bytes: ByteArray) {
        private var at = 0
        fun take(n: Int): ByteArray {
            val slice = bytes.copyOfRange(at, at + n)
            at += n
            return slice
        }
    }
}
