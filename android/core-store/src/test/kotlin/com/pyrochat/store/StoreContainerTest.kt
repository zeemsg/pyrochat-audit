package com.pyrochat.store

import com.pyrochat.store.crypto.StoreCrypto
import java.security.SecureRandom
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [SecureRandom] that is not random at all: the determinism seam of docs/DECOY-ACCOUNT.md §7.
 * Two platforms cannot produce identical containers from identical blobs — key order, nonces,
 * padding and the slot choice are all free — so byte-identity is only ever asserted here, with the
 * payload and the randomness both injected.
 */
class SeededRandom(seed: Long) : SecureRandom() {
    private val source = Random(seed)
    override fun nextBytes(bytes: ByteArray) = source.nextBytes(bytes)
    override fun nextInt(bound: Int): Int = source.nextInt(bound)
}

/**
 * The pinned container format of docs/DECOY-ACCOUNT.md §1-2 and §9's tests for it. iOS reads and
 * writes the same layout, so every constant asserted here is a cross-platform contract.
 */
class StoreContainerTest {

    private val random = SecureRandom()
    private fun key(fill: Int) = ByteArray(StoreCrypto.KEY_BYTES) { fill.toByte() }
    private fun json(text: String) = text.toByteArray(Charsets.UTF_8)

    // MARK: the header, byte for byte

    @Test fun headerIsMagicSlotCountAndBigEndianSlotBytes() {
        val bytes = StoreContainer.buildNew(key(1), json("""{"a":1}"""), slot = 0, random = random)
        assertEquals("PYROVLT1", bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertContentEquals(byteArrayOf(0x50, 0x59, 0x52, 0x4F, 0x56, 0x4C, 0x54, 0x31), bytes.copyOfRange(0, 8))
        assertEquals(3, bytes[8].toInt(), "slotCount is a build constant")
        assertContentEquals(byteArrayOf(0x00, 0x40, 0x00, 0x00), bytes.copyOfRange(9, 13), "slotBytes = 4 MiB, big-endian")
        assertEquals(13, StoreContainer.HEADER_BYTES)
        assertEquals(4_194_304, StoreContainer.SLOT_BYTES)
        assertEquals(12_582_925, StoreContainer.FILE_BYTES)
        assertEquals(StoreContainer.FILE_BYTES, bytes.size)
        // §1: two halves per slot, and the seq + length live inside each half's seal
        assertEquals(2_097_152, StoreContainer.HALF_BYTES)
        assertEquals(2_097_124, StoreContainer.HALF_PLAINTEXT_BYTES, "half - nonce(12) - tag(16)")
        assertEquals(2_097_112, StoreContainer.MAX_JSON_BYTES, "half plaintext - seq(8) - length(4)")
        assertEquals(13 until 4_194_317, StoreContainer.slotRange(0))
        assertEquals(4_194_317 until 8_388_621, StoreContainer.slotRange(1))
        assertEquals(8_388_621 until 12_582_925, StoreContainer.slotRange(2))
        assertEquals(13 until 2_097_165, StoreContainer.halfRange(0, 0))
        assertEquals(2_097_165 until 4_194_317, StoreContainer.halfRange(0, 1))
        assertEquals(8_388_621 until 10_485_773, StoreContainer.halfRange(2, 0))
        assertEquals(10_485_773 until 12_582_925, StoreContainer.halfRange(2, 1))
    }

    @Test fun theFileIsTheSameSizeWhateverItHolds() {
        val empty = StoreContainer.buildNew(key(1), ByteArray(0), slot = 0, random = random)
        val heavy = StoreContainer.buildNew(key(1), ByteArray(2_000_000) { it.toByte() }, slot = 0, random = random)
        assertEquals(StoreContainer.FILE_BYTES, empty.size)
        assertEquals(StoreContainer.FILE_BYTES, heavy.size)
        assertContentEquals(empty.copyOfRange(0, 13), heavy.copyOfRange(0, 13), "the header never moves either")
    }

    // MARK: §9 — one real slot is indistinguishable from three

    @Test fun oneOccupiedSlotLooksExactlyLikeThree() {
        val payload = json("""{"usernameDisplay":"zee"}""")
        val one = StoreContainer.buildNew(key(1), payload, slot = 2, random = random)
        val three = StoreContainer.assemble(
            (0..2).associateWith { StoreContainer.newSlot(key(it + 1), payload, random) },
            random,
        )
        assertEquals(one.size, three.size, "same length")
        assertContentEquals(one.copyOfRange(0, 13), three.copyOfRange(0, 13), "same header")
        assertEquals(StoreContainer.layout(one), StoreContainer.layout(three), "same slot boundaries")
        assertFalse(one.contentEquals(three), "only the bytes themselves differ")
        for (i in 0..2) {
            val opened = StoreContainer.open(key(i + 1), three)!!
            assertEquals(i, opened.slot)
            assertContentEquals(payload, opened.json)
        }
        // and the one-account file gives nothing away about its two unused slots
        for (slot in listOf(0, 1)) {
            val noise = one.sliceArray(StoreContainer.slotRange(slot))
            assertTrue(noise.toSet().size > 200, "slot $slot should use the whole byte range")
            assertTrue(noise.count { it == 0.toByte() } < 20_000, "slot $slot holds too many zero bytes to be noise")
            assertNull(StoreContainer.openSlot(key(1), one, slot), "noise authenticates under nothing")
        }
    }

    @Test fun anUnusedSlotIsWrittenOnceAndAPreservedSlotPassesThroughVerbatim() {
        val payload = json("""{"usernameDisplay":"zee"}""")
        val first = StoreContainer.buildNew(key(1), payload, slot = 0, random = random)
        // a second account's ciphertext, handed through assemble instead of being overwritten
        val theirs = first.sliceArray(StoreContainer.slotRange(2))
        val next = StoreContainer.assemble(
            mapOf(0 to StoreContainer.newSlot(key(1), payload, random), 2 to theirs),
            random,
        )
        assertContentEquals(theirs, next.sliceArray(StoreContainer.slotRange(2)), "a slot you cannot open is never rewritten")
        assertFalse(
            first.sliceArray(StoreContainer.slotRange(1)).contentEquals(next.sliceArray(StoreContainer.slotRange(1))),
            "a slot that assemble was not given is fresh noise",
        )
    }

    // MARK: §2 — unlock tries every slot, always

    @Test fun everySlotIsAttemptedWhicheverOneMatched() {
        val payload = json("""{"usernameDisplay":"zee"}""")
        for (slot in 0..2) {
            val bytes = StoreContainer.buildNew(key(9), payload, slot, random)
            val attempted = mutableListOf<Pair<Int, Int>>()
            val matched = mutableListOf<Pair<Int, Int>>()
            val opened = StoreContainer.open(key(9), bytes) { index, half, hit ->
                attempted += index to half
                if (hit) matched += index to half
            }!!
            assertEquals(slot, opened.slot)
            assertEquals(0, opened.half, "a freshly authored slot is live in half A")
            assertEquals(1L, opened.seq)
            assertEquals(6, opened.attempts, "no early exit: slot 0 must not be faster to open than slot 2")
            assertEquals(
                listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1),
                attempted,
                "every half of every slot, in order, whichever one matched",
            )
            assertEquals(listOf(slot to 0), matched)
        }
    }

    @Test fun aWrongKeyAuthenticatesNothing() {
        val bytes = StoreContainer.buildNew(key(1), json("""{"a":1}"""), slot = 0, random = random)
        val attempted = mutableListOf<Pair<Int, Int>>()
        assertNull(StoreContainer.open(key(2), bytes) { index, half, _ -> attempted += index to half })
        assertEquals(6, attempted.size, "a failed unlock still costs exactly six attempts")
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1), attempted)
    }

    @Test fun containerOpensWithTheOccupiedSlotAtZeroOneAndTwo() {
        val payload = json("""{"usernameDisplay":"zee","usernameHash":"abc"}""")
        for (slot in 0..2) {
            val bytes = StoreContainer.buildNew(key(5), payload, slot, random)
            val opened = StoreContainer.open(key(5), bytes)!!
            assertEquals(slot, opened.slot)
            assertContentEquals(payload, opened.json)
            assertContentEquals(payload, StoreContainer.openSlot(key(5), bytes, slot))
            for (other in (0..2) - slot) assertNull(StoreContainer.openSlot(key(5), bytes, other))
        }
    }

    // MARK: the sealed plaintext

    @Test fun seqAndLengthLiveInsideTheSealAndThePaddingIsRandom() {
        val payload = json("""{"usernameDisplay":"zee"}""")
        val sealed = StoreContainer.sealHalf(key(3), payload, seq = 7, random = random)
        assertEquals(StoreContainer.HALF_BYTES, sealed.size, "nonce(12) || ciphertext || tag(16) fills the half exactly")
        val plain = StoreCrypto.open(key(3), sealed)
        assertEquals(StoreContainer.HALF_PLAINTEXT_BYTES, plain.size)
        assertEquals(7L, StoreContainer.readLong(plain, 0), "seq(8), big-endian, first")
        assertEquals(payload.size, StoreContainer.readInt(plain, 8), "length(4), big-endian, after it")
        assertContentEquals(payload, plain.copyOfRange(12, 12 + payload.size))
        val padding = plain.copyOfRange(12 + payload.size, plain.size)
        assertTrue(padding.toSet().size > 200, "the padding is random, not zeroes")
        // nothing in the clear marks a real half: it opens with a nonce, and reseals differ
        val again = StoreContainer.sealHalf(key(3), payload, seq = 7, random = random)
        assertFalse(sealed.copyOfRange(0, 12).contentEquals(again.copyOfRange(0, 12)), "a fresh nonce every time")
        val padAgain = StoreCrypto.open(key(3), again).copyOfRange(12 + payload.size, plain.size)
        assertFalse(padding.contentEquals(padAgain), "fresh padding every time")
    }

    @Test fun theLiveHalfIsTheHighestSeqThatAuthenticates() {
        val old = json("""{"usernameDisplay":"old"}""")
        val new = json("""{"usernameDisplay":"new"}""")
        // half A at seq 4, half B at seq 5: B is live wherever the slot sits
        for (slot in 0..2) {
            val file = StoreContainer.assemble(
                mapOf(slot to StoreContainer.sealHalf(key(6), old, 4, random) + StoreContainer.sealHalf(key(6), new, 5, random)),
                random,
            )
            val opened = StoreContainer.open(key(6), file)!!
            assertEquals(slot, opened.slot)
            assertEquals(1, opened.half)
            assertEquals(5L, opened.seq)
            assertContentEquals(new, opened.json)
            assertContentEquals(new, StoreContainer.openSlot(key(6), file, slot))
            // and the other way round, so it is the seq that decides and not the order
            val flipped = StoreContainer.assemble(
                mapOf(slot to StoreContainer.sealHalf(key(6), new, 9, random) + StoreContainer.sealHalf(key(6), old, 8, random)),
                random,
            )
            val live = StoreContainer.open(key(6), flipped)!!
            assertEquals(0, live.half)
            assertEquals(9L, live.seq)
            assertContentEquals(new, live.json)
        }
    }

    @Test fun aTornHalfCostsOnlyItsOwnCopy() {
        val old = json("""{"usernameDisplay":"old"}""")
        val new = json("""{"usernameDisplay":"new"}""")
        val file = StoreContainer.assemble(
            mapOf(1 to StoreContainer.sealHalf(key(6), old, 4, random) + StoreContainer.sealHalf(key(6), new, 5, random)),
            random,
        )
        // a power cut during the write of half B: its bytes are part new, part noise
        val torn = file.copyOf()
        val range = StoreContainer.halfRange(1, 1)
        val junk = StoreCrypto.randomBytes(4096, random)
        System.arraycopy(junk, 0, torn, range.first + 1024, junk.size)
        val opened = StoreContainer.open(key(6), torn)!!
        assertEquals(1, opened.slot)
        assertEquals(0, opened.half, "the untouched half is still there")
        assertEquals(4L, opened.seq)
        assertContentEquals(old, opened.json, "one save behind, which is the whole point of two halves")
    }

    @Test fun aPayloadFillsTheHalfToTheByteAndOneMoreIsRefused() {
        val exact = ByteArray(StoreContainer.MAX_JSON_BYTES) { (it % 251).toByte() }
        val bytes = StoreContainer.buildNew(key(4), exact, slot = 2, random = random)
        assertContentEquals(exact, StoreContainer.open(key(4), bytes)!!.json, "zero padding still round-trips")
        val tooBig = assertFailsWith<StoreError.TooLarge> {
            StoreContainer.sealHalf(key(4), ByteArray(StoreContainer.MAX_JSON_BYTES + 1), 1, random)
        }
        assertEquals(StoreContainer.MAX_JSON_BYTES + 1, tooBig.jsonBytes)
        assertEquals(StoreContainer.MAX_JSON_BYTES, tooBig.capacityBytes)
    }

    // MARK: §7 — byte-identity is a test-only property, with the randomness injected

    @Test fun identicalInputsAndIdenticalRandomnessGiveIdenticalBytes() {
        val payload = json("""{"usernameDisplay":"zee","usernameHash":"abc"}""")
        val a = StoreContainer.buildNew(key(7), payload, slot = 1, random = SeededRandom(1234))
        val b = StoreContainer.buildNew(key(7), payload, slot = 1, random = SeededRandom(1234))
        assertContentEquals(a, b, "the format is a pure function of key, payload, slot and randomness")
        val c = StoreContainer.buildNew(key(7), payload, slot = 1, random = SeededRandom(1235))
        assertFalse(a.contentEquals(c), "different randomness, different file")
        assertContentEquals(a.copyOfRange(0, 13), c.copyOfRange(0, 13), "but never a different header")
        assertContentEquals(payload, StoreContainer.open(key(7), a)!!.json)
    }

    // MARK: malformed files

    @Test fun aFileWithoutTheMagicIsNotAContainer() {
        assertFalse(StoreContainer.isContainer(ByteArray(0)))
        assertFalse(StoreContainer.isContainer(StoreCrypto.seal(key(1), json("{}"), random)))
        assertFalse(StoreContainer.isContainer("PYROBAK1".toByteArray() + ByteArray(100)))
        assertNull(StoreContainer.layout(StoreCrypto.seal(key(1), json("{}"), random)))
    }

    @Test fun structuralDamageIsCorruptNotBadPassword() {
        val good = StoreContainer.buildNew(key(1), json("""{"a":1}"""), slot = 0, random = random)
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOfRange(0, good.size - 1)) }
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { it[8] = 4 }) }
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { it[8] = 0 }) }
        // strict: any slot size but this build's is corruption, never a reinterpretation
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { StoreContainer.writeInt(it, 9, 4) }) }
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { StoreContainer.writeInt(it, 9, 65_536) }) }
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { StoreContainer.writeInt(it, 9, 2 * StoreContainer.SLOT_BYTES) }) }
        assertFailsWith<StoreError.Corrupt> { StoreContainer.layout(good.copyOf().also { it[8] = 2 }) }
        // a slot whose seal authenticates but whose inner length is nonsense
        val plain = ByteArray(StoreContainer.HALF_PLAINTEXT_BYTES).also { StoreContainer.writeInt(it, 8, 65_000_000) }
        val lying = StoreContainer.assemble(
            mapOf(0 to StoreCrypto.seal(key(1), plain, random) + StoreCrypto.randomBytes(StoreContainer.HALF_BYTES, random)),
            random,
        )
        assertFailsWith<StoreError.Corrupt> { StoreContainer.open(key(1), lying) }
    }

    @Test fun randomSlotCoversEveryIndexUniformly() {
        val seen = IntArray(3)
        repeat(3_000) { seen[StoreContainer.randomSlot(random)]++ }
        for (i in 0..2) assertTrue(seen[i] > 700, "slot $i came up ${seen[i]} times in 3000 draws")
        assertEquals(3_000, seen.sum())
    }
}
