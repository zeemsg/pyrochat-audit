package com.pyrochat.service

import com.pyrochat.protocol.GroupControl
import com.pyrochat.protocol.GroupMember
import com.pyrochat.protocol.GroupRef
import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.GroupPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.BeforeEach
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/GROUPS.md §8 live scenario on the local dev relay: three real services form a group,
 * exchange text and an image, receipts aggregate to `READ 2/2`, a removal by a non-creator is
 * ignored, `burn_remove` drops the screenshotter everywhere, and the creator's `group_burn`
 * destroys the channel on all three phones.
 */
class GroupLiveTest {
    @BeforeEach
    fun requireRelay() = Live.requireRelay()

    private fun TestUser.flushed() = run { svc.store.flush(); reopen() }

    @Test
    fun `three services - create, text + image, READ 2 of 2, non-creator removal ignored, burn_remove, group_burn`(): Unit = runBlocking {
        val readOn: (com.pyrochat.store.model.Blob) -> com.pyrochat.store.model.Blob = { it.copy(settings = it.settings.copy(sendReadReceipts = true)) }
        val a = Live.makeService("ga", configure = readOn)
        val b = Live.makeService("gb", configure = readOn)
        val c = Live.makeService("gc", configure = readOn)
        try {
            a.svc.register(a.username); b.svc.register(b.username); c.svc.register(c.username)
            a.svc.addContact(b.username); a.svc.addContact(c.username)
            b.svc.debugRows = true; c.svc.debugRows = true

            // --- create: every member holds the same roster, policy and epoch
            val g = a.svc.createGroup("ops", listOf(b.hash, c.hash), GroupPolicy.BURN_REMOVE)
            assertTrue(waitUntil { b.svc.group(g.id) != null && c.svc.group(g.id) != null }, "group_create must reach both members")
            for (u in listOf(a, b, c)) {
                val local = u.svc.group(g.id)!!
                assertEquals("ops", local.name); assertEquals(a.hash, local.creator); assertEquals(0, local.epoch)
                assertEquals(GroupPolicy.BURN_REMOVE, local.policyKind)
                assertEquals(listOf(a.hash, b.hash, c.hash), local.members.map { it.hash })
                assertEquals(g.createdAt.epochSecond, local.createdAt.epochSecond)
            }
            assertEquals(listOf(a.hash, b.hash, c.hash).sorted(), GroupColours.joinOrder(b.svc.group(g.id)!!.members).map { it.hash }, "same joinedAt ⇒ ordered by hash")
            assertTrue(waitUntil { a.svc.pendingGroupControlCount() == 0 }, "every create control confirmed by the relay")

            // --- text: one row on a, one row on b and c attributed to a, aggregated READ 2/2 after both read
            a.svc.sendText("hello ops", g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]?.any { it.direction == Direction.OUTGOING && it.status == Status.SENT } == true }, "sent once both recipients' `sent` arrived")
            assertTrue(waitUntil { b.svc.messages.value[g.id]?.any { it.text == "hello ops" } == true && c.svc.messages.value[g.id]?.any { it.text == "hello ops" } == true })
            val mine = a.svc.messages.value[g.id]!!.first { it.direction == Direction.OUTGOING }
            for (u in listOf(b, c)) {
                val row = u.svc.messages.value[g.id]!!.first { it.text == "hello ops" }
                assertEquals(mine.id, row.id, "filed under the sender's row id")
                assertEquals(a.hash, row.sender); assertEquals(g.id, row.group)
                assertNull(u.svc.messages.value[a.hash]?.firstOrNull { it.text == "hello ops" }, "never in a 1:1 thread")
                assertEquals(1, u.svc.unreadCount(g.id))
            }
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.first { it.id == mine.id }.receipts?.size == 2 }, "delivered from both")
            assertEquals("DELIVERED 2/2", GroupReceipts.aggregate(Status.SENT, a.svc.messages.value[g.id]!!.first { it.id == mine.id }.receipts, 2, true))
            b.svc.noteRowVisible(mine.id, g.id); c.svc.noteRowVisible(mine.id, g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.first { it.id == mine.id }.receipts?.values?.all { it == "read" } == true }, "READ from both")
            assertEquals("READ 2/2", GroupReceipts.aggregate(Status.SENT, a.svc.messages.value[g.id]!!.first { it.id == mine.id }.receipts, 2, true))

            // --- a member's text reaches the creator and the other member with colours agreeing everywhere
            b.svc.sendText("ack from b", g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]?.any { it.text == "ack from b" && it.sender == b.hash } == true && c.svc.messages.value[g.id]?.any { it.text == "ack from b" } == true })
            val colourOnA = GroupColours.colour(a.svc.group(g.id)!!.members, b.hash)
            assertEquals(colourOnA, GroupColours.colour(c.svc.group(g.id)!!.members, b.hash))
            assertNotNull(colourOnA)

            // --- image: the blob pipeline once per recipient, same plaintext, one sidecar each
            val jpeg = ByteArray(150_000).also { Random(7).nextBytes(it) }
            a.svc.sendImage(jpeg, 640, 480, g.id)
            assertTrue(waitUntil(20_000) { listOf(b, c).all { u -> u.svc.messages.value[g.id]?.any { it.kind == Kind.IMAGE } == true } }, "image on both")
            for (u in listOf(b, c)) {
                val img = u.svc.messages.value[g.id]!!.first { it.kind == Kind.IMAGE }
                assertEquals(a.hash, img.sender); assertEquals(640, img.imageWidth)
                assertTrue(jpeg.contentEquals(u.svc.attachmentData(img)), "byte-identical plaintext")
            }
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.first { it.kind == Kind.IMAGE }.status == Status.SENT })
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.first { it.kind == Kind.IMAGE }.receipts?.size == 2 })

            // --- a removal from a non-creator is ignored everywhere (b forges the control)
            b.svc.sendControlForTest(a.hash, Wire.Payload(kind = "control", control = GroupControl.REMOVE, group = GroupRef(g.id, 1), member = GroupMember(c.hash)))
            b.svc.sendControlForTest(c.hash, Wire.Payload(kind = "control", control = GroupControl.REMOVE, group = GroupRef(g.id, 1), member = GroupMember(c.hash)))
            assertTrue(waitUntil { c.svc.messages.value[g.id]?.any { it.text.startsWith("── ignored a removal from") } == true }, "debug builds log the ignored removal")
            delay(500)
            assertTrue(a.svc.group(g.id)!!.isMember(c.hash)); assertTrue(c.svc.group(g.id)!!.isMember(c.hash))
            assertEquals(0, a.svc.group(g.id)!!.epoch)

            // --- burn_remove: c screenshots → a and b drop c; c's own copy burns and turns read-only
            c.svc.reportCapture("screenshot", g.id)
            assertTrue(waitUntil { !a.svc.group(g.id)!!.isMember(c.hash) && !b.svc.group(g.id)!!.isMember(c.hash) }, "offender dropped on every other phone")
            for (u in listOf(a, b)) {
                val texts = u.svc.messages.value[g.id]!!.filter { it.kind == Kind.SYSTEM }.map { it.text }
                assertTrue("⚠ <${c.username}> took a screenshot" in texts, texts.toString())
                assertTrue("── <${c.username}> was removed for taking a screenshot ──" in texts, texts.toString())
                assertEquals(1, u.svc.group(g.id)!!.epoch)
            }
            assertTrue(c.svc.group(g.id)!!.isLeft)
            assertEquals(listOf(c.hash), a.svc.group(g.id)!!.former!!.map { it.hash }, "the offender keeps a colour slot in `former`")
            assertEquals(GroupColours.colour(a.svc.group(g.id)!!.colourRoster, b.hash), GroupColours.colour(b.svc.group(g.id)!!.colourRoster, b.hash), "nobody recolours")
            assertEquals(listOf("── this channel burned: you took a screenshot ──"), c.svc.messages.value[g.id]!!.map { it.text })
            c.svc.sendText("still there?", g.id)
            assertEquals(GroupRules.ERR_NOT_IN_GROUP, c.svc.lastError.value)
            a.svc.sendText("after the removal", g.id)
            assertTrue(waitUntil { b.svc.messages.value[g.id]?.any { it.text == "after the removal" } == true })
            delay(1_000)
            assertFalse(c.svc.messages.value[g.id]!!.any { it.text == "after the removal" }, "a removed member is no longer a recipient")

            // --- group_burn by the creator: destroyed on all three, flash on the receivers
            val flashB = async { withTimeout(15_000) { b.svc.flashes.first() } }
            delay(100) // subscribe before the burn can land
            a.svc.burnGroup(g.id)
            assertNull(a.svc.group(g.id)); assertNull(a.svc.messages.value[g.id])
            assertTrue(waitUntil { b.svc.group(g.id) == null && c.svc.group(g.id) == null }, "group_burn reaches every member and the removed phone")
            assertEquals("#ops was burned by the creator", flashB.await())
            assertNull(b.svc.messages.value[g.id]); assertNull(c.svc.messages.value[g.id])
            assertTrue(waitUntil { a.svc.pendingGroupControlCount() == 0 })
            for (u in listOf(a, b, c)) {
                u.svc.goOffline()
                u.svc.store.flush()
                val stored = u.reopen()
                assertNull(stored.groups, "${u.username}: nothing left on disk")
                assertNull(stored.messages[g.id])
            }
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun `fan-out partial failure - a deregistered member is gone, the row still sends, retry targets only failures`(): Unit = runBlocking {
        val a = Live.makeService("ga")
        val b = Live.makeService("gb")
        val c = Live.makeService("gc")
        try {
            a.svc.register(a.username); b.svc.register(b.username); c.svc.register(c.username)
            a.svc.addContact(b.username); a.svc.addContact(c.username)
            val g = a.svc.createGroup("fan", listOf(b.hash, c.hash))
            assertTrue(waitUntil { b.svc.group(g.id) != null && c.svc.group(g.id) != null })
            // c deletes their account: the relay answers unknown_user for c from now on
            c.svc.deregister()
            c.svc.goOffline()
            delay(500)
            a.svc.sendText("who is left?", g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.any { it.text == "who is left?" && it.status == Status.SENT } }, "unknown_user drops the member instead of failing the row")
            assertTrue(waitUntil { a.svc.goneMembers.value[g.id]?.contains(c.hash) == true })
            assertTrue(a.svc.messages.value[g.id]!!.any { it.text == "── <${c.username}> deleted their account ──" })
            assertTrue(a.svc.group(g.id)!!.isMember(c.hash), "gone members keep their roster slot (and colour)")
            assertTrue(a.svc.group(g.id)!!.member(c.hash)!!.isGone)
            assertTrue(a.flushed().groups!!.single().member(c.hash)!!.isGone, "`gone` is persisted")
            // the gone member leaves the denominator: DELIVERED 1/1 once b got it
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.first { it.text == "who is left?" }.receipts?.containsKey(b.hash) == true })
            val live = a.svc.group(g.id)!!.members.count { it.hash != a.hash && !it.isGone }
            assertEquals("DELIVERED 1/1", GroupReceipts.aggregate(Status.SENT, a.svc.messages.value[g.id]!!.first { it.text == "who is left?" }.receipts, live, true))
            assertTrue(waitUntil { b.svc.messages.value[g.id]?.any { it.text == "who is left?" } == true })
            // a second text never even addresses c
            a.svc.sendText("only b now", g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.any { it.text == "only b now" && it.status == Status.SENT } })
            assertEquals(1, a.svc.messages.value[g.id]!!.count { it.text.contains("deleted their account") })

            // partial failure + retry: b goes away mid-flight (socket closed) → the row fails, retry re-sends to b only
            a.svc.goOffline()
            delay(300)
            a.svc.sendText("offline attempt", g.id)
            assertTrue(waitUntil { a.svc.messages.value[g.id]!!.any { it.text == "offline attempt" && it.status == Status.FAILED } }, "no link ⇒ failed")
            a.svc.goOnline()
            assertTrue(waitUntil(15_000) { a.svc.messages.value[g.id]!!.any { it.text == "offline attempt" && it.status == Status.SENT } }, "auto-retry on reconnect")
            assertTrue(waitUntil { b.svc.messages.value[g.id]?.count { it.text == "offline attempt" } == 1 }, "exactly one copy at b")
            a.svc.goOffline(); b.svc.goOffline()
        } finally {
            a.close(); b.close(); c.close()
        }
    }
}
