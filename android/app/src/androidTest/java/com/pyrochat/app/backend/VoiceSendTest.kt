package com.pyrochat.app.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.platform.VoiceChanger
import com.pyrochat.app.platform.VoiceDsp
import com.pyrochat.app.platform.VoiceEffect
import com.pyrochat.app.platform.Waveform
import com.pyrochat.app.ui.BackendException
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.sin

/**
 * The one call that sends a voice note (docs/VOICE-CHANGER.md), through the real backend on a
 * device: with a voice chosen, what is sent and kept is the changed note and never the spoken
 * one; with none chosen the note goes out untouched; a note that cannot be changed is refused
 * and leaves no row; a note still goes when its chat is closed while the voice is being changed,
 * and does not when the account is locked in that moment.
 * iOS's twin is in `VoiceChangerTests`.
 *
 * Skips (JUnit assumption) when the relay is not reachable from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "voice-send-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-voice-send-test"),
        secretsAlias = "com.pyrochat.app.secrets.voice-send-test",
    )
    private val deadman = DeadmanSwitch(context.getSharedPreferences("pyro-voice-send-test", Context.MODE_PRIVATE))
    private val rate = VoiceDsp.SAMPLE_RATE
    private var backend: RealBackend? = null

    @Before fun clean() {
        assumeTrue("SKIPPED: no relay at $RELAY_HOST:$RELAY_PORT (start the dev harness on the host)", relayListening())
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        deadman.disarm()
    }

    @After fun close() {
        backend?.let { b -> runBlocking { runCatching { b.totalWipe() } }; b.close() }
        root.deleteRecursively()
    }

    /** Something voice-like at 150 Hz, as the recorder would have saved it. */
    private fun spoken(seconds: Double = 1.0): ByteArray {
        val samples = FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (k in 1..8) v += sin(2.0 * PI * k * 150.0 * t) / k
            (0.25 * v * (0.7 + 0.3 * sin(2.0 * PI * 2.0 * t))).toFloat()
        }
        return VoiceChanger.encode(context, samples)
    }

    /** The pitch of [m4a] in Hz, looked for within a fifth either side of [near]. */
    private fun heard(m4a: ByteArray, near: Double): Double {
        val x = Waveform.decodeChannel0(m4a)!!
        val a = x.size / 4
        val n = x.size / 2
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in (rate / near * 0.8).toInt()..(rate / near * 1.2).toInt() + 1) {
            var dot = 0.0
            for (i in a until a + n - lag) dot += x[i].toDouble() * x[i + lag]
            val score = dot / (n - lag)
            if (score > bestScore) {
                best = lag
                bestScore = score
            }
        }
        return rate.toDouble() / best
    }

    /** An account that is online, with the demo peer as its one contact: (backend, the peer's hash). */
    private suspend fun account(): Pair<RealBackend, String> {
        val b = RealBackend(context, paths, deadman, haptics = null)
        backend = b
        b.serverOverride = ServerConfig.Override(relay = RELAY, billing = BILLING)
        b.finishBoot()
        assertNull(b.createAccount("vc" + hex(4), PASSWORD, relay = ""))
        assertEquals(Stage.Main, b.stage.value)
        assertTrue("must come online after registration", waitUntil(20_000) { b.connState.value == ConnState.online })
        b.addContact("pyrobot")
        assertTrue(waitUntil(5_000) { b.contacts.value.any { it.name == "pyrobot" } })
        return b to b.contacts.value.first { it.name == "pyrobot" }.hash
    }

    private fun voiceRows(b: RealBackend, hash: String) =
        b.messages.value[hash].orEmpty().filter { it.direction == Direction.outgoing && it.kind == Kind.voice }

    @Test fun aNoteGoesOutInTheChosenVoiceAndTheSenderKeepsWhatWasSent(): Unit = runBlocking {
        val (b, peer) = account()
        val note = spoken()
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.DEEP.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.DEEP })
        b.sendVoice(note, 1.0, peer)
        assertTrue(waitUntil(5_000) { voiceRows(b, peer).size == 1 })
        val kept = b.voiceData(voiceRows(b, peer).single())!!
        assertFalse("what was sent is not the voice as it was spoken", kept.contentEquals(note))
        val pitch = heard(kept, 150.0 * 0.77)
        assertTrue("a deep voice: $pitch Hz for 150 Hz spoken", pitch in 150.0 * 0.74 * 0.97..150.0 * 0.80 * 1.03)
        assertEquals(1.0, voiceRows(b, peer).single().voiceDuration!!, 0.0)

        // with no voice chosen the note goes out untouched, byte for byte
        b.updateSettings { it.copy(voiceChanger = null) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.OFF })
        b.sendVoice(note, 1.0, peer)
        assertTrue(waitUntil(5_000) { voiceRows(b, peer).size == 2 })
        assertArrayEquals(note, b.voiceData(voiceRows(b, peer).last()))
    }

    @Test fun aNoteWhoseVoiceCannotBeChangedIsNotSentAndLeavesNoRow(): Unit = runBlocking {
        val (b, peer) = account()
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.ROBOT.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.ROBOT })
        try {
            b.sendVoice(ByteArray(4096) { (it * 13).toByte() }, 2.0, peer)
            fail("a note that could not be changed was accepted")
        } catch (e: BackendException) {
            assertEquals(VoiceChanger.REFUSAL, e.message)
        }
        delay(500)
        assertTrue("no row: nothing claims to have been sent", voiceRows(b, peer).isEmpty())
    }

    /**
     * A note that was sent has no row while its voice is being changed, and a long one takes a
     * moment. The backend names its chat for that moment, so the composer can say so, and not a
     * moment longer; a note that goes out as it was spoken is never named.
     */
    @Test fun aChatIsNamedWhileANoteForItIsBeingChangedAndNoLonger(): Unit = runBlocking {
        val (b, peer) = account()
        val note = spoken(seconds = 6.0)
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.HIGH.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.HIGH })
        assertTrue(b.voiceBeingChanged.value.isEmpty())
        var named = async(Dispatchers.Default) { withTimeoutOrNull(15_000) { b.voiceBeingChanged.first { peer in it } } }
        b.sendVoice(note, 6.0, peer)
        assertEquals("named while its voice was being changed", setOf(peer), named.await())
        assertTrue("and not once the note is in the thread", b.voiceBeingChanged.value.isEmpty())
        assertEquals(1, voiceRows(b, peer).size)

        // refused: named meanwhile, and not afterwards
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.ROBOT.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.ROBOT })
        named = async(Dispatchers.Default) { withTimeoutOrNull(15_000) { b.voiceBeingChanged.first { peer in it } } }
        try {
            b.sendVoice(ByteArray(4096) { (it * 13).toByte() }, 2.0, peer)
            fail("a note that could not be changed was accepted")
        } catch (e: BackendException) {
            assertEquals(VoiceChanger.REFUSAL, e.message)
        }
        named.cancel()
        assertTrue("a refused note is not being changed any more", b.voiceBeingChanged.value.isEmpty())

        // as spoken: nothing is being changed, so nothing is named
        b.updateSettings { it.copy(voiceChanger = null) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.OFF })
        val never = async(Dispatchers.Default) { withTimeoutOrNull(3_000) { b.voiceBeingChanged.first { it.isNotEmpty() } } }
        b.sendVoice(note, 6.0, peer)
        assertNull("a note that goes out as it was spoken is not named", never.await())
    }

    /**
     * The chat screen sends from a scope that ends when the screen is closed. Changing the voice of
     * a note takes a moment, and a note that was sent must not be lost because the chat was closed
     * in that moment: it goes all the same, changed.
     */
    @Test fun aNoteStillGoesWhenItsChatIsClosedWhileItsVoiceIsBeingChanged(): Unit = runBlocking {
        val (b, peer) = account()
        val note = spoken(seconds = 6.0)
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.HIGH.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.HIGH })
        // the chat screen: its work runs on the main thread, in a scope that ends with it
        val screen = CoroutineScope(Job() + Dispatchers.Main)
        val sending = screen.launch { b.sendVoice(note, 6.0, peer) }
        assertNotNull("the voice is being changed", withTimeoutOrNull(15_000) { b.voiceBeingChanged.first { peer in it } })
        screen.cancel() // and in that moment the chat is closed
        sending.join()
        assertTrue("the screen's own work ended with it", sending.isCancelled)
        assertTrue("the note went all the same", waitUntil(20_000) { voiceRows(b, peer).size == 1 })
        val kept = b.voiceData(voiceRows(b, peer).single())!!
        assertFalse("and not as it was spoken", kept.contentEquals(note))
        val pitch = heard(kept, 150.0 * 1.35)
        assertTrue("a high voice: $pitch Hz for 150 Hz spoken", pitch in 150.0 * 1.30 * 0.97..150.0 * 1.40 * 1.03)
    }

    /**
     * And the other way round: an account that is locked while a note's voice is still being
     * changed sends nothing more. The note is not in the thread when the account is opened again.
     */
    @Test fun aNoteWhoseAccountIsLockedWhileItsVoiceIsBeingChangedIsNotSent(): Unit = runBlocking {
        val (b, peer) = account()
        val note = spoken(seconds = 6.0)
        b.updateSettings { it.copy(voiceChanger = VoiceEffect.HIGH.wire) }
        assertTrue(waitUntil(3_000) { b.settings.value.voice == VoiceEffect.HIGH })
        val screen = CoroutineScope(Job() + Dispatchers.Main)
        val sending = screen.launch { runCatching { b.sendVoice(note, 6.0, peer) } }
        assertNotNull("the voice is being changed", withTimeoutOrNull(15_000) { b.voiceBeingChanged.first { peer in it } })
        withContext(Dispatchers.Main) { b.lock() } // and in that moment the account is locked
        sending.join()
        b.awaitTeardown()
        assertNull(b.unlock(PASSWORD))
        assertEquals(Stage.Main, b.stage.value)
        delay(1_000)
        assertTrue("nothing of the note is in the account", voiceRows(b, peer).isEmpty())
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
        const val PASSWORD = "voice-send-pass-123"
    }
}
