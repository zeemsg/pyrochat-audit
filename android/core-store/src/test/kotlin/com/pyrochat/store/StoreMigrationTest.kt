package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Forces the slot an account is created in, so a test does not have to draw until it sees one. */
class FixedSlotRandom(private val fixed: Int) : SecureRandom() {
    override fun nextInt(bound: Int): Int = fixed
}

/**
 * [SecureStore] over the three-slot container: the one-way migration of docs/DECOY-ACCOUNT.md §7,
 * the unlock behaviour of §2 and — the rule everything bends around — a save that rewrites only the
 * slot it can open.
 *
 * The thing these tests defend against is a store that no longer opens, so every write case checks
 * the file on disk as well as the object in memory.
 */
class StoreMigrationTest {
    private val dir: Path = Files.createTempDirectory("pyro-migration-test")
    private val file: Path = dir.resolve(SecureStore.FILE_NAME)
    private val secrets = InMemorySecretStore()
    private val password = "correct horse battery staple"
    private val random = SecureRandom()

    @AfterTest fun cleanup() {
        dir.toFile().setWritable(true, false)
        dir.toFile().deleteRecursively()
    }

    private fun key(): ByteArray = SecureStore.deriveKey(FastKdf, password, SecureStore.salt(secrets))

    /** Yesterday's `pyro.store`: the whole file is one sealed blob, no magic. */
    private fun writeLegacy(blob: Blob): ByteArray {
        val bytes = StoreCrypto.seal(key(), SwiftJson.encodeBlob(blob), random)
        Files.write(file, bytes)
        return bytes
    }

    private fun slice(bytes: ByteArray, slot: Int) = bytes.sliceArray(StoreContainer.slotRange(slot))

    private fun half(bytes: ByteArray, slot: Int, half: Int) = bytes.sliceArray(StoreContainer.halfRange(slot, half))

    private val sample = Blob(usernameDisplay = "zee", usernameHash = "abc")

    // MARK: §7 — one way, on first successful unlock

    @Test fun legacyFileMigratesToAContainerThatOpensWithTheSamePassword() {
        val legacy = writeLegacy(sample)
        assertFalse(StoreContainer.isContainer(legacy))

        val opened = SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        assertEquals(sample, opened.blob.withoutSlotRecord(), "the blob survives the unlock itself")
        assertTrue(opened.migratedLegacyFile)
        assertNull(opened.lastWriteError)
        opened.close()

        val onDisk = Files.readAllBytes(file)
        assertTrue(StoreContainer.isContainer(onDisk), "the file on disk is a container now")
        assertEquals(StoreContainer.FILE_BYTES, onDisk.size)
        assertEquals(opened.slot, StoreContainer.open(key(), onDisk)!!.slot, "the store knows which slot it landed in")
        // §1: the migration authored the container, so the sealed plaintext also carries the slot
        // record it stamped — the account's content is otherwise byte-identical
        assertContentEquals(SwiftJson.encodeBlob(opened.blob), StoreContainer.open(key(), onDisk)!!.json)
        assertEquals(sample, SwiftJson.decodeBlob(StoreContainer.open(key(), onDisk)!!.json).withoutSlotRecord())
        assertEquals(listOf(opened.slot), opened.blob.allocatedSlots)
        assertEquals(SecureStore.ALL_SLOTS, opened.blob.knownSlots, "it wrote the other slots' noise itself")

        // and it reopens with the same password, holding an identical Blob
        SecureStore.open(file, password, secrets, FastKdf).use { reopened ->
            assertEquals(sample, reopened.blob.withoutSlotRecord())
            assertEquals(opened.slot, reopened.slot)
        }
        // migrating is one way: a second unlock finds a container and leaves every byte alone
        SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true).use {
            assertFalse(it.migratedLegacyFile)
        }
        assertContentEquals(onDisk, Files.readAllBytes(file), "an already-migrated file is not rewritten on unlock")
    }

    @Test fun theRealLegacyFixtureMigratesAndStillHoldsTheSameBlob() {
        // The irreplaceable regression case: the actual iOS-written `pyro.store`, copied out of
        // android/fixtures/ios-store (never migrated in place), real PBKDF2, real password.
        Files.copy(Fixtures.iosDir.resolve("pyro.store"), file, StandardCopyOption.REPLACE_EXISTING)
        assertFalse(StoreContainer.isContainer(Files.readAllBytes(file)))

        val store = SecureStore.open(file, Fixtures.password, Fixtures.secrets(), migrateLegacy = true)
        assertTrue(store.migratedLegacyFile, "migration failed: ${store.lastWriteError}")
        // §1a: the fixture's two inline voice notes leave the blob on the way into the container
        assertEquals(2, store.spilledVoiceNotes)
        assertNull(store.tooLargeToMigrate)
        assertSpilledEquivalent(Fixtures.blob, store.blob, store::readAttachment)
        val spilled = store.blob
        store.close()

        val onDisk = Files.readAllBytes(file)
        assertTrue(StoreContainer.isContainer(onDisk))
        assertEquals(StoreContainer.FILE_BYTES, onDisk.size, "a heavy account and an empty one are the same size")
        val sealedJson = StoreContainer.open(Fixtures.storeKey, onDisk)!!.json
        // `spilled` is the store's own blob, slot record and all — memory and disk agree exactly
        assertContentEquals(SwiftJson.encodeBlob(spilled), sealedJson)
        assertEquals(SecureStore.ALL_SLOTS, spilled.knownSlots, "the migration authored the container")
        assertTrue(
            SwiftJson.encodeBlob(spilled.withoutSlotRecord()).size < Fixtures.storePlaintext.size,
            "the sealed plaintext is smaller than the legacy one by exactly the audio",
        )
        SecureStore.open(file, Fixtures.password, Fixtures.secrets()).use { reopened ->
            assertEquals(spilled, reopened.blob, "the same Blob, byte for byte through the container")
            assertSpilledEquivalent(Fixtures.blob, reopened.blob, reopened::readAttachment)
        }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, "wrong password", Fixtures.secrets()) }
    }

    @Test fun migrationIsOptInSoReadingAHistoricalFileNeverRewritesIt() {
        val legacy = writeLegacy(sample)
        SecureStore.open(file, password, secrets, FastKdf).use { store ->
            assertEquals(sample, store.blob.withoutSlotRecord())
            assertFalse(store.migratedLegacyFile)
        }
        assertContentEquals(legacy, Files.readAllBytes(file), "a plain open leaves a legacy file untouched")
    }

    @Test fun aFailedMigrationLeavesTheLegacyFileIntactAndStillUnlocks() {
        val legacy = writeLegacy(sample)
        assertTrue(dir.toFile().setWritable(false, false), "the test needs a read-only directory")
        try {
            val store = SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
            assertEquals(sample, store.blob.withoutSlotRecord(), "the user is unlocked even when the rewrite cannot happen")
            assertFalse(store.migratedLegacyFile)
            assertNotNull(store.lastWriteError, "the failure is recorded, not thrown")
            store.close()
        } finally {
            dir.toFile().setWritable(true, false)
        }
        assertContentEquals(legacy, Files.readAllBytes(file), "the only copy of the account is still on disk, openable")
        assertEquals(
            listOf(SecureStore.FILE_NAME),
            dir.toFile().list()!!.sorted(),
            "an abandoned migration leaves no temp file behind",
        )
        SecureStore.open(file, password, secrets, FastKdf).use { assertEquals(sample, it.blob.withoutSlotRecord()) }
    }

    @Test fun aLegacyFileWithTheWrongPasswordIsNeverMigrated() {
        val legacy = writeLegacy(sample)
        assertFailsWith<StoreError.BadPassword> {
            SecureStore.open(file, "wrong password", secrets, FastKdf, migrateLegacy = true)
        }
        assertContentEquals(legacy, Files.readAllBytes(file))
    }

    @Test fun aLegacyFileThatDoesNotDecodeIsCorruptAndIsNeverMigrated() {
        val bytes = StoreCrypto.seal(key(), """{"contacts":[{"hash":"h"}]}""".toByteArray(), random)
        Files.write(file, bytes)
        assertFailsWith<StoreError.Corrupt> {
            SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        }
        assertContentEquals(bytes, Files.readAllBytes(file), "a blob that does not decode is not rewritten")
    }

    @Test fun aLegacyStoreTooBigForASlotIsNotMigratedAndKeepsItsOldFile() {
        // Pre-container files had no size limit; a 4 MiB slot does. Such a store must keep working
        // from its old file rather than lose anything — the migration simply does not happen.
        val huge = Blob(usernameDisplay = "x".repeat(StoreContainer.MAX_JSON_BYTES))
        val legacy = writeLegacy(huge)
        val store = SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        assertEquals(huge, store.blob.withoutSlotRecord(), "the user is still unlocked")
        assertFalse(store.migratedLegacyFile)
        assertNull(store.lastWriteError, "nothing failed — not migrating is a decision, not an error")
        assertEquals(StoreContainer.MAX_JSON_BYTES, store.tooLargeToMigrate?.capacityBytes, "reported as a condition")
        assertTrue(store.legacyFormat)
        store.close()
        assertContentEquals(legacy, Files.readAllBytes(file), "the old file is the only copy and it stays")
    }

    // MARK: §1a — the audio leaves the blob BEFORE the container exists

    private fun voiceRow(n: Int, audio: ByteArray) = ChatMessage(
        id = "6d000000-0000-4000-8000-%012d".format(n),
        direction = if (n % 2 == 0) ChatMessage.Direction.INCOMING else ChatMessage.Direction.OUTGOING,
        kind = ChatMessage.Kind.VOICE,
        text = "[voice 7s]",
        timestamp = Instant.ofEpochSecond(1_700_000_000L + n),
        status = ChatMessage.Status.DELIVERED,
        voiceData = Bytes(audio),
        voiceDuration = 7.5,
    )

    @Test fun aLegacyStoreOfInlineVoiceNotesTooBigForASlotMigratesWithTheAudioInSidecars() {
        // 20 notes of 300 KB: exactly the case §1a exists for — base64 inflates them past a 4 MiB
        // slot, so a container could never have held this account. Legacy has no such limit.
        val audio = (0 until 20).associate { n ->
            "6d000000-0000-4000-8000-%012d".format(n) to ByteArray(300 * 1024) { ((n * 31 + it) % 251).toByte() }
        }
        val rows = audio.entries.mapIndexed { i, (_, bytes) -> voiceRow(i, bytes) }
        val big = Blob(usernameDisplay = "zee", usernameHash = "abc", messages = mapOf("peer" to rows))
        val legacy = writeLegacy(big)
        assertTrue(
            SwiftJson.encodeBlob(big).size > StoreContainer.MAX_JSON_BYTES,
            "the premise: this account does not fit in a slot while its audio is inline",
        )
        assertEquals(20, VoiceSpill.countInline(big))

        val store = SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        assertTrue(store.migratedLegacyFile, "it must migrate anyway: ${store.lastWriteError}")
        assertEquals(20, store.spilledVoiceNotes)
        assertNull(store.tooLargeToMigrate, "the spill alone is enough to make this fit")
        assertFalse(store.legacyFormat)
        assertEquals(20, store.attachmentIds().size, "every note is a sidecar now")
        store.close()

        assertFalse(Files.readAllBytes(file).contentEquals(legacy))
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file))
        SecureStore.open(file, password, secrets, FastKdf).use { reopened ->
            val back = reopened.blob.messages.getValue("peer")
            assertEquals(20, back.size, "every row survives")
            for ((i, row) in back.withIndex()) {
                assertNull(row.voiceData, "row $i keeps no audio inline")
                val attId = row.attachmentId
                assertNotNull(attId, "row $i names a sidecar")
                assertEquals(7.5, row.voiceDuration, "row $i keeps its duration")
                assertEquals("[voice 7s]", row.text)
                assertContentEquals(
                    audio.getValue(row.id),
                    reopened.readAttachment(attId),
                    "row $i is still playable, byte for byte",
                )
            }
            assertTrue(
                SwiftJson.encodeBlob(reopened.blob).size < 64 * 1024,
                "the blob is small once the audio is out of it",
            )
        }
    }

    @Test fun aSpillThatCannotWriteItsSidecarsLeavesTheLegacyFileAndItsAudioAlone() {
        val audio = ByteArray(1024) { it.toByte() }
        val blob = Blob(usernameDisplay = "zee", messages = mapOf("peer" to listOf(voiceRow(0, audio))))
        val legacy = writeLegacy(blob)
        assertTrue(dir.toFile().setWritable(false, false))
        val store = try {
            SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        } finally {
            dir.toFile().setWritable(true, false)
        }
        assertFalse(store.migratedLegacyFile)
        assertNotNull(store.lastWriteError)
        assertContentEquals(audio, store.blob.messages.getValue("peer")[0].voiceData?.bytes, "the audio is still there")
        store.close()
        assertContentEquals(legacy, Files.readAllBytes(file))
        assertEquals(listOf(SecureStore.FILE_NAME), dir.toFile().list()!!.sorted(), "no half-written sidecars")
    }

    @Test fun anAccountThatStillDoesNotFitAfterTheSpillIsLeftEntirelyAlone() {
        // No audio at all: more than 2 MiB of pure text, which the spill cannot help with.
        // docs/DECOY-ACCOUNT.md §1, "never trim without being asked": do not migrate it, and do not
        // delete anything. It keeps opening, keeps saving, and simply has no decoy.
        val filler = "y".repeat(4096)
        val rows = (0 until 700).map { n ->
            ChatMessage(
                id = "6d000000-0000-4000-8000-%012d".format(n),
                direction = ChatMessage.Direction.INCOMING,
                kind = ChatMessage.Kind.TEXT,
                text = "$n $filler",
                timestamp = Instant.ofEpochSecond(1_700_000_000L + n),
                status = ChatMessage.Status.READ,
            )
        }
        val big = Blob(usernameDisplay = "zee", usernameHash = "abc", messages = mapOf("peer" to rows))
        val encoded = SwiftJson.encodeBlob(big)
        assertTrue(encoded.size > StoreContainer.MAX_JSON_BYTES, "the premise: over a slot half, with no audio to move")
        val legacy = writeLegacy(big)

        val store = SecureStore.open(file, password, secrets, FastKdf, migrateLegacy = true)
        assertFalse(store.migratedLegacyFile, "no container is written")
        assertTrue(store.legacyFormat, "the file keeps the shape it had")
        assertEquals(rows.size, store.blob.messages.getValue("peer").size, "not one message is dropped")
        val condition = store.tooLargeToMigrate
        assertNotNull(condition, "the condition is reported for wave 2 to surface")
        // the record the migration would have stamped is part of what would not fit
        assertTrue(condition.jsonBytes >= encoded.size, "the refused size is the whole plaintext")
        assertEquals(StoreContainer.MAX_JSON_BYTES, condition.capacityBytes)
        assertNull(store.lastWriteError, "nothing failed — this is a decision, not an error")
        assertContentEquals(legacy, Files.readAllBytes(file), "not a byte of the store is rewritten")
        assertTrue(store.attachmentIds().isEmpty(), "not one sidecar is written either")

        // and it keeps saving, in the shape it already had
        store.update { it.copy(usernameDisplay = "still working") }
        store.flush()
        store.close()
        val after = Files.readAllBytes(file)
        assertFalse(StoreContainer.isContainer(after), "an unmigrated store is never silently converted")
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("still working", it.blob.usernameDisplay)
            assertEquals(rows.size, it.blob.messages.getValue("peer").size)
        }
    }

    @Test fun aLegacyStoreOpenedWithoutMigrationKeepsSavingAsLegacy() {
        val legacy = writeLegacy(sample)
        val store = SecureStore.open(file, password, secrets, FastKdf)      // no migrateLegacy
        assertTrue(store.legacyFormat)
        store.update { it.copy(usernameDisplay = "still legacy") }
        store.flush()
        store.close()
        val after = Files.readAllBytes(file)
        assertFalse(StoreContainer.isContainer(after), "reading a historical file never converts it")
        assertFalse(after.contentEquals(legacy))
        SecureStore.open(file, password, secrets, FastKdf).use { assertEquals("still legacy", it.blob.usernameDisplay) }
    }

    @Test fun restoringABackupMadeBeforeTheSpillMovesItsAudioToSidecars() {
        val audio = ByteArray(2048) { (it * 7).toByte() }
        val blob = Blob(usernameDisplay = "zee", messages = mapOf("peer" to listOf(voiceRow(0, audio))))
        val target = dir.resolve("restored.store")
        val fresh = InMemorySecretStore()
        SecureStore.restore(target, blob, emptyMap(), password, fresh, FastKdf).use { store ->
            assertEquals(1, store.spilledVoiceNotes)
            val row = store.blob.messages.getValue("peer")[0]
            assertNull(row.voiceData)
            assertContentEquals(audio, store.readAttachment(row.attachmentId!!))
        }
        SecureStore.open(target, password, fresh, FastKdf).use { reopened ->
            val row = reopened.blob.messages.getValue("peer")[0]
            assertNull(row.voiceData, "the sealed blob carries no audio")
            assertContentEquals(audio, reopened.readAttachment(row.attachmentId!!))
        }
    }

    // MARK: §2 — unlocking a container through SecureStore

    @Test fun aContainerOpensWithTheOccupiedSlotAtZeroOneAndTwo() {
        for (slot in 0..2) {
            // as if written by the other platform: the account is simply somewhere in the file
            Files.write(file, StoreContainer.buildNew(key(), SwiftJson.encodeBlob(sample), slot, random))
            SecureStore.open(file, password, secrets, FastKdf).use { store ->
                assertEquals(sample, store.blob.withoutSlotRecord())
                assertEquals(slot, store.slot)
            }
            SecureStore.openWithKey(file, key()).use { store ->
                assertEquals(sample, store.blob.withoutSlotRecord(), "biometric unlock behaves the same whichever slot holds the key")
                assertEquals(slot, store.slot)
            }
        }
    }

    @Test fun aWrongPasswordAuthenticatesNoSlotAndReportsBadPassword() {
        Files.write(file, StoreContainer.buildNew(key(), SwiftJson.encodeBlob(sample), 1, random))
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, "wrong password", secrets, FastKdf) }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, password, InMemorySecretStore(), FastKdf) }
        assertFailsWith<StoreError.BadPassword> {
            SecureStore.openWithKey(file, ByteArray(StoreCrypto.KEY_BYTES) { 7 })
        }
    }

    // MARK: §1 — never write to a slot you cannot open

    @Test fun aSaveRewritesOnlyTheOtherHalfOfTheCurrentSlot() {
        val store = SecureStore.create(file, password, secrets, FastKdf, FixedSlotRandom(1))
        store.flush()
        val first = Files.readAllBytes(file)
        assertEquals(1, store.slot)
        assertEquals(0, store.half, "a fresh container is live in half A")
        assertEquals(1L, store.seq)
        assertEquals(StoreContainer.FILE_BYTES, first.size)

        store.update { it.copy(usernameDisplay = "zee") }
        store.flush()
        val second = Files.readAllBytes(file)
        assertEquals(1, store.half, "the save went to the OTHER half")
        assertEquals(2L, store.seq)

        assertEquals(first.size, second.size, "the file's length never changes")
        assertContentEquals(first.copyOfRange(0, 13), second.copyOfRange(0, 13), "and neither does its header")
        assertContentEquals(half(first, 1, 0), half(second, 1, 0), "the live half is never the half being written")
        assertFalse(half(first, 1, 1).contentEquals(half(second, 1, 1)), "the other half took the new copy")
        assertContentEquals(slice(first, 0), slice(second, 0), "slot 0 is not touched — it might be another account")
        assertContentEquals(slice(first, 2), slice(second, 2), "slot 2 is not touched — it might be another account")

        // and the next save alternates back
        store.update { it.copy(usernameHash = "abc") }
        store.flush()
        store.close()
        val third = Files.readAllBytes(file)
        assertContentEquals(half(second, 1, 1), half(third, 1, 1), "half B now holds the live copy and is left alone")
        assertFalse(half(second, 1, 0).contentEquals(half(third, 1, 0)), "half A took this one")
        assertContentEquals(slice(second, 0), slice(third, 0))
        assertContentEquals(slice(second, 2), slice(third, 2))
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("zee", it.blob.usernameDisplay)
            assertEquals("abc", it.blob.usernameHash)
            assertEquals(1, it.slot)
            assertEquals(0, it.half)
            assertEquals(3L, it.seq, "seq counts saves, starting at 1")
        }
    }

    @Test fun aTornSaveCostsTheStaleCopyAndNothingElse() {
        val store = SecureStore.create(file, password, secrets, FastKdf, FixedSlotRandom(2))
        store.update { it.copy(usernameDisplay = "first") }
        store.flush()
        store.update { it.copy(usernameDisplay = "second") }
        store.flush()
        val liveHalf = store.half
        store.close()

        val intact = Files.readAllBytes(file)
        val junk = ByteArray(8192) { (it * 13).toByte() }
        fun tear(half: Int): ByteArray = intact.copyOf().also {
            System.arraycopy(junk, 0, it, StoreContainer.halfRange(2, half).first + 512, junk.size)
        }

        // a power cut in the middle of the NEXT save tears the half that is NOT live
        Files.write(file, tear(StoreContainer.otherHalf(liveHalf)))
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("second", it.blob.usernameDisplay, "the live half was never the half being written")
            assertEquals(2, it.slot)
            assertEquals(liveHalf, it.half)
        }

        // and even if the LIVE half is the damaged one, the previous save is still there
        Files.write(file, tear(liveHalf))
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("first", it.blob.usernameDisplay, "one save behind, never an empty app")
            assertEquals(StoreContainer.otherHalf(liveHalf), it.half)
        }
    }

    @Test fun aNewStoreCanLandInAnySlotAndKeepsItAcrossSaves() {
        for (slot in 0..2) {
            val target = dir.resolve("run-$slot.store")
            val store = SecureStore.create(target, password, InMemorySecretStore(), FastKdf, FixedSlotRandom(slot))
            store.flush()
            assertEquals(slot, store.slot)
            val bytes = Files.readAllBytes(target)
            assertTrue(StoreContainer.isContainer(bytes), "a new store is written as a container")
            assertEquals(StoreContainer.FILE_BYTES, bytes.size)
            assertEquals(slot, StoreContainer.open(store.storeKey, bytes)!!.slot)
            store.close()
            Files.delete(target)
        }
    }

    @Test fun aRestoredStoreIsAContainerToo() {
        val target = dir.resolve("restored.store")
        SecureStore.restore(target, sample, emptyMap(), password, InMemorySecretStore(), FastKdf, FixedSlotRandom(2))
            .use { store ->
                assertEquals(2, store.slot)
                val bytes = Files.readAllBytes(target)
                assertEquals(StoreContainer.FILE_BYTES, bytes.size)
                assertEquals(sample, SwiftJson.decodeBlob(StoreContainer.open(store.storeKey, bytes)!!.json).withoutSlotRecord())
            }
    }

    // MARK: a slot that cannot grow

    @Test fun anAccountTooBigForItsSlotIsRefusedAndTheLastGoodStoreSurvives() {
        val store = SecureStore.create(file, password, secrets, FastKdf, FixedSlotRandom(0))
        store.update { it.copy(usernameDisplay = "zee") }
        store.flush()
        val good = Files.readAllBytes(file)

        store.update { it.copy(usernameDisplay = "x".repeat(StoreContainer.MAX_JSON_BYTES + 1)) }
        val refused = assertFailsWith<StoreError.TooLarge> { store.flush() }
        assertTrue(refused.jsonBytes > StoreContainer.MAX_JSON_BYTES)
        assertEquals(StoreContainer.MAX_JSON_BYTES, refused.capacityBytes)
        store.close()

        assertContentEquals(good, Files.readAllBytes(file), "the store is refused, never truncated")
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("zee", it.blob.usernameDisplay, "the last account that fitted is still there")
        }
    }
}
