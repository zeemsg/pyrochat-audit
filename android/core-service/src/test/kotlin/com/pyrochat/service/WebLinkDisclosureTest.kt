package com.pyrochat.service

import com.pyrochat.protocol.GroupControl
import com.pyrochat.protocol.GroupMember
import com.pyrochat.protocol.GroupRef
import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.GroupPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/MIRROR-GROUPS.md §5 on the Android service: `sendWebLink` discloses the desktop link to ONE
 * conversation — a group fans out with `group:{id,epoch}` exactly like a roster control, a left
 * group or an unknown thread is a no-op — and a received `web_link` carrying `group` lands in the
 * group thread naming the member, while one without `group` keeps today's 1:1 row. The offline
 * half drives the decrypted-payload hook; the live half runs three real services on the dev relay.
 */
class WebLinkDisclosureTest {
    private val me = "1".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val dave = "d".repeat(64)
    private val gid = "0f9d2c44-1111-4222-8333-444455556666"
    private val t0 = Instant.parse("2026-09-19T12:00:00Z")

    private fun makeUser() = Live.makeService("wl") { b ->
        b.copy(usernameDisplay = "me", usernameHash = me, contacts = listOf(Contact(alice, "alice", t0), Contact(bob, "bob", t0)))
    }.also { it.svc.requireLinkForRosterOps = false }

    private fun wm(hash: String, name: String) = GroupMember(hash, name, SwiftDate.toSeconds(t0))

    private fun createPayload(epoch: Int = 0) = Wire.Payload(
        kind = "control", control = GroupControl.CREATE, group = GroupRef(gid, epoch), name = "ops", creator = alice,
        members = listOf(wm(alice, "alice"), wm(me, "me"), wm(bob, "bob")), policy = "alert", createdAt = SwiftDate.toSeconds(t0),
    )

    private fun webLink(up: Boolean, browser: String?, group: GroupRef? = null) =
        Wire.Payload(kind = "control", control = "web_link", linkUp = up, browser = browser, group = group)

    private fun ChatService.texts(thread: String) = (messages.value[thread] ?: emptyList()).map { it.text }

    @Test
    fun `received web_link with group lands in the group thread naming the member, without group in the 1 to 1 thread as today`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload())
            u.svc.receivePayloadForTest(alice, webLink(true, "Safari on Mac", GroupRef(gid, 0)))
            assertEquals(listOf("── <alice> linked a desktop browser (Safari on Mac) — this channel may be viewed there ──"), u.svc.texts(gid))
            assertEquals(emptyList(), u.svc.texts(alice), "never in the 1:1 thread")
            // the label is sanitized like the 1:1 notice; without one the parenthesis is dropped
            u.svc.receivePayloadForTest(bob, webLink(true, "<Chrome/on:Mac>", GroupRef(gid, 0)))
            u.svc.receivePayloadForTest(bob, webLink(true, null, GroupRef(gid, 0)))
            u.svc.receivePayloadForTest(alice, webLink(false, null, GroupRef(gid, 0)))
            assertEquals(
                listOf(
                    "── <alice> linked a desktop browser (Safari on Mac) — this channel may be viewed there ──",
                    "── <bob> linked a desktop browser (ChromeonMac) — this channel may be viewed there ──",
                    "── <bob> linked a desktop browser — this channel may be viewed there ──",
                    "── <alice> unlinked the desktop browser ──",
                ),
                u.svc.texts(gid),
            )
            val rows = assertNotNull(u.svc.messages.value[gid])
            assertTrue(rows.all { it.kind == Kind.SYSTEM && it.direction == Direction.INCOMING && it.group == gid }, "group system rows, filed under the group")
            // a non-member's notice and one for an unknown group are dropped
            u.svc.receivePayloadForTest(dave, webLink(true, "x", GroupRef(gid, 0)))
            u.svc.receivePayloadForTest(alice, webLink(true, "x", GroupRef("22222222-0000-4000-8000-000000000002", 0)))
            assertEquals(4, u.svc.texts(gid).size)
            assertEquals(emptyList(), u.svc.texts(dave))
            // without `group`: the 1:1 thread, text unchanged
            u.svc.receivePayloadForTest(alice, webLink(true, "Chrome on Mac"))
            u.svc.receivePayloadForTest(alice, webLink(false, null))
            assertEquals(
                listOf("⚠ peer linked a desktop browser (Chrome on Mac) — this conversation may be viewed there", "peer unlinked the desktop browser"),
                u.svc.texts(alice),
            )
            assertEquals(4, u.svc.texts(gid).size)
            // once removed from the group nothing lands there any more
            u.svc.receivePayloadForTest(alice, Wire.Payload(kind = "control", control = GroupControl.REMOVE, group = GroupRef(gid, 1), member = GroupMember(me)))
            assertTrue(u.svc.group(gid)!!.isLeft)
            u.svc.receivePayloadForTest(alice, webLink(true, "Safari on Mac", GroupRef(gid, 1)))
            assertEquals(5, u.svc.texts(gid).size, "only the `was removed` row was added")
        } finally {
            u.close()
        }
    }

    @Test
    fun `sendWebLink to a group queues one control per recipient carrying group and epoch, nothing for a left group or an unknown thread`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload(epoch = 3))
            u.svc.sendWebLink(gid, true, "Chrome on Mac")
            val queued = u.svc.pendingGroupControlsForTest()
            assertEquals(listOf(alice, bob), queued.map { it.first }, "every recipient (the roster minus me), retried like a roster control")
            for ((_, p) in queued) {
                assertEquals("control", p.kind)
                assertEquals("web_link", p.control)
                assertEquals(true, p.linkUp)
                assertEquals("Chrome on Mac", p.browser)
                assertEquals(GroupRef(gid, 3), p.group)
            }
            u.svc.sendWebLink(gid, false, "stale label") // a label on the retraction is dropped (iOS `webLinkFrames`)
            val down = u.svc.pendingGroupControlsForTest().drop(2)
            assertEquals(listOf(alice, bob), down.map { it.first })
            assertTrue(down.all { it.second.linkUp == false && it.second.browser == null && it.second.group == GroupRef(gid, 3) }, "$down")
            // an unknown thread and a 1:1 peer while offline: no crash, nothing queued on the group lane
            u.svc.sendWebLink("22222222-0000-4000-8000-000000000002", true, "x")
            u.svc.sendWebLink(alice, true, "x")
            assertEquals(4, u.svc.pendingGroupControlCount())
            // removed from the group: a later notice (the unlink after a leave) is a no-op
            u.svc.receivePayloadForTest(alice, Wire.Payload(kind = "control", control = GroupControl.REMOVE, group = GroupRef(gid, 4), member = GroupMember(me)))
            u.svc.sendWebLink(gid, false, null)
            assertEquals(4, u.svc.pendingGroupControlCount())
        } finally {
            u.close()
        }
    }

    @Test
    fun `live - a group member and a 1 to 1 peer see the notice in the right thread, and only the announced ones`(): Unit = runBlocking {
        Live.requireRelay()
        val a = Live.makeService("wa")
        val b = Live.makeService("wb")
        val c = Live.makeService("wc")
        try {
            a.svc.register(a.username); b.svc.register(b.username); c.svc.register(c.username)
            a.svc.addContact(b.username); a.svc.addContact(c.username)
            c.svc.addContact(a.username) // the 1:1 notice is only filed for a known contact (as today)
            val g = a.svc.createGroup("ops", listOf(b.hash), GroupPolicy.ALERT)
            assertTrue(waitUntil { b.svc.group(g.id) != null }, "group_create must reach b")
            assertTrue(waitUntil { a.svc.pendingGroupControlCount() == 0 })

            // the browser opened #ops on a's phone → b files the notice in the group thread, naming a
            a.svc.sendWebLink(g.id, true, "Chrome on Mac")
            val linked = "── <${a.username}> linked a desktop browser (Chrome on Mac) — this channel may be viewed there ──"
            assertTrue(waitUntil { linked in b.svc.texts(g.id) }, "b: ${b.svc.texts(g.id)}")
            assertTrue(waitUntil { a.svc.pendingGroupControlCount() == 0 }, "confirmed by the relay like a roster control")
            assertEquals(emptyList(), b.svc.texts(a.hash), "never in b's 1:1 thread with a")
            assertEquals(emptyList(), c.svc.texts(a.hash), "c was not touched by the browser")

            // the browser opened c's thread → c gets today's 1:1 row (a bundle is fetched: no session yet)
            a.svc.sendWebLink(c.hash, true, "Chrome on Mac")
            assertTrue(waitUntil { "⚠ peer linked a desktop browser (Chrome on Mac) — this conversation may be viewed there" in c.svc.texts(a.hash) }, "c: ${c.svc.texts(a.hash)}")

            // unlink: only the announced group is told here
            a.svc.sendWebLink(g.id, false, null)
            assertTrue(waitUntil { "── <${a.username}> unlinked the desktop browser ──" in b.svc.texts(g.id) }, "b: ${b.svc.texts(g.id)}")
            delay(500)
            assertEquals(1, c.svc.texts(a.hash).size, "c was not told about the unlink")
            assertEquals(emptyList(), b.svc.texts(a.hash))
            assertTrue(waitUntil { a.svc.pendingGroupControlCount() == 0 })
            a.svc.goOffline(); b.svc.goOffline(); c.svc.goOffline()
        } finally {
            a.close(); b.close(); c.close()
        }
    }
}
