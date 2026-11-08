package com.pyrochat.app.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.store.SecureStore
import com.pyrochat.store.StoreContainer
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Status
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * docs/DECOY-ACCOUNT.md §3, §4, §5 and §6 over the REAL backend against the host's dev relay
 * (`10.0.2.2:7444`) — the decoy is a genuine account with real identity keys and a real
 * registration, so nothing short of this proves it.
 *
 * Everything is asserted on the session's reachable surface (§9): what [RealBackend] returns and
 * exposes, and what the file on disk does when reopened. Nothing here looks at the interface.
 *
 * Skips when the relay is not reachable from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class DecoyAccountGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "decoy-gate-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-decoy-gate-test"),
        secretsAlias = "com.pyrochat.app.secrets.decoy-gate-test",
    )
    private val deadman = DeadmanSwitch(context.getSharedPreferences("pyro-decoy-gate-test", Context.MODE_PRIVATE))
    private val override = ServerConfig.Override(relay = RELAY, billing = BILLING)

    @Before fun clean() {
        assumeTrue("SKIPPED: no relay at $RELAY_HOST:$RELAY_PORT (start the dev harness on the host)", relayListening())
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        deadman.disarm()
    }

    /** Every backend this test opens, closed in [tearDown] — a leaked save queue would write into
     *  the next test's freshly created container. */
    private val backends = ArrayList<RealBackend>()

    @After fun tearDown() {
        backends.forEach { runCatching { it.lock() }; runCatching { it.close() } }
        backends.clear()
        Thread.sleep(400)   // let the save queues finish before the next @Before obliterates
    }

    private fun backend(): RealBackend = RealBackend(context, paths, deadman, haptics = null).also {
        it.serverOverride = override
        it.finishBoot()
        backends += it
    }

    @Test fun aDecoyIsARealAccountTheRealOneCannotSeeAndTheRowReplacesRatherThanRefusing(): Unit = runBlocking {
        val realUser = "dr" + hex(4)
        val decoyUser = "dd" + hex(4)

        val b1 = backend()
        assertNull(b1.createAccount(realUser, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(20_000) { b1.connState.value == ConnState.online })
        b1.updateSettings { it.copy(theme = "amber", textSize = "largest") }
        assertTrue(waitUntil(5_000) { paths.storeExists() })

        // §3: the password must not be the real one, in the ordinary wrong-value wording
        assertEquals(Mapping.ERR_DECOY_SAME_PASSWORD, b1.setDecoyPassword(decoyUser, REAL_PASSWORD))

        // §3: a genuine account — real identity keys, real relay registration
        assertNull("the decoy must register for real", b1.setDecoyPassword(decoyUser, DECOY_PASSWORD))

        // §1/§3: one decoy, and a second call REPLACES it rather than taking a third slot
        assertTrue(b1.decoyExists())

        b1.lock()
        assertEquals(Stage.Auth, b1.stage.value)

        // ---- §4: inside the decoy ------------------------------------------------------------
        assertNull(b1.unlock(DECOY_PASSWORD))
        assertEquals(Stage.Main, b1.stage.value)
        assertEquals(decoyUser, b1.myUsername)
        assertTrue(
            "the decoy is a full PyroChat, not a crippled one (state=${b1.connState.value} err=${b1.lastError.value})",
            waitUntil(20_000) { b1.connState.value == ConnState.online },
        )
        // nothing written in the real slot is visible here
        assertEquals("ember", b1.settings.value.theme)
        assertNull(b1.settings.value.textSize)
        assertTrue(b1.contacts.value.isEmpty())
        assertTrue(b1.messages.value.isEmpty())
        // §1: a decoy cannot create another, and it fails in the SAME words the real account got
        assertEquals(Mapping.ERR_DECOY_NO_ROOM, b1.setDecoyPassword("dy" + hex(4), "yet another password"))

        // ---- §5: BURN EVERYTHING in the decoy takes only the decoy ---------------------------
        b1.totalWipe()
        assertEquals("a wipe leaves the screen a launch shows — which claims nothing", Stage.Auth, b1.stage.value)
        assertTrue("the container has to survive — it holds the other account", paths.storeExists())
        b1.lock(); b1.close()

        val b2 = backend()
        assertNotNull("the decoy's password opens nothing now", b2.unlock(DECOY_PASSWORD))
        assertNull("and the real account is exactly where it was", b2.unlock(REAL_PASSWORD))
        assertEquals(realUser, b2.myUsername)
        assertEquals("amber", b2.settings.value.theme)
        assertEquals("largest", b2.settings.value.textSize)
        // §3: the row creates OR REPLACES. From the account that owns the decoy it rebuilds it in
        // the slot already allocated, so a mistyped decoy password is not a one-way trip.
        assertTrue("only the owning account can know this", b2.decoyExists())
        assertNull(b2.setDecoyPassword("dz" + hex(4), "a replacement password"))
        b2.lock()
        assertNotNull("the replaced decoy's old password opens nothing", b2.unlock(DECOY_PASSWORD))
        assertNull(b2.unlock("a replacement password"))
    }

    @Test fun aBurnLooksTheSameWhetherOrNotThisAccountIsAlone(): Unit = runBlocking {
        val user = "ds" + hex(4)
        val b = backend()
        assertNull(b.createAccount(user, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(5_000) { paths.storeExists() })
        val before = java.io.File(root, "pyro.store").length()
        // §5: NO escalation. A solo account's burn leaves the same container a decoy's burn does —
        // behaving differently in the common case is exactly what makes the uncommon case legible.
        b.totalWipe()
        assertEquals(Stage.Auth, b.stage.value)
        assertTrue("the container outlives every wipe", paths.storeExists())
        assertEquals(before, java.io.File(root, "pyro.store").length())
        assertNotNull("and nothing in it opens", b.unlock(REAL_PASSWORD))
        // …and the screen still offers a new identity, into the slot this app burned itself
        assertNull(b.createAccount("dt" + hex(4), "another password here", relay = ""))
    }

    @Test fun theSlotABurnLeavesBehindTakesTheNextIdentity(): Unit = runBlocking {
        val realUser = "dp" + hex(4)
        val b = backend()
        assertNull(b.createAccount(realUser, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(20_000) { b.connState.value == ConnState.online })
        assertNull(b.setDecoyPassword("dq" + hex(4), DECOY_PASSWORD))
        b.lock()
        assertNull(b.unlock(DECOY_PASSWORD))
        b.totalWipe()
        // §5: the create screen a burn leaves behind must not answer "identity exists" — that would
        // be a louder tell than the unlock screen it is hiding
        val fresh = "dn" + hex(4)
        assertNull(b.createAccount(fresh, "a brand new password here", relay = ""))
        assertEquals(fresh, b.myUsername)
        b.lock()
        assertNull("and the account that was never burned is untouched", b.unlock(REAL_PASSWORD))
        assertEquals(realUser, b.myUsername)
    }

    /**
     * docs/DECOY-ACCOUNT.md §1, "Creating an identity from cold" — the sequence where getting this
     * wrong is unrecoverable for a real person: the duress PIN fires, the process dies, the phone
     * is relaunched, and a new identity has to be creatable and usable.
     *
     * The container survives the wipe by design (§5), so this is exactly the path that would have
     * bricked the device if creating from cold refused for want of free-slot knowledge.
     */
    @Test fun aDuressWipedPhoneCanCreateAnIdentityAgainAndSendAMessage(): Unit = runBlocking {
        val user = "dw" + hex(4)
        val b1 = backend()
        assertNull(b1.createAccount(user, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(20_000) { b1.connState.value == ConnState.online })
        b1.setDuressPin(DURESS_PIN)
        assertTrue(waitUntil(5_000) { b1.duressPinSet.value })
        b1.lock()
        b1.awaitTeardown()

        // the duress PIN at the unlock prompt: every slot goes, the container does not
        assertEquals(Mapping.ERR_DURESS_COVER, b1.unlock(DURESS_PIN))
        assertEquals(Stage.Auth, b1.stage.value)
        assertTrue("§5: the container outlives the wipe", paths.storeExists())
        assertFalse("…and nothing in it opens", b1.duressPinSet.value)
        assertNotNull(b1.unlock(REAL_PASSWORD))

        // "kill the process": tear the instance down and cold-open in a new one
        b1.close()
        val b2 = backend()
        assertEquals(Stage.Auth, b2.stage.value)

        // create a new identity on the wiped phone — the thing that must not be refused
        val reborn = "dz" + hex(4)
        assertNull("a wiped phone must be able to hold an account again", b2.createAccount(reborn, "a whole new password", relay = ""))
        assertEquals(reborn, b2.myUsername)
        assertTrue(waitUntil(20_000) { b2.connState.value == ConnState.online })

        // …and send a message with it
        b2.addContact("pyrobot")
        assertTrue("contact appears", waitUntil(10_000) { b2.contacts.value.any { it.name == "pyrobot" } })
        val bot = b2.contacts.value.first { it.name == "pyrobot" }
        b2.setActiveThread(bot.hash)
        b2.sendText("alive after the wipe", bot.hash)
        assertTrue(
            "the reborn identity must actually be able to send",
            waitUntil(20_000) {
                b2.messages.value[bot.hash]?.firstOrNull { it.direction == Direction.outgoing }?.status != Status.pending
            },
        )
        // §1: a cold create rewrites the WHOLE container, so the device is in a fully known state
        // afterwards and a decoy simply works — whatever the phone held before
        assertNull("a decoy must be settable straight after a cold create", b2.setDecoyPassword("dy" + hex(4), DECOY_PASSWORD))
    }

    /**
     * docs/DECOY-ACCOUNT.md §1 — the cost the whole-container rewrite accepts, stated plainly: a
     * cold create on a phone that still holds a reachable account destroys it, for certain.
     *
     * And the property it buys: the outcome is identical whatever the device held — same file, same
     * size, same fully-known state — so there is nothing for an adversary to compare and no signal
     * in whether a decoy can be created afterwards.
     */
    @Test fun aColdCreateErasesWhatWasThereAndLooksIdenticalEitherWay(): Unit = runBlocking {
        val store = java.io.File(root, "pyro.store")
        val first = "dc" + hex(4)
        val b1 = backend()
        assertNull(b1.createAccount(first, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(20_000) { b1.connState.value == ConnState.online })
        assertNull(b1.setDecoyPassword("de" + hex(4), DECOY_PASSWORD))
        val sizeWithTwoAccounts = store.length()
        b1.lock()
        b1.awaitTeardown()
        b1.close()

        // a cold launch — no free-slot knowledge at all — and a create the person confirmed
        val b2 = backend()
        val reborn = "dg" + hex(4)
        assertNull(b2.createAccount(reborn, "a third password here", relay = ""))
        assertEquals(reborn, b2.myUsername)
        assertEquals("the container is the same size it always is", sizeWithTwoAccounts, store.length())
        b2.lock()
        assertNotNull("the account that was there is gone, for certain", b2.unlock(REAL_PASSWORD))
        assertNotNull("and so is its decoy", b2.unlock(DECOY_PASSWORD))
        assertNull(b2.unlock("a third password here"))
        // …and the device is fully known again, so a decoy works exactly as on a fresh phone
        assertTrue(waitUntil(20_000) { b2.connState.value == ConnState.online })
        assertNull(b2.setDecoyPassword("dh" + hex(4), "its own decoy password"))
    }

    /**
     * docs/DECOY-ACCOUNT.md §1 + §7 — **an account that arrived by MIGRATION can still set a decoy.**
     *
     * Every other test in this class starts with `createAccount`, which authors the container in
     * this process and stamps the slot record on the way. Not one of them therefore exercises the
     * path every account in the wild actually took: an upgrade, where the container is authored by
     * `migrateFromLegacy` on the first unlock of yesterday's file. iOS shipped a `migrateLegacy`
     * that authored the container and then did not stamp `knownSlots`, and the arithmetic of §1 did
     * the rest — `knownSlots(absent -> own) - allocatedSlots(own)` is empty, so `freeSlots` is empty,
     * so the decoy row fails with the one vague string it is required to fail with, forever, for
     * every upgraded account.
     *
     * Android's `migrateFromLegacy` stamps both keys and `StoreMigrationTest` pins that through
     * disk against the real legacy fixture — but only at the core-store layer, so nothing proved the
     * whole stack. This does, in the user's own words: upgrade, then set a decoy.
     */
    @Test fun anAccountThatArrivedByMigrationCanStillSetADecoy(): Unit = runBlocking {
        val user = "dg" + hex(4)
        val decoyUser = "dk" + hex(4)

        // a real account with real identity keys and a real registration — nothing synthetic, so the
        // only thing that differs from the other tests is HOW its store came to be a container
        val b1 = backend()
        assertNull(b1.createAccount(user, REAL_PASSWORD, relay = ""))
        assertTrue(waitUntil(20_000) { b1.connState.value == ConnState.online })
        b1.lock()
        // `lock` flushes and closes the store on a background coroutine, so the file is not final
        // until this returns — without it the rewind below races the create's own write
        b1.awaitTeardown()

        // rewind the device to YESTERDAY'S FILE: one sealed blob, no container, and — the part that
        // matters — no slot record at all, because the keys did not exist when it was written.
        val key = paths.deriveKey(REAL_PASSWORD)
        try {
            val opened = StoreContainer.open(key, java.nio.file.Files.readAllBytes(paths.storeFile))
            assertNotNull("precondition: the created account is on disk as a container", opened)
            val blob = SwiftJson.decodeBlob(opened!!.json).copy(allocatedSlots = null, knownSlots = null)
            java.nio.file.Files.write(paths.storeFile, StoreCrypto.seal(key, SwiftJson.encodeBlob(blob), SecureRandom()))
        } finally {
            key.fill(0)
        }
        assertFalse(
            "precondition: the device now looks exactly like an install that predates containers",
            StoreContainer.isContainer(java.nio.file.Files.readAllBytes(paths.storeFile)),
        )

        // the upgrade: the first unlock migrates, and the migration AUTHORS the container
        val b2 = backend()
        assertNull("yesterday's file still opens with the same password", b2.unlock(REAL_PASSWORD))
        assertEquals(user, b2.myUsername)
        assertTrue(
            "precondition: the file is a container now",
            StoreContainer.isContainer(java.nio.file.Files.readAllBytes(paths.storeFile)),
        )

        // §1: migration is an authoring event — it wrote the other slots' noise itself, so it knows
        // every index, and the record has to say so or the subtraction below comes out empty
        paths.open(REAL_PASSWORD).use { migrated ->
            assertEquals("the migration must stamp what it knows", SecureStore.ALL_SLOTS, migrated.blob.knownSlots)
            assertEquals(listOf(migrated.slot), migrated.blob.allocatedSlots)
            assertEquals("so there is room for a decoy", StoreContainer.SLOT_COUNT - 1, migrated.freeSlots.size)
        }

        // and the thing the user actually reported
        assertTrue(waitUntil(20_000) { b2.connState.value == ConnState.online })
        assertNull("an upgraded account must be able to set a decoy", b2.setDecoyPassword(decoyUser, DECOY_PASSWORD))

        // the decoy is real: it opens, and it is not the account that migrated
        b2.lock()
        assertNull(b2.unlock(DECOY_PASSWORD))
        assertEquals(decoyUser, b2.myUsername)
        b2.lock()
        assertNull("and the migrated account is untouched beside it", b2.unlock(REAL_PASSWORD))
        assertEquals(user, b2.myUsername)
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(100)
        }
        return cond()
    }

    private fun relayListening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), 1_500); true }
    } catch (_: IOException) {
        false
    }

    private fun hex(n: Int): String = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private companion object {
        const val RELAY_HOST = "10.0.2.2"
        const val RELAY_PORT = 7444
        const val RELAY = "ws://$RELAY_HOST:$RELAY_PORT/ws"
        const val BILLING = "http://$RELAY_HOST:8088"
        const val REAL_PASSWORD = "decoy-gate-real-pass-123"
        const val DECOY_PASSWORD = "decoy-gate-decoy-pass-456"
        const val DURESS_PIN = "2580"
    }
}
