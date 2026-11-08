package com.pyrochat.app.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.Stage
import com.pyrochat.app.ui.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * End-to-end over the REAL service layer on the emulator against the host's dev relay
 * (`10.0.2.2:7444`, allow-all tokens) with the local `pyrobot` registered on it:
 * create account → add pyrobot → send → READ receipt + the tour (incl. the 20 s burn row) →
 * tear the process state down and cold-open the store in a fresh backend → unlock → the
 * conversation persisted → echo → total wipe deregisters and leaves no store.
 *
 * "Kill & relaunch": the instrumentation runner cannot survive `Process.killProcess`, so the
 * first backend is torn down the way the OS would leave it on disk (flush + close, no further
 * writes) and a NEW instance cold-opens the same files — what a relaunch after a kill sees. The
 * UI gate (`am force-stop` + relaunch) exercises the literal kill.
 *
 * Skips (JUnit assumption) when the relay is not reachable from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class RealBackendSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "smoke-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-smoke-test"),
        secretsAlias = "com.pyrochat.app.secrets.smoke-test",
    )
    private val deadman = DeadmanSwitch(context.getSharedPreferences("pyro-smoke-test", Context.MODE_PRIVATE))
    private val override = ServerConfig.Override(relay = RELAY, billing = BILLING)

    @Before fun clean() {
        assumeTrue("SKIPPED: no relay at $RELAY_HOST:$RELAY_PORT (start the dev harness on the host)", relayListening())
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        deadman.disarm()
    }

    @Test fun createTalkToPyrobotRelaunchUnlockPersistedThenWipe(): Unit = runBlocking {
        val user = "sm" + hex(4)
        val b1 = RealBackend(context, paths, deadman, haptics = null)
        b1.serverOverride = override
        b1.finishBoot()
        assertEquals(Stage.Auth, b1.stage.value)

        // create account: identity → store → register → online
        assertNull(b1.createAccount(user, PASSWORD, relay = ""))
        assertEquals(Stage.Main, b1.stage.value)
        assertEquals(user, b1.myUsername)
        assertEquals(RELAY, b1.settings.value.relayURL)
        assertTrue("must come online after registration", waitUntil(20_000) { b1.connState.value == ConnState.online })
        assertTrue("store is written (debounced 250 ms, like iOS)", waitUntil(3_000) { paths.storeExists() })

        // add the demo peer and send
        b1.addContact("pyrobot")
        // the UI flows are re-published on the main dispatcher — a hop behind the service actor
        assertTrue("contact appears in the UI flow", waitUntil(5_000) { b1.contacts.value.any { it.name == "pyrobot" } })
        val bot = b1.contacts.value.first { it.name == "pyrobot" }
        b1.setActiveThread(bot.hash)
        b1.sendText("hi from android", bot.hash)
        assertTrue("bot receipts must upgrade our message to READ", waitUntil(20_000) {
            b1.messages.value[bot.hash]?.firstOrNull { it.direction == Direction.outgoing }?.status == Status.read
        })
        // the welcome tour: 5 texts, one self-destructing after 20 s of being read
        assertTrue("tour must arrive", waitUntil(40_000) {
            (b1.messages.value[bot.hash] ?: emptyList()).count { it.direction == Direction.incoming && it.kind == Kind.text } >= 5
        })
        val tour = b1.messages.value[bot.hash]!!.filter { it.direction == Direction.incoming }
        assertTrue(tour.any { it.burnAfter == 20L })
        assertTrue(tour.any { it.text.contains("pyrobot") })
        // the rows the chat screen would report as visible (0.5 s dwell) — marks them read locally
        for (m in tour) if (b1.needsVisibilityReport(m)) b1.noteRowVisible(m.id, bot.hash)
        assertTrue(waitUntil(5_000) {
            b1.messages.value[bot.hash]!!.filter { it.direction == Direction.incoming && it.kind == Kind.text }.all { it.status == Status.read }
        })
        val sentId = b1.messages.value[bot.hash]!!.first { it.direction == Direction.outgoing }.id

        // "kill": lock tears the service down and flushes; close releases the instance
        b1.lock()
        b1.awaitTeardown()
        assertEquals(Stage.Auth, b1.stage.value)
        assertTrue(b1.messages.value.isEmpty())
        b1.close()

        // "relaunch": a fresh backend over the same on-disk store
        val b2 = RealBackend(context, paths, deadman, haptics = null)
        b2.serverOverride = override
        b2.finishBoot()
        assertEquals(Stage.Auth, b2.stage.value)
        assertEquals(Mapping.ERR_WRONG_PASSWORD, b2.unlock("not-the-password"))
        assertNull(b2.unlock(PASSWORD))
        assertEquals(Stage.Main, b2.stage.value)
        assertEquals(user, b2.myUsername)
        val persisted = b2.messages.value[bot.hash] ?: emptyList()
        assertTrue("outgoing row persisted with its READ status", persisted.any { it.id == sentId && it.status == Status.read })
        assertTrue("tour persisted", persisted.count { it.direction == Direction.incoming && it.kind == Kind.text } >= 5)
        assertEquals("pyrobot", b2.contacts.value.first { it.hash == bot.hash }.name)
        assertTrue(waitUntil(20_000) { b2.connState.value == ConnState.online })

        // echo mode after the tour
        b2.sendText("echo me", bot.hash)
        assertTrue("bot echoes after the tour", waitUntil(20_000) {
            (b2.messages.value[bot.hash] ?: emptyList()).any { it.direction == Direction.incoming && it.text == "you said: echo me" }
        })

        // TOTAL WIPE: deregister (online) → obliterate → fresh-install stage
        b2.totalWipe()
        // docs/DECOY-ACCOUNT.md §5: the container outlives every wipe, so a wiped device and one
        // that never held anything are the same bytes — nothing opens, and nothing is deleted
        assertEquals(Stage.Auth, b2.stage.value)
        assertTrue(paths.storeExists())
        assertNotNull(b2.unlock(PASSWORD))
        assertEquals("", b2.myUsername)
        b2.finishBoot()
        assertEquals(Stage.Auth, b2.stage.value)
        b2.close()
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
        const val PASSWORD = "smoke-test-pass-123"
    }
}
