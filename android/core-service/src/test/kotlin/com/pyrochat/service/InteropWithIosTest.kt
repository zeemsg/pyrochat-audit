package com.pyrochat.service

import com.pyrochat.crypto.Envelope
import com.pyrochat.crypto.Sealed
import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATE B — iOS interop, the milestone gate. The counterpart `ios/Tests/InteropEchoTests.swift`
 * registers `PYRO_INTEROP_USER` on the local relay through the REAL iOS ChatService and echoes
 * every text back (prefix `echo: `) while marking it read. Run both concurrently:
 *
 *   cd ios && TEST_RUNNER_PYRO_INTEROP_USER=<name> xcodebuild test … -only-testing:PyroChatTests/InteropEchoTests
 *   cd android && ./gradlew :core-service:test --tests '*InteropWithIosTest*' -PinteropUser=<name>
 *
 * Skipped unless the username is given (`-PinteropUser` / `PYRO_INTEROP_USER`).
 *
 * With sealed sender on the relay the same gate is the live matrix of docs/SEALED-SENDER.md §9 (X2):
 * `PYRO_INTEROP_LEGACY=true` makes this side a build from before sealed sender, and
 * `TEST_RUNNER_PYRO_INTEROP_LEGACY=1` does the same to the iOS side. The messages take the sealed
 * path only when both ends can, and the assertions follow what this side learned about its peer.
 */
class InteropWithIosTest {
    private val iosUser: String? = (System.getProperty("pyro.interop.user") ?: System.getenv("PYRO_INTEROP_USER"))?.takeIf { it.isNotBlank() }
    private val legacy: Boolean = (System.getProperty("pyro.interop.legacy") ?: System.getenv("PYRO_INTEROP_LEGACY")) == "true"

    @Test
    fun `Gate B - Android text round trip with the real iOS ChatService`(): Unit = runBlocking {
        Assumptions.assumeTrue(iosUser != null, "SKIPPED: no iOS peer named — pass -PinteropUser=<name> (and run InteropEchoTests on the simulator with the same name)")
        Live.requireRelay()
        val ios = iosUser!!
        val me = Live.makeService("ad", legacy = legacy)
        val sends = CopyOnWriteArrayList<Wire.Out.Send>()
        val delivers = CopyOnWriteArrayList<Wire.Out.Deliver>()
        try {
            me.svc.wireTap = { if (it is Wire.Out.Send && it.kind == "msg") sends += it }
            me.svc.anonWireTap = { if (it is Wire.Out.Deliver && it.kind == "msg") delivers += it }
            me.svc.register(me.username)
            println("GATE B: android <${me.username}> registered, waiting for iOS <$ios> to register on the relay")

            // xcodebuild needs minutes to build + boot — poll the add until the iOS identity exists
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (true) {
                try {
                    me.svc.addContact(ios)
                    break
                } catch (e: ServiceError) {
                    if (e.code != Wire.ErrorCode.UNKNOWN_USER || System.currentTimeMillis() > deadline) throw e
                    delay(2_000)
                }
            }
            val peer = me.svc.contacts.value.first { it.name == ios }
            println("GATE B: iOS peer online — prekey bundle processed for ${peer.hash.take(12)}…")
            // sealed only when this login has it AND the peer's bundle said it can take sealed messages
            val sealed = me.svc.sealedStatus.value == SealedStatus.ON && me.svc.store.blob.peerKeys?.get(peer.hash)?.sealed == true
            val before = Live.relayTotals()
            println("GATE B: this side is ${if (legacy) "an old build" else "a new build"}, sealed sender with this peer: $sealed")

            me.svc.sendText("hello from android #1", peer)
            assertTrue(waitUntil(60_000) {
                me.svc.messages.value[peer.hash]?.any { it.direction == Direction.INCOMING && it.text == "echo: hello from android #1" } == true
            }, "iOS must decrypt the Android PreKey message and echo it")
            println("GATE B: echo #1 received from iOS")
            assertTrue(waitUntil(20_000) {
                me.svc.messages.value[peer.hash]?.first { it.direction == Direction.OUTGOING }?.status == Status.READ
            }, "iOS DELIVERED and READ receipts must land on the Android row")
            println("GATE B: outgoing #1 status = ${me.svc.messages.value[peer.hash]!!.first { it.direction == Direction.OUTGOING }.status}")

            me.svc.sendText("hello from android #2", peer)
            assertTrue(waitUntil(60_000) {
                me.svc.messages.value[peer.hash]?.any { it.direction == Direction.INCOMING && it.text == "echo: hello from android #2" } == true
            }, "the second message rides the established Double Ratchet")
            assertTrue(waitUntil(20_000) {
                me.svc.messages.value[peer.hash]?.filter { it.direction == Direction.OUTGOING }?.all { it.status == Status.READ } == true
            })

            if (sealed) {
                // docs/SEALED-SENDER.md §5.2: the first message to someone new on the first-contact
                // lane, and the key lane as soon as the iOS reply has brought its delivery key
                assertTrue(sends.isEmpty(), "nothing named its sender to the relay: $sends")
                assertEquals(2, delivers.size)
                assertNull(delivers[0].access, "first contact: no delivery key yet")
                assertNotNull(delivers[1].access, "the key lane after the reply")
                assertTrue(delivers.all { Envelope.open(it.body).type == Sealed.ENVELOPE_TYPE })
                val after = Live.relayTotals()
                if (before != null && after != null) {
                    assertEquals(0, after.legacy - before.legacy, "neither phone sent anything the old way")
                    println("GATE B: the relay counted ${after.firstContact - before.firstContact} first-contact and ${after.sealed - before.sealed} key-lane messages, none the old way")
                }
            } else {
                val types = sends.map { Envelope.open(it.body).type }
                println("GATE B: envelope types sent = $types (3 = PreKey bootstrap, 2 = Whisper)")
                assertEquals(2, types.size)
                assertEquals(Protocol.CIPHERTEXT_PREKEY, types[0], "first message bootstraps the session (PreKeySignalMessage)")
                assertEquals(Protocol.CIPHERTEXT_WHISPER, types[1], "after the iOS reply the ratchet is acknowledged: Whisper (type 2)")
                assertTrue(delivers.isEmpty(), "an old build on either end: nothing sealed was sent")
                val after = Live.relayTotals()
                if (before != null && after != null) assertEquals(0, (after.sealed - before.sealed) + (after.firstContact - before.firstContact), "and the relay took nothing sealed from either phone")
            }
            val incoming = me.svc.messages.value[peer.hash]!!.filter { it.direction == Direction.INCOMING }
            assertEquals(ios, peer.name)
            println("GATE B: PASSED — ${incoming.size} echoes from iOS, contact <${peer.name}>, both outgoing rows READ")
            me.svc.goOffline()
        } finally {
            me.close()
        }
    }
}
