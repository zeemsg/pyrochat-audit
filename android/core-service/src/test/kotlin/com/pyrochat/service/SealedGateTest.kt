package com.pyrochat.service

import com.pyrochat.crypto.RelayAuth
import com.pyrochat.protocol.usernameHash
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions
import org.signal.libsignal.protocol.IdentityKeyPair
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scenario of the leak gate (docs/SEALED-SENDER.md §9, M1–M5 and X1), run by
 * `node relay/test/gate.mjs android`. Four identities, each talking to the relay through its own
 * recording proxy, do everything the app does; the script then reads what went over the wire.
 * On its own this test skips: it has nothing to connect to.
 *
 * The steps are the same in iOS `SealedGateTests`. What goes the old way on purpose is written to
 * `expect.json`, and the script holds the wire to exactly that.
 */
class SealedGateTest {
    private class Identity(val user: TestUser, val proxy: Int) {
        val svc: ChatService get() = user.svc
        val name: String get() = user.username
        val hash: String get() = user.hash
    }

    private suspend fun identity(prefix: String, proxy: Int, relay: String, legacy: Boolean = false): Identity {
        val user = Live.makeService(prefix, relayUrl = relay, legacy = legacy) { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        user.svc.register(user.username)
        return Identity(user, proxy)
    }

    private suspend fun until(timeoutMs: Long, what: String, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(100)
        }
        assertTrue(cond(), what)
    }

    private fun contact(a: Identity, b: Identity): Contact = a.svc.contacts.value.first { it.hash == b.hash }
    private fun rows(a: Identity, thread: String): List<ChatMessage> = a.svc.messages.value[thread].orEmpty()
    private fun has(a: Identity, thread: String, text: String): Boolean = rows(a, thread).any { it.text == text }
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * seed + seed).toByte() }
    private fun peerKey(a: Identity, b: Identity): PeerKey? = a.svc.store.blob.peerKeys?.get(b.hash)
    private suspend fun lane(a: Identity, b: Identity): Lane = a.svc.sealedForTest { lane(b.hash) }
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** [a] adds [b], writes first, and both end up holding each other's delivery key. */
    private suspend fun introduce(a: Identity, b: Identity, text: String) {
        a.svc.addContact(b.name)
        a.svc.sendText(text, contact(a, b))
        until(10_000, "$text: delivered, keys exchanged") {
            peerKey(a, b)?.dk != null && peerKey(b, a)?.dk != null && rows(a, b.hash).firstOrNull { it.text == text }?.status == Status.DELIVERED
        }
    }

    @Test
    fun `the gate scenario`(): Unit = runBlocking {
        val relays = System.getenv("PYRO_GATE_RELAYS")?.split(",")?.takeIf { it.size == 4 }
        val out = System.getenv("PYRO_GATE_DIR")
        val echoName = System.getenv("PYRO_GATE_ECHO")
        Assumptions.assumeTrue(relays != null && out != null && echoName != null, "SKIPPED: run by `node relay/test/gate.mjs android`")
        relays!!
        val alice = identity("ga", 0, relays[0])
        val bob = identity("gb", 1, relays[1])
        val carol = identity("gc", 2, relays[2])
        val dora = identity("gd", 3, relays[3], legacy = true) // a build from before sealed sender
        val all = listOf(alice, bob, carol, dora)

        /**
         * What the script needs to read the recording. Written as soon as the four exist, so the
         * wire is still checked when a step below fails, and again at the end.
         */
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
                            put("legacy", i.proxy == 3)
                        },
                    )
                }
            }
            val expect = buildJsonObject {
                // messages sent the old way on purpose: two to carol around her old build, one each way with dora
                putJsonObject("legacyMsgs") {
                    put("0>2", 2)
                    put("0>3", 1)
                    put("3>0", 1)
                }
                putJsonArray("legacyPairs") {
                    add(buildJsonArray { add(0); add(2) })
                    add(buildJsonArray { add(0); add(3) })
                }
            }
            val dir = Paths.get(out!!)
            Files.writeString(dir.resolve("expect.json"), expect.toString())
            Files.writeString(dir.resolve("identities.json"), identities.toString())
        }
        try {
            publish()
            for (i in listOf(alice, bob, carol)) assertEquals(SealedStatus.ON, i.svc.sealedStatus.value, "${i.name} is offered sealed sender")
            assertEquals(SealedStatus.NOT_OFFERED, dora.svc.sealedStatus.value)

            // 1 — first contact, a reply, receipts, typing
            introduce(alice, bob, "hello bob")
            val first = rows(alice, bob.hash).first { it.text == "hello bob" }
            bob.svc.noteRowVisible(first.id, alice.hash)
            until(10_000, "read receipt") { rows(alice, bob.hash).first { it.id == first.id }.status == Status.READ }
            bob.svc.sendText("hello alice", contact(bob, alice))
            until(10_000, "reply") { has(alice, bob.hash, "hello alice") }
            alice.svc.noteComposerActivity(bob.hash)
            until(10_000, "typing reaches bob") { bob.svc.typingPeers.value[alice.hash] != null }

            // 2 — attachments, both ways
            val image = bytes(150_000, 7)
            alice.svc.sendImage(image, 8, 8, contact(alice, bob))
            until(15_000, "image to bob") { rows(bob, alice.hash).any { it.kind == Kind.IMAGE } }
            assertContentEquals(image, bob.svc.attachmentData(rows(bob, alice.hash).first { it.kind == Kind.IMAGE }))
            val file = bytes(80_000, 11)
            bob.svc.sendFile(file, "plan.bin", "application/octet-stream", contact(bob, alice))
            until(15_000, "file to alice") { rows(alice, bob.hash).any { it.kind == Kind.FILE } }
            assertContentEquals(file, alice.svc.attachmentData(rows(alice, bob.hash).first { it.kind == Kind.FILE }))

            // 3 — a group with text and an image; bob and carol have never spoken
            introduce(alice, carol, "hello carol")
            val g = alice.svc.createGroup("gate", listOf(bob.hash, carol.hash))
            until(10_000, "group created") { bob.svc.group(g.id)?.members?.size == 3 && carol.svc.group(g.id)?.members?.size == 3 }
            alice.svc.sendText("to the group", g.id)
            bob.svc.sendText("bob to the group", g.id)
            until(10_000, "group texts") {
                has(bob, g.id, "to the group") && has(carol, g.id, "to the group") &&
                    has(alice, g.id, "bob to the group") && has(carol, g.id, "bob to the group")
            }
            val groupImage = bytes(120_000, 13)
            alice.svc.sendImage(groupImage, 8, 8, g.id)
            until(15_000, "group image") { rows(bob, g.id).any { it.kind == Kind.IMAGE } && rows(carol, g.id).any { it.kind == Kind.IMAGE } }
            assertContentEquals(groupImage, carol.svc.attachmentData(rows(carol, g.id).first { it.kind == Kind.IMAGE }))
            until(10_000, "group receipts") { rows(alice, g.id).first { it.kind == Kind.IMAGE }.status != Status.PENDING }

            // 4 — bob loses the session; the next message heals it, and an image follows on the new
            //     session (a PreKey message inside: the case sealing the blob exists for)
            bob.svc.sealedForTest { bob.svc.store.update { it.copy(sessions = it.sessions - alice.hash) } }
            alice.svc.sendText("this one is lost", contact(alice, bob))
            until(15_000, "bob re-keys") { rows(bob, alice.hash).any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } }
            until(15_000, "alice adopts it") { rows(alice, bob.hash).any { it.kind == Kind.SYSTEM && it.text.contains("re-keyed") } }
            val second = bytes(90_000, 17)
            alice.svc.sendImage(second, 8, 8, contact(alice, bob))
            until(15_000, "image after the reset") { rows(bob, alice.hash).count { it.kind == Kind.IMAGE } == 2 }
            assertContentEquals(second, bob.svc.attachmentData(rows(bob, alice.hash).last { it.kind == Kind.IMAGE }))

            // 5 — delete for both, and a screenshot alert
            val reply = rows(bob, alice.hash).first { it.text == "hello alice" }
            assertTrue(bob.svc.deleteForBoth(reply.id, alice.hash))
            until(10_000, "deleted on alice's phone too") { !has(alice, bob.hash, "hello alice") }
            alice.svc.reportCapture("screenshot", contact(alice, bob))
            until(10_000, "screenshot alert") { rows(bob, alice.hash).any { it.text.contains("screenshot") } }

            // 6 — carol goes back to an old build, and returns
            carol.svc.goOffline()
            carol.svc.legacyWireOnly = true
            carol.svc.goOnline()
            alice.svc.sendText("to carol on an old build", contact(alice, carol)) // legacy message 1 of 2 to carol
            until(10_000, "reaches the old build") { has(carol, alice.hash, "to carol on an old build") }
            until(10_000, "and is acknowledged") { rows(alice, carol.hash).last().status == Status.DELIVERED }
            carol.svc.goOffline()
            carol.svc.legacyWireOnly = false
            carol.svc.goOnline()
            alice.svc.sendText("carol is back", contact(alice, carol)) // legacy message 2 of 2: it carries alice's key
            until(10_000, "back on the key lane") { lane(alice, carol) == Lane.KEY && rows(alice, carol.hash).last().status == Status.DELIVERED }
            alice.svc.sendText("sealed again", contact(alice, carol))
            until(10_000, "sealed again") { has(carol, alice.hash, "sealed again") }
            until(10_000, "and acknowledged") { rows(alice, carol.hash).last().status == Status.DELIVERED }

            // 7 — bob's delivery key changes (a restore from a backup made before sealed sender)
            bob.svc.goOffline()
            bob.svc.sealedForTest { bob.svc.store.update { it.copy(deliveryKey = null, peerKeys = null) } }
            bob.svc.goOnline()
            alice.svc.sendText("after bob's restore", contact(alice, bob))
            until(5_000, "found again, promptly") { has(bob, alice.hash, "after bob's restore") }
            until(10_000, "alice has his new key") {
                peerKey(alice, bob)?.dk == bob.svc.store.blob.deliveryKey && rows(alice, bob.hash).last().status == Status.DELIVERED
            }
            alice.svc.sendText("and once more", contact(alice, bob))
            until(10_000, "both on the key lane again") { lane(bob, alice) == Lane.KEY && rows(alice, bob.hash).last().status == Status.DELIVERED }

            // 8 — an old build
            alice.svc.addContact(dora.name)
            alice.svc.sendText("to an old build", contact(alice, dora)) // legacy, alice to dora
            until(10_000, "old build gets it") { has(dora, alice.hash, "to an old build") }
            dora.svc.sendText("from an old build", contact(dora, alice)) // legacy, dora to alice
            until(10_000, "and answers") { has(alice, dora.hash, "from an old build") }
            until(10_000, "receipts settle") {
                rows(alice, dora.hash).first().status == Status.DELIVERED && rows(dora, alice.hash).last().status == Status.DELIVERED
            }

            // 9 — an independent libsignal phone: it opens what this app sealed, and seals back
            val echo = usernameHash(echoName!!)
            alice.svc.addContact(echoName)
            // typing before the first message: nothing may leave (C9), and the echo phone would say so
            alice.svc.noteComposerActivity(echo)
            delay(300)
            alice.svc.sendText("ping", echo)
            until(10_000, "the echo answers") { has(alice, echo, "echo: ping") }
            until(10_000, "and acknowledged") { rows(alice, echo).first { it.text == "ping" }.status == Status.DELIVERED }
            val toEcho = bytes(100_000, 19)
            alice.svc.sendFile(toEcho, "to-echo.bin", "application/octet-stream", echo)
            until(20_000, "the echo's attachment arrives") { rows(alice, echo).any { it.kind == Kind.FILE && it.direction == Direction.INCOMING } }
            val back = rows(alice, echo).first { it.kind == Kind.FILE && it.direction == Direction.INCOMING }
            assertContentEquals(toEcho.reversedArray(), alice.svc.attachmentData(back), "sealed by another implementation, opened here")

            // let the last receipts leave before the sockets close
            delay(1_000)

            // again at the end: bob's delivery key changed on the way
            publish()
        } finally {
            for (i in all) {
                runCatching { i.svc.goOffline() }
                i.user.close()
            }
        }
    }
}
