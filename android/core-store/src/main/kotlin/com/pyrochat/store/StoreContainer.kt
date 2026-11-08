package com.pyrochat.store

import com.pyrochat.store.crypto.StoreCrypto
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The multi-slot `pyro.store` container — docs/DECOY-ACCOUNT.md §1-2, §7.
 *
 * ```
 * file      = "PYROVLT1"(8) ‖ slotCount(1) ‖ slotBytes(4, big-endian) ‖ slot[0] slot[1] slot[2]
 * slot      = half A(2097152) ‖ half B(2097152)
 * half      = nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16)
 * plaintext = seq(8, big-endian) ‖ length(4, big-endian) ‖ JSON(Blob) ‖ random padding, 2097124 bytes
 * dead slot = 4 MiB of CSPRNG bytes, written ONCE when the container is created
 * ```
 *
 * THE FORMAT IS PINNED: [SLOT_COUNT] is 3 and [SLOT_BYTES] is 4 MiB whatever the device holds, so
 * the file is always exactly [FILE_BYTES] and its size says nothing at all. The header is read
 * STRICTLY — a slot size other than the constant is corruption, not something to reinterpret; a
 * different geometry would need a different magic string.
 *
 * **Never write to a slot you cannot open (§1).** A slot that does not authenticate under your key
 * is either noise or another account's ciphertext and nothing can tell the two apart, so refreshing
 * noise and destroying a decoy are the same operation. Only [buildNew] — creation and migration,
 * where there is provably nothing to lose — ever writes a slot it cannot open.
 *
 * **A slot is two halves because an in-place write cannot be atomic (§1).** A save seals the OTHER
 * half at `seq + 1` and writes only that half's range, so the half holding the last good copy is
 * never the half being written and a power cut costs the stale copy. [open] tries both halves of
 * every slot — six attempts, always all six — and the live half is the authenticating one with the
 * highest `seq`.
 *
 * The magic is in the clear on purpose: it says "this app can hold decoys", which is public. It
 * never says whether this device does.
 */
object StoreContainer {

    /** ASCII "PYROVLT1". */
    val MAGIC: ByteArray = "PYROVLT1".toByteArray(Charsets.US_ASCII)

    /** Always three slots, whatever the device holds (§1). */
    const val SLOT_COUNT = 3

    /** magic(8) ‖ slotCount(1) ‖ slotBytes(4). */
    const val HEADER_BYTES = 13

    /** 4 MiB, fixed. It never grows: a growing container would have to re-pad slots it cannot read. */
    const val SLOT_BYTES = 4 * 1024 * 1024

    /** Two halves per slot; a save alternates between them so a torn write costs only the stale copy. */
    const val HALVES_PER_SLOT = 2

    /** 2 MiB — one half, sealed whole. */
    const val HALF_BYTES = SLOT_BYTES / HALVES_PER_SLOT

    /** nonce(12) + tag(16): what a half spends on the AEAD rather than on plaintext. */
    const val AEAD_BYTES = StoreCrypto.NONCE_BYTES + StoreCrypto.TAG_BYTES

    /** The big-endian write counter, first in the sealed plaintext. Starts at 1, never rolls over. */
    const val SEQ_BYTES = 8

    /** The big-endian payload length, sealed right after [SEQ_BYTES]. */
    const val LENGTH_BYTES = 4

    /** What one half's payload unseals into: `seq(8) ‖ length(4) ‖ JSON ‖ padding`. */
    const val HALF_PLAINTEXT_BYTES = HALF_BYTES - AEAD_BYTES

    /** Every container is exactly this long — 12 MiB + 13 bytes — fresh install or heavy one. */
    const val FILE_BYTES = HEADER_BYTES + SLOT_COUNT * SLOT_BYTES

    /** The largest `JSON(Blob)` a half can hold: 2097112 bytes. §1a keeps audio out of it. */
    const val MAX_JSON_BYTES = HALF_PLAINTEXT_BYTES - SEQ_BYTES - LENGTH_BYTES

    /** The first sequence number a freshly authored half carries. */
    const val FIRST_SEQ = 1L

    /** The half of a slot that authenticated, and what it held. */
    data class Opened(
        /** The slot index — the lowest one, if more than one authenticated. */
        val slot: Int,
        /** Which half is live: the authenticating half with the highest [seq]. */
        val half: Int,
        /** That half's write counter. The next save writes the other half at `seq + 1`. */
        val seq: Long,
        /** `JSON(Blob)` with the padding stripped. */
        val json: ByteArray,
        /** How many halves were tried. Always every half of every slot (§1-2). */
        val attempts: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Opened && slot == other.slot && half == other.half && seq == other.seq &&
                attempts == other.attempts && json.contentEquals(other.json)

        override fun hashCode(): Int = (((slot * 31 + half) * 31 + seq.hashCode()) * 31 + attempts) * 31 + json.contentHashCode()
    }

    /** The parsed header. Strict: it only ever holds the build's own constants. */
    data class Layout(val slotCount: Int, val slotBytes: Int) {
        val size: Int get() = HEADER_BYTES + slotCount * slotBytes
        fun offset(slot: Int): Int = HEADER_BYTES + slot * slotBytes
    }

    /** True when [bytes] carries the magic — i.e. is a container and not today's single sealed blob. */
    fun isContainer(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    /**
     * Header of a container, or null when there is no magic. Throws [StoreError.Corrupt] when the
     * magic is there and anything else is off — a slot count or slot size other than this build's,
     * or a file length that does not match. Strict on purpose: an anomalous header is corruption,
     * and a different geometry would need a different magic string, never a silent reinterpretation.
     * [totalBytes] defaults to `bytes.size`, for a whole file held in memory.
     */
    fun layout(bytes: ByteArray, totalBytes: Long = bytes.size.toLong()): Layout? {
        if (!isContainer(bytes)) return null
        val slotCount = bytes[8].toInt() and 0xFF
        val slotBytes = readInt(bytes, 9)
        if (slotCount != SLOT_COUNT) throw corrupt("container declares $slotCount slots, this build writes $SLOT_COUNT")
        if (slotBytes != SLOT_BYTES) throw corrupt("container declares $slotBytes-byte slots, this build writes $SLOT_BYTES")
        if (totalBytes != FILE_BYTES.toLong()) throw corrupt("container is $totalBytes bytes, a container is $FILE_BYTES")
        return Layout(slotCount, slotBytes)
    }

    /** Where slot [slot] lives in the file: `[offset, offset + slotBytes)`. */
    fun slotRange(slot: Int): IntRange {
        require(slot in 0 until SLOT_COUNT) { "slot $slot is outside the container" }
        val offset = HEADER_BYTES + slot * SLOT_BYTES
        return offset until (offset + SLOT_BYTES)
    }

    /** Where one half lives: the range a save overwrites, and nothing else. */
    fun halfRange(slot: Int, half: Int): IntRange {
        require(half in 0 until HALVES_PER_SLOT) { "half $half does not exist" }
        val offset = slotRange(slot).first + half * HALF_BYTES
        return offset until (offset + HALF_BYTES)
    }

    /** The half a save writes next: the one that is NOT live. */
    fun otherHalf(half: Int): Int = 1 - half

    /** A uniformly random slot index — creation never defaults to slot 0 (§2). */
    fun randomSlot(random: SecureRandom): Int = random.nextInt(SLOT_COUNT)

    /**
     * Try [key] against EVERY half of EVERY slot, in order, and return the live one — the
     * authenticating half with the highest `seq` — or null when none authenticates (the caller
     * turns that into [StoreError.BadPassword]).
     *
     * **Never exits early, by design (§2).** Stopping at the first match would make slot 0
     * measurably faster to open than slot 2, and that timing difference says which account a
     * password belongs to. The one PBKDF2 derivation dominates the cost anyway.
     *
     * [onAttempt] is the test seam for "all six halves were tried" — assert the count, never the clock.
     */
    fun open(
        key: ByteArray,
        bytes: ByteArray,
        onAttempt: ((slot: Int, half: Int, matched: Boolean) -> Unit)? = null,
    ): Opened? {
        val layout = layout(bytes) ?: throw corrupt("not a container")
        var best: Opened? = null
        var attempts = 0
        for (slot in 0 until layout.slotCount) {
            for (half in 0 until HALVES_PER_SLOT) {
                val plain = unseal(key, bytes, slot, half)
                attempts++
                onAttempt?.invoke(slot, half, plain != null)
                if (plain == null) continue
                val seq = readLong(plain, 0)
                // strictly greater: on the impossible tie the first half in scan order wins
                if (best == null || seq > best!!.seq) {
                    best = Opened(slot, half, seq, unpad(plain), 0)
                }
            }
        }
        return best?.copy(attempts = attempts)
    }

    /** The live payload of ONE slot, or null when neither half authenticates. */
    fun openSlot(key: ByteArray, bytes: ByteArray, slot: Int): ByteArray? {
        layout(bytes) ?: throw corrupt("not a container")
        require(slot in 0 until SLOT_COUNT) { "slot $slot is outside the container" }
        var bestSeq = Long.MIN_VALUE
        var bestJson: ByteArray? = null
        for (half in 0 until HALVES_PER_SLOT) {
            val plain = unseal(key, bytes, slot, half) ?: continue
            val seq = readLong(plain, 0)
            if (seq > bestSeq) {
                bestSeq = seq
                bestJson = unpad(plain)
            }
        }
        return bestJson
    }

    private fun unseal(key: ByteArray, bytes: ByteArray, slot: Int, half: Int): ByteArray? {
        val range = halfRange(slot, half)
        return try {
            StoreCrypto.open(key, bytes.copyOfRange(range.first, range.last + 1))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /**
     * A WHOLE NEW container holding [json] sealed under [key] at index [slot]; that slot's first
     * half is real at [FIRST_SEQ], its second half and every other slot are CSPRNG noise.
     *
     * This is the only function that writes bytes into a slot it cannot open, and it is only ever
     * called where there is provably nothing to lose: creating a store, restoring a backup, and
     * migrating a pre-container file. An ordinary save must use [sealHalf] on the other half.
     */
    fun buildNew(key: ByteArray, json: ByteArray, slot: Int, random: SecureRandom): ByteArray {
        require(slot in 0 until SLOT_COUNT) { "slot $slot is outside the container" }
        return assemble(mapOf(slot to newSlot(key, json, random)), random)
    }

    /** A freshly authored slot: half A real at [FIRST_SEQ], half B noise until the first save fills it. */
    fun newSlot(key: ByteArray, json: ByteArray, random: SecureRandom): ByteArray =
        sealHalf(key, json, FIRST_SEQ, random) + StoreCrypto.randomBytes(HALF_BYTES, random)

    /**
     * A container of nothing but noise — docs/DECOY-ACCOUNT.md §5, "the container is created at
     * first run and is never deleted".
     *
     * This is what a device holds before it has any account at all, and what every wipe path leaves
     * behind. Wiped, never used, and holding two accounts are then the same [FILE_BYTES], and the
     * first screen has nothing to read them off. Authoring it costs one pass of the CSPRNG.
     */
    fun buildEmpty(random: SecureRandom): ByteArray = assemble(emptyMap(), random)

    /**
     * A container from already-sealed slot regions: index -> exactly [SLOT_BYTES] bytes. Indices not
     * in [occupied] become CSPRNG noise.
     *
     * The write primitive behind [buildNew], and the seam a caller uses to pass another account's
     * ciphertext through verbatim instead of letting noise be written over it.
     */
    fun assemble(occupied: Map<Int, ByteArray>, random: SecureRandom): ByteArray {
        require(occupied.keys.all { it in 0 until SLOT_COUNT }) { "a slot index is outside the container" }
        val out = ByteArray(FILE_BYTES)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[8] = SLOT_COUNT.toByte()
        writeInt(out, 9, SLOT_BYTES)
        for (i in 0 until SLOT_COUNT) {
            val region = occupied[i] ?: StoreCrypto.randomBytes(SLOT_BYTES, random)
            require(region.size == SLOT_BYTES) { "slot $i is ${region.size} bytes, expected $SLOT_BYTES" }
            System.arraycopy(region, 0, out, HEADER_BYTES + i * SLOT_BYTES, SLOT_BYTES)
        }
        return out
    }

    /**
     * ONE half: `nonce(12) ‖ AES-256-GCM(seq(8) ‖ length(4) ‖ json ‖ random padding) ‖ tag(16)`,
     * exactly [HALF_BYTES] long — what a save writes over the non-live half's range and nothing else.
     *
     * Throws [StoreError.TooLarge] when the account no longer fits: a half cannot grow, so the
     * caller must surface that rather than write a truncated store.
     */
    fun sealHalf(key: ByteArray, json: ByteArray, seq: Long, random: SecureRandom): ByteArray {
        require(seq >= FIRST_SEQ) { "seq starts at $FIRST_SEQ" }
        if (json.size > MAX_JSON_BYTES) throw StoreError.TooLarge(json.size, MAX_JSON_BYTES)
        val plain = ByteArray(HALF_PLAINTEXT_BYTES)
        writeLong(plain, 0, seq)
        writeInt(plain, SEQ_BYTES, json.size)
        System.arraycopy(json, 0, plain, SEQ_BYTES + LENGTH_BYTES, json.size)
        val padFrom = SEQ_BYTES + LENGTH_BYTES + json.size
        if (padFrom < HALF_PLAINTEXT_BYTES) {
            val padding = StoreCrypto.randomBytes(HALF_PLAINTEXT_BYTES - padFrom, random)
            System.arraycopy(padding, 0, plain, padFrom, padding.size)
        }
        return StoreCrypto.seal(key, plain, random)
    }

    /** `seq(8) ‖ length(4) ‖ json ‖ padding` -> `json`. The seal authenticated, so a bad length is corruption. */
    private fun unpad(plain: ByteArray): ByteArray {
        val prefix = SEQ_BYTES + LENGTH_BYTES
        if (plain.size < prefix) throw corrupt("half plaintext is ${plain.size} bytes")
        val length = readInt(plain, SEQ_BYTES)
        if (length < 0 || length > plain.size - prefix) throw corrupt("half declares a $length-byte payload in ${plain.size} bytes")
        return plain.copyOfRange(prefix, prefix + length)
    }

    private fun corrupt(message: String) = StoreError.Corrupt(IllegalArgumentException(message))

    internal fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    internal fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    internal fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    internal fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * (7 - i))).toByte()
    }
}
