package com.pyrochat.service

import com.pyrochat.crypto.Sealed
import com.pyrochat.protocol.Wire
import com.pyrochat.protocol.usernameHash
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.GroupPolicy
import com.pyrochat.store.model.PeerKey
import com.pyrochat.store.model.PeerKeys.Lane
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sealed sender end to end (docs/SEALED-SENDER.md §9): real `ChatService`s against the dev relay
 * on 127.0.0.1:7444 with `sealed.enabled=true` and the throwaway test keys. Every test skips
 * itself on a relay that does not offer sealed sender, so the suite stays green in both relay
 * modes. What the relay was told is read from its own totals (`/admin/data`): how many messages
 * it accepted sealed, on the first-contact lane, and the old way. The port of iOS `SealedLiveTests`.
 */
class SealedLiveTest {
    private val users = ArrayList<TestUser>()

    @AfterTest
    fun close(): Unit = runBlocking {
        for (u in users) {
            runCatching { u.svc.goOffline() }
            u.close()
        }
    }

    private fun make(
        prefix: String,
        port: Int = Live.PORT,
        legacy: Boolean = false,
        linkTiming: RelayLink.Timing = RelayLink.Timing(),
        inFront: () -> Boolean = { true },
    ): TestUser =
        Live.makeService(prefix, relayUrl = "ws://${Live.HOST}:$port/ws", legacy = legacy, linkTiming = linkTiming, inFront = inFront) {
            it.copy(settings = it.settings.copy(sendReadReceipts = true))
        }.also { users += it }

    private suspend fun eventually(timeoutMs: Long = 10_000, cond: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(100)
        }
        return cond()
    }

    /** The relay's own count of what it accepted, since it started. */
    private fun totals(port: Int = Live.PORT): Live.RelayTotals {
        val counted = Live.relayTotals(port)
        Assumptions.assumeTrue(counted != null, "SKIPPED: the relay's totals are not readable (admin password, or a relay without them)")
        return counted!!
    }

    private fun assumeSealed(vararg who: TestUser) = Assumptions.assumeTrue(
        who.all { it.svc.sealedStatus.value == SealedStatus.ON },
        "SKIPPED: this relay does not offer sealed sender",
    )

    /** Two registered services on a relay that offers sealed sender, or a skip. */
    private suspend fun pair(a: String = "alice", b: String = "bob", port: Int = Live.PORT): Pair<TestUser, TestUser> {
        Assumptions.assumeTrue(Live.relayListening(port = port), "SKIPPED: no relay listening on ${Live.HOST}:$port")
        val alice = make(a, port)
        val bob = make(b, port)
        alice.svc.register(alice.username)
        bob.svc.register(bob.username)
        assumeSealed(alice, bob)
        return alice to bob
    }

    private fun contact(s: TestUser, peer: TestUser): Contact = s.svc.contacts.value.first { it.hash == peer.hash }
    private fun rows(s: TestUser, thread: String): List<ChatMessage> = s.svc.messages.value[thread].orEmpty()
    private fun texts(s: TestUser, with: TestUser, direction: Direction): List<ChatMessage> =
        rows(s, with.hash).filter { it.kind == Kind.TEXT && it.direction == direction }
    private fun peerKey(s: TestUser, peer: TestUser): PeerKey? = s.svc.store.blob.peerKeys?.get(peer.hash)
    private fun deliveryKey(s: TestUser): Bytes? = s.svc.store.blob.deliveryKey
    private suspend fun lane(s: TestUser, peer: TestUser): Lane = s.svc.sealedForTest { lane(peer.hash) }
    private suspend fun anon(s: TestUser): AnonLink? = s.svc.sealedForTest { anon }
    private fun captured(s: TestUser, row: ChatMessage): String? = s.svc.capturedFrame(row.id)?.t

    /** alice adds bob, writes first, and both end up holding each other's delivery key. */
    private suspend fun introduce(alice: TestUser, bob: TestUser) {
        alice.svc.addContact(bob.username)
        alice.svc.sendText("hello", contact(alice, bob))
        assertTrue(
            eventually {
                peerKey(alice, bob)?.dk != null && peerKey(bob, alice)?.dk != null &&
                    texts(alice, bob, Direction.OUTGOING).firstOrNull()?.status == Status.DELIVERED
            },
            "first message delivered and delivery keys exchanged",
        )
    }

    // ----------------------------------------------------------------

    @Test
    fun `first contact, then both ways sealed, and the relay is not told who writes`(): Unit = runBlocking {
        val (alice, bob) = pair()
        val before = totals()

        // adding a contact asks for their keys on the anonymous connection: the relay is not
        // told who is about to write to whom
        alice.svc.addContact(bob.username)
        assertEquals(true, peerKey(alice, bob)?.sealed, "the bundle said bob can receive sealed messages")
        assertNotNull(anon(alice), "the private connection is open")
        assertEquals(Lane.FIRST, lane(alice, bob))

        alice.svc.sendText("hello bob", contact(alice, bob))
        val row = texts(alice, bob, Direction.OUTGOING).first()
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).firstOrNull()?.text == "hello bob" }, "bob gets a frame that names nobody, and knows it is alice")
        assertEquals(row.id, texts(bob, alice, Direction.INCOMING).first().id, "filed under her row id, which rode inside")
        assertEquals(false, bob.svc.contacts.value.firstOrNull { it.hash == alice.hash }?.name?.isEmpty())

        // SENT when the relay took it, DELIVERED when bob's receipt came back (sealed, on the key lane)
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).first().status == Status.DELIVERED })
        assertEquals(deliveryKey(bob), peerKey(alice, bob)?.dk, "his receipt carried his key")
        assertEquals(deliveryKey(alice), peerKey(bob, alice)?.dk, "her message carried hers")
        assertEquals(Lane.KEY, lane(alice, bob))

        // READ, and a reply
        bob.svc.noteRowVisible(row.id, alice.hash)
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).first().status == Status.READ }, "the read receipt is sealed too")
        bob.svc.sendText("hello alice", contact(bob, alice))
        assertTrue(eventually { texts(alice, bob, Direction.INCOMING).firstOrNull()?.text == "hello alice" })
        eventually { texts(bob, alice, Direction.OUTGOING).firstOrNull()?.status == Status.DELIVERED }

        // the details sheet shows the frames as they were: `deliver` going out, no `from` coming in
        val sent = assertNotNull(alice.svc.capturedFrame(row.id))
        assertEquals(CapturedFrame.DELIVER, sent.t)
        assertNotEquals(row.id, sent.id, "the relay never saw the row id")
        val got = assertNotNull(bob.svc.capturedFrame(row.id))
        assertEquals(CapturedFrame.RECV, got.t)
        assertEquals("", got.peer, "the frame bob got names no sender")

        // what the relay counted: nothing the old way, and exactly one first-contact message
        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "not one message named its sender to the relay")
        assertEquals(1, after.firstContact - before.firstContact, "only the very first message used the first-contact lane")
        assertEquals(4, after.sealed - before.sealed, "bob's two receipts, his reply and alice's receipt for it: all on the key lane")
    }

    @Test
    fun `an attachment waits for the first text, then sends itself`(): Unit = runBlocking {
        val (alice, bob) = pair()
        val before = totals()
        alice.svc.addContact(bob.username)

        // D1: the first message to someone new is text only
        val bytes = ByteArray(90_000) { (it * 17).toByte() }
        alice.svc.sendFile(bytes, "notes.bin", "application/octet-stream", contact(alice, bob))
        assertTrue(eventually { rows(alice, bob.hash).firstOrNull { it.kind == Kind.FILE }?.status == Status.FAILED })
        val file = rows(alice, bob.hash).first { it.kind == Kind.FILE }
        assertEquals(Sealed.firstContactRefusal(bob.username), alice.svc.lastError.value)
        assertTrue(rows(bob, alice.hash).isEmpty(), "nothing was sent")

        // once a text is delivered bob's key is here, and the attachment goes by itself
        alice.svc.sendText("first, a text", contact(alice, bob))
        assertTrue(eventually(15_000) { rows(bob, alice.hash).any { it.kind == Kind.FILE } }, "the attachment was retried when bob's delivery key arrived")
        val got = rows(bob, alice.hash).first { it.kind == Kind.FILE }
        assertEquals(file.id, got.id, "under the sender's row id")
        assertContentEquals(bytes, bob.svc.attachmentData(got), "byte for byte")
        assertTrue(eventually { rows(alice, bob.hash).first { it.id == file.id }.status == Status.DELIVERED })

        // and back: a voice note on the key lane
        val audio = ByteArray(40_000) { (it * 5).toByte() }
        bob.svc.sendVoice(audio, 3.0, contact(bob, alice))
        assertTrue(eventually { rows(alice, bob.hash).any { it.kind == Kind.VOICE } })
        assertContentEquals(audio, alice.svc.voiceContent(rows(alice, bob.hash).first { it.kind == Kind.VOICE }))

        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "manifests and receipts all went sealed")
    }

    @Test
    fun `an old build and a new one talk the old way, and one message after the update is enough`(): Unit = runBlocking {
        Live.requireRelay()
        val nora = make("nora")
        val olga = make("olga", legacy = true) // a build from before sealed sender
        nora.svc.register(nora.username)
        olga.svc.register(olga.username)
        assumeSealed(nora)
        assertEquals(SealedStatus.NOT_OFFERED, olga.svc.sealedStatus.value)
        assertNull(deliveryKey(olga), "an old build has no delivery key to offer")
        val before = totals()

        nora.svc.addContact(olga.username)
        assertEquals(false, peerKey(nora, olga)?.sealed, "her bundle does not say sealed")
        nora.svc.sendText("to an old build", contact(nora, olga))
        assertTrue(eventually { texts(olga, nora, Direction.INCOMING).firstOrNull()?.text == "to an old build" })
        olga.svc.sendText("from an old build", contact(olga, nora))
        assertTrue(eventually { texts(nora, olga, Direction.INCOMING).firstOrNull()?.text == "from an old build" })
        eventually { texts(nora, olga, Direction.OUTGOING).firstOrNull()?.status == Status.DELIVERED }
        assertNull(olga.svc.store.blob.peerKeys, "an old build keeps nothing about delivery keys")
        val mid = totals()
        assertEquals(0, mid.sealed - before.sealed, "nothing sealed reached the old build")
        assertEquals(0, mid.firstContact - before.firstContact)
        assertTrue(mid.legacy - before.legacy >= 2)
        assertEquals(CapturedFrame.SEND, captured(nora, texts(nora, olga, Direction.OUTGOING)[0]))

        // olga updates: the same account logs in with a delivery key
        olga.svc.goOffline()
        olga.svc.legacyWireOnly = false
        olga.svc.goOnline()
        assertEquals(SealedStatus.ON, olga.svc.sealedStatus.value)
        // nora still believes olga cannot receive sealed: one more message the old way, carrying her key…
        nora.svc.sendText("after your update", contact(nora, olga))
        // …and olga's receipt for it comes back sealed, carrying hers
        assertTrue(eventually { peerKey(nora, olga)?.dk != null && peerKey(nora, olga)?.sealed == true }, "one legacy message after the update is enough")
        assertEquals(Lane.KEY, lane(nora, olga))
        assertEquals(Lane.KEY, lane(olga, nora))
        val sealedFrom = totals()
        nora.svc.sendText("sealed now", contact(nora, olga))
        assertTrue(eventually { texts(olga, nora, Direction.INCOMING).lastOrNull()?.text == "sealed now" })
        eventually { texts(nora, olga, Direction.OUTGOING).lastOrNull()?.status == Status.DELIVERED }
        val after = totals()
        assertEquals(0, after.legacy - sealedFrom.legacy, "from then on nothing names its sender")
    }

    @Test
    fun `a peer who goes back to an old build is reached the old way`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val used = assertNotNull(anon(alice))

        bob.svc.goOffline()
        bob.svc.legacyWireOnly = true // an old build takes the account over
        bob.svc.goOnline()
        alice.svc.sendText("are you still there", contact(alice, bob))
        assertTrue(
            eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "are you still there" },
            "the relay said `unsupported`; the message went again the old way",
        )
        val row = texts(alice, bob, Direction.OUTGOING).last()
        assertEquals(false, peerKey(alice, bob)?.sealed)
        assertNotNull(peerKey(alice, bob)?.dk, "his key is kept for when he is back")
        assertEquals(CapturedFrame.SEND, captured(alice, row), "the details sheet shows the frame that got through")
        assertEquals(row.id, alice.svc.capturedFrame(row.id)?.id)
        assertTrue(eventually { alice.svc.sealedForTest { used.ended } }, "the connection the refusal came in on is not used again")
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status != Status.PENDING })
        assertNotEquals(Status.FAILED, texts(alice, bob, Direction.OUTGOING).last().status)
    }

    @Test
    fun `a changed delivery key is found again without anyone doing anything`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val oldKey = assertNotNull(deliveryKey(bob))

        // bob restores a backup from before sealed sender: a new delivery key, and nothing
        // remembered about anyone else's
        bob.svc.goOffline()
        bob.svc.sealedForTest { bob.svc.store.update { it.copy(deliveryKey = null, peerKeys = null) } }
        bob.svc.goOnline()
        val newKey = assertNotNull(deliveryKey(bob))
        assertNotEquals(oldKey, newKey)

        alice.svc.sendText("after your restore", contact(alice, bob))
        // promptly: the lookup after `denied` must not wait on the loop that delivers its answer
        assertTrue(
            eventually(5_000) { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "after your restore" },
            "`denied`, then looked up again, then the first-contact lane",
        )
        assertEquals(2, texts(bob, alice, Direction.INCOMING).size, "and it arrived once")
        // his receipt for it carries the new key, and no longer echoes hers…
        assertTrue(eventually { peerKey(alice, bob)?.dk == newKey && texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })
        assertEquals(Lane.KEY, lane(alice, bob))
        assertNull(peerKey(alice, bob)?.ack, "he no longer holds her key, and his receipt said so")
        // …so her next message carries her key again, and both are back on the key lane
        alice.svc.sendText("and again", contact(alice, bob))
        assertTrue(eventually { peerKey(bob, alice)?.dk == deliveryKey(alice) })
        assertEquals(Lane.KEY, lane(bob, alice))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })
    }

    @Test
    fun `a peer who starts over under the same name is found again and nothing is lost`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val before = totals()

        // bob wipes his phone and registers the same name again: a new identity key and a new delivery key
        bob.svc.deregister()
        bob.svc.goOffline()
        val again = make("bob")
        again.svc.register(bob.username)
        assertEquals(bob.hash, again.hash, "the same account name")
        assumeSealed(again)

        alice.svc.sendText("after you started over", contact(alice, bob))
        // `denied`, a lookup that finds a new identity key, a new session, then the first-contact lane
        assertTrue(
            eventually { rows(again, alice.hash).any { it.text == "after you started over" } },
            "the message that met the refusal is the one that arrives: nothing is lost on the way",
        )
        assertEquals(1, rows(again, alice.hash).count { it.kind == Kind.TEXT })
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })
        assertTrue(eventually { peerKey(alice, bob)?.dk == deliveryKey(again) }, "his receipt brought the new delivery key")
        assertEquals(Lane.KEY, lane(alice, bob))
        // the change of identity is shown as it always was
        assertTrue(rows(alice, bob.hash).any { it.kind == Kind.SYSTEM && it.text.contains("identity key changed") })
        assertEquals(false, contact(alice, bob).verified)

        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "and nothing named its sender while it was sorted out")
    }

    @Test
    fun `a message or an attachment to an account that was deleted says so, as it always did`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        bob.svc.deregister()
        bob.svc.goOffline()
        fun notices() = rows(alice, bob.hash).count { it.kind == Kind.SYSTEM && it.text.contains("deleted their account") }

        alice.svc.sendFile(ByteArray(40_000) { it.toByte() }, "late.bin", "application/octet-stream", contact(alice, bob))
        assertTrue(eventually { rows(alice, bob.hash).firstOrNull { it.kind == Kind.FILE }?.status == Status.FAILED })
        assertEquals(1, notices(), "the refusal of a sealed manifest leaves the same row a refused `send` does")
        alice.svc.sendText("are you there", contact(alice, bob))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.FAILED })
        assertEquals(1, notices(), "and it is said once")
    }

    @Test
    fun `a key lookup in flight keeps a retiring connection open, and one that ends fails it at once`(): Unit = runBlocking {
        val (alice, bob) = pair()
        val carol = make("carol")
        val dave = make("dave")
        carol.svc.register(carol.username)
        dave.svc.register(dave.username)
        introduce(alice, bob)
        val hooks = alice.svc.sealedForTest { this } // used from the tap below, which runs on the service thread

        // the lookup for carol is written and, in the same breath, the connection is told to retire
        // (what a fallback or leaving the app does): it stays until the answer is back
        val first = assertNotNull(anon(alice))
        alice.svc.anonWireTap = { if (it is Wire.Out.PreKeyFetch) hooks.anonRetire(first) }
        alice.svc.addContact(carol.username)
        assertEquals(true, peerKey(alice, carol)?.sealed, "the answer came back on the retiring connection")
        assertTrue(alice.svc.sealedForTest { first.ended }, "which then closed")

        // a connection that ends with a lookup on it says so at once, not after the fifteen-second wait
        alice.svc.anonWireTap = { if (it is Wire.Out.PreKeyFetch) hooks.anon?.let(hooks::anonEnd) }
        val started = System.currentTimeMillis()
        assertFailsWith<IOException> { alice.svc.addContact(dave.username) }
        assertTrue(System.currentTimeMillis() - started < 3_000, "failed promptly")
        // and the next try opens another connection
        alice.svc.anonWireTap = null
        alice.svc.addContact(dave.username)
        alice.svc.sendText("hello dave", contact(alice, dave))
        assertTrue(eventually { texts(dave, alice, Direction.INCOMING).firstOrNull()?.text == "hello dave" })
    }

    @Test
    fun `with no private connection messages go the old way and the status says so`(): Unit = runBlocking {
        val (alice, bob) = pair()
        alice.svc.anonRelayUrlForTest = "ws://127.0.0.1:9/ws" // nothing listens there
        val before = totals()

        alice.svc.addContact(bob.username)
        assertEquals(SealedStatus.NO_PRIVATE_CONNECTION, alice.svc.sealedStatus.value, "two attempts failed, and the status says so")
        assertTrue(alice.svc.sealedForTest { anonFailed })
        alice.svc.sendText("the old way then", contact(alice, bob))
        assertTrue(
            eventually { texts(bob, alice, Direction.INCOMING).firstOrNull()?.text == "the old way then" },
            "nothing is lost: the message names its sender, as before",
        )
        assertEquals(CapturedFrame.SEND, captured(alice, texts(alice, bob, Direction.OUTGOING)[0]))
        val after = totals()
        assertTrue(after.legacy - before.legacy >= 1)

        // the next login tries again
        alice.svc.goOffline()
        alice.svc.anonRelayUrlForTest = null
        alice.svc.goOnline()
        assertEquals(SealedStatus.ON, alice.svc.sealedStatus.value)
    }

    @Test
    fun `time in the background or a new network retires the private connection and the next message opens another`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val before = totals()

        // the app leaves the foreground: nothing in flight, so the connection closes at once
        val first = assertNotNull(anon(alice))
        alice.svc.noteBackgrounded()
        assertTrue(alice.svc.sealedForTest { first.ended })
        alice.svc.sendText("after a while away", contact(alice, bob))
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "after a while away" })
        val second = assertNotNull(anon(alice))
        assertNotSame(first, second)
        assertEquals(CapturedFrame.DELIVER, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })

        // the phone changes network: the connection may have stayed on the old one
        alice.svc.noteNetworkChanged()
        assertTrue(alice.svc.sealedForTest { second.ended })
        assertTrue(eventually { alice.svc.connState.value == ConnState.ONLINE && alice.svc.sealedStatus.value == SealedStatus.ON })
        alice.svc.sendText("on another network", contact(alice, bob))
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "on another network" })
        assertEquals(CapturedFrame.DELIVER, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })

        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "neither sent anything the old way")
    }

    /** Android only: without push the main link stays up in the background (`LinkPolicy`), the private connection does not (§5.5). */
    @Test
    fun `out of the foreground a private connection serves what needed it and is not kept alive`(): Unit = runBlocking {
        Live.requireRelay()
        val inFront = AtomicBoolean(true)
        val alice = make("alice", linkTiming = RelayLink.Timing(keepaliveMs = 400), inFront = { inFront.get() })
        val bob = make("bob")
        alice.svc.register(alice.username)
        bob.svc.register(bob.username)
        assumeSealed(alice, bob)
        introduce(alice, bob)
        val before = totals()

        // in front it is pinged like the main link, and stays
        val kept = assertNotNull(anon(alice))
        delay(1_400) // three keepalive ticks
        assertFalse(alice.svc.sealedForTest { kept.ended }, "kept alive while the app is in front")

        // the app leaves, and a message arrives on the main link: its receipt opens a connection, sealed…
        inFront.set(false)
        alice.svc.noteBackgrounded()
        assertTrue(alice.svc.sealedForTest { kept.ended })
        bob.svc.sendText("while you were away", contact(bob, alice))
        assertTrue(eventually { texts(bob, alice, Direction.OUTGOING).lastOrNull()?.status == Status.DELIVERED }, "the receipt went out")
        // …which is let go at the next keepalive tick instead of being pinged
        assertTrue(eventually(3_000) { anon(alice) == null }, "not kept alive out of sight")
        assertEquals(ConnState.ONLINE, alice.svc.connState.value, "the main link is untouched")

        // back in front: the next one stays again
        inFront.set(true)
        alice.svc.sendText("back", contact(alice, bob))
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "back" })
        val again = assertNotNull(anon(alice))
        delay(1_400)
        assertFalse(alice.svc.sealedForTest { again.ended })

        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "nothing went the old way for lack of a connection")
    }

    @Test
    fun `a group of three, sealed, and members who never spoke use the roster keys`(): Unit = runBlocking {
        val (alice, bob) = pair()
        val carol = make("carol")
        carol.svc.register(carol.username)
        introduce(alice, bob)
        introduce(alice, carol)
        assertNull(peerKey(bob, carol), "bob and carol have never spoken")
        val before = totals()

        val g = alice.svc.createGroup("sealed", alice.svc.contacts.value.map { it.hash })
        assertTrue(eventually { bob.svc.group(g.id)?.members?.size == 3 && carol.svc.group(g.id)?.members?.size == 3 })
        assertEquals(deliveryKey(carol), peerKey(bob, carol)?.dk, "the roster carried carol's key to bob")
        assertEquals(deliveryKey(bob), peerKey(carol, bob)?.dk)

        // bob writes to the group: his copy for carol goes straight to the key lane
        bob.svc.sendText("hello group", g.id)
        assertTrue(eventually { rows(alice, g.id).any { it.text == "hello group" } && rows(carol, g.id).any { it.text == "hello group" } })
        assertEquals(bob.hash, rows(carol, g.id).first { it.text == "hello group" }.sender, "attributed to the certified sender")
        // an image to the whole group
        val jpeg = ByteArray(70_000) { (it * 13).toByte() }
        alice.svc.sendImage(jpeg, 4, 4, g.id)
        assertTrue(eventually(15_000) { rows(bob, g.id).any { it.kind == Kind.IMAGE } && rows(carol, g.id).any { it.kind == Kind.IMAGE } })
        assertContentEquals(jpeg, carol.svc.attachmentData(rows(carol, g.id).first { it.kind == Kind.IMAGE }))
        eventually { rows(alice, g.id).first { it.kind == Kind.IMAGE }.status != Status.PENDING }

        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "no copy of anything named its sender")
        assertEquals(0, after.firstContact - before.firstContact, "and nobody needed the first-contact lane")
    }

    @Test
    fun `the bot answers sealed`(): Unit = runBlocking {
        Live.requireRelay()
        Assumptions.assumeTrue(Live.pyrobotRunning(), "SKIPPED: pyrobot is not running")
        val alice = make("botfan")
        alice.svc.register(alice.username)
        assumeSealed(alice)
        val bot = usernameHash("pyrobot")
        Assumptions.assumeTrue(runCatching { alice.svc.addContact("pyrobot") }.isSuccess, "SKIPPED: no pyrobot on this relay")
        Assumptions.assumeTrue(alice.svc.store.blob.peerKeys?.get(bot)?.sealed == true, "SKIPPED: this pyrobot has no SEALED_ROOT")
        val before = totals()
        alice.svc.sendText("hello", bot)
        assertTrue(
            eventually(20_000) { rows(alice, bot).count { it.direction == Direction.INCOMING && it.kind == Kind.TEXT } >= 5 },
            "the whole tour arrives",
        )
        assertTrue(eventually { rows(alice, bot).first { it.direction == Direction.OUTGOING }.status == Status.READ })
        assertNotNull(alice.svc.store.blob.peerKeys?.get(bot)?.dk)
        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "neither side named itself to the relay")
    }

    @Test
    fun `an old build still gets the tour from a bot that can seal`(): Unit = runBlocking {
        Live.requireRelay()
        Assumptions.assumeTrue(Live.pyrobotRunning(), "SKIPPED: pyrobot is not running")
        val olga = make("oldfan", legacy = true)
        olga.svc.register(olga.username)
        val bot = usernameHash("pyrobot")
        Assumptions.assumeTrue(runCatching { olga.svc.addContact("pyrobot") }.isSuccess, "SKIPPED: no pyrobot on this relay")
        val before = totals()
        olga.svc.sendText("hello", bot)
        assertTrue(
            eventually(20_000) { rows(olga, bot).count { it.direction == Direction.INCOMING && it.kind == Kind.TEXT } >= 5 },
            "the whole tour arrives, the old way",
        )
        assertNull(olga.svc.store.blob.peerKeys, "an old build keeps nothing about delivery keys")
        val after = totals()
        assertEquals(0, (after.sealed - before.sealed) + (after.firstContact - before.firstContact), "nothing sealed was sent to it")
    }

    /**
     * §5.2 `intro_full`, against a relay that holds one first-contact message per person
     * (`sealed.firstContactQueued=1`, on 127.0.0.1:7450). Skips when there is none.
     */
    @Test
    fun `when a first-contact allowance is used up the message goes the old way`(): Unit = runBlocking {
        val (alice, bob) = pair("full", "fullb", SMALL_ALLOWANCE_PORT)
        val before = totals(SMALL_ALLOWANCE_PORT)

        // bob is away while alice writes to him for the first time
        bob.svc.goOffline()
        alice.svc.addContact(bob.username)
        alice.svc.sendText("one", contact(alice, bob))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).firstOrNull()?.status == Status.SENT }, "the first message waits for him on the first-contact lane")
        val used = assertNotNull(anon(alice))
        alice.svc.sendText("two", contact(alice, bob))
        assertTrue(
            eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.SENT },
            "the relay said `intro_full`; the message went the old way instead of failing",
        )
        val sent = texts(alice, bob, Direction.OUTGOING)
        assertEquals(CapturedFrame.DELIVER, captured(alice, sent[0]))
        assertEquals(CapturedFrame.SEND, captured(alice, sent[1]), "and the details sheet says which path it took")
        assertTrue(eventually { alice.svc.sealedForTest { used.ended } }, "the connection the refusal came in on is not used again")

        bob.svc.goOnline()
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).map { it.text }.sorted() == listOf("one", "two") }, "nothing was lost")
        val after = totals(SMALL_ALLOWANCE_PORT)
        assertEquals(1, after.firstContact - before.firstContact)
        assertEquals(1, after.legacy - before.legacy)
    }

    /**
     * C6, against a relay whose certificates last seconds (`sealed.certTtlSeconds=8` with
     * `sealed.testShortCertTtl=true`, on 127.0.0.1:7449). Skips when there is none.
     */
    @Test
    fun `a certificate is renewed in time and nothing is sealed with one that ran out`(): Unit = runBlocking {
        val (alice, bob) = pair("cert", "certb", SHORT_CERT_PORT)
        val first = assertNotNull(alice.svc.sealedForTest { certExpiresMs })
        val lifeMs = first - System.currentTimeMillis()
        Assumptions.assumeTrue(lifeMs < 30_000, "SKIPPED: the relay on :$SHORT_CERT_PORT issues long-lived certificates")

        // renewed at half its life
        assertTrue(eventually(lifeMs) { (alice.svc.sealedForTest { certExpiresMs } ?: 0) > first }, "a new certificate arrived before the first one ended")
        introduce(alice, bob)

        // renewals stop getting through: when the certificate runs out, nothing is sealed with it
        alice.svc.sealedForTest { stopRenewal() }
        assertTrue(eventually(lifeMs + 2_000) { !alice.svc.sealedForTest { usable } })
        assertEquals(Lane.LEGACY, lane(alice, bob))
        alice.svc.sendText("with no certificate", contact(alice, bob))
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "with no certificate" }, "it goes the old way and still arrives")
        assertEquals(CapturedFrame.SEND, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))

        // a renewal gets through: sealing resumes
        alice.svc.sealedForTest { askCertificate() }
        assertTrue(eventually { alice.svc.sealedForTest { usable } })
        assertEquals(Lane.KEY, lane(alice, bob))
        alice.svc.sendText("sealed again", contact(alice, bob))
        assertTrue(eventually { texts(bob, alice, Direction.INCOMING).lastOrNull()?.text == "sealed again" })
        assertEquals(CapturedFrame.DELIVER, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))
    }

    /** F6, on the relay with eight-second certificates: what waited in the queue is judged at the time the relay took it. */
    @Test
    fun `a text and an attachment that waited past their sender's certificate still open`(): Unit = runBlocking {
        val (alice, bob) = pair("late", "lateb", SHORT_CERT_PORT)
        introduce(alice, bob)
        bob.svc.goOffline()
        val bytes = ByteArray(150_000) { (it * 23).toByte() }
        alice.svc.sendText("written before it ran out", contact(alice, bob))
        alice.svc.sendFile(bytes, "late.bin", "application/octet-stream", contact(alice, bob))
        assertTrue(eventually { rows(alice, bob.hash).filter { it.direction == Direction.OUTGOING }.takeLast(2).all { it.status == Status.SENT } })
        assertEquals(CapturedFrame.DELIVER, captured(alice, rows(alice, bob.hash).last { it.kind == Kind.FILE }))
        // every certificate alice held when she sealed those two has run out by now
        val sealedUnder = assertNotNull(alice.svc.sealedForTest { certExpiresMs })
        Assumptions.assumeTrue(sealedUnder - System.currentTimeMillis() < 30_000, "SKIPPED: the relay on :$SHORT_CERT_PORT issues long-lived certificates")
        while (System.currentTimeMillis() < sealedUnder + 1_500) delay(200)
        bob.svc.goOnline()
        assertTrue(eventually(15_000) { rows(bob, alice.hash).any { it.text == "written before it ran out" } && rows(bob, alice.hash).any { it.kind == Kind.FILE } },
            "judged at the moment the relay took them, not at the moment they are opened")
        assertContentEquals(bytes, bob.svc.attachmentData(rows(bob, alice.hash).first { it.kind == Kind.FILE }))
        assertNull(bob.svc.lastError.value)
    }

    /** X4: each thing the app can do, once, between people who hold each other's keys. */
    @Test
    fun `every feature once, and the relay is not told who sent any of it`(): Unit = runBlocking {
        val (alice, bob) = pair()
        val carol = make("carol")
        carol.svc.register(carol.username)
        introduce(alice, bob)
        introduce(alice, carol)
        val before = totals()
        fun got(who: TestUser, from: TestUser, text: String) = rows(who, from.hash).any { it.text == text }

        // a text, DELIVERED, READ
        alice.svc.sendText("a text", contact(alice, bob))
        assertTrue(eventually { got(bob, alice, "a text") })
        bob.svc.noteRowVisible(rows(bob, alice.hash).first { it.text == "a text" }.id, alice.hash)
        assertTrue(eventually { rows(alice, bob.hash).first { it.text == "a text" }.status == Status.READ })
        // typing
        alice.svc.noteComposerActivity(bob.hash)
        assertTrue(eventually { bob.svc.typingPeers.value[alice.hash] != null }, "typing")
        // an image, a file, a voice note
        val image = ByteArray(120_000) { (it * 3).toByte() }
        alice.svc.sendImage(image, 8, 8, contact(alice, bob))
        assertTrue(eventually(15_000) { rows(bob, alice.hash).any { it.kind == Kind.IMAGE } }, "image")
        assertContentEquals(image, bob.svc.attachmentData(rows(bob, alice.hash).first { it.kind == Kind.IMAGE }))
        val file = ByteArray(90_000) { (it * 5).toByte() }
        bob.svc.sendFile(file, "plan.bin", "application/octet-stream", contact(bob, alice))
        assertTrue(eventually(15_000) { rows(alice, bob.hash).any { it.kind == Kind.FILE } }, "file")
        assertContentEquals(file, alice.svc.attachmentData(rows(alice, bob.hash).first { it.kind == Kind.FILE }))
        val voice = ByteArray(40_000) { (it * 7).toByte() }
        alice.svc.sendVoice(voice, 2.5, contact(alice, bob))
        assertTrue(eventually(15_000) { rows(bob, alice.hash).any { it.kind == Kind.VOICE } }, "voice note")
        assertContentEquals(voice, bob.svc.voiceContent(rows(bob, alice.hash).first { it.kind == Kind.VOICE }))
        // a disappearing message: its timer starts when it is read, on both phones
        alice.svc.setBurnDefault(2.0, bob.hash)
        alice.svc.sendText("gone two seconds after you read it", contact(alice, bob))
        assertTrue(eventually { got(bob, alice, "gone two seconds after you read it") })
        bob.svc.noteRowVisible(rows(bob, alice.hash).first { it.text == "gone two seconds after you read it" }.id, alice.hash)
        assertTrue(eventually(12_000) { !got(bob, alice, "gone two seconds after you read it") && !got(alice, bob, "gone two seconds after you read it") }, "burned on both")
        alice.svc.setBurnDefault(0.0, bob.hash)
        // delete for both
        alice.svc.sendText("to be deleted", contact(alice, bob))
        assertTrue(eventually { got(bob, alice, "to be deleted") })
        assertTrue(alice.svc.deleteForBoth(rows(alice, bob.hash).first { it.text == "to be deleted" }.id, bob.hash))
        assertTrue(eventually { !got(bob, alice, "to be deleted") }, "deleted on the other phone too")
        // a screenshot alert
        alice.svc.reportCapture("screenshot", contact(alice, bob))
        assertTrue(eventually { rows(bob, alice.hash).any { it.kind == Kind.SYSTEM && it.text.contains("screenshot") } }, "screenshot alert")
        // written in a linked browser: the phone is what sends it
        alice.svc.sendText("from the desktop", bob.hash, viaWeb = true)
        assertTrue(eventually { rows(bob, alice.hash).any { it.text == "from the desktop" && it.viaWeb == true } }, "desktop-link send")

        // a group: text, an image, a screenshot under burn_remove, and the creator's burn
        val g = alice.svc.createGroup("everything", listOf(bob.hash, carol.hash), GroupPolicy.BURN_REMOVE)
        assertTrue(eventually { bob.svc.group(g.id)?.members?.size == 3 && carol.svc.group(g.id)?.members?.size == 3 })
        bob.svc.sendText("to the group", g.id)
        assertTrue(eventually { rows(alice, g.id).any { it.text == "to the group" } && rows(carol, g.id).any { it.text == "to the group" } }, "group text")
        val groupImage = ByteArray(100_000) { (it * 11).toByte() }
        alice.svc.sendImage(groupImage, 8, 8, g.id)
        assertTrue(eventually(15_000) { rows(bob, g.id).any { it.kind == Kind.IMAGE } && rows(carol, g.id).any { it.kind == Kind.IMAGE } }, "group image")
        assertContentEquals(groupImage, carol.svc.attachmentData(rows(carol, g.id).first { it.kind == Kind.IMAGE }))
        carol.svc.reportCapture("screenshot", g.id)
        assertTrue(eventually { alice.svc.group(g.id)?.isMember(carol.hash) == false && bob.svc.group(g.id)?.isMember(carol.hash) == false }, "the screenshot policy removed her on both")
        alice.svc.burnGroup(g.id)
        assertTrue(eventually { bob.svc.group(g.id) == null }, "group burn")

        delay(1_500) // the last receipts leave
        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "not one of those named its sender to the relay")
        assertEquals(0, after.firstContact - before.firstContact, "and nobody needed the first-contact lane: the roster carried the keys")
    }

    /** X5: his copy of the conversation is older than hers, and he has no delivery key at all. */
    @Test
    fun `a restore from a backup made before sealed sender heals as it does today and ends on the key lane`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = make("alice", legacy = true) // both start on builds from before sealed sender
        val bob = make("bob", legacy = true)
        alice.svc.register(alice.username)
        bob.svc.register(bob.username)
        alice.svc.addContact(bob.username)
        suspend fun say(from: TestUser, to: TestUser, toHash: String, text: String) {
            from.svc.sendText(text, toHash)
            assertTrue(eventually { rows(to, from.hash).any { it.text == text } }, text)
        }
        say(alice, bob, bob.hash, "before the backup")
        say(bob, alice, alice.hash, "yes")
        bob.svc.store.flush()
        val backup = bob.svc.store.blob // the backup: his store as it is now
        // the conversation goes on, so the backup falls behind it
        repeat(2) {
            say(alice, bob, bob.hash, "later $it")
            say(bob, alice, alice.hash, "reply $it")
        }
        // both update
        for (u in listOf(alice, bob)) {
            u.svc.goOffline()
            u.svc.legacyWireOnly = false
            u.svc.goOnline()
        }
        assumeSealed(alice, bob)
        say(alice, bob, bob.hash, "after the update")
        assertTrue(eventually { lane(alice, bob) == Lane.KEY && lane(bob, alice) == Lane.KEY }, "one message after the update, and both are on the key lane")

        // bob's phone is gone; he restores the backup on a new one
        bob.svc.goOffline()
        val restored = Live.makeService("bob") { backup }.also { users += it }
        assertEquals(bob.hash, restored.hash)
        restored.svc.goOnline()
        assumeSealed(restored)
        assertNotEquals(deliveryKey(bob), assertNotNull(deliveryKey(restored)), "the backup held no delivery key: a new one")
        val before = totals()

        // his copy of the conversation is older than hers: her next message cannot be read, and both are told, as today
        alice.svc.sendText("are you there", contact(alice, bob))
        assertTrue(eventually(20_000) { rows(restored, alice.hash).any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } }, "he is told a message was lost")
        assertTrue(eventually(20_000) { rows(alice, bob.hash).any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } }, "and so is she")
        // from here it works again, and after an exchange both are back on the key lane
        say(alice, restored, bob.hash, "once more")
        say(restored, alice, alice.hash, "got it")
        say(alice, restored, bob.hash, "and again")
        assertTrue(eventually { lane(alice, bob) == Lane.KEY && restored.svc.sealedForTest { lane(alice.hash) } == Lane.KEY }, "nobody did anything but write")
        delay(1_000)
        val after = totals()
        assertEquals(0, after.legacy - before.legacy, "and through all of it the relay was not told who was writing")
    }

    /** Android only: OkHttp queues what it is handed, so "written" is not yet "left the phone". */
    @Test
    fun `an upload whose connection ended before its last piece was seen out is sent again and arrives once`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val hooks = alice.svc.sealedForTest { this } // used from the taps below, which run on the service thread
        val manifests = AtomicInteger(0)
        val ended = AtomicBoolean(false)
        alice.svc.anonWireTap = { if (it is Wire.Out.Deliver && it.kind == "blob") manifests.incrementAndGet() }
        // the connection ends the moment the last chunk has been handed over
        alice.svc.anonBinaryTap = { frame ->
            if (BlobFrames.seq(frame) == BlobFrames.total(frame) - 1 && ended.compareAndSet(false, true)) hooks.anon?.let(hooks::anonEnd)
        }
        val bytes = ByteArray(200_000) { (it * 29).toByte() }
        alice.svc.sendFile(bytes, "tail.bin", "application/octet-stream", contact(alice, bob))
        assertTrue(eventually(20_000) { rows(alice, bob.hash).first { it.kind == Kind.FILE }.status == Status.DELIVERED })
        assertTrue(ended.get())
        assertEquals(2, manifests.get(), "nobody could tell whether the tail had left: it was sent again")
        assertEquals(1, rows(bob, alice.hash).count { it.kind == Kind.FILE }, "and the second copy was recognised by its row id")
        assertContentEquals(bytes, bob.svc.attachmentData(rows(bob, alice.hash).first { it.kind == Kind.FILE }))
        assertTrue(eventually { bob.svc.rxTransfers.value.isEmpty() }, "nothing is left half-received")
    }

    @Test
    fun `when no private connection can be had, what was lost with the last one goes the old way`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        val hooks = alice.svc.sealedForTest { this } // used from the tap below, which runs on the service thread
        alice.svc.anonRelayUrlForTest = "ws://127.0.0.1:9/ws" // from now on none can be opened
        // the connection dies with this message on it (the frame is written from another coroutine: wait for it)
        val died = AtomicBoolean(false)
        alice.svc.anonWireTap = { if (it is Wire.Out.Deliver && died.compareAndSet(false, true)) hooks.anon?.let(hooks::anonEnd) }
        alice.svc.sendText("lost with the connection", contact(alice, bob))
        assertTrue(eventually { died.get() })
        alice.svc.anonWireTap = null
        // the try the app makes by itself decides nothing; the next message settles it, even one
        // written while that try is still going on
        assertEquals(SealedStatus.ON, alice.svc.sealedStatus.value)
        alice.svc.sendText("the next one", contact(alice, bob))
        assertTrue(eventually { alice.svc.sealedStatus.value == SealedStatus.NO_PRIVATE_CONNECTION }, "two attempts failed, and the status says so")
        assertTrue(eventually { rows(bob, alice.hash).count { it.text == "lost with the connection" } == 1 && rows(bob, alice.hash).count { it.text == "the next one" } == 1 },
            "both arrive, once each: the lost one was sent again the old way")
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).takeLast(2).all { it.status == Status.DELIVERED } })
        assertEquals(CapturedFrame.SEND, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))
    }

    @Test
    fun `a connection that died is counted once, and the app opens two in a row by itself and no more`(): Unit = runBlocking {
        val (alice, bob) = pair()
        introduce(alice, bob)
        // A message waiting for its verdict and an attachment going out notice the same death,
        // one after the other.
        val first = alice.svc.sealedForTest {
            val was = anon
            was?.let(::anonEnd) // nothing was on it, so this alone opens nothing
            lostWithConnection()
            lostWithConnection()
            was
        }
        assertNotNull(first)
        assertTrue(eventually { anon(alice).let { it != null && it !== first } }, "another is opened")
        assertEquals(1, alice.svc.sealedForTest { reopens }, "one, counted once")
        // it dies with work on it again: the second one the app opens by itself
        val second = alice.svc.sealedForTest {
            val was = anon
            was?.let(::anonEnd)
            lostWithConnection()
            was
        }
        assertTrue(eventually { anon(alice).let { it != null && it !== second } })
        assertEquals(2, alice.svc.sealedForTest { reopens })
        // and a third time: now the work waits, or a path that kills every connection would be a loop
        alice.svc.sealedForTest {
            anon?.let(::anonEnd)
            lostWithConnection()
        }
        delay(1_500)
        assertNull(anon(alice), "no third one")
        assertEquals(SealedStatus.ON, alice.svc.sealedStatus.value, "which is not the same as there being none to be had")
        // the next message opens one, and its verdict starts the count again
        alice.svc.sendText("after all that", contact(alice, bob))
        assertTrue(eventually { texts(alice, bob, Direction.OUTGOING).last().status == Status.DELIVERED })
        assertEquals(CapturedFrame.DELIVER, captured(alice, texts(alice, bob, Direction.OUTGOING).last()))
        assertEquals(0, alice.svc.sealedForTest { reopens })
    }

    private companion object {
        /** `sealed.certTtlSeconds=8` with `sealed.testShortCertTtl=true`. */
        const val SHORT_CERT_PORT = 7449

        /** `sealed.firstContactQueued=1`. */
        const val SMALL_ALLOWANCE_PORT = 7450
    }
}
