package com.pyrochat.mirror

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/MIRROR-GROUPS.md §5 — contextual browser-link disclosure on the Android engine: nothing at
 * link time; exactly one `web_link {linkUp:true, browser}` per thread on the first `view` /
 * `send_text` / `typing` / `mark_read`; never for an unknown, blocked or left thread; the unlink
 * notice ONLY to the announced threads; a fresh link starts with a fresh set.
 */
class MirrorDisclosureTest {
    private val me = "0".repeat(64)
    private val alice = MirrorContact("a".repeat(64), "alice", verified = true, addedAt = 1_000)
    private val bob = MirrorContact("b".repeat(64), "bob", verified = false, addedAt = 2_000)
    private val carol = MirrorContact("c".repeat(64), "carol", verified = false, addedAt = 3_000)
    private val dave = "d".repeat(64)
    private val gid = "6f6f7073-0000-4000-8000-00000000006f"
    private val ops = MirrorGroup(
        gid, "ops",
        listOf(
            MirrorMember(me, "andro", "#3ef573", joinedAt = 1),
            MirrorMember(alice.hash, "alice", "#4fd8ff", joinedAt = 2),
            MirrorMember(bob.hash, "bob", "#c792ff", joinedAt = 3),
        ),
        "alert", 5_000,
    )
    private val old = MirrorGroup("old-0000", "old", listOf(MirrorMember(me, "andro", "#3ef573")), "alert", 1_000, left = true)

    private fun seed(h: Harness) {
        h.source.addContact(alice); h.source.addContact(bob); h.source.addContact(carol)
        h.source.block(carol.hash)
        h.source.addGroup(ops); h.source.addGroup(old)
        h.source.append(alice.hash, textRow("a1", MirrorDirection.incoming, "hi", 10_000))
        h.source.append(bob.hash, textRow("b1", MirrorDirection.incoming, "yo", 11_000))
        h.source.append(gid, MirrorMessage("g1", MirrorDirection.incoming, MirrorKind.text, "ops?", 12_000, MirrorStatus.delivered, senderHash = alice.hash))
    }

    private fun view(thread: String): JsonObject = buildJsonObject { put("c", "view"); put("thread", thread) }
    private fun typing(thread: String): JsonObject = buildJsonObject { put("c", "typing"); put("thread", thread) }
    private fun markRead(thread: String, vararg ids: String): JsonObject = buildJsonObject {
        put("c", "mark_read"); put("thread", thread); put("ids", buildJsonArray { for (id in ids) add(JsonPrimitive(id)) })
    }
    private fun sendText(thread: String, text: String, tempId: String): JsonObject = buildJsonObject {
        put("c", "send_text"); put("thread", thread); put("text", text); put("tempId", tempId)
    }

    private suspend fun FakeSource.nextWebLink(): Triple<String, Boolean, String?> = withTimeout(2_000) { webLinks.receive() }
    private fun FakeSource.noWebLink(why: String) = assertTrue(webLinks.tryReceive().isFailure, why)

    @Test
    fun nothingAtLinkOncePerThreadOnFirstTouchAndUnlinkOnlyToAnnounced(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake("Safari on Mac")
            h.drainSnapshot()
            h.source.noWebLink("NOTHING is announced at link time")
            assertEquals(emptyList(), h.engine.sessionForTest!!.announcedForTest())

            // the page opened alice → one web_link to alice, with the sanitized browser label
            h.pushCmd(view(alice.hash))
            assertEquals(Triple(alice.hash, true, "Safari on Mac"), h.source.nextWebLink())
            // every further touch of that thread is silent: view again, a send, a read, typing
            h.pushCmd(view(alice.hash))
            h.pushCmd(sendText(alice.hash, "from web", "t1"))
            assertEquals("from web" to alice.hash, withTimeout(2_000) { h.source.sentTexts.receive() })
            assertEquals("t1", h.nextEvent().obj["row"]!!.jsonObject.str("tempId"))
            h.pushCmd(markRead(alice.hash, "a1"))
            assertEquals("a1" to alice.hash, withTimeout(2_000) { h.source.visible.receive() })
            h.pushCmd(typing(alice.hash))
            assertEquals(alice.hash, withTimeout(2_000) { h.source.composer.receive() })
            h.source.noWebLink("alice hears about the link once per link session")

            // the first typing in the group announces to the group (fan-out inside the service),
            // the first mark_read to bob announces to bob — each ahead of the action itself
            h.pushCmd(typing(gid))
            assertEquals(Triple(gid, true, "Safari on Mac"), h.source.nextWebLink())
            assertEquals(gid, withTimeout(2_000) { h.source.composer.receive() })
            h.pushCmd(markRead(bob.hash, "b1"))
            assertEquals(Triple(bob.hash, true, "Safari on Mac"), h.source.nextWebLink())
            assertEquals("b1" to bob.hash, withTimeout(2_000) { h.source.visible.receive() })

            // never for a blocked contact, a left group or an unknown thread — an old page may still
            // send these (the typing signal is forwarded as before; the service decides)
            h.pushCmd(view(carol.hash)); h.pushCmd(markRead(carol.hash, "x")); h.pushCmd(typing(carol.hash))
            assertEquals(carol.hash, withTimeout(2_000) { h.source.composer.receive() })
            h.pushCmd(view("old-0000")); h.pushCmd(typing("old-0000"))
            assertEquals("old-0000", withTimeout(2_000) { h.source.composer.receive() })
            h.pushCmd(view(dave))
            h.pushCmd(sendText(dave, "x", "t2"))
            assertEquals("""{"e":"cmd_err","tempId":"t2","code":"unknown_thread"}""", h.nextEvent().plaintext)
            h.pushCmd(sendText(carol.hash, "x", "t3"))
            assertEquals("""{"e":"cmd_err","tempId":"t3","code":"blocked"}""", h.nextEvent().plaintext)
            h.pushCmd(sendText("old-0000", "x", "t4"))
            assertEquals("""{"e":"cmd_err","tempId":"t4","code":"left"}""", h.nextEvent().plaintext)
            h.source.noWebLink("a refused send, a blocked contact or a left group never discloses anything")
            assertEquals(listOf(alice.hash, gid, bob.hash), h.engine.sessionForTest!!.announcedForTest())

            // unlink: `web_link {linkUp:false}` to the announced threads only, in announcement order
            h.engine.stop()
            assertEquals("""{"e":"bye","reason":"unlink"}""", h.nextEvent().plaintext)
            assertEquals(
                listOf(Triple(alice.hash, false, null), Triple(gid, false, null), Triple(bob.hash, false, null)),
                List(3) { h.source.nextWebLink() },
            )
            h.source.noWebLink("carol, the left group and the unknown thread were never told, so they are not told now either")
        } finally {
            h.close()
        }
    }

    @Test
    fun aTouchWhileOfflineIsNotCountedAsAnnounced(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake()
            h.drainSnapshot()
            h.source.goOnline(false)
            assertEquals("""{"e":"conn","up":false}""", h.nextEvent().plaintext)
            h.pushCmd(view(alice.hash))
            h.pushCmd(typing(alice.hash))
            assertEquals(alice.hash, withTimeout(2_000) { h.source.composer.receive() })
            h.source.noWebLink("a notice that cannot leave the phone must not mark the thread as announced")
            assertEquals(emptyList(), h.engine.sessionForTest!!.announcedForTest())
            h.source.goOnline(true)
            assertEquals("""{"e":"conn","up":true}""", h.nextEvent().plaintext)
            h.pushCmd(view(alice.hash))
            assertEquals(Triple(alice.hash, true, "Test on JVM"), h.source.nextWebLink(), "the next touch, once online, discloses")
            h.engine.stop()
            assertEquals("""{"e":"bye","reason":"unlink"}""", h.nextEvent().plaintext)
            assertEquals(Triple(alice.hash, false, null), h.source.nextWebLink())
            h.source.noWebLink("only alice")
        } finally {
            h.close()
        }
    }

    @Test
    fun aThreadGoneWhileLinkedIsNotToldAtUnlink(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake()
            h.drainSnapshot()
            h.pushCmd(view(gid))
            assertEquals(Triple(gid, true, "Test on JVM"), h.source.nextWebLink())
            h.pushCmd(view(alice.hash))
            assertEquals(Triple(alice.hash, true, "Test on JVM"), h.source.nextWebLink())
            h.pushCmd(view(bob.hash))
            assertEquals(Triple(bob.hash, true, "Test on JVM"), h.source.nextWebLink())
            // this phone leaves the group and blocks alice: group_rm / contact_rm on the page, and
            // neither can be told about the unlink any more (nothing to fan out to, blocked peers are never messaged)
            h.source.replaceGroup(ops.copy(left = true))
            assertEquals("""{"e":"group_rm","id":"$gid"}""", h.nextEvent().plaintext)
            h.source.block(alice.hash)
            assertEquals("""{"e":"contact_rm","hash":"${alice.hash}"}""", h.nextEvent().plaintext)
            assertEquals("""{"e":"thread_rm","thread":"${alice.hash}"}""", h.nextEvent().plaintext)
            assertEquals(listOf(bob.hash), h.engine.sessionForTest!!.announcedForTest())
            h.engine.stop()
            assertEquals("""{"e":"bye","reason":"unlink"}""", h.nextEvent().plaintext)
            assertEquals(Triple(bob.hash, false, null), h.source.nextWebLink())
            h.source.noWebLink("the left group and the blocked contact are not told")
        } finally {
            h.close()
        }
    }

    @Test
    fun aRelinkStartsWithAFreshSet(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        val h2 = Harness(source = h.source)
        try {
            h.handshake("Chrome on Mac")
            h.drainSnapshot()
            h.pushCmd(view(alice.hash))
            assertEquals(Triple(alice.hash, true, "Chrome on Mac"), h.source.nextWebLink())
            h.engine.stop()
            assertEquals("""{"e":"bye","reason":"unlink"}""", h.nextEvent().plaintext)
            assertEquals(Triple(alice.hash, false, null), h.source.nextWebLink())
            // the same phone links a fresh page: nothing at link time, and alice is told again on
            // the page's reopen (`view` after the relink's snapshot, MIRROR-GROUPS.md §5)
            h2.handshake("Chrome on Mac")
            h2.drainSnapshot()
            h2.source.noWebLink("nothing at relink time")
            h2.pushCmd(view(alice.hash))
            assertEquals(Triple(alice.hash, true, "Chrome on Mac"), h2.source.nextWebLink())
            h2.engine.stop()
            assertEquals("""{"e":"bye","reason":"unlink"}""", h2.nextEvent().plaintext)
            assertEquals(Triple(alice.hash, false, null), h2.source.nextWebLink())
            h2.source.noWebLink("only alice, once")
        } finally {
            h2.close()
            h.close()
        }
    }
}
