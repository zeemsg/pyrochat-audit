package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/GROUPS.md §5 / §7 rendering rules over the UI models — pure, no Android. */
class GroupUiTest {
    private val t0 = 1_700_000_000_000L
    private val me = "1".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val group = UiGroup(
        id = "g1", name = "ops", creator = me, createdAt = t0, epoch = 2, policy = "burn_remove",
        members = listOf(UiMember(me, "me", t0), UiMember(alice, "alice", t0), UiMember(bob, "bob", t0 + 1000)),
    )
    private fun row(dir: Direction, sender: String? = null, text: String = "hi", receipts: Map<String, String>? = null, status: Status = Status.sent, kind: Kind = Kind.text) =
        ChatMessage("m", dir, kind, text, t0, status, group = "g1", sender = sender, receipts = receipts)

    @Test fun titleAndJoinOrderColours() {
        assertEquals("#ops · 3", group.title)
        // same joinedAt ⇒ by hash: me ("1…") before alice ("a…"); bob joined later
        assertEquals(0, GroupUi.joinIndex(group, me)); assertEquals(1, GroupUi.joinIndex(group, alice)); assertEquals(2, GroupUi.joinIndex(group, bob))
        assertEquals(0x4fd8ff, GroupUi.colour(group, alice)); assertEquals(0xc792ff, GroupUi.colour(group, bob))
        assertNull(GroupUi.colour(group, carol))
    }

    @Test fun senderTags() {
        assertEquals(SenderTag("<you>", null, false), GroupUi.senderTag(row(Direction.outgoing), group, emptyList(), emptyList()))
        assertEquals(SenderTag("<alice>", 0x4fd8ff, false), GroupUi.senderTag(row(Direction.incoming, alice), group, emptyList(), emptyList()))
        // a former member keeps her name AND colour slot (§5: indices never shift) — no `?`
        val withFormer = group.copy(former = listOf(UiMember(carol, "carol", t0 + 500)))
        assertEquals(SenderTag("<carol>", 0xc792ff, false), GroupUi.senderTag(row(Direction.incoming, carol), withFormer, emptyList(), emptyList()))
        assertEquals("bob moved to slot 3 because carol (former) joined before him", 0xffd166, GroupUi.colour(withFormer, bob))
        assertEquals(SenderTag("<carol>", 0xc792ff, false), GroupUi.senderTag(row(Direction.incoming, carol), group, listOf(UiMember(carol, "carol", t0 + 500)), emptyList()))
        // neither member nor former: dim `<name>?` from contacts, else anon-<hash>
        assertEquals(SenderTag("<caz>?", null, true), GroupUi.senderTag(row(Direction.incoming, carol), group, emptyList(), listOf(Contact(carol, "caz", t0))))
        assertEquals(SenderTag("<anon-cccccccc>?", null, true), GroupUi.senderTag(row(Direction.incoming, carol), group, emptyList(), emptyList()))
    }

    @Test fun aggregatedStatusWords() {
        assertEquals("SENT", GroupUi.statusWord(row(Direction.outgoing, receipts = emptyMap()), group, me, true))
        assertEquals("DELIVERED 1/2", GroupUi.statusWord(row(Direction.outgoing, receipts = mapOf(alice to "delivered")), group, me, true))
        assertEquals("READ 1/2", GroupUi.statusWord(row(Direction.outgoing, receipts = mapOf(alice to "read", bob to "delivered")), group, me, true))
        assertEquals("READ 2/2", GroupUi.statusWord(row(Direction.outgoing, receipts = mapOf(alice to "read", bob to "read")), group, me, true))
        assertEquals("DELIVERED 2/2", GroupUi.statusWord(row(Direction.outgoing, receipts = mapOf(alice to "read", bob to "read")), group, me, false))
        assertEquals("FAILED", GroupUi.statusWord(row(Direction.outgoing, status = Status.failed), group, me, true))
        assertEquals("…", GroupUi.statusWord(row(Direction.outgoing, status = Status.pending), group, me, true))
        // a gone member never fails a row and leaves the denominator: `DELIVERED 1/1`
        val withGone = group.copy(members = group.members.map { if (it.hash == bob) it.copy(gone = true) else it })
        assertEquals("DELIVERED 1/1", GroupUi.statusWord(row(Direction.outgoing, receipts = mapOf(alice to "delivered")), withGone, me, true))
        assertEquals(1, withGone.recipients(me))
    }

    @Test fun typingStripAndPreview() {
        assertNull(GroupUi.typingStrip(group, emptySet()))
        assertEquals("alice is typing…", GroupUi.typingStrip(group, setOf(alice)))
        assertEquals("alice, bob are typing…", GroupUi.typingStrip(group, setOf(bob, alice)))
        assertEquals("channel open — no traffic", GroupUi.previewLine(null, group, emptyList(), emptyList()))
        assertEquals("<alice> hi", GroupUi.previewLine(row(Direction.incoming, alice), group, emptyList(), emptyList()))
        assertEquals("you: hi", GroupUi.previewLine(row(Direction.outgoing), group, emptyList(), emptyList()))
        assertEquals("── <bob> left ──", GroupUi.previewLine(row(Direction.incoming, text = "── <bob> left ──", kind = Kind.system), group, emptyList(), emptyList()))
        assertEquals("<carol> [image]", GroupUi.previewLine(row(Direction.incoming, carol, kind = Kind.image).copy(text = ""), group, listOf(UiMember(carol, "carol", t0)), emptyList()))
        assertEquals("<anon-cccccccc>? hi", GroupUi.previewLine(row(Direction.incoming, carol), group, emptyList(), emptyList()))
    }

    @Test fun homeEntriesInterleaveByActivity() {
        val c = Contact(alice, "alice", t0 - 10)
        val newerGroup = group.copy(createdAt = t0 + 5)
        val messages = mapOf(alice to listOf(row(Direction.incoming, alice).copy(timestamp = t0 + 50)))
        assertEquals(listOf(alice, "g1"), homeEntries(listOf(c), listOf(newerGroup), messages).map { it.key })
        assertEquals(listOf("g1", alice), homeEntries(listOf(c), listOf(newerGroup), emptyMap()).map { it.key })
        assertTrue(homeEntries(emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun policyLabelsAndVerbatimStrings() {
        assertEquals(listOf("alert", "burn for the screenshotter", "burn and remove"), GroupUi.POLICY_LABELS)
        assertEquals("burn and remove", GroupUi.policyLabel("burn_remove"))
        assertEquals("burn_remove", GroupUi.policyWire("burn and remove"))
        assertEquals("burn", GroupUi.policyWire("burn for the screenshotter"))
        assertEquals("BURN #ops", GroupUi.burnTitle(group))
        assertEquals("destroys this channel on every member's phone: every message, every file, the member list. there is no undo.", GroupUi.BURN_MESSAGE)
        assertEquals("ERR: group is full (20)", GroupUi.ERR_GROUP_FULL)
        assertEquals("ERR: only the creator can remove members", GroupUi.ERR_ONLY_CREATOR)
        assertEquals("ERR: you are no longer in this group", GroupUi.ERR_NOT_IN_GROUP)
        assertEquals(19, GroupUi.MAX_PICKED)
        assertTrue(GroupUi.ERR_LINK_DOWN.startsWith("ERR: link down — "))
    }
}
