package com.pyrochat.service

import com.pyrochat.protocol.GroupControl
import com.pyrochat.protocol.GroupMember
import com.pyrochat.protocol.GroupRef
import com.pyrochat.protocol.Wire
import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.Group
import com.pyrochat.store.model.GroupPolicy
import com.pyrochat.store.model.Member
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/GROUPS.md §2 / §6 / §8 — the roster state machine, authority rules, epochs, screenshot
 * policies, receipts and delete-for-all, driven OFFLINE through the decrypted-payload hook
 * (`receivePayloadForTest`): the session identity is what a control is attributed to.
 */
class GroupRosterTest {
    private val me = "1".repeat(64)
    private val alice = "a".repeat(64) // the creator in most scenarios
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)
    private val gid = "0f9d2c44-1111-4222-8333-444455556666"
    private val t0 = Instant.parse("2026-09-18T12:00:00Z")

    private fun makeUser(configure: (com.pyrochat.store.model.Blob) -> com.pyrochat.store.model.Blob = { it }) = Live.makeService("gr") { b ->
        configure(
            b.copy(
                usernameDisplay = "me", usernameHash = me,
                contacts = listOf(Contact(alice, "alice", t0), Contact(bob, "bob", t0), Contact(carol, "carol", t0)),
            ),
        )
    }.also { it.svc.requireLinkForRosterOps = false } // the state machine is exercised offline

    private fun wm(hash: String, name: String, at: Instant = t0) = GroupMember(hash, name, SwiftDate.toSeconds(at))

    private fun createPayload(creator: String = alice, members: List<GroupMember> = listOf(wm(alice, "alice"), wm(me, "me"), wm(bob, "bob")), control: String = GroupControl.CREATE, epoch: Int = 0, policy: String = "burn_remove") =
        Wire.Payload(
            kind = "control", control = control, group = GroupRef(gid, epoch), name = "ops", creator = creator,
            members = members, policy = policy, createdAt = SwiftDate.toSeconds(t0),
        )

    private fun ctl(control: String, epoch: Int, member: GroupMember? = null, name: String? = null, policy: String? = null, burnSeconds: Double? = null, refIds: List<String>? = null, receipt: String? = null) =
        Wire.Payload(kind = "control", control = control, group = GroupRef(gid, epoch), member = member, name = name, policy = policy, burnSeconds = burnSeconds, refIds = refIds, receipt = receipt)

    /** The store as a cold open would see it — flushed first (the save queue is asynchronous). */
    private fun TestUser.flushed() = run { svc.store.flush(); reopen() }

    private fun ChatService.rows() = messages.value[gid] ?: emptyList()
    private fun ChatService.systemTexts() = rows().filter { it.kind == Kind.SYSTEM }.map { it.text }
    private fun ChatService.g() = assertNotNull(group(gid))

    @Test
    fun `group_create from its creator creates the group, from anyone else it is ignored`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(bob, createPayload(creator = alice)) // creator field ≠ session identity
            assertNull(u.svc.group(gid), "creator must equal the sender's hash")
            u.svc.receivePayloadForTest(alice, createPayload(members = listOf(wm(alice, "alice"), wm(bob, "bob"))))
            assertNull(u.svc.group(gid), "a roster without me is not mine")
            u.svc.receivePayloadForTest(alice, createPayload(policy = "quarantine"))
            assertEquals(GroupPolicy.ALERT, u.svc.g().policyKind, "an unknown policy word reads as alert (agreed with iOS)")
            u.svc.deleteGroup(gid)

            u.svc.receivePayloadForTest(alice, createPayload())
            val g = u.svc.g()
            assertEquals("ops", g.name); assertEquals(alice, g.creator); assertEquals(0, g.epoch)
            assertEquals(GroupPolicy.BURN_REMOVE, g.policyKind); assertEquals(t0, g.createdAt)
            assertNull(g.former)
            assertEquals(listOf(alice, me, bob), g.members.map { it.hash })
            assertEquals(listOf("alice", "me", "bob"), g.members.map { it.name })
            assertEquals(emptyList(), u.svc.rows(), "the thread exists, empty")
            assertTrue(u.svc.isGroup(gid))
            // idempotent by id: a second create (even a different roster) changes nothing
            u.svc.receivePayloadForTest(alice, createPayload(members = listOf(wm(alice, "alice"), wm(me, "me")), epoch = 9))
            assertEquals(3, u.svc.g().members.size)
            assertEquals(0, u.svc.g().epoch)
            // persisted with the spec keys
            val stored = u.flushed().groups!!.single()
            assertEquals(g, stored)
        } finally {
            u.close()
        }
    }

    @Test
    fun `group_invite must come from a roster member and only refreshes a copy we had left`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(dave, createPayload(control = GroupControl.INVITE, epoch = 3))
            assertNull(u.svc.group(gid), "an invite from someone outside the roster it carries is ignored")
            u.svc.receivePayloadForTest(bob, createPayload(control = GroupControl.INVITE, epoch = 3)) // bob (a member, not the creator) added me
            assertEquals(3, u.svc.g().epoch)
            assertEquals(alice, u.svc.g().creator)
            // an existing member never lets an invite rewrite the roster
            u.svc.receivePayloadForTest(bob, createPayload(control = GroupControl.INVITE, epoch = 7, members = listOf(wm(bob, "bob"), wm(me, "me"))))
            assertEquals(3, u.svc.g().members.size)
            // removed by the creator → left (read-only) … re-added → the invite refreshes the roster and clears `left`
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.REMOVE, 4, member = GroupMember(me)))
            assertTrue(u.svc.g().isLeft)
            assertEquals(listOf(alice, bob), u.svc.g().members.map { it.hash })
            assertEquals("── <me> was removed ──", u.svc.systemTexts().last())
            u.svc.sendText("hello?", gid)
            assertEquals(GroupRules.ERR_NOT_IN_GROUP, u.svc.lastError.value)
            assertTrue(u.svc.rows().none { it.direction == Direction.OUTGOING })
            u.svc.receivePayloadForTest(alice, createPayload(control = GroupControl.INVITE, epoch = 6, members = listOf(wm(alice, "alice"), wm(bob, "bob"), wm(me, "me", t0.plusSeconds(60)))))
            assertFalse(u.svc.g().isLeft)
            assertEquals(6, u.svc.g().epoch)
            assertEquals(3, u.svc.g().members.size)
        } finally {
            u.close()
        }
    }

    @Test
    fun `add remove leave rename policy - epochs, authority, system rows`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.debugRows = true
            u.svc.receivePayloadForTest(alice, createPayload())
            // add: any member may; a non-member may not
            u.svc.receivePayloadForTest(dave, ctl(GroupControl.ADD, 1, member = wm(carol, "carol", t0.plusSeconds(10))))
            assertEquals(3, u.svc.g().members.size)
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.ADD, 1, member = wm(carol, "carol", t0.plusSeconds(10))))
            assertEquals(listOf(alice, me, bob, carol), u.svc.g().members.map { it.hash })
            assertEquals(1, u.svc.g().epoch)
            assertEquals(listOf("── <carol> joined ──"), u.svc.systemTexts())
            assertEquals(t0.plusSeconds(10), u.svc.g().member(carol)!!.joinedAt)
            // a name that does not survive sanitizing becomes anon-<hash>
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.ADD, 2, member = GroupMember(dave, "<‮>", SwiftDate.toSeconds(t0))))
            assertEquals("anon-dddddddd", u.svc.g().member(dave)!!.name)
            // remove: creator only — a member's attempt is ignored (debug row), the creator's applies
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.REMOVE, 3, member = GroupMember(carol)))
            assertTrue(u.svc.g().isMember(carol))
            assertEquals("── ignored a removal from <bob> (not the creator) ──", u.svc.systemTexts().last())
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.REMOVE, 3, member = GroupMember(carol)))
            assertFalse(u.svc.g().isMember(carol))
            assertEquals(3, u.svc.g().epoch)
            assertEquals("── <carol> was removed ──", u.svc.systemTexts().last())
            assertEquals(listOf(Member(carol, "carol", t0.plusSeconds(10))), u.svc.g().former, "removed members keep their original joinedAt in `former`")
            assertEquals("carol", u.svc.memberName(u.svc.g(), carol))
            assertEquals(listOf(carol), u.svc.formerMembers.value[gid]!!.map { it.hash })
            // colours: the removed member keeps slot 4 (alice, me, bob and dave joined at t0; carol at t0+10)
            assertEquals(4, GroupColours.joinIndex(u.svc.g().colourRoster, carol))
            // leave
            u.svc.receivePayloadForTest(dave, ctl(GroupControl.LEAVE, 4))
            assertFalse(u.svc.g().isMember(dave))
            assertEquals("── <anon-dddddddd> left ──", u.svc.systemTexts().last())
            assertEquals(4, u.svc.g().epoch)
            // rename / policy: creator only
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.RENAME, 5, name = "hijack"))
            assertEquals("ops", u.svc.g().name)
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.RENAME, 5, name = "  #ops2 "))
            assertEquals("ops2", u.svc.g().name)
            assertEquals("── renamed to #ops2 ──", u.svc.systemTexts().last())
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 6, policy = "alert", burnSeconds = 60.0))
            assertEquals(GroupPolicy.ALERT, u.svc.g().policyKind)
            assertEquals(60.0, u.svc.g().burnSeconds)
            assertEquals("── screenshot policy: alert ──", u.svc.systemTexts().last())
            val rowsBefore = u.svc.rows().size
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 7, policy = "alert", burnSeconds = 0.0))
            assertEquals(0.0, u.svc.g().burnSeconds)
            assertEquals(rowsBefore, u.svc.rows().size, "a burn-default-only change adds no row")
            assertEquals(7, u.svc.g().epoch)
            // per-member last-writer: a STALE add of carol (epoch 2 < the removal's 3) is ignored…
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.ADD, 2, member = wm(carol, "carol", t0.plusSeconds(99))))
            assertFalse(u.svc.g().isMember(carol))
            // …a fresh one applies, bumps the epoch, and the re-added member keeps her EARLIEST join (and slot)
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.ADD, 8, member = wm(carol, "carol", t0.plusSeconds(99))))
            assertEquals(8, u.svc.g().epoch)
            assertEquals(listOf(alice, me, bob, carol), u.svc.g().members.map { it.hash })
            assertEquals(t0.plusSeconds(10), u.svc.g().member(carol)!!.joinedAt)
            assertEquals(listOf(dave), u.svc.g().former!!.map { it.hash }, "back in the roster ⇒ out of former (dave, who left, stays)")
            assertEquals(4, GroupColours.joinIndex(u.svc.g().colourRoster, carol))
            // every group system row names its group and the whole thing persists
            assertTrue(u.svc.rows().all { it.group == gid })
            assertEquals(u.svc.g(), u.flushed().groups!!.single())
        } finally {
            u.close()
        }
    }

    @Test
    fun `concurrent roster changes - last writer by (epoch, senderHash) per member, rename and policy unconditional`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload(members = listOf(wm(alice, "alice"), wm(me, "me"), wm(bob, "bob"), wm(carol, "carol"))))
            // the creator removes bob at epoch 5 …
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.REMOVE, 5, member = GroupMember(bob)))
            assertFalse(u.svc.g().isMember(bob))
            // … carol's concurrent add at the same epoch wins by hash ("c" > "a") in either arrival order
            u.svc.receivePayloadForTest(carol, ctl(GroupControl.ADD, 5, member = wm(bob, "bob")))
            assertTrue(u.svc.g().isMember(bob))
            // a re-delivered copy of the older stamp changes nothing
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.REMOVE, 5, member = GroupMember(bob)))
            assertTrue(u.svc.g().isMember(bob))
            // a newer epoch does
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.REMOVE, 6, member = GroupMember(bob)))
            assertFalse(u.svc.g().isMember(bob))
            // rename / policy: single-writer, applied in arrival order regardless of epoch
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.RENAME, 1, name = "one"))
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.RENAME, 0, name = "zero"))
            assertEquals("zero", u.svc.g().name)
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 0, policy = "burn"))
            assertEquals(GroupPolicy.BURN, u.svc.g().policyKind)
        } finally {
            u.close()
        }
    }

    @Test
    fun `screenshot policies on the receiving phone`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload(policy = "alert"))
            u.svc.receivePayloadForTest(bob, ctl("screenshot", 0))
            assertEquals(listOf("⚠ <bob> took a screenshot"), u.svc.systemTexts())
            assertEquals(0, u.svc.unreadCount(gid), "system rows never count as unread (agreed with iOS)")
            assertFalse(u.svc.needsVisibilityReport(u.svc.rows().single()))
            assertTrue(u.svc.g().isMember(bob), "alert: nothing else")
            // a screen recording has no group semantics
            u.svc.receivePayloadForTest(bob, ctl("screen_record", 0))
            assertEquals(1, u.svc.rows().size)
            // burn (for the screenshotter) behaves like alert on OTHER phones
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 1, policy = "burn"))
            u.svc.receivePayloadForTest(bob, ctl("screenshot", 1))
            assertTrue(u.svc.g().isMember(bob))
            // burn_remove: drop M from the roster, epoch+1, no control needed
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 2, policy = "burn_remove"))
            val epoch = u.svc.g().epoch
            u.svc.receivePayloadForTest(bob, ctl("screenshot", epoch))
            assertFalse(u.svc.g().isMember(bob))
            assertEquals(epoch + 1, u.svc.g().epoch)
            assertEquals(listOf("⚠ <bob> took a screenshot", "── <bob> was removed for taking a screenshot ──"), u.svc.systemTexts().takeLast(2))
            assertEquals(listOf(bob), u.svc.g().former!!.map { it.hash })
            assertEquals(t0, u.svc.g().former!!.single().joinedAt)
            // a second screenshot control from the (now former) member is not a member's and changes nothing
            val epochAfter = u.svc.g().epoch
            u.svc.receivePayloadForTest(bob, ctl("screenshot", epochAfter))
            assertEquals(epochAfter, u.svc.g().epoch)
            // a stranger's screenshot control is meaningless
            val n = u.svc.rows().size
            u.svc.receivePayloadForTest(dave, ctl("screenshot", epoch))
            assertEquals(n, u.svc.rows().size)
            // and a removed member's text still shows, attributed to the session (the UI renders `<bob>?`)
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "still here?", group = GroupRef(gid, 1), refId = "6d000000-0000-4000-8000-000000000001"))
            val late = u.svc.rows().last()
            assertEquals(bob, late.sender); assertEquals(gid, late.group); assertEquals("6d000000-0000-4000-8000-000000000001", late.id)
        } finally {
            u.close()
        }
    }

    @Test
    fun `screenshot policies on the offender's phone`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload(policy = "alert"))
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "secret", group = GroupRef(gid, 0)))
            u.svc.reportCapture("screenshot", gid)
            assertEquals(2, u.svc.pendingGroupControlCount(), "screenshot + group queued for both other members")
            assertEquals(1, u.svc.rows().size, "alert: nothing else happens locally")
            u.svc.reportCapture("screen_record", gid)
            assertEquals(2, u.svc.pendingGroupControlCount(), "a screen recording is not fanned out to a group")
            // burn: wipe rows + the burned row
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 1, policy = "burn"))
            u.svc.reportCapture("screenshot", gid)
            assertEquals(listOf("── this channel burned: you took a screenshot ──"), u.svc.rows().map { it.text })
            assertFalse(u.svc.g().isLeft)
            // burn_remove: as burn, plus left
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.POLICY, 2, policy = "burn_remove"))
            u.svc.reportCapture("screenshot", gid)
            assertTrue(u.svc.g().isLeft)
            assertEquals(listOf("── this channel burned: you took a screenshot ──"), u.svc.rows().map { it.text })
            assertTrue(u.flushed().groups!!.single().isLeft)
        } finally {
            u.close()
        }
    }

    @Test
    fun `group_burn destroys rows roster and drafts, only from the creator, with a home flash`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload())
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "hi", group = GroupRef(gid, 0)))
            u.svc.setDraft("wip", gid)
            u.svc.receivePayloadForTest(bob, ctl(GroupControl.BURN, 0))
            assertNotNull(u.svc.group(gid), "a burn from anyone but the creator is ignored")
            val flash = async { withTimeout(5_000) { u.svc.flashes.first() } }
            delay(100) // the collector must be subscribed before the burn lands (no replay by design)
            u.svc.receivePayloadForTest(alice, ctl(GroupControl.BURN, 0))
            assertNull(u.svc.group(gid))
            assertNull(u.svc.messages.value[gid])
            assertEquals("", u.svc.draft(gid))
            assertEquals("#ops was burned by the creator", flash.await())
            val stored = u.flushed()
            assertNull(stored.groups); assertNull(stored.messages[gid]); assertNull(stored.drafts)
        } finally {
            u.close()
        }
    }

    @Test
    fun `incoming group text - sender attribution, unread, dedupe, unknown group dropped`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "??", group = GroupRef(gid, 0)))
            assertNull(u.svc.messages.value[gid], "a message for a group we never joined is dropped")
            u.svc.receivePayloadForTest(alice, createPayload())
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "hi all", senderName = "bob", burnAfter = 20.0, group = GroupRef(gid, 0), refId = "6d000000-0000-4000-8000-000000000007"), id = "relay-id-1")
            val row = u.svc.rows().single()
            assertEquals("6d000000-0000-4000-8000-000000000007", row.id, "filed under the sender's row id")
            assertEquals(bob, row.sender); assertEquals(gid, row.group); assertEquals(20.0, row.burnAfter); assertEquals(Status.DELIVERED, row.status)
            assertEquals(1, u.svc.unreadCount(gid))
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "hi all", group = GroupRef(gid, 0), refId = "6d000000-0000-4000-8000-000000000007"), id = "relay-id-2")
            assertEquals(1, u.svc.rows().size, "dedupe by the logical id")
            assertNull(u.svc.messages.value[bob], "no 1:1 thread and no contact side effects")
            // reading it: local READ + a burn start, both as group receipts to the SENDER
            u.svc.noteRowVisible(row.id, gid)
            val read = u.svc.rows().single()
            assertEquals(Status.READ, read.status); assertNotNull(read.burnStartedAt)
            assertEquals(0, u.svc.unreadCount(gid))
            assertNull(u.svc.store.blob.receiptOutbox?.read?.get(bob), "group receipts never enter the 1:1 outbox lanes")
        } finally {
            u.close()
        }
    }

    @Test
    fun `receipts aggregate per member and never downgrade, delete_msg is author-only`(): Unit = runBlocking {
        val mine = "6d000000-0000-4000-8000-000000000042"
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload())
            u.svc.store.update {
                it.copy(messages = it.messages + (gid to listOf(
                    ChatMessage(mine, Direction.OUTGOING, Kind.TEXT, "mine", t0, Status.SENT, burnAfter = 30.0, group = gid, receipts = emptyMap()),
                )))
            }
            val svc = ChatService(u.svc.store)
            try {
                svc.receivePayloadForTest(bob, ctl("receipt", 0, receipt = "delivered", refIds = listOf(mine)))
                assertEquals(mapOf(bob to "delivered"), svc.rows().single().receipts)
                svc.receivePayloadForTest(alice, ctl("receipt", 0, receipt = "read", refIds = listOf(mine)))
                assertEquals(mapOf(bob to "delivered", alice to "read"), svc.rows().single().receipts)
                svc.receivePayloadForTest(alice, ctl("receipt", 0, receipt = "delivered", refIds = listOf(mine)))
                assertEquals("read", svc.rows().single().receipts!![alice], "no downgrade")
                assertEquals("READ 1/2", GroupReceipts.aggregate(Status.SENT, svc.rows().single().receipts, 2, true))
                // a stranger's receipt touches nothing; a receipt without `group` still resolves through the roster
                svc.receivePayloadForTest(dave, ctl("receipt", 0, receipt = "read", refIds = listOf(mine)))
                assertEquals(2, svc.rows().single().receipts!!.size)
                svc.receivePayloadForTest(bob, Wire.Payload(kind = "control", control = "receipt", receipt = "read", refIds = listOf(mine)))
                assertEquals("read", svc.rows().single().receipts!![bob])
                // synced burn: the first reader's burn_start starts my countdown
                assertNull(svc.rows().single().burnStartedAt)
                svc.receivePayloadForTest(bob, ctl("burn_start", 0, refIds = listOf(mine)))
                assertNotNull(svc.rows().single().burnStartedAt)
                assertEquals(Status.SENT, svc.rows().single().status, "the fan-out status word is not touched by receipts")
                // delete for all: bob may retract his own row, never mine
                svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "oops", group = GroupRef(gid, 0), refId = "6d000000-0000-4000-8000-000000000099"))
                svc.receivePayloadForTest(alice, ctl("delete_msg", 0, refIds = listOf("6d000000-0000-4000-8000-000000000099", mine)))
                assertEquals(2, svc.rows().size, "alice is not the author of either row")
                svc.receivePayloadForTest(bob, ctl("delete_msg", 0, refIds = listOf("6d000000-0000-4000-8000-000000000099", mine)))
                assertEquals(listOf(mine), svc.rows().map { it.id })
                assertEquals(svc.rows(), svc.store.blob.messages[gid])
            } finally {
                svc.close()
            }
        } finally {
            u.close()
        }
    }

    @Test
    fun `local actions - createGroup, add beyond 20, creator-only remove, leave, typing per member`(): Unit = runBlocking {
        val u = makeUser()
        try {
            // roster ops need the link up (agreed with iOS) — the rule is switched off for the rest of this test
            u.svc.requireLinkForRosterOps = true
            assertEquals(GroupRules.ERR_LINK_DOWN, assertFailsWith<ServiceError> { u.svc.createGroup("ops", listOf(alice)) }.code)
            u.svc.requireLinkForRosterOps = false
            val g = u.svc.createGroup("  #ops ", listOf(alice, bob), GroupPolicy.BURN_REMOVE, burnSeconds = 60.0)
            assertEquals("ops", g.name); assertEquals(me, g.creator); assertEquals(0, g.epoch); assertEquals(60.0, g.burnSeconds)
            assertEquals(listOf(me, alice, bob), g.members.map { it.hash })
            assertEquals(listOf("me", "alice", "bob"), g.members.map { it.name })
            assertTrue(g.members.all { it.joinedAt == g.createdAt })
            assertEquals(2, u.svc.pendingGroupControlCount(), "group_create to each initial member")
            assertEquals(emptyList(), u.svc.messages.value[g.id])
            assertEquals(60.0, u.svc.groupBurnDefault(g))
            assertFailsWith<ServiceError> { u.svc.createGroup("", listOf(alice)) }
            assertEquals(GroupRules.ERR_GROUP_FULL, assertFailsWith<ServiceError> { u.svc.createGroup("big", (0 until 20).map { "%02x".format(0x20 + it).repeat(32) }) }.code)
            // add: any member, up to 20
            u.svc.addMember(g.id, carol)
            assertEquals(4, u.svc.group(g.id)!!.members.size); assertEquals(1, u.svc.group(g.id)!!.epoch)
            assertEquals("── <carol> joined ──", u.svc.messages.value[g.id]!!.last().text)
            assertEquals(2 + 3, u.svc.pendingGroupControlCount(), "group_add to the 2 existing members + group_invite to carol")
            for (i in 4 until 20) u.svc.addMember(g.id, "%02x".format(0x20 + i).repeat(32))
            assertEquals(20, u.svc.group(g.id)!!.members.size)
            assertEquals(GroupRules.ERR_GROUP_FULL, assertFailsWith<ServiceError> { u.svc.addMember(g.id, dave) }.code)
            // remove: creator (me) may
            u.svc.removeMember(g.id, carol)
            assertFalse(u.svc.group(g.id)!!.isMember(carol))
            assertEquals("── <carol> was removed ──", u.svc.messages.value[g.id]!!.last().text)
            assertEquals(listOf(carol), u.svc.group(g.id)!!.former!!.map { it.hash })
            u.svc.addMember(g.id, carol) // re-added: earliest join kept, out of former
            assertNull(u.svc.group(g.id)!!.former)
            u.svc.removeMember(g.id, carol)
            // a group I did not create: removal refused with the verbatim error
            u.svc.receivePayloadForTest(alice, createPayload())
            assertEquals(GroupRules.ERR_ONLY_CREATOR, assertFailsWith<ServiceError> { u.svc.removeMember(gid, bob) }.code)
            // rename + policy locally
            u.svc.renameGroup(g.id, "ops2")
            assertEquals("ops2", u.svc.group(g.id)!!.name)
            u.svc.setGroupPolicy(g.id, GroupPolicy.ALERT, null)
            assertEquals("── screenshot policy: alert ──", u.svc.messages.value[g.id]!!.last().text)
            assertEquals(GroupPolicy.ALERT, u.svc.group(g.id)!!.policyKind)
            assertNull(u.svc.groupBurnDefault(u.svc.group(g.id)!!))
            // typing per member: two members typing → both listed
            u.svc.receivePayloadForTest(alice, ctl("typing", 0))
            u.svc.receivePayloadForTest(bob, ctl("typing", 0))
            assertEquals(setOf(alice, bob), u.svc.groupTyping.value[gid]!!.keys)
            u.svc.receivePayloadForTest(dave, ctl("typing", 0))
            assertEquals(setOf(alice, bob), u.svc.groupTyping.value[gid]!!.keys, "non-members never type here")
            u.svc.receivePayloadForTest(bob, Wire.Payload(kind = "text", text = "sent", group = GroupRef(gid, 0)))
            assertEquals(setOf(alice), u.svc.groupTyping.value[gid]!!.keys, "a text clears its sender's typing")
            // leave the group alice created
            u.svc.leaveGroup(gid)
            assertTrue(u.svc.group(gid)!!.isLeft)
            assertFalse(u.svc.group(gid)!!.isMember(me))
            assertEquals(listOf(me), u.svc.group(gid)!!.former!!.map { it.hash })
            assertEquals("── <me> left ──", u.svc.messages.value[gid]!!.last().text)
            assertEquals(GroupRules.ERR_NOT_IN_GROUP, assertFailsWith<ServiceError> { u.svc.addMember(gid, dave) }.code)
            // delete the left group locally; the one I created stays
            u.svc.deleteGroup(gid)
            assertNull(u.svc.group(gid)); assertNull(u.svc.messages.value[gid])
            assertEquals(listOf(g.id), u.flushed().groups!!.map { it.id })
            // burn my own group: gone locally, controls queued for every member
            val before = u.svc.pendingGroupControlCount()
            u.svc.burnGroup(g.id)
            assertNull(u.svc.group(g.id))
            assertTrue(u.svc.pendingGroupControlCount() > before)
            assertNull(u.flushed().groups)
        } finally {
            u.close()
        }
    }

    @Test
    fun `mixed store - a 1 to 1 thread and a group thread coexist and the 1 to 1 path is unchanged`(): Unit = runBlocking {
        val u = makeUser()
        try {
            u.svc.receivePayloadForTest(alice, createPayload())
            u.svc.receivePayloadForTest(alice, Wire.Payload(kind = "text", text = "direct", senderName = "alice"))
            u.svc.receivePayloadForTest(alice, Wire.Payload(kind = "text", text = "in the group", group = GroupRef(gid, 0)))
            val direct = u.svc.messages.value[alice]!!.single()
            assertNull(direct.group); assertNull(direct.sender); assertNull(direct.receipts)
            val grouped = u.svc.rows().single()
            assertEquals(alice, grouped.sender); assertEquals(gid, grouped.group)
            assertEquals(2, u.svc.totalUnread)
            assertEquals(listOf("direct"), u.svc.store.blob.receiptOutbox!!.delivered[alice]?.map { u.svc.messages.value[alice]!!.first { m -> m.id == it }.text })
            val stored = u.flushed()
            assertEquals(1, stored.groups!!.size)
            assertEquals(setOf(alice, gid), stored.messages.keys)
        } finally {
            u.close()
        }
    }

    @Test
    fun `store round trip - a group written by the service decodes with every spec key`(): Unit = runBlocking {
        val u = makeUser()
        try {
            val g = u.svc.createGroup("ops", listOf(alice, bob))
            u.svc.receivePayloadForTest(alice, Wire.Payload(kind = "text", text = "yo", group = GroupRef(g.id, 0), refId = "6d000000-0000-4000-8000-000000000005"))
            u.svc.store.flush()
            val blob = u.flushed()
            val stored = blob.groups!!.single()
            // Instant.now() carries nanoseconds the Swift double cannot: compare at the codec's precision
            val at = SwiftDate.toInstant(SwiftDate.toSeconds(g.createdAt))
            assertEquals(Group(g.id, "ops", me, at, 0, "burn_remove", null, listOf(Member(me, "me", at), Member(alice, "alice", at), Member(bob, "bob", at)), null), stored)
            val row = blob.messages[g.id]!!.single()
            assertEquals(g.id, row.group); assertEquals(alice, row.sender); assertNull(row.receipts)
        } finally {
            u.close()
        }
    }
}
