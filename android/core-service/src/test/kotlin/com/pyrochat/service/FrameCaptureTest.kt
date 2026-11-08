package com.pyrochat.service

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/MESSAGE-DETAILS.md §3 + §4 — the capture buffer: both bounds, oldest evicted, emptied by
 * lock and by wipe, nothing after a restart, and no way for key material to get in.
 */
class FrameCaptureTest {
    private fun frame(body: String, id: String = "id", peer: String = PEER) =
        CapturedFrame(t = CapturedFrame.SEND, id = id, peer = peer, ttl = Protocol.TTL_MSG, kind = "msg", body = body)

    private fun body(bytes: Int) = Base64.getEncoder().encodeToString(ByteArray(bytes) { it.toByte() })

    // ---- the entry bound -------------------------------------------------------------

    @Test
    fun `keeps the most recent 50 messages and evicts the oldest first`() {
        val c = FrameCapture()
        repeat(60) { i -> c.put("m$i", frame(body(16))) }
        assertEquals(FrameCapture.MAX_ENTRIES, c.size())
        assertEquals((10 until 60).map { "m$it" }, c.ids(), "oldest evicted, newest kept, in capture order")
        assertNull(c["m9"])
        assertNotNull(c["m59"])
    }

    @Test
    fun `50 is the bound, not 51`() {
        val c = FrameCapture()
        repeat(50) { i -> c.put("m$i", frame(body(16))) }
        assertEquals(50, c.size())
        assertNotNull(c["m0"])
        c.put("m50", frame(body(16)))
        assertEquals(50, c.size())
        assertNull(c["m0"], "the 51st capture evicts the 1st")
    }

    // ---- the byte bound --------------------------------------------------------------

    @Test
    fun `honours 256 KB even with far fewer than 50 messages`() {
        val c = FrameCapture()
        // 10 frames of ~44 KB of base64: the byte bound bites long before the entry bound
        repeat(10) { i -> c.put("m$i", frame(body(32 * 1024))) }
        assertTrue(c.size() < 10, "the byte bound must have evicted something")
        assertTrue(c.byteSize() <= FrameCapture.MAX_BYTES, "held ${c.byteSize()} bytes")
        assertNull(c["m0"], "the oldest went first")
        assertNotNull(c["m9"], "the newest is always kept")
    }

    @Test
    fun `whichever comes first — both bounds hold at once`() {
        val c = FrameCapture()
        repeat(200) { i -> c.put("m$i", frame(body(2 * 1024))) }
        assertTrue(c.size() <= FrameCapture.MAX_ENTRIES)
        assertTrue(c.byteSize() <= FrameCapture.MAX_BYTES)
    }

    @Test
    fun `a single frame too large to fit is refused without evicting the rest`() {
        val c = FrameCapture()
        c.put("keep", frame(body(64)))
        c.put("huge", frame(body(FrameCapture.MAX_BYTES)))
        assertNull(c["huge"])
        assertNotNull(c["keep"], "one oversized frame must not empty the buffer")
    }

    @Test
    fun `re-capturing a row replaces it and does not double-count its bytes`() {
        val c = FrameCapture()
        c.put("m", frame(body(1024)))
        val once = c.byteSize()
        c.put("m", frame(body(1024)))
        assertEquals(1, c.size())
        assertEquals(once, c.byteSize())
    }

    @Test
    fun `putIfAbsent keeps the first envelope of a fan-out`() {
        val c = FrameCapture()
        c.putIfAbsent("row", frame(body(16), id = "relay-1", peer = PEER))
        c.putIfAbsent("row", frame(body(16), id = "relay-2", peer = OTHER))
        assertEquals("relay-1", c["row"]?.id)
        assertEquals(PEER, c["row"]?.peer)
    }

    @Test
    fun `rekey moves a capture to the row id it landed under`() {
        val c = FrameCapture()
        c.put("relay", frame(body(16)))
        val before = c.byteSize()
        c.rekey("relay", "row")
        assertNull(c["relay"])
        assertNotNull(c["row"])
        assertEquals(before, c.byteSize(), "a rename is not a second copy")
    }

    @Test
    fun `remove drops one row's envelope`() {
        val c = FrameCapture()
        c.put("a", frame(body(16)))
        c.put("b", frame(body(16)))
        c.remove("a")
        assertNull(c["a"])
        assertNotNull(c["b"])
        assertEquals(1, c.size())
    }

    @Test
    fun `clear empties the buffer and its byte count`() {
        val c = FrameCapture()
        repeat(5) { i -> c.put("m$i", frame(body(512))) }
        c.clear()
        assertEquals(0, c.size())
        assertEquals(0, c.byteSize())
        assertNull(c["m0"])
    }

    // ---- what a frame may contain ----------------------------------------------------

    @Test
    fun `a captured frame is exactly the six wire fields — there is no field a key could ride in`() {
        // the declared fields ARE the state: `outgoing`, `bodyBytes` and `weight` are computed
        assertEquals(
            setOf("t", "id", "peer", "ttl", "kind", "body"),
            CapturedFrame::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet(),
        )
    }

    @Test
    fun `a frame is built only from a relay frame, never from a payload`() {
        val send = Wire.Out.Send("7f3c", PEER, Protocol.TTL_MSG, "msg", "MwohBQZ4k")
        assertEquals(CapturedFrame(CapturedFrame.SEND, "7f3c", PEER, Protocol.TTL_MSG, "msg", "MwohBQZ4k"), CapturedFrame.of(send))
        val recv = Wire.In.Recv("7f3c", PEER, "msg", "MwohBQZ4k", ts = 1700)
        val captured = CapturedFrame.of(recv)
        assertEquals(CapturedFrame(CapturedFrame.RECV, "7f3c", PEER, null, "msg", "MwohBQZ4k"), captured)
        assertNull(captured.ttl, "a recv frame never carried a ttl and one is never invented")
        assertFalse(captured.outgoing)
    }

    @Test
    fun `bodyBytes counts the plain envelope behind the base64`() {
        for (n in listOf(0, 1, 2, 3, 16, 17, 18, 412, 1023)) {
            assertEquals(n, CapturedFrame(CapturedFrame.SEND, "i", PEER, 1, "msg", body(n)).bodyBytes, "n=$n")
        }
    }

    // ---- the service owns it (§3): lock, wipe, restart --------------------------------

    @Test
    fun `lock empties the capture and a restart starts with nothing`(): Unit = runBlocking {
        val user = Live.makeService("cap")
        val secret = body(64)
        try {
            user.svc.captureForTest("m1", frame(secret))
            assertNotNull(user.svc.capturedFrame("m1"))

            // AUTO-LOCK / lock teardown
            user.svc.shutdown()
            assertNull(user.svc.capturedFrame("m1"), "shutdown must drop every captured envelope")
            assertEquals(0, user.svc.capturedCountForTest())
        } finally {
            user.close()
        }

        // "holds nothing after a restart": the same store, a brand-new service
        val restarted = ChatService(
            com.pyrochat.store.SecureStore.open(user.file, user.password, user.secrets, FastKdf),
            noticeStore = InMemoryNoticeStore(),
        )
        try {
            assertNull(restarted.capturedFrame("m1"))
            assertEquals(0, restarted.capturedCountForTest())
        } finally {
            restarted.close()
        }

        // and it was never written: the ciphertext appears nowhere in the store file
        val onDisk = Files.readAllBytes(user.file).toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains(secret), "a captured envelope must never reach the store")
    }

    @Test
    fun `a wiped session keeps no envelope`(): Unit = runBlocking {
        val user = Live.makeService("cap")
        try {
            user.svc.captureForTest("m1", frame(body(64)))
            // TOTAL WIPE closes the service before the files go (RealBackend.totalWipe)
            user.svc.shutdown()
            assertEquals(0, user.svc.capturedCountForTest())
        } finally {
            user.close()
        }
    }

    @Test
    fun `deleting a row forgets its envelope`(): Unit = runBlocking {
        val user = Live.makeService("cap")
        try {
            user.svc.receivePayloadForTest(PEER, Wire.Payload(kind = "text", text = "gone soon", senderName = "peer"), id = "row-1")
            user.svc.captureForTest("row-1", frame(body(64)))
            assertNotNull(user.svc.capturedFrame("row-1"))
            user.svc.deleteMessage("row-1", PEER)
            assertNull(user.svc.capturedFrame("row-1"), "a row that is gone keeps no envelope either")
        } finally {
            user.close()
        }
    }

    // ---- sealed frames (docs/SEALED-SENDER.md §5.8) ----------------------------------

    @Test
    fun `a sealed frame is kept without its delivery key, and one that arrives names nobody`() {
        val out = Wire.Out.Deliver("attempt-1", PEER, Protocol.TTL_MSG, "msg", "AAAA", access = "c2VjcmV0LWRlbGl2ZXJ5LWtleQ==")
        val sent = CapturedFrame.of(out)
        assertEquals(CapturedFrame.DELIVER, sent.t)
        assertTrue(sent.outgoing)
        assertEquals(PEER, sent.peer)
        assertFalse(sent.toString().contains("c2VjcmV0"), "there is nowhere in the type to put it")
        val arrived = CapturedFrame.of(Wire.In.Recv("r", null, "msg", "AAAA"))
        assertEquals("", arrived.peer, "a sealed frame arrives naming nobody")
        assertFalse(arrived.outgoing)
    }

    private companion object {
        const val PEER = "9a1fd4000000000000000000000000000000000000000000000000000000d4c2"
        const val OTHER = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
