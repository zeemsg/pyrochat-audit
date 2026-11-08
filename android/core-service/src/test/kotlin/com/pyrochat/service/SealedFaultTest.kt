package com.pyrochat.service

import com.pyrochat.crypto.RelayAuth
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.PeerKey
import com.pyrochat.store.model.PeerKeys.Lane
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions
import org.signal.libsignal.protocol.IdentityKeyPair
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scenario of the fault gate (docs/SEALED-SENDER.md §9, F1, F3, F4, F5, F8), run by
 * `node relay/test/faults.mjs android`. Three identities, each talking to the relay through its
 * own proxy, keep writing to each other while the script breaks things under them: a message
 * lost with its connection, a verdict that never comes back, a network that goes away, a
 * connection and then the relay itself dying in the middle of an attachment, the relay coming
 * back without sealed sender and then with it again, no second connection to be had, a path
 * that kills every connection a message goes out on, the end of an attachment lost after the app
 * had handed all of it over (on the new path and on the old one), and the relay's answer to an
 * attachment lost on its way back. Every message has to arrive, once, and nobody taps anything
 * to make that happen. Last, the relay refuses a piece of an attachment: that one fails, says
 * why, and goes again when asked to. On its own this test skips.
 *
 * The steps are the same in iOS `SealedFaultTests`.
 */
class SealedFaultTest {
    private class Identity(val user: TestUser, val proxy: Int) {
        val svc: ChatService get() = user.svc
        val name: String get() = user.username
        val hash: String get() = user.hash
    }

    private lateinit var dir: Path
    private var asked = 0

    /** Ask the script for a fault, and wait until it is in place. Its answer comes back. */
    private suspend fun ask(what: String, vararg fields: Pair<String, Any>): String {
        val n = ++asked
        val request = buildJsonObject {
            put("do", what)
            for ((key, value) in fields) when (value) {
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
        val tmp = dir.resolve("req-$n.tmp")
        Files.writeString(tmp, request.toString())
        Files.move(tmp, dir.resolve("req-$n.json"), StandardCopyOption.ATOMIC_MOVE)
        val ack = dir.resolve("ack-$n.json")
        val deadline = System.currentTimeMillis() + 60_000
        while (!Files.exists(ack)) {
            check(System.currentTimeMillis() < deadline) { "the fault script did not answer `$what`" }
            delay(30)
        }
        val answer = Files.readString(ack)
        check(answer.contains("\"ok\":true")) { "the fault script refused `$what`: $answer" }
        return answer
    }

    /** How many faults have happened so far, as the script counts them. */
    private suspend fun faults(): Int = Regex("\"faults\":(\\d+)").find(ask("count"))!!.groupValues[1].toInt()

    private suspend fun until(timeoutMs: Long, what: String, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(100)
        }
        assertTrue(cond(), what)
    }

    private fun contact(a: Identity, b: Identity): Contact = a.svc.contacts.value.first { it.hash == b.hash }
    private fun rows(a: Identity, b: Identity): List<ChatMessage> = a.svc.messages.value[b.hash].orEmpty()
    private fun copies(a: Identity, b: Identity, text: String): Int = rows(a, b).count { it.text == text }
    private fun sent(a: Identity, b: Identity, text: String): ChatMessage? = rows(a, b).firstOrNull { it.text == text && it.direction == Direction.OUTGOING }
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * seed + seed).toByte() }
    private fun peerKey(a: Identity, b: Identity): PeerKey? = a.svc.store.blob.peerKeys?.get(b.hash)
    private suspend fun lane(a: Identity, b: Identity): Lane = a.svc.sealedForTest { lane(b.hash) }
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun up(i: Identity, status: SealedStatus) = i.svc.connState.value == ConnState.ONLINE && i.svc.sealedStatus.value == status
    private fun file(a: Identity, b: Identity, name: String): ChatMessage? = rows(a, b).firstOrNull { it.fileName == name }

    /** The file [name] is with [b], whole and once, [a]'s row is DELIVERED, and nothing is left half received. */
    private suspend fun fileArrivesOnce(a: Identity, b: Identity, name: String, content: ByteArray, timeoutMs: Long, what: String) {
        until(timeoutMs, what) { file(b, a, name) != null && file(a, b, name)?.status == Status.DELIVERED }
        delay(600) // a second copy would have had time to land
        assertEquals(1, rows(b, a).count { it.fileName == name }, "`$name` arrived once")
        assertContentEquals(content, b.svc.attachmentData(file(b, a, name)!!))
        assertTrue(b.svc.rxTransfers.value.isEmpty(), "nothing is left there, still receiving")
    }

    /** [a] adds [b], writes first, and both end up holding each other's delivery key. */
    private suspend fun introduce(a: Identity, b: Identity, text: String) {
        a.svc.addContact(b.name)
        a.svc.sendText(text, contact(a, b))
        until(10_000, "$text: delivered, keys exchanged") {
            peerKey(a, b)?.dk != null && peerKey(b, a)?.dk != null && sent(a, b, text)?.status == Status.DELIVERED
        }
    }

    /** [text] reaches [b] once and [a]'s row ends DELIVERED, without anyone doing anything more. */
    private suspend fun arrivesOnce(a: Identity, b: Identity, text: String, timeoutMs: Long) {
        until(timeoutMs, "`$text` arrives and is acknowledged") { copies(b, a, text) >= 1 && sent(a, b, text)?.status == Status.DELIVERED }
        delay(600) // a second copy would have had time to land
        assertEquals(1, copies(b, a, text), "`$text` arrived once")
        assertEquals(1, copies(a, b, text), "and is one row on the sender's side")
    }

    @Test
    fun `the fault scenario`(): Unit = runBlocking {
        val relays = System.getenv("PYRO_FAULT_RELAYS")?.split(",")?.takeIf { it.size == 3 }
        val out = System.getenv("PYRO_FAULT_DIR")
        Assumptions.assumeTrue(relays != null && out != null, "SKIPPED: run by `node relay/test/faults.mjs android`")
        dir = Paths.get(out!!)
        val all = relays!!.mapIndexed { i, relay ->
            val user = Live.makeService(listOf("fa", "fb", "fc")[i], relayUrl = relay) { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
            user.svc.register(user.username)
            Identity(user, i)
        }
        val (alice, bob, carol) = all

        /** What the script needs to read the recording; written at once, so the wire is still checked when a step fails. */
        fun publish() {
            val identities = buildJsonArray {
                for (i in all) {
                    val blob = i.svc.store.blob
                    add(
                        buildJsonObject {
                            put("name", i.name)
                            put("proxy", i.proxy)
                            put("hash", i.hash)
                            put("identityKey", b64(IdentityKeyPair(blob.identityKeyPair.bytes).publicKey.serialize()))
                            put("authPub", b64(RelayAuth.publicKey(blob.authPrivateKey.bytes)))
                            put("deliveryKey", blob.deliveryKey?.let { b64(it.bytes) } ?: "")
                        },
                    )
                }
            }
            // the only messages sent the old way: one each way between alice and bob while the relay had
            // sealed sender off, and two from alice while she could not open an anonymous connection
            val expect = buildJsonObject {
                putJsonObject("legacyMsgs") {
                    put("0>1", 3)
                    put("1>0", 1)
                }
            }
            Files.writeString(dir.resolve("expect.json"), expect.toString())
            Files.writeString(dir.resolve("identities.json"), identities.toString())
        }
        try {
            for (i in all) assertEquals(SealedStatus.ON, i.svc.sealedStatus.value, "${i.name} is offered sealed sender")
            // the relay's answer after an attachment is given four seconds here, not sixty
            for (i in all) i.svc.uploadAnswerTimeoutMs = 4_000
            publish()
            introduce(alice, bob, "hello bob")
            introduce(alice, carol, "hello carol")

            // 1 — a message is lost on its way, and its connection dies with it
            ask("lose-deliver", "proxy" to 0)
            alice.svc.sendText("lost with its connection", contact(alice, bob))
            arrivesOnce(alice, bob, "lost with its connection", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "lost with its connection")!!.id)?.t, "and it went again sealed")

            // 2 — the relay took a message, and its `sent` never came back
            bob.svc.goOffline()
            ask("lose-sent", "proxy" to 0)
            alice.svc.sendText("its verdict was lost", contact(alice, bob))
            until(25_000, "no verdict in fifteen seconds: the row fails, and is sent again by itself") { sent(alice, bob, "its verdict was lost")?.status == Status.SENT }
            bob.svc.goOnline()
            arrivesOnce(alice, bob, "its verdict was lost", 15_000) // the relay held both copies for him

            // 3 — the network goes away with a message on its way, and comes back
            ask("blackout", "proxy" to 0, "ms" to 3_000)
            alice.svc.sendText("sent into a dead network", contact(alice, bob))
            arrivesOnce(alice, bob, "sent into a dead network", 40_000)
            until(10_000, "alice is back, with sealed sender") { up(alice, SealedStatus.ON) }

            // 4 — a connection dies in the middle of an attachment, large enough that the app is
            //     still writing it when that happens
            val cut = bytes(6 * 1024 * 1024, 7)
            ask("cut-on-chunk", "proxy" to 0, "chunk" to 3)
            alice.svc.sendFile(cut, "cut.bin", "application/octet-stream", contact(alice, bob))
            until(45_000, "the attachment is sent again by itself, and arrives") {
                rows(bob, alice).any { it.fileName == "cut.bin" } && rows(alice, bob).first { it.fileName == "cut.bin" }.status == Status.DELIVERED
            }
            assertEquals(1, rows(bob, alice).count { it.fileName == "cut.bin" })
            assertContentEquals(cut, bob.svc.attachmentData(rows(bob, alice).first { it.fileName == "cut.bin" }))
            assertTrue(bob.svc.rxTransfers.value.isEmpty(), "the half that never finished is not left there, still receiving")

            // 4b — the end of an attachment is lost after every piece had been handed over. Only
            //      the answer that never comes says so: without it this row would stay SENT.
            val tail = bytes(600 * 1024, 5)
            ask("cut-on-last-chunk", "proxy" to 0)
            alice.svc.sendFile(tail, "tail.bin", "application/octet-stream", contact(alice, bob))
            fileArrivesOnce(alice, bob, "tail.bin", tail, 45_000, "it is sent again by itself, and arrives")

            // 5 — the relay restarts in the middle of an attachment
            val restarted = bytes(6 * 1024 * 1024, 11)
            ask("restart-on-chunk", "proxy" to 0, "chunk" to 3)
            alice.svc.sendFile(restarted, "restart.bin", "application/octet-stream", contact(alice, bob))
            until(60_000, "everyone comes back, the attachment is sent again and arrives") {
                all.all { up(it, SealedStatus.ON) } && rows(bob, alice).any { it.fileName == "restart.bin" } &&
                    rows(alice, bob).first { it.fileName == "restart.bin" }.status == Status.DELIVERED
            }
            assertEquals(1, rows(bob, alice).count { it.fileName == "restart.bin" })
            assertContentEquals(restarted, bob.svc.attachmentData(rows(bob, alice).first { it.fileName == "restart.bin" }))
            assertTrue(bob.svc.rxTransfers.value.isEmpty())

            // 6 — the relay comes back without sealed sender, and then with it again
            ask("relay", "sealed" to false)
            until(40_000, "alice and bob are back, and told it is off") { up(alice, SealedStatus.NOT_OFFERED) && up(bob, SealedStatus.NOT_OFFERED) }
            alice.svc.sendText("while it is off", contact(alice, bob)) // the old way, on purpose
            bob.svc.sendText("still here", contact(bob, alice)) // the old way, on purpose
            arrivesOnce(alice, bob, "while it is off", 15_000)
            arrivesOnce(bob, alice, "still here", 15_000)
            assertEquals(CapturedFrame.SEND, alice.svc.capturedFrame(sent(alice, bob, "while it is off")!!.id)?.t)
            assertEquals(CapturedFrame.SEND, bob.svc.capturedFrame(sent(bob, alice, "still here")!!.id)?.t)
            // 6b — the same lost ending on the old path, where the pieces travel on the logged-in
            //      connection: it dies, comes back by itself, and the attachment goes again
            val old = bytes(600 * 1024, 9)
            ask("cut-on-last-chunk", "proxy" to 0, "main" to true)
            alice.svc.sendFile(old, "old-path.bin", "application/octet-stream", contact(alice, bob))
            fileArrivesOnce(alice, bob, "old-path.bin", old, 45_000, "alice reconnects, and it is sent again by itself")
            assertEquals(CapturedFrame.SEND, alice.svc.capturedFrame(file(alice, bob, "old-path.bin")!!.id)?.t)
            until(10_000, "alice is back, and still told it is off") { up(alice, SealedStatus.NOT_OFFERED) }

            // 6c — still on the old path: the relay took a whole attachment and its answer was lost.
            //      Bob is away, so no receipt of his settles it first. A link that does not answer
            //      is given up, and the login that follows sends the attachment again.
            val quiet = bytes(200 * 1024, 21)
            bob.svc.goOffline()
            ask("lose-pong", "proxy" to 0, "main" to true)
            val before = faults()
            alice.svc.sendFile(quiet, "quiet.bin", "application/octet-stream", contact(alice, bob))
            until(20_000, "the relay's answer is lost") { faults() == before + 1 }
            delay(9_000) // four seconds with no answer, a new login, and time to send it again
            assertEquals(Status.SENT, file(alice, bob, "quiet.bin")?.status, "it went again and the relay took it; bob is still away")
            bob.svc.goOnline()
            fileArrivesOnce(alice, bob, "quiet.bin", quiet, 30_000, "bob comes back to two copies of it, and has one")
            assertEquals(CapturedFrame.SEND, alice.svc.capturedFrame(file(alice, bob, "quiet.bin")!!.id)?.t)
            until(10_000, "both are there, and still told it is off") { up(alice, SealedStatus.NOT_OFFERED) && up(bob, SealedStatus.NOT_OFFERED) }
            delay(1_000) // the receipts of that exchange leave before the relay goes again
            ask("relay", "sealed" to true)
            until(40_000, "everyone is back, with sealed sender") { all.all { up(it, SealedStatus.ON) } }
            assertEquals(Lane.KEY, lane(alice, bob), "both still hold each other's key: no message the old way is needed")
            assertEquals(Lane.KEY, lane(bob, alice))
            alice.svc.sendText("on again", contact(alice, bob))
            arrivesOnce(alice, bob, "on again", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "on again")!!.id)?.t)

            // 7 — carol sat through all of it
            alice.svc.sendText("carol, after everything", contact(alice, carol))
            arrivesOnce(alice, carol, "carol, after everything", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, carol, "carol, after everything")!!.id)?.t)

            // 8 — the private connection dies with a message on it, and no other can be had
            ask("refuse-anon", "proxy" to 0, "on" to true)
            ask("lose-deliver", "proxy" to 0)
            alice.svc.sendText("lost, with no way back in private", contact(alice, bob))
            until(10_000, "it fails: the try the app makes by itself finds nothing to open") { sent(alice, bob, "lost, with no way back in private")?.status == Status.FAILED }
            assertEquals(SealedStatus.ON, alice.svc.sealedStatus.value, "which decides nothing yet")
            alice.svc.sendText("the one after it", contact(alice, bob)) // settles it: two real attempts, then the old way for both
            arrivesOnce(alice, bob, "lost, with no way back in private", 20_000)
            arrivesOnce(alice, bob, "the one after it", 20_000)
            assertEquals(SealedStatus.NO_PRIVATE_CONNECTION, alice.svc.sealedStatus.value, "and the status says why")
            assertEquals(CapturedFrame.SEND, alice.svc.capturedFrame(sent(alice, bob, "lost, with no way back in private")!!.id)?.t)
            assertEquals(CapturedFrame.SEND, alice.svc.capturedFrame(sent(alice, bob, "the one after it")!!.id)?.t)
            delay(1_000) // her receipts for what bob answers leave while this still holds
            ask("refuse-anon", "proxy" to 0, "on" to false)
            alice.svc.goOffline() // the next login tries again
            alice.svc.goOnline()
            until(10_000, "sealed sender is back with the next login") { up(alice, SealedStatus.ON) }
            alice.svc.sendText("in private again", contact(alice, bob))
            arrivesOnce(alice, bob, "in private again", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "in private again")!!.id)?.t)

            // 9 — a path that kills every connection a message goes out on. The app opens another by
            //     itself, twice; after that the message waits, because this must not become a loop.
            ask("dead-path", "proxy" to 0, "on" to true)
            val base = faults()
            alice.svc.sendText("on a path that kills its connection", contact(alice, bob))
            until(30_000, "lost three times: as it was written, and on the two connections the app opened by itself") { faults() >= base + 3 }
            delay(5_000) // a loop would have gone round many times by now
            assertEquals(base + 3, faults(), "and not once more")
            assertEquals(Status.FAILED, sent(alice, bob, "on a path that kills its connection")?.status, "it waits")
            assertEquals(0, copies(bob, alice, "on a path that kills its connection"))
            assertEquals(SealedStatus.ON, alice.svc.sealedStatus.value, "and none of that says there is no private connection")
            ask("dead-path", "proxy" to 0, "on" to false)
            alice.svc.sendText("the path is back", contact(alice, bob)) // the next message opens a connection, and what waited goes with it
            arrivesOnce(alice, bob, "on a path that kills its connection", 15_000)
            arrivesOnce(alice, bob, "the path is back", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "on a path that kills its connection")!!.id)?.t)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "the path is back")!!.id)?.t)
            // a verdict came back in between, so the count starts again: the next loss is retried by itself
            ask("lose-deliver", "proxy" to 0)
            alice.svc.sendText("lost once more", contact(alice, bob))
            arrivesOnce(alice, bob, "lost once more", 15_000)
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(sent(alice, bob, "lost once more")!!.id)?.t)

            // 10 — the relay took a whole attachment, and its answer to that was lost on the way back.
            //      Bob is away, so no receipt of his settles it first (with him there, his receipt
            //      does, and nothing is sent twice): the app sends it again.
            val unanswered = bytes(300 * 1024, 13)
            bob.svc.goOffline()
            ask("lose-pong", "proxy" to 0)
            val asked = faults()
            alice.svc.sendFile(unanswered, "unanswered.bin", "application/octet-stream", contact(alice, bob))
            until(20_000, "the relay's answer is lost") { faults() == asked + 1 }
            delay(8_000) // four seconds with no answer, then time to send it again
            assertEquals(Status.SENT, file(alice, bob, "unanswered.bin")?.status, "it went again and the relay took it; bob is still away")
            bob.svc.goOnline()
            fileArrivesOnce(alice, bob, "unanswered.bin", unanswered, 30_000, "bob comes back to two copies of it, and has one")
            assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame(file(alice, bob, "unanswered.bin")!!.id)?.t)

            // 11 — the relay refuses a piece of an attachment, as it does when the recipient's queue is full
            val refused = bytes(600 * 1024, 17)
            ask("refuse-chunk", "proxy" to 0, "chunk" to 2)
            alice.svc.sendFile(refused, "refused.bin", "application/octet-stream", contact(alice, bob))
            until(15_000, "the row fails: it is not left as sent") { file(alice, bob, "refused.bin")?.status == Status.FAILED }
            assertEquals("queue_full", alice.svc.lastError.value, "and it says what the relay said")
            delay(3_000) // a refusal is not a loss: nothing sends it again by itself
            assertEquals(Status.FAILED, file(alice, bob, "refused.bin")?.status)
            assertEquals(null, file(bob, alice, "refused.bin"))
            alice.svc.retryBlob(file(alice, bob, "refused.bin")!!.id, bob.hash) // the [RETRY] tap
            fileArrivesOnce(alice, bob, "refused.bin", refused, 30_000, "asked to, it goes again and arrives")

            // let the last receipts leave before the sockets close
            delay(1_000)
            publish()
        } finally {
            for (i in all) {
                runCatching { i.svc.goOffline() }
                i.user.close()
            }
        }
    }
}
