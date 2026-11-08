package com.pyrochat.store

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/DECOY-ACCOUNT.md §1, "What a save costs, measured" — the two lanes, at the layer that owns
 * them.
 *
 * Every save writes a whole 2 MiB half whatever changed, so the only lever is how often. A sent or
 * received message and any key material are [Durability.PROMPT], because losing them to a crash is
 * real damage. A draft, read state and interface state are [Durability.LAZY], because losing the
 * last few seconds of an unsent draft is not — and 2 MiB per keystroke is.
 */
class DurabilityTest {
    private val dir: Path = Files.createTempDirectory("pyro-durability-test")
    private val file: Path = dir.resolve(SecureStore.FILE_NAME)
    private val secrets = InMemorySecretStore()
    private val password = "correct horse battery staple"
    private val opened = ArrayList<SecureStore>()

    @AfterTest fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    /** A created store whose first save has already landed, so the counters start from rest. */
    private fun store(): SecureStore =
        SecureStore.create(file, password, secrets, FastKdf).also { opened += it; settle(); it.flush() }

    private fun settle() = Thread.sleep(SecureStore.DEBOUNCE_MS + 250)

    @Test fun aLazyUpdateWritesNothingAtAll() {
        val s = store()
        val base = s.saveCount
        repeat(50) { i -> s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "x".repeat(i + 1))) } }
        settle()
        assertEquals(base, s.saveCount, "a keystroke must never reach the store")
        val bytesBefore = s.bytesWritten
        // …and the disk still holds what it held
        SecureStore.open(file, password, secrets, FastKdf).use { assertNull(it.blob.drafts) }
        assertEquals(bytesBefore, s.bytesWritten, "and no bytes went with it")
    }

    @Test fun aPromptUpdateCarriesEveryLazyChangeWithItForFree() {
        val s = store()
        val base = s.saveCount
        s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "unsent")) }
        s.update { it.copy(usernameDisplay = "me") }        // a message arrives: prompt
        settle()
        assertEquals(base + 1, s.saveCount, "one save, not two — a save writes the whole blob anyway")
        SecureStore.open(file, password, secrets, FastKdf).use {
            assertEquals("me", it.blob.usernameDisplay)
            assertEquals("unsent", it.blob.drafts?.get("t"), "the draft rode along at no cost")
        }
    }

    @Test fun flushIsTheMomentTheLazyLaneHasBeenWaitingFor() {
        val s = store()
        val base = s.saveCount
        repeat(20) { i -> s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "x".repeat(i + 1))) } }
        settle()
        assertEquals(base, s.saveCount)
        s.flush()                                           // composer blur / screen leave / background
        assertEquals(base + 1, s.saveCount, "the whole burst costs exactly one save")
        SecureStore.open(file, password, secrets, FastKdf).use { assertEquals("x".repeat(20), it.blob.drafts?.get("t")) }
    }

    @Test fun flushingAnUnchangedStoreWritesNothing() {
        val s = store()
        val base = s.saveCount
        s.flush(); s.flush(); s.flush()
        assertEquals(base, s.saveCount, "backgrounding an untouched app must not cost 2 MiB a time")
    }

    @Test fun theIdleTimerEventuallyWritesWithoutAnyoneAsking() {
        val s = store()
        s.lazyIdleMs = 300
        s.lazyMaxMs = 3_000
        val base = s.saveCount
        s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "left on the screen")) }
        Thread.sleep(150)
        assertEquals(base, s.saveCount, "still nothing, the phone is simply sitting there")
        Thread.sleep(400)
        assertEquals(base + 1, s.saveCount, "and after the idle wait the draft is on disk anyway")
        SecureStore.open(file, password, secrets, FastKdf).use { assertEquals("left on the screen", it.blob.drafts?.get("t")) }
    }

    @Test fun anUnbrokenRunOfLazyChangesStillReachesTheDiskAtTheCap() {
        val s = store()
        s.lazyIdleMs = 10_000          // never idle
        s.lazyMaxMs = 400              // but the cap is short
        val base = s.saveCount
        repeat(8) { i ->
            s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "x".repeat(i + 1))) }
            Thread.sleep(80)
        }
        Thread.sleep(300)
        assertTrue(s.saveCount > base, "typing for ten minutes without a pause must still leave a draft behind")
    }

    @Test fun theConstantsTheAppUsesAreALongIdleTimerAndACapAboveIt() {
        assertTrue(SecureStore.LAZY_IDLE_MS in 1_000..300_000, "a 'long idle timer', in ms")
        assertTrue(SecureStore.LAZY_MAX_MS >= SecureStore.LAZY_IDLE_MS, "the cap cannot be shorter than the idle wait")
    }

    @Test fun everyOrdinarySaveStillCostsExactlyOneHalf() {
        val s = store()
        val base = s.bytesWritten
        s.update { it.copy(usernameDisplay = "one") }
        settle()
        assertEquals(
            StoreContainer.HALF_BYTES.toLong(), s.bytesWritten - base,
            "the padded half is the honest price of a slot whose size never hints at its contents",
        )
    }

    @Test fun theMeasurementSeamRestoresTheOldBehaviourExactly() {
        val s = store()
        s.measureWithoutLazyLane = true
        val base = s.saveCount
        s.update(Durability.LAZY) { it.copy(drafts = mapOf("t" to "a")) }
        settle()
        assertEquals(base + 1, s.saveCount, "with the seam on, a lazy change is a durable save again")
    }
}
