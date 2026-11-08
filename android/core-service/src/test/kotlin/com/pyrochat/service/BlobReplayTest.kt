package com.pyrochat.service

import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A socket drop in the middle of an incoming attachment. The relay replays the blob manifest and
 * every chunk on reconnect; the manifest re-decrypts as a libsignal DUPLICATE (its message key is
 * spent). Acking that duplicate told the relay to drop the whole blob route — chunks included —
 * and the attachment was lost without a row or an error. Live on 127.0.0.1:7444 (skipped when down).
 */
class BlobReplayTest {
    private val random = SecureRandom()

    @Test
    fun `a replayed blob manifest keeps the partial transfer alive`(): Unit = runBlocking {
        Live.requireRelay()
        val alice = Live.makeService("al")
        val bob = Live.makeService("bo")
        try {
            alice.svc.register(alice.username)
            bob.svc.register(bob.username)
            alice.svc.addContact(bob.username)
            val bobContact = alice.svc.contacts.value.first()
            // a live session, so the manifest below decrypts on bob's side
            alice.svc.sendText("hello", bobContact)
            assertTrue(waitUntil { bob.svc.messages.value[alice.hash]?.any { it.text == "hello" } == true })
            // With sealed sender an attachment waits until the first text is DELIVERED
            // (docs/SEALED-SENDER.md §5.2); without it the wait changes nothing.
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.firstOrNull()?.status == Status.DELIVERED })

            // bob loses the socket; alice sends an image into bob's relay queue. Big enough that the
            // relay's pump is still paused on backpressure when bob's ack for the replayed manifest
            // lands — the window in which the chunks behind it were dropped.
            bob.svc.goOffline()
            val jpeg = ByteArray(6 * 1024 * 1024).also(random::nextBytes)
            alice.svc.sendImage(jpeg, 640, 480, bobContact)
            assertTrue(waitUntil { alice.svc.messages.value[bob.hash]?.any { it.kind == Kind.IMAGE } == true })
            val row = alice.svc.messages.value[bob.hash]!!.first { it.kind == Kind.IMAGE }
            assertTrue(waitUntil { alice.svc.capturedFrame(row.id) != null }, "the manifest envelope is captured as it is sent")
            val manifest = alice.svc.capturedFrame(row.id)!!

            // bob's app had already decrypted the manifest before the drop: hand it the frame once,
            // as the relay delivers it. A sealed manifest (`deliver`) arrives without `from`.
            bob.svc.routeForTest(
                Wire.In.Recv(
                    id = manifest.id, from = if (manifest.t == CapturedFrame.DELIVER) null else alice.hash,
                    kind = manifest.kind, body = manifest.body, ts = System.currentTimeMillis(),
                ),
            )
            assertTrue(waitUntil { bob.svc.rxTransfers.value.containsKey(manifest.id) }, "the manifest opened a partial transfer")

            // reconnect: the relay replays the manifest (now a DUPLICATE) + every chunk
            bob.svc.goOnline()
            assertTrue(
                waitUntil(30_000) {
                    bob.svc.messages.value[alice.hash]?.any { it.direction == Direction.INCOMING && it.kind == Kind.IMAGE } == true
                },
                "the replayed manifest must not ack away the chunks behind it",
            )
            val image = bob.svc.messages.value[alice.hash]!!.first { it.kind == Kind.IMAGE }
            assertNotNull(bob.svc.attachmentData(image))
            assertContentEquals(jpeg, bob.svc.attachmentData(image))
        } finally {
            alice.close(); bob.close()
        }
    }
}
