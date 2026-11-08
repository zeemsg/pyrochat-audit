package com.pyrochat.app.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.ChatMessage
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Direction
import com.pyrochat.app.ui.Kind
import com.pyrochat.app.ui.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * What a screen starts must not end with the screen.
 *
 * The chat screen's work runs in a scope that is cancelled when the screen closes. An upload
 * waits for the socket between chunks, a delete-for-both waits for the relay's word, a group
 * message waits for the keys of a member the sender has never written to. Closing the chat in
 * such a moment used to cut the work off where it stood: half a file at the relay, a message
 * deleted on the other phone and not on this one, a group message that reached some members.
 * (Group changes such as leaving are queued by the service itself and were never cut off.)
 *
 * Every test here slows the sender's link to a trickle or holds it still ([HeldLink]), starts the
 * work the way the screen does, closes the "screen" while the work is waiting, lets the link go
 * and looks at the other side. iOS starts the same work from a task of its own, which a closing
 * view does not end.
 *
 * Skips when the relay is not reachable from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class SendOutlivesScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val random = SecureRandom()

    private class Account(val backend: RealBackend, val paths: StorePaths, val root: File)

    private val accounts = ArrayList<Account>()
    private val links = ArrayList<HeldLink>()

    @Before fun relayIsThere() {
        assumeTrue("SKIPPED: no relay at $RELAY_HOST:$RELAY_PORT (start the dev harness on the host)", relayListening())
    }

    @After fun close() {
        links.forEach { it.close() }
        for (a in accounts) {
            runBlocking { runCatching { a.backend.totalWipe() } }
            a.backend.close()
            a.paths.obliterate()
            a.root.deleteRecursively()
        }
        Thread.sleep(400) // let the save queues finish before the next test makes its stores
    }

    /** An account that is online, reaching the relay at [relay], with a store of its own. */
    private suspend fun account(relay: String = RELAY): RealBackend {
        val tag = "outlives-" + hex(4)
        val root = File(context.filesDir, tag)
        val paths = StorePaths(
            context,
            filesDir = root,
            secretsDir = File(context.noBackupFilesDir, "secrets-$tag"),
            secretsAlias = "com.pyrochat.app.secrets.$tag",
        )
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
        val deadman = DeadmanSwitch(context.getSharedPreferences("pyro-$tag", Context.MODE_PRIVATE))
        deadman.disarm()
        val b = RealBackend(context, paths, deadman, haptics = null)
        accounts += Account(b, paths, root)
        b.serverOverride = ServerConfig.Override(relay = relay, billing = BILLING)
        b.finishBoot()
        assertNull(b.createAccount("so" + hex(5), PASSWORD, relay = ""))
        assertTrue("must come online after registration", waitUntil(30_000) { b.connState.value == ConnState.online })
        return b
    }

    private fun heldLink(): HeldLink = HeldLink(RELAY_HOST, RELAY_PORT).also { links += it }

    /**
     * Alice, behind [link], and bob, who have already exchanged a first text. With sealed sender
     * the first message to someone new is text only, and attachments work once it shows DELIVERED
     * (docs/SEALED-SENDER.md, decision D1); without sealed sender it changes nothing here.
     */
    private suspend fun acquainted(link: HeldLink): Pair<RealBackend, RealBackend> {
        val alice = account(link.url)
        val bob = account()
        alice.addContact(bob.myUsername)
        alice.sendText("hello", bob.myHash)
        assertTrue("the first text was delivered", waitUntil(30_000) {
            alice.messages.value[bob.myHash].orEmpty().any { it.direction == Direction.outgoing && (it.status == Status.delivered || it.status == Status.read) }
        })
        return alice to bob
    }

    /** The chat screen: its work runs on the main thread, in a scope that ends with it. */
    private fun screen() = CoroutineScope(Job() + Dispatchers.Main)

    private fun incoming(b: RealBackend, thread: String): List<ChatMessage> =
        b.messages.value[thread].orEmpty().filter { it.direction == Direction.incoming }

    /** The one upload in flight has gone part of the way: some of it has left, and not all. */
    private fun partWay(b: RealBackend): Boolean {
        val gone = b.blobProgress.value.values.singleOrNull() ?: return false
        return gone > 0f && gone < 1f
    }

    /**
     * [send] starts an upload of [content] from alice to bob the way the chat screen would, over
     * a link slow enough for it to take a good while. The chat is closed while the upload is part
     * of the way; bob still gets all of it.
     */
    private suspend fun arrivesWholeThoughItsChatWasClosed(
        kind: Kind,
        content: ByteArray,
        send: suspend (alice: RealBackend, bob: String) -> Unit,
    ) {
        val link = heldLink()
        val (alice, bob) = acquainted(link)
        val peer = bob.myHash

        link.trickle = SLOW
        val screen = screen()
        val sending = screen.launch { send(alice, peer) }
        assertTrue("the upload is under way, part of the way", waitUntil(30_000) { partWay(alice) })
        screen.cancel() // the chat is closed
        sending.join()
        assertTrue("the screen's own work ended with it", sending.isCancelled)
        assertTrue("it has not arrived yet: the link is slow", incoming(bob, alice.myHash).none { it.kind == kind })
        link.trickle = 0

        assertTrue("all of it arrived on the other side", waitUntil(30_000) {
            incoming(bob, alice.myHash).any {
                it.kind == kind && (if (kind == Kind.voice) bob.voiceData(it) else bob.attachmentData(it))?.contentEquals(content) == true
            }
        })
        assertTrue("and the sender's row is no longer part of the way", waitUntil(10_000) { alice.blobProgress.value.isEmpty() })
    }

    @Test fun aFileArrivesWholeThoughItsChatWasClosedMidUpload(): Unit = runBlocking {
        val content = bytes(3_000_000)
        arrivesWholeThoughItsChatWasClosed(Kind.file, content) { alice, bob ->
            alice.sendFile(content, "report.bin", "application/octet-stream", bob, null)
        }
    }

    @Test fun anImageArrivesWholeThoughItsChatWasClosedMidUpload(): Unit = runBlocking {
        val content = bytes(3_000_000)
        arrivesWholeThoughItsChatWasClosed(Kind.image, content) { alice, bob ->
            alice.sendImage(content, 640, 480, bob, null)
        }
    }

    @Test fun aVoiceNoteArrivesWholeThoughItsChatWasClosedMidUpload(): Unit = runBlocking {
        val content = bytes(3_000_000)
        arrivesWholeThoughItsChatWasClosed(Kind.voice, content) { alice, bob ->
            alice.sendVoice(content, 90.0, bob)
        }
    }

    /**
     * The same for `[RETRY]` on a row that failed. A row that failed while the phone was offline is
     * sent again by itself when the link comes back, except in a blocked chat. That is how this
     * test comes by a failed row to retry by hand: the chat is blocked while the link comes back,
     * and unblocked afterwards.
     */
    @Test fun aRetriedFileArrivesWholeThoughItsChatWasClosedMidUpload(): Unit = runBlocking {
        val link = heldLink()
        val (alice, bob) = acquainted(link)
        val peer = bob.myHash
        val content = bytes(3_000_000)

        alice.goOffline()
        assertTrue(waitUntil(10_000) { alice.connState.value == ConnState.offline })
        alice.sendFile(content, "again.bin", "application/octet-stream", peer, null)
        fun row() = alice.messages.value[peer].orEmpty().single { it.fileName == "again.bin" }
        assertTrue("failed: there is no link", waitUntil(15_000) { row().status == Status.failed })
        alice.blockContact(peer)
        assertTrue(waitUntil(5_000) { peer in alice.blockedHashes.value })
        alice.goOnline()
        assertTrue(waitUntil(30_000) { alice.connState.value == ConnState.online })
        delay(1_500)
        assertEquals("a failed row in a blocked chat is not sent again by itself", Status.failed, row().status)
        alice.unblockContact(peer)
        assertTrue(waitUntil(5_000) { peer !in alice.blockedHashes.value })

        link.trickle = SLOW
        val screen = screen()
        val retrying = screen.launch { alice.retry(row().id, peer) }
        assertTrue("the upload is under way again, part of the way", waitUntil(30_000) { partWay(alice) })
        screen.cancel() // the chat is closed
        retrying.join()
        assertTrue("it has not arrived yet: the link is slow", incoming(bob, alice.myHash).none { it.kind == Kind.file })
        link.trickle = 0

        assertTrue("all of it arrived on the other side", waitUntil(30_000) {
            incoming(bob, alice.myHash).any { it.kind == Kind.file && bob.attachmentData(it)?.contentEquals(content) == true }
        })
        assertTrue("and the sender's row is no longer part of the way", waitUntil(10_000) { alice.blobProgress.value.isEmpty() })
    }

    /**
     * Delete-for-both sends the request, waits for the relay to take it and only then removes the
     * row here. Closed in that wait, the message used to go on the other phone and stay on this one.
     */
    @Test fun aMessageDeletedForBothIsGoneOnBothThoughItsChatWasClosedMeanwhile(): Unit = runBlocking {
        val link = heldLink()
        val alice = account(link.url)
        val bob = account()
        alice.addContact(bob.myUsername)
        val peer = bob.myHash
        alice.sendText("not for keeping", peer)
        assertTrue(waitUntil(20_000) { incoming(bob, alice.myHash).any { it.text == "not for keeping" } })
        val id = alice.messages.value[peer].orEmpty().single { it.text == "not for keeping" }.id

        link.held = true
        val screen = screen()
        val deleting = screen.launch { alice.deleteForBoth(id, peer) }
        delay(700) // the request is written and held: the relay has not answered
        assertTrue("still waiting for the relay's word", alice.messages.value[peer].orEmpty().any { it.id == id })
        screen.cancel() // the chat is closed
        deleting.join()
        link.held = false

        assertTrue("gone on the other side", waitUntil(20_000) { incoming(bob, alice.myHash).none { it.text == "not for keeping" } })
        assertTrue("and gone here", waitUntil(10_000) { alice.messages.value[peer].orEmpty().none { it.id == id } })
    }

    /**
     * Alice, in a group bob made, with carol whom she has never written to: her first message to the
     * group has to fetch carol's keys before it can go to her. Closed in that wait, it used to reach
     * some of the group and not the rest.
     */
    @Test fun aGroupMessageReachesEveryMemberThoughItsChatWasClosedMeanwhile(): Unit = runBlocking {
        val (alice, bob, carol, link) = groupOfThree()
        val group = bob.groups.value.single().id

        link.held = true
        val screen = screen()
        val sending = screen.launch { alice.sendText("to all of you", group) }
        delay(700) // carol's keys have been asked for, and the question is held
        screen.cancel() // the chat is closed
        sending.join()
        link.held = false

        assertTrue("bob has it", waitUntil(30_000) { incoming(bob, group).any { it.text == "to all of you" } })
        assertTrue("and carol has it", waitUntil(30_000) { incoming(carol, group).any { it.text == "to all of you" } })
    }

    private data class Three(val alice: RealBackend, val bob: RealBackend, val carol: RealBackend, val link: HeldLink)

    /** Bob's group with alice and carol. Alice (behind the held link) and carol have never written to each other. */
    private suspend fun groupOfThree(): Three {
        val link = heldLink()
        val alice = account(link.url)
        val bob = account()
        val carol = account()
        bob.addContact(alice.myUsername)
        bob.addContact(carol.myUsername)
        val group = bob.createGroup("three", listOf(alice.myHash, carol.myHash), "alert", null)
        assertTrue("alice is in", waitUntil(30_000) { alice.groups.value.any { it.id == group } })
        assertTrue("carol is in", waitUntil(30_000) { carol.groups.value.any { it.id == group } })
        assertEquals(3, alice.groups.value.single().members.size)
        assertTrue("alice does not know carol", alice.contacts.value.none { it.hash == carol.myHash })
        return Three(alice, bob, carol, link)
    }

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun hex(n: Int): String = ByteArray(n).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private suspend fun waitUntil(timeoutMs: Long, cond: suspend () -> Boolean): Boolean {
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

    /**
     * A pass-through to the relay on this device's own loopback whose outgoing half can be slowed
     * or held. While [held], nothing the app writes goes on to the relay, and after the first few
     * kilobytes nothing is read either; with [trickle] set, it goes on at that many bytes a second.
     * Either way the app's socket fills up and the app waits, as it does on a poor link. What
     * comes back from the relay always passes.
     */
    private class HeldLink(private val host: String, private val port: Int) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).apply { receiveBufferSize = 16 * 1024 }
        private val sockets = ConcurrentLinkedQueue<Socket>()

        @Volatile var held = false

        /** Bytes a second at which what the app writes is passed on; 0 = as fast as it comes. */
        @Volatile var trickle = 0

        val url: String get() = "ws://127.0.0.1:${server.localPort}/ws"

        init {
            thread(isDaemon = true, name = "held-link") {
                try {
                    while (true) {
                        val app = server.accept()
                        val relay = Socket(host, port)
                        sockets += app
                        sockets += relay
                        pump(app, relay, gated = true)
                        pump(relay, app, gated = false)
                    }
                } catch (_: IOException) {
                    // closed
                }
            }
        }

        private fun pump(from: Socket, to: Socket, gated: Boolean) = thread(isDaemon = true, name = "held-link-pump") {
            val buffer = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    while (gated && held) Thread.sleep(10) // what was just read is kept back too
                    output.write(buffer, 0, n)
                    output.flush()
                    val rate = if (gated) trickle else 0
                    if (rate > 0) Thread.sleep(n * 1000L / rate)
                }
            } catch (_: IOException) {
                // one side went away
            } catch (_: InterruptedException) {
                // closing
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }

        override fun close() {
            held = false
            trickle = 0
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val RELAY_HOST = "10.0.2.2"
        const val RELAY_PORT = 7444
        const val RELAY = "ws://$RELAY_HOST:$RELAY_PORT/ws"
        const val BILLING = "http://$RELAY_HOST:8088"
        const val PASSWORD = "outlives-pass-123"

        /** A link on which the 3 MB the upload tests send would take some twenty seconds. */
        const val SLOW = 150_000
    }
}
