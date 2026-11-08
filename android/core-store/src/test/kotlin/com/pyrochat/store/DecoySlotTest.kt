package com.pyrochat.store

import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.ChatMessage
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/DECOY-ACCOUNT.md §1 allocation, §4 session isolation, §5 the wipe that narrows, §6 what must
 * never leak — at the store layer, which is the reachable surface §9 asks these to be asserted on:
 * "a decoy session cannot read, name, count or wipe another slot. Assert on the session's reachable
 * surface, not on the UI."
 */
class DecoySlotTest {
    private val dir: Path = Files.createTempDirectory("pyro-decoy-test")
    private val file: Path = dir.resolve(SecureStore.FILE_NAME)
    private val secrets = InMemorySecretStore()
    private val real = "correct horse battery staple"
    private val decoy = "a different password entirely"
    private val opened = ArrayList<SecureStore>()

    @AfterTest fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    private fun track(s: SecureStore) = s.also { opened += it }

    /** The first account, its container written and on disk. */
    private fun firstAccount(): SecureStore {
        val store = track(SecureStore.create(file, real, secrets, FastKdf))
        store.update { it.copy(usernameDisplay = "real") }
        store.flush()
        return store
    }

    private fun addDecoy(host: SecureStore): Int {
        val key = SecureStore.deriveKey(FastKdf, decoy, SecureStore.salt(secrets))
        return host.authorSlot(key, Blob(usernameDisplay = "decoy"))
    }

    private fun openDecoy(): SecureStore = track(SecureStore.open(file, decoy, secrets, FastKdf))

    private fun bytes(): ByteArray = Files.readAllBytes(file)

    private fun slotBytes(all: ByteArray, slot: Int): ByteArray =
        StoreContainer.slotRange(slot).let { all.copyOfRange(it.first, it.last + 1) }

    // ---- §1 allocation ---------------------------------------------------------------------

    @Test fun theAccountThatAuthoredTheContainerKnowsEverySlotAndHasAllocatedItsOwn() {
        val store = firstAccount()
        assertEquals(listOf(store.slot), store.allocatedSlots)
        assertEquals((0 until StoreContainer.SLOT_COUNT).toList(), store.knownSlots)
        assertEquals(StoreContainer.SLOT_COUNT - 1, store.freeSlots.size)
        assertFalse(store.slot in store.freeSlots)
    }

    @Test fun allocatingRecordsTheIndexInsideTheAccountsOwnEncryptedPlaintext() {
        val store = firstAccount()
        val slot = addDecoy(store)
        assertEquals(listOf(store.slot, slot).sorted(), store.allocatedSlots)
        // …and it is IN the blob, not in the header, the keystore or a sidecar
        assertEquals(listOf(store.slot, slot).sorted(), store.blob.allocatedSlots?.sorted())
        val header = bytes().copyOfRange(0, StoreContainer.HEADER_BYTES)
        assertContentEquals(StoreContainer.MAGIC + byteArrayOf(StoreContainer.SLOT_COUNT.toByte()), header.copyOfRange(0, 9))
        assertEquals(setOf(SecretStore.KDF_SALT), secrets.names(), "no new keystore item exists to count")
        assertEquals(listOf(SecureStore.FILE_NAME), dir.toFile().list()!!.sorted(), "and no sidecar either")
    }

    @Test fun theFirstAccountCanCreateOneDecoyAndTheDecoyCannotCreateAnother() {
        val store = firstAccount()
        addDecoy(store)
        val d = openDecoy()
        // §1: "A decoy knows only its own index" — so the subtraction that finds a free slot is empty
        assertEquals(listOf(d.slot), d.knownSlots)
        assertEquals(listOf(d.slot), d.allocatedSlots)
        assertEquals(emptyList(), d.freeSlots)
        assertFailsWith<StoreError.NoFreeSlot> {
            d.authorSlot(SecureStore.deriveKey(FastKdf, "a third one", SecureStore.salt(secrets)), Blob())
        }
    }

    @Test fun theRowCreatesOrReplacesAndTheDecoyStaysInTheSlotItWasGiven() {
        val store = firstAccount()
        val first = addDecoy(store)
        assertEquals(emptyList(), store.freeSlots, "§1: the first account gets one decoy, not two")
        assertEquals(first, store.allocatedDecoySlot)

        // §3: used again from the account that OWNS the decoy, the row rebuilds it in the slot it
        // already allocated — otherwise a mistyped decoy password costs a total wipe
        val second = store.authorSlot(SecureStore.deriveKey(FastKdf, "a corrected password", SecureStore.salt(secrets)), Blob(usernameDisplay = "decoy2"))
        assertEquals(first, second, "the replacement lands in the slot already allocated, never a third")
        assertEquals(listOf(store.slot, first).sorted(), store.allocatedSlots)
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
        SecureStore.open(file, "a corrected password", secrets, FastKdf).use {
            assertEquals("decoy2", it.blob.usernameDisplay)
            assertEquals(first, it.slot)
            assertEquals(listOf(first), it.knownSlots, "and it still knows only its own index")
        }
        assertEquals(StoreContainer.SLOT_COUNT, 3, "one slot is never used, which is what keeps the count a constant")
    }

    @Test fun anAllocationIsOnDiskBeforeTheSlotItNamesIsTouched() {
        val store = firstAccount()
        val slot = addDecoy(store)
        // reopened cold, the real account still knows what it gave away — the record was flushed,
        // so a crash between the two costs a free slot and never a second allocation on top
        SecureStore.open(file, real, secrets, FastKdf).use { cold ->
            assertEquals(listOf(cold.slot, slot).sorted(), cold.allocatedSlots)
            assertFalse(slot in cold.freeSlots)
        }
    }

    @Test fun aRegistrationThatFailsGivesTheSlotBack() {
        val store = firstAccount()
        val before = slotBytesOfFree(store)
        val slot = addDecoy(store)
        store.releaseSlot(slot)
        assertEquals(listOf(store.slot), store.allocatedSlots)
        assertTrue(slot in store.freeSlots, "a mistyped username must not cost the one decoy")
        assertFalse(slotBytes(bytes(), slot).contentEquals(before.getValue(slot)), "and the slot is fresh noise, not the abandoned account")
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
    }

    private fun slotBytesOfFree(store: SecureStore): Map<Int, ByteArray> {
        val all = bytes()
        return store.freeSlots.associateWith { slotBytes(all, it) }
    }

    @Test fun aLegacyStoreHasNowhereToPutASecondAccount() {
        // the §1 "never trim without being asked" store: still legacy on disk, still working
        val store = track(SecureStore.create(file, real, secrets, FastKdf))
        store.flush()
        SecureStore.open(file, real, secrets, FastKdf).use { }   // container exists; now fake the legacy case
        val legacyFile = dir.resolve("legacy.store")
        Files.write(legacyFile, com.pyrochat.store.crypto.StoreCrypto.seal(SecureStore.deriveKey(FastKdf, real, SecureStore.salt(secrets)), com.pyrochat.store.codec.SwiftJson.encodeBlob(Blob()), SecureRandom()))
        SecureStore.open(legacyFile, real, secrets, FastKdf).use { legacy ->
            assertTrue(legacy.legacyFormat)
            assertFailsWith<StoreError.NoFreeSlot> { legacy.authorSlot(ByteArray(32) { 3 }, Blob()) }
        }
    }

    // ---- §4 / §6 nothing written in one slot is visible from another -------------------------

    @Test fun nothingWrittenInOneSlotIsVisibleFromTheOther() {
        val store = firstAccount()
        addDecoy(store)
        store.update {
            it.copy(
                usernameDisplay = "real",
                settings = it.settings.copy(theme = "amber", textSize = "largest", socksProxy = "127.0.0.1:9050"),
                messages = mapOf("peer" to listOf(ChatMessage(id = "m1", direction = ChatMessage.Direction.OUTGOING, kind = ChatMessage.Kind.TEXT, text = "the real thing", timestamp = Instant.ofEpochSecond(1_700_000_000), status = ChatMessage.Status.SENT))),
            )
        }
        store.flush()
        val d = openDecoy()
        assertEquals("decoy", d.blob.usernameDisplay)
        assertEquals("ember", d.blob.settings.theme, "a theme chosen in the real account and visible in the decoy is a leak")
        assertNull(d.blob.settings.textSize)
        assertNull(d.blob.settings.socksProxy)
        assertEquals(emptyMap(), d.blob.messages)
        // and the other way: the decoy's own writes never reach the real account
        d.update { it.copy(settings = it.settings.copy(textSize = "small", socksProxy = "10.0.0.1:1080"), usernameDisplay = "decoy") }
        d.flush()
        SecureStore.open(file, real, secrets, FastKdf).use { cold ->
            assertEquals("real", cold.blob.usernameDisplay)
            assertEquals("largest", cold.blob.settings.textSize, "the decoy's text size must not reach the real account")
            assertEquals("127.0.0.1:9050", cold.blob.settings.socksProxy)
        }
    }

    @Test fun aSessionCannotReadNameCountOrWipeAnotherSlot() {
        val store = firstAccount()
        val decoySlot = addDecoy(store)
        val d = openDecoy()
        // read: its key opens exactly one slot, and openSlot on any other returns nothing
        val key = SecureStore.deriveKey(FastKdf, decoy, SecureStore.salt(secrets))
        val all = bytes()
        for (i in 0 until StoreContainer.SLOT_COUNT) {
            val payload = StoreContainer.openSlot(key, all, i)
            if (i == decoySlot) assertNotNull(payload) else assertNull(payload, "slot $i must not open under the decoy's key")
        }
        // name / count: the only index it holds is its own, and the record says nothing else
        assertEquals(listOf(d.slot), d.knownSlots)
        assertEquals(listOf(d.slot), d.allocatedSlots)
        assertEquals(decoySlot, d.slot)
        // wipe: releasing its own slot is refused outright, and there is no other index to pass
        assertFailsWith<IllegalArgumentException> { d.releaseSlot(d.slot) }
    }

    @Test fun anAttachmentSealedInOneSlotDoesNotOpenInTheOther() {
        val store = firstAccount()
        addDecoy(store)
        val id = "11111111-2222-3333-4444-555555555555"
        store.writeAttachment(id, "a photograph".toByteArray())
        val d = openDecoy()
        // §6.2: one directory, opaque ids — an examiner can count files but cannot attribute them,
        // and neither can the other account
        assertEquals(listOf(id), store.attachmentIds())
        assertEquals(listOf(id), d.attachmentIds(), "the directory is shared and is not hidden")
        assertNull(d.readAttachment(id), "but the bytes do not open under another slot's key")
        assertContentEquals("a photograph".toByteArray(), store.readAttachment(id))
    }

    // ---- §5 the voluntary wipe narrows to one slot -------------------------------------------

    @Test fun burningTheDecoyLeavesEveryOtherSlotByteIdentical() {
        val store = firstAccount()
        store.update { it.copy(usernameDisplay = "real") }
        store.flush()
        addDecoy(store)
        val d = openDecoy()
        d.update { it.copy(usernameDisplay = "decoy") }
        d.flush()
        val beforeAll = bytes()
        val others = (0 until StoreContainer.SLOT_COUNT).filter { it != d.slot }.associateWith { slotBytes(beforeAll, it) }

        d.burnSlot()

        val afterAll = bytes()
        assertEquals(StoreContainer.FILE_BYTES, afterAll.size, "the file is the same size it always is")
        assertContentEquals(beforeAll.copyOfRange(0, StoreContainer.HEADER_BYTES), afterAll.copyOfRange(0, StoreContainer.HEADER_BYTES))
        for ((i, before) in others) {
            assertContentEquals(before, slotBytes(afterAll, i), "slot $i must not change when another slot burns")
        }
        assertFalse(slotBytes(afterAll, d.slot).contentEquals(slotBytes(beforeAll, d.slot)))
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
        SecureStore.open(file, real, secrets, FastKdf).use { assertEquals("real", it.blob.usernameDisplay) }
    }

    @Test fun burningOneSlotDeletesOnlyItsOwnSidecars() {
        val store = firstAccount()
        addDecoy(store)
        val mine = "11111111-1111-1111-1111-111111111111"
        val theirs = "22222222-2222-2222-2222-222222222222"
        store.writeAttachment(mine, "real bytes".toByteArray())
        val d = openDecoy()
        d.writeAttachment(theirs, "decoy bytes".toByteArray())
        d.burnSlot()
        assertEquals(listOf(mine), store.attachmentIds(), "the other slot's sidecar survives")
        assertContentEquals("real bytes".toByteArray(), store.readAttachment(mine))
    }

    @Test fun everyBurnLeavesTheSameContainerBehindWhoeverRanIt() {
        // §5: there is no "this account is alone, so take the device with it" case. Behaving
        // differently in the common case is exactly what makes the uncommon case legible.
        val solo = firstAccount()
        solo.burnSlot()
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file), "a solo burn leaves a container, not nothing")
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, real, secrets, FastKdf) }

        // …and so does a burn inside a decoy, byte-count for byte-count
        dir.toFile().deleteRecursively(); Files.createDirectories(dir)
        val store = firstAccount()
        val slot = addDecoy(store)
        assertTrue(slot != store.slot)
        val d = openDecoy()
        d.burnSlot()
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file))
    }

    @Test fun theInvoluntaryPathsStayTotalAndLeaveNoSlotOpenable() {
        val store = firstAccount()
        addDecoy(store)
        openDecoy().update { it.copy(usernameDisplay = "decoy") }
        opened.forEach { it.flush(); it.close() }
        // §5: the duress PIN, ten wrong passwords and the dead-man switch all run THIS, unchanged —
        // a partial wipe under duress leaves the interesting slot behind, which is the opposite of
        // what they are for
        SecureStore.obliterate(file, secrets)
        // §5: the container is never deleted — it is overwritten with fresh noise, so a wiped
        // device and a device that never held anything are the same bytes
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file))
        assertEquals(emptySet(), secrets.names(), "and the shared salt went with it")
        // …so the old passwords cannot even derive the same key any more (a fresh salt is minted
        // on the next unlock attempt, exactly as it would be on a device that never held anything)
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, real, secrets, FastKdf) }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
    }

    // ---- §5 the container exists from first run and nothing ever deletes it ---------------------

    @Test fun aDeviceWithNoAccountAtAllAlreadyHasAContainer() {
        assertTrue(SecureStore.ensureContainer(file, SecureRandom()), "first run authors it")
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file))
        assertFalse(SecureStore.ensureContainer(file, SecureRandom()), "and never again")
        // nothing opens it, because there is nothing in it
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, real, secrets, FastKdf) }
    }

    @Test fun aWipedDeviceAndAnUntouchedOneAreTheSameBytesLong() {
        val untouched = dir.resolve("untouched.store")
        SecureStore.ensureContainer(untouched, SecureRandom())
        val store = firstAccount()
        addDecoy(store)
        openDecoy().update { it.copy(usernameDisplay = "decoy") }
        opened.forEach { it.flush(); it.close() }
        SecureStore.obliterate(file, secrets)
        assertEquals(Files.size(untouched), Files.size(file), "a wipe and a fresh install are indistinguishable by size")
        assertContentEquals(
            Files.readAllBytes(untouched).copyOfRange(0, StoreContainer.HEADER_BYTES),
            Files.readAllBytes(file).copyOfRange(0, StoreContainer.HEADER_BYTES),
            "…and by header",
        )
    }

    @Test fun anAccountIsOnlyEverWrittenIntoASlotSomethingKnowsToBeFree() {
        // first run: this call authored the noise, so every index is provably free
        val store = firstAccount()
        assertEquals(SecureStore.ALL_SLOTS, store.blob.knownSlots, "and it says so explicitly")
        store.flush()
        store.close()
        // a second create on the same container, with nothing known: refused in the ordinary words
        assertFailsWith<StoreError.NoFreeSlot> { SecureStore.create(file, "another password", secrets, FastKdf) }
        // …unless the caller can name a slot it knows is free, e.g. one it just burned itself
        val free = store.freeSlots.first()
        SecureStore.create(file, "another password", secrets, FastKdf, knownFree = listOf(free)).use { second ->
            assertEquals(free, second.slot)
            assertEquals(listOf(free), second.knownSlots, "an account put in a freed slot knows only its own")
        }
        SecureStore.open(file, real, secrets, FastKdf).use { assertEquals("real", it.blob.usernameDisplay) }
    }

    @Test fun replacingADecoyScrubsBOTHHalvesOfItsSlot() {
        // A slot is two halves and a save alternates between them (§1), so a replacement that
        // wrote only half 0 would leave the decoy it replaced sitting in half 1, still
        // authenticating under its own password — and whether it survived would depend on the
        // parity of how many times it had saved. iOS shipped exactly that; this is the window.
        val store = firstAccount()
        val slot = addDecoy(store)
        val oldKey = SecureStore.deriveKey(FastKdf, decoy, SecureStore.salt(secrets))

        // drive the old decoy onto HALF 1: its first save alternates off the half it was authored in
        val d1 = openDecoy()
        assertEquals(0, d1.half, "authored into half 0 at seq 1")
        d1.update { it.copy(usernameDisplay = "the old decoy") }
        d1.flush()
        assertEquals(1, d1.half, "…and its live copy is now in half 1")
        d1.close()
        // both halves authenticate under the old password at this point — that is the trap
        val before = bytes()
        assertNotNull(StoreContainer.open(oldKey, before), "precondition: the old decoy is openable")
        assertEquals(2, (0 until StoreContainer.HALVES_PER_SLOT).count { half -> halfOpens(oldKey, before, slot, half) })

        // now replace it
        store.authorSlot(SecureStore.deriveKey(FastKdf, "the corrected password", SecureStore.salt(secrets)), Blob(usernameDisplay = "the new decoy"))

        val after = bytes()
        for (half in 0 until StoreContainer.HALVES_PER_SLOT) {
            assertFalse(halfOpens(oldKey, after, slot, half), "half $half still holds the decoy that was replaced")
        }
        assertNull(StoreContainer.open(oldKey, after), "the replaced decoy's password opens nothing, whatever its parity")
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
        SecureStore.open(file, "the corrected password", secrets, FastKdf).use {
            assertEquals("the new decoy", it.blob.usernameDisplay)
            assertEquals(slot, it.slot)
        }
        SecureStore.open(file, real, secrets, FastKdf).use { assertEquals("real", it.blob.usernameDisplay) }
    }

    /** Does ONE half of one slot authenticate under [key]? The reachable surface of the parity bug. */
    private fun halfOpens(key: ByteArray, all: ByteArray, slot: Int, half: Int): Boolean {
        val r = StoreContainer.halfRange(slot, half)
        return runCatching { com.pyrochat.store.crypto.StoreCrypto.open(key, all.copyOfRange(r.first, r.last + 1)) }.isSuccess
    }

    @Test fun aColdCreateRewritesTheWholeContainerAndClaimsEverySlot() {
        // §1 "Creating an identity from cold": a device wiped by the duress PIN still has a
        // container, and refusing there would mean it could never hold an account again
        val store = firstAccount()
        addDecoy(store)
        opened.forEach { it.flush(); it.close() }
        val sizeBefore = Files.size(file)

        // no knowledge at all, and not blind: still refused, so nothing is destroyed by accident
        assertFailsWith<StoreError.NoFreeSlot> { SecureStore.create(file, "another password", secrets, FastKdf) }
        SecureStore.open(file, real, secrets, FastKdf).use { } // still there

        // blind — the person confirmed it, unconditionally — takes the WHOLE container
        val reborn = track(SecureStore.create(file, "another password", secrets, FastKdf, blind = true))
        reborn.flush()
        assertEquals(sizeBefore, Files.size(file), "the same 12,582,925 bytes, whatever it held")
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, real, secrets, FastKdf) }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, decoy, secrets, FastKdf) }
        // …and the device is in a fully known state, so a decoy simply works
        assertEquals(SecureStore.ALL_SLOTS, reborn.blob.knownSlots)
        assertEquals(StoreContainer.SLOT_COUNT - 1, reborn.freeSlots.size)
        reborn.authorSlot(SecureStore.deriveKey(FastKdf, "its decoy", SecureStore.salt(secrets)), Blob())
        SecureStore.open(file, "its decoy", secrets, FastKdf).use { assertTrue(it.slot != reborn.slot) }
    }

    @Test fun aColdCreateLooksTheSameWhetherTheDeviceHeldAnythingOrNot() {
        // the property the whole-container rewrite exists for: nothing to compare, no signal to read
        val loaded = dir.resolve("loaded.store")
        SecureStore.create(loaded, real, secrets, FastKdf).use { it.update { b -> b.copy(usernameDisplay = "real") }; it.flush() }
        val empty = dir.resolve("empty.store")
        SecureStore.ensureContainer(empty, SecureRandom())

        for (f in listOf(loaded, empty)) {
            track(SecureStore.create(f, "the same new password", secrets, FastKdf, blind = true)).let { s ->
                s.flush()
                assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(f))
                assertEquals(SecureStore.ALL_SLOTS, s.blob.knownSlots)
                assertEquals(StoreContainer.SLOT_COUNT - 1, s.freeSlots.size, "a decoy is allocatable either way")
            }
        }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(loaded, real, secrets, FastKdf) }
    }

    @Test fun aTargetedCreateKeepsEveryOtherSlotAndKnowsOnlyItsOwn() {
        // the other shape: the caller can NAME a free slot out of an account it opened, so only
        // that slot is written — which is what makes "burn the decoy, make a new one" possible
        val store = firstAccount()
        store.update { it.copy(usernameDisplay = "real") }
        store.flush()
        val free = store.freeSlots.first()
        store.close()
        SecureStore.create(file, "a targeted one", secrets, FastKdf, knownFree = listOf(free)).use { second ->
            assertEquals(free, second.slot)
            assertEquals(listOf(free), second.knownSlots, "it did not touch the rest, so it knows nothing of them")
            assertEquals(emptyList(), second.freeSlots)
        }
        SecureStore.open(file, real, secrets, FastKdf).use { assertEquals("real", it.blob.usernameDisplay) }
    }

    @Test fun aWipedDeviceCanHoldAnAccountAgainAndStillGetsADecoy() {
        // the sequence that matters: duress wipe → relaunch → create → and the feature still works
        val store = firstAccount()
        addDecoy(store)
        opened.forEach { it.flush(); it.close() }
        SecureStore.obliterate(file, secrets)          // duress / ten wrong passwords / dead-man
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file), "the container survives")
        assertEquals(emptySet(), secrets.names())

        // a cold launch afterwards: no salt means nothing has been created since the wipe, which is
        // the same state a fresh install is in — so every slot is provably free
        val reborn = track(SecureStore.create(file, "a new life", secrets, FastKdf, blind = true))
        assertEquals(SecureStore.ALL_SLOTS, reborn.blob.knownSlots, "not bricked, and not crippled either")
        assertEquals(StoreContainer.SLOT_COUNT - 1, reborn.freeSlots.size, "it can still have a decoy")
        reborn.update { it.copy(usernameDisplay = "after the wipe") }
        reborn.flush()
        SecureStore.open(file, "a new life", secrets, FastKdf).use { assertEquals("after the wipe", it.blob.usernameDisplay) }
    }

    @Test fun aFailedUnlockOnAWipedPhoneLeavesNoMarkAndCostsItNothing() {
        val store = firstAccount()
        store.flush(); store.close()
        SecureStore.obliterate(file, secrets)
        assertEquals(emptySet(), secrets.names())
        // somebody tries the old password at the wiped phone, twice
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, real, secrets, FastKdf) }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, "a guess", secrets, FastKdf) }
        assertEquals(emptySet(), secrets.names(), "§5: a wiped phone someone poked at looks like one nobody touched")
        // …and the device can still create an account that knows every slot is free
        track(SecureStore.create(file, "a new life", secrets, FastKdf, blind = true)).let {
            assertEquals(SecureStore.ALL_SLOTS, it.blob.knownSlots)
        }
    }

    @Test fun anAbsentRecordMeansOnlyMyOwnSlotAndNeverEveryIndex() {
        val store = firstAccount()
        addDecoy(store)
        val d = openDecoy()
        // simulate the record being lost — a round trip through a build that does not know the keys
        d.update { it.copy(allocatedSlots = null, knownSlots = null) }
        assertEquals(listOf(d.slot), d.knownSlots, "the least it can mean is the only safe reading")
        assertEquals(listOf(d.slot), d.allocatedSlots)
        assertEquals(emptyList(), d.freeSlots, "so a lost record can never re-grant permission to allocate")
    }

    // ---- §6.4 backups --------------------------------------------------------------------------

    @Test fun aBackupExportedFromTheDecoyCarriesOnlyTheDecoy() {
        val store = firstAccount()
        store.update { it.copy(usernameDisplay = "real", messages = mapOf("peer" to listOf(ChatMessage(id = "m1", direction = ChatMessage.Direction.OUTGOING, kind = ChatMessage.Kind.TEXT, text = "SECRET-MARKER", timestamp = Instant.ofEpochSecond(1_700_000_000), status = ChatMessage.Status.SENT)))) }
        store.flush()
        addDecoy(store)
        val d = openDecoy()
        d.update { it.copy(usernameDisplay = "decoy") }
        d.flush()
        val backup = Backup.export(d, "a backup passphrase for the decoy", FastKdf)
        assertFalse(String(backup, Charsets.ISO_8859_1).contains("SECRET-MARKER"))
        val decoded = Backup.decode(backup, "a backup passphrase for the decoy", FastKdf)
        assertEquals("decoy", decoded.blob.usernameDisplay)
        assertEquals(emptyMap(), decoded.blob.messages, "unremarkable on its own: one account, and no sign of another")
        assertTrue(backup.size < StoreContainer.SLOT_BYTES, "and nothing like the size of a container")
    }

    // ---- §6.1 the stored items never multiply --------------------------------------------------

    @Test fun theNumberOfStoredItemsIsIdenticalWithOneAccountAndWithTwo() {
        val store = firstAccount()
        val one = secrets.names()
        addDecoy(store)
        assertEquals(one, secrets.names(), "§6.1: a fixed set of stored items whose number is identical on every install")
        assertEquals(setOf(SecretStore.KDF_SALT), one)
        // the duress verifier is device-level and acts on every slot, so it does not multiply either
        SecureStore.setDuressPin(secrets, FastKdf, "0000")
        assertEquals(setOf(SecretStore.KDF_SALT, SecretStore.DURESS_VERIFIER), secrets.names())
    }

    // ---- §2 unlocking --------------------------------------------------------------------------

    @Test fun everyHalfOfEverySlotIsTriedWhicheverAccountThePasswordBelongsTo() {
        val store = firstAccount()
        addDecoy(store)
        val all = bytes()
        for (password in listOf(real, decoy, "neither of them")) {
            var attempts = 0
            val key = SecureStore.deriveKey(FastKdf, password, SecureStore.salt(secrets))
            StoreContainer.open(key, all) { _, _, _ -> attempts++ }
            assertEquals(
                StoreContainer.SLOT_COUNT * StoreContainer.HALVES_PER_SLOT, attempts,
                "six attempts, always all six — slot 0 must not be measurably faster to open than slot 2",
            )
        }
    }
}
