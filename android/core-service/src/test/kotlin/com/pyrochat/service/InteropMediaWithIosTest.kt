package com.pyrochat.service

import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATE M2 — media interop with the REAL iOS ChatService. `ios/Tests/InteropEchoTests.swift` registers
 * `PYRO_INTEROP_USER` on the local relay and echoes every image / file / voice note it receives back
 * as the same kind with the same bytes (texts come back with the `echo: ` prefix). Run both:
 *
 *   cd ios && TEST_RUNNER_PYRO_INTEROP_USER=<name> xcodebuild test -workspace PyroChat.xcworkspace -scheme PyroChat \
 *     -destination 'id=<simulator>' -only-testing:PyroChatTests/InteropEchoTests
 *   cd android && ./gradlew :core-service:test --tests '*InteropMediaWithIosTest*' -PinteropUser=<name>
 *
 * Skipped unless the username is given (`-PinteropUser` / `PYRO_INTEROP_USER`).
 */
class InteropMediaWithIosTest {
    private val iosUser: String? = (System.getProperty("pyro.interop.user") ?: System.getenv("PYRO_INTEROP_USER"))?.takeIf { it.isNotBlank() }

    /** As in Gate B: this side is, on the wire, a build from before sealed sender. */
    private val legacy: Boolean = (System.getProperty("pyro.interop.legacy") ?: System.getenv("PYRO_INTEROP_LEGACY")) == "true"
    private val random = SecureRandom()

    private fun jpeg(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, random.nextInt() and 0xffffff)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    @Test
    fun `Gate M2 - image, file and voice note echo back byte-identical from the real iOS ChatService`(): Unit = runBlocking {
        Assumptions.assumeTrue(iosUser != null, "SKIPPED: no iOS peer named — pass -PinteropUser=<name> (and run InteropEchoTests on the simulator with the same name)")
        Live.requireRelay()
        val ios = iosUser!!
        val me = Live.makeService("am", legacy = legacy)
        try {
            me.svc.register(me.username)
            println("GATE M2: android <${me.username}> registered, waiting for iOS <$ios> to register on the relay")
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
            println("GATE M2: iOS peer online — prekey bundle processed for ${peer.hash.take(12)}…")
            fun incoming(kind: Kind): ChatMessage? = me.svc.messages.value[peer.hash]?.firstOrNull { it.direction == Direction.INCOMING && it.kind == kind }

            // With sealed sender the first message to someone new is text only, and attachments go
            // once it is delivered (docs/SEALED-SENDER.md §5.2). Without it the text changes nothing.
            me.svc.sendText("media gate", peer)
            assertTrue(waitUntil(60_000) {
                me.svc.messages.value[peer.hash]?.firstOrNull { it.direction == Direction.OUTGOING }?.status?.let { it.receiptRank >= Status.DELIVERED.receiptRank } == true
            }, "iOS must take the first text before any attachment is sent")
            val sealed = me.svc.sealedStatus.value == SealedStatus.ON && me.svc.store.blob.peerKeys?.get(peer.hash)?.dk != null
            val before = Live.relayTotals()
            val mine = java.util.concurrent.CopyOnWriteArrayList<String>()
            me.svc.anonWireTap = { if (it is Wire.Out.Deliver) mine += it.kind }
            println("GATE M2: first text delivered; sealed sender with this peer: $sealed")

            // ---- image: 640×480 JPEG (several 64 KiB chunks)
            val jpeg = jpeg(640, 480)
            me.svc.sendImage(jpeg, 640, 480, peer)
            assertTrue(waitUntil(120_000) { incoming(Kind.IMAGE) != null }, "iOS must reassemble the image and echo it back as an image")
            val image = incoming(Kind.IMAGE)!!
            assertContentEquals(jpeg, me.svc.attachmentData(image), "image bytes must be byte-identical after iOS decrypt → re-encrypt")
            assertEquals(640, image.imageWidth); assertEquals(480, image.imageHeight)
            assertEquals("image/jpeg", image.fileMime); assertEquals(jpeg.size, image.fileBytes); assertEquals("[IMG]", image.text)
            println("GATE M2: image echo OK — ${jpeg.size} B, ${image.imageWidth}x${image.imageHeight}, ${image.fileMime}")

            // ---- file: 1 MiB
            val payload = ByteArray(1024 * 1024).also(random::nextBytes)
            me.svc.sendFile(payload, "trip.bin", "application/octet-stream", peer)
            assertTrue(waitUntil(120_000) { incoming(Kind.FILE) != null }, "iOS must echo the file back as a file")
            val file = incoming(Kind.FILE)!!
            assertContentEquals(payload, me.svc.attachmentData(file), "file bytes must be byte-identical")
            assertEquals("trip.bin", file.fileName); assertEquals("application/octet-stream", file.fileMime)
            assertEquals(payload.size, file.fileBytes); assertEquals("[FILE] trip.bin", file.text)
            println("GATE M2: file echo OK — ${file.fileName}, ${file.fileBytes} B, ${file.fileMime}")

            // ---- voice note: 200 KB, 7.5 s
            val audio = ByteArray(200 * 1024).also(random::nextBytes)
            me.svc.sendVoice(audio, 7.5, peer)
            assertTrue(waitUntil(120_000) { incoming(Kind.VOICE) != null }, "iOS must echo the voice note back as a voice note")
            val voice = incoming(Kind.VOICE)!!
            assertContentEquals(audio, me.svc.voiceContent(voice), "voice bytes must be byte-identical")
            assertEquals(7.5, voice.voiceDuration); assertEquals("[voice 7s]", voice.text)
            // docs/DECOY-ACCOUNT.md §1a: the audio lands in a sidecar, never inline in the blob
            assertNotNull(voice.attachmentId, "an arriving voice note is spilled to a sidecar on receipt")
            assertNull(voice.voiceData, "nothing writes inline audio any more")
            println("GATE M2: voice echo OK — ${me.svc.voiceContent(voice)?.size} B, ${voice.voiceDuration} s")

            // ---- receipts: iOS marks every echoed row visible ⇒ DELIVERED then READ on our outgoing blobs
            assertTrue(waitUntil(30_000) {
                me.svc.messages.value[peer.hash]!!.filter { it.direction == Direction.OUTGOING }.all { it.status == Status.READ }
            }, "iOS DELIVERED and READ receipts must land on every Android blob row: " +
                me.svc.messages.value[peer.hash]!!.filter { it.direction == Direction.OUTGOING }.map { "${it.kind}=${it.status}" })
            assertTrue(me.svc.blobProgress.value.isEmpty()); assertTrue(me.svc.rxTransfers.value.isEmpty())
            val after = Live.relayTotals()
            if (sealed && before != null && after != null) {
                assertEquals(0, after.legacy - before.legacy, "every manifest and receipt went sealed, both ways")
                println("GATE M2: the relay counted ${after.sealed - before.sealed} key-lane and ${after.firstContact - before.firstContact} first-contact messages, none the old way; this side sent ${mine.groupingBy { it }.eachCount()}")
            }
            val outgoing = me.svc.messages.value[peer.hash]!!.filter { it.direction == Direction.OUTGOING }
            println("GATE M2: PASSED — image/file/voice echoed byte-identical by iOS <${peer.name}>; outgoing rows ${outgoing.map { "${it.kind}=${it.status}" }}")
            me.svc.goOffline()
        } finally {
            me.close()
        }
    }
}
