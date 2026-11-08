package com.pyrochat.mirror

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/MIRROR-GROUPS.md §1/§3/§4 on the Android engine: the `Group` / `sender` / `receipts` / `who`
 * schema builders, a left or burned group never in the snapshot and producing `group_rm`, burn
 * rows in groups content-free, `send_text` to a left group → `cmd_err left`, `mark_read` on a
 * group reaching the read path for non-burn ids only.
 */
class MirrorGroupsTest {
    private val me = "0".repeat(64)
    private val alice = MirrorContact("a".repeat(64), "alice", verified = true, addedAt = 1_000)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)
    private val gid = "6f6f7073-0000-4000-8000-00000000006f"

    /**
     * `#ops`: me, alice, bob, carol (gone) with the phone's own colours (GROUPS.md §5 palette 0–3 by
     * join). The roster is STORED with bob ahead of alice — the wire keeps that order, `who` sorts by join.
     */
    private val ops = MirrorGroup(
        id = gid, name = "ops", policy = "burn_remove", createdAt = 5_000,
        members = listOf(
            MirrorMember(me, "andro", "#3ef573", joinedAt = 1_000),
            MirrorMember(bob, "bob", "#c792ff", joinedAt = 3_000),
            MirrorMember(alice.hash, "alice", "#4fd8ff", joinedAt = 2_000),
            MirrorMember(carol, "carol", "#ffd166", gone = true, joinedAt = 4_000),
        ),
    )
    private val membersJson =
        """[{"hash":"$me","name":"andro","color":"#3ef573"},{"hash":"$bob","name":"bob","color":"#c792ff"},""" +
            """{"hash":"${alice.hash}","name":"alice","color":"#4fd8ff"},{"hash":"$carol","name":"carol","color":"#ffd166","gone":true}]"""

    private fun groupRow(id: String, dir: MirrorDirection, text: String, ts: Long, status: MirrorStatus = MirrorStatus.delivered,
                         sender: String? = null, receipts: Map<String, String>? = null, kind: MirrorKind = MirrorKind.text,
                         isBurn: Boolean = false, viaWeb: Boolean = false) =
        MirrorMessage(id, dir, kind, text, ts, status, isBurn = isBurn, viaWeb = viaWeb, senderHash = sender, receipts = receipts)

    private fun seed(h: Harness) {
        h.source.addGroup(ops)
        // a group this phone left before linking: never mirrored, never `group_rm` (it was never sent)
        h.source.addGroup(MirrorGroup("old-0000", "old", listOf(MirrorMember(me, "andro", "#3ef573")), "alert", 1_000, left = true))
        h.source.append("old-0000", groupRow("o1", MirrorDirection.incoming, "left-over", 2_000, sender = bob))
        h.source.append(gid, groupRow("g1", MirrorDirection.incoming, "relay maintenance?", 10_000, sender = alice.hash))
        h.source.append(gid, groupRow("g2", MirrorDirection.incoming, "── <carol> joined ──", 11_000, MirrorStatus.read, kind = MirrorKind.system))
        h.source.append(gid, groupRow("g3", MirrorDirection.incoming, "hi all", 12_000, sender = carol))
        h.source.append(gid, groupRow("g4", MirrorDirection.outgoing, "welcome", 13_000, MirrorStatus.sent,
            receipts = mapOf(alice.hash to "read", bob to "read", carol to "delivered")))
        h.source.append(gid, groupRow("g5", MirrorDirection.incoming, "SECRET burn", 14_000, sender = alice.hash, isBurn = true))
        h.source.append(gid, groupRow("g6", MirrorDirection.incoming, "who dis", 14_500, sender = dave))
        h.source.append(gid, groupRow("g7", MirrorDirection.outgoing, "SECRET out burn", 15_000, MirrorStatus.pending, receipts = emptyMap(), isBurn = true))
    }

    private fun enc(o: JsonObject) = json.encodeToString(JsonObject.serializer(), o)

    @Test
    fun aggregateStatusFollowsIosRowStatus() {
        val f = MirrorEngine.Companion::aggregateStatus
        assertEquals(MirrorStatus.pending, f(MirrorStatus.pending, mapOf(alice.hash to "read"), 1))
        assertEquals(MirrorStatus.failed, f(MirrorStatus.failed, mapOf(alice.hash to "read"), 1))
        assertEquals(MirrorStatus.sent, f(MirrorStatus.sent, null, 3))
        assertEquals(MirrorStatus.sent, f(MirrorStatus.sent, mapOf(alice.hash to "read"), 3), "read only once EVERY recipient reported")
        assertEquals(MirrorStatus.delivered, f(MirrorStatus.sent, mapOf(alice.hash to "read", bob to "delivered"), 2))
        assertEquals(MirrorStatus.read, f(MirrorStatus.sent, mapOf(alice.hash to "read", bob to "read"), 2))
        assertEquals(MirrorStatus.read, f(MirrorStatus.read, emptyMap(), 2), "receipts never downgrade")
        assertEquals(MirrorStatus.sent, f(MirrorStatus.sent, emptyMap(), 0), "a group of one has no recipients")
        assertEquals(MirrorStatus.read, f(MirrorStatus.sent, mapOf(alice.hash to "read"), 1), "recipients = roster minus me and the gone, so one read is everyone")
    }

    @Test
    fun whoIsMemberNamesInJoinOrderCappedAtThree() {
        assertEquals(emptyList(), MirrorEngine.whoTyping(ops, emptySet()))
        assertEquals(listOf("alice", "carol"), MirrorEngine.whoTyping(ops, setOf(carol, alice.hash)))
        assertEquals(listOf("alice", "bob"), MirrorEngine.whoTyping(ops, setOf(bob, alice.hash)), "join order, not roster order")
        assertEquals(listOf("andro", "alice", "bob"), MirrorEngine.whoTyping(ops, setOf(carol, bob, alice.hash, me)))
        assertEquals(emptyList(), MirrorEngine.whoTyping(ops, setOf(dave)), "a hash outside the roster has no name to show")
    }

    @Test
    fun snapshotCarriesGroupsAndGroupRowsWithSenderAndReceipts(): Unit = runBlocking {
        val h = Harness()
        h.source.addContact(alice)
        seed(h)
        try {
            h.handshake()
            val events = h.drainSnapshot()
            assertEquals(listOf("snapshot_begin", "thread", "thread", "conn", "snapshot_end"), events.map { it.obj.str("e") })
            // Group = {id, name, members:[{hash,name,color,gone?}], lastTs, left?, policy}; the left group is absent
            assertEquals(
                """{"e":"snapshot_begin","contacts":[{"hash":"${alice.hash}","name":"alice","verified":true,"lastTs":1000}],""" +
                    """"groups":[{"id":"$gid","name":"ops","members":$membersJson,"lastTs":15000,"policy":"burn_remove"}]}""",
                events[0].plaintext,
            )
            // contact threads first, then the group thread, both from window(thread)
            assertEquals(alice.hash, events[1].obj.str("thread"))
            assertEquals(gid, events[2].obj.str("thread"))
            val rows = events[2].obj["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(7, rows.size)
            assertEquals("""{"id":"g1","dir":"in","kind":"text","ts":10000,"status":"delivered","text":"relay maintenance?","sender":{"hash":"${alice.hash}","name":"alice","color":"#4fd8ff"}}""", enc(rows[0]))
            // system rows: the phone's own text, no sender, no receipts
            assertEquals("""{"id":"g2","dir":"in","kind":"system","ts":11000,"status":"read","text":"── <carol> joined ──"}""", enc(rows[1]))
            // a gone member still has her name and colour on history rows
            assertEquals("""{"id":"g3","dir":"in","kind":"text","ts":12000,"status":"delivered","text":"hi all","sender":{"hash":"$carol","name":"carol","color":"#ffd166"}}""", enc(rows[2]))
            // outgoing: aggregated receipts, total = members − 1, status the aggregate word (everyone reported, not all read)
            assertEquals("""{"id":"g4","dir":"out","kind":"text","ts":13000,"status":"delivered","text":"welcome","receipts":{"delivered":3,"read":2,"total":3}}""", enc(rows[3]))
            // FLAGSHIP: burn rows in groups stay content-free — no text, no sender, no receipts
            assertEquals("""{"id":"g5","dir":"in","kind":"burn","ts":14000,"status":"delivered"}""", enc(rows[4]))
            assertEquals("""{"id":"g7","dir":"out","kind":"burn","ts":15000,"status":"pending"}""", enc(rows[6]))
            assertFalse(events[2].plaintext.contains("SECRET"))
            // a sender outside the roster and the contacts: `anon-<hash>`, no colour (iOS colour(of:in:) is nil)
            assertEquals("""{"id":"g6","dir":"in","kind":"text","ts":14500,"status":"delivered","text":"who dis","sender":{"hash":"$dave","name":"anon-dddddddd"}}""", enc(rows[5]))
            assertFalse(events.any { it.plaintext.contains("old-0000") }, "a left group is never in the snapshot")
        } finally {
            h.close()
        }
    }

    @Test
    fun diffEmitsGroupUpsertsRemovalsReceiptsAndWho(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake()
            h.drainSnapshot()
            // rename → group (full Group, upsert by id)
            h.source.replaceGroup(ops.copy(name = "ops2"))
            assertEquals("""{"e":"group","id":"$gid","name":"ops2","members":$membersJson,"lastTs":15000,"policy":"burn_remove"}""", h.nextEvent().plaintext)
            // the aggregate word counts the fan-out's recipients (me and the gone carol excluded, iOS
            // `recipients(of:)`): alice + bob read → `read`, while `receipts.total` stays members − 1
            h.source.replace(gid, groupRow("g4", MirrorDirection.outgoing, "welcome", 13_000, MirrorStatus.sent,
                receipts = mapOf(alice.hash to "read", bob to "read")))
            assertEquals("""{"e":"msg","thread":"$gid","row":{"id":"g4","dir":"out","kind":"text","ts":13000,"status":"read","text":"welcome","receipts":{"delivered":2,"read":2,"total":3}}}""", h.nextEvent().plaintext)
            // roster change → group; policy change → group
            val erin = "e".repeat(64)
            val withErin = ops.copy(name = "ops2", members = ops.members + MirrorMember(erin, "erin", "#ff8fab", joinedAt = 5_000))
            h.source.replaceGroup(withErin)
            assertEquals(5, h.nextEvent().obj["members"]!!.jsonArray.size)
            h.source.replaceGroup(withErin.copy(policy = "alert"))
            assertEquals("alert", h.nextEvent().obj.str("policy"))
            // a member's receipt landing → the outgoing row re-mirrors (status + counts; total now 4)
            h.source.replace(gid, groupRow("g4", MirrorDirection.outgoing, "welcome", 13_000, MirrorStatus.sent,
                receipts = mapOf(alice.hash to "read", bob to "read", carol to "read", erin to "read")))
            assertEquals("""{"e":"msg","thread":"$gid","row":{"id":"g4","dir":"out","kind":"text","ts":13000,"status":"read","text":"welcome","receipts":{"delivered":4,"read":4,"total":4}}}""", h.nextEvent().plaintext)
            // an incoming row from a member → msg with sender
            h.source.append(gid, groupRow("g8", MirrorDirection.incoming, "yo", 16_000, sender = bob))
            assertEquals("""{"e":"msg","thread":"$gid","row":{"id":"g8","dir":"in","kind":"text","ts":16000,"status":"delivered","text":"yo","sender":{"hash":"$bob","name":"bob","color":"#c792ff"}}}""", h.nextEvent().plaintext)
            // typing: who in join order, ≤3 names, re-emitted on any change; off carries an empty who (iOS)
            h.source.setGroupTyping(gid, setOf(carol, alice.hash))
            assertEquals("""{"e":"typing","thread":"$gid","on":true,"who":["alice","carol"]}""", h.nextEvent().plaintext)
            h.source.setGroupTyping(gid, setOf(carol, alice.hash, bob, erin, dave))
            assertEquals("""{"e":"typing","thread":"$gid","on":true,"who":["alice","bob","carol"]}""", h.nextEvent().plaintext)
            h.source.setGroupTyping(gid, setOf(erin))
            assertEquals("""{"e":"typing","thread":"$gid","on":true,"who":["erin"]}""", h.nextEvent().plaintext)
            h.source.setGroupTyping(gid, emptySet())
            assertEquals("""{"e":"typing","thread":"$gid","on":false,"who":[]}""", h.nextEvent().plaintext)
            // a fresh group mid-link → group, then its rows as msg
            val g2 = MirrorGroup("11111111-0000-4000-8000-000000000001", "night", listOf(MirrorMember(me, "andro", "#3ef573", joinedAt = 1), MirrorMember(bob, "bob", "#4fd8ff", joinedAt = 2)), "burn", 20_000)
            h.source.addGroup(g2)
            h.source.append(g2.id, groupRow("n1", MirrorDirection.incoming, "night shift", 21_000, sender = bob))
            assertEquals("""{"e":"group","id":"${g2.id}","name":"night","members":[{"hash":"$me","name":"andro","color":"#3ef573"},{"hash":"$bob","name":"bob","color":"#4fd8ff"}],"lastTs":21000,"policy":"burn"}""", h.nextEvent().plaintext)
            assertEquals("n1", h.nextEvent().obj["row"]!!.jsonObject.str("id"))
            // this phone leaves → group_rm (the page drops the thread too), and nothing more from that thread
            h.source.replaceGroup(withErin.copy(policy = "alert", left = true))
            assertEquals("""{"e":"group_rm","id":"$gid"}""", h.nextEvent().plaintext)
            h.source.append(gid, groupRow("g9", MirrorDirection.incoming, "after leaving", 30_000, sender = bob))
            h.source.setGroupTyping(gid, setOf(bob))
            assertNull(withTimeoutOrNull(300) { h.nextEvent() }, "a left group is silent")
            // burned on this phone (gone from the list entirely) → group_rm
            h.source.removeGroup(g2.id)
            assertEquals("""{"e":"group_rm","id":"${g2.id}"}""", h.nextEvent().plaintext)
        } finally {
            h.close()
        }
    }

    @Test
    fun sendTextToAGroupFansOutAndEchoesTempId(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake()
            h.drainSnapshot()
            h.pushCmd(buildJsonObject { put("c", "send_text"); put("thread", gid); put("text", "from web"); put("tempId", "tg1") })
            assertEquals("from web" to gid, withTimeout(2_000) { h.source.sentTexts.receive() })
            val echo = h.nextEvent()
            assertEquals("msg", echo.obj.str("e"))
            assertEquals(gid, echo.obj.str("thread"))
            val row = echo.obj["row"]!!.jsonObject
            assertEquals("tg1", row.str("tempId"))
            assertEquals("web", row.str("via"))
            assertEquals("out", row.str("dir"))
            assertEquals("pending", row.str("status"))
            assertEquals("""{"delivered":0,"read":0,"total":3}""", enc(row["receipts"]!!.jsonObject))
            assertNull(row["sender"])
            // typing from the browser reaches the group typing control
            h.pushCmd(buildJsonObject { put("c", "typing"); put("thread", gid) })
            assertEquals(gid, withTimeout(2_000) { h.source.composer.receive() })
        } finally {
            h.close()
        }
    }

    @Test
    fun sendTextToLeftOrUnknownGroupErrs(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        h.source.onSendText = { _, _ -> }
        try {
            h.handshake()
            h.drainSnapshot()
            // iOS check order: unknown_thread and left come before offline / too_long
            h.source.online = false
            h.pushCmd(buildJsonObject { put("c", "send_text"); put("thread", "old-0000"); put("text", "x".repeat(4001)); put("tempId", "t1") })
            assertEquals("""{"e":"cmd_err","tempId":"t1","code":"left"}""", h.nextEvent().plaintext)
            h.pushCmd(buildJsonObject { put("c", "send_text"); put("thread", "22222222-0000-4000-8000-000000000002"); put("text", "x"); put("tempId", "t2") })
            assertEquals("""{"e":"cmd_err","tempId":"t2","code":"unknown_thread"}""", h.nextEvent().plaintext)
            h.pushCmd(buildJsonObject { put("c", "send_text"); put("thread", gid); put("text", "x"); put("tempId", "t2b") })
            assertEquals("""{"e":"cmd_err","tempId":"t2b","code":"offline"}""", h.nextEvent().plaintext)
            h.source.online = true
            // a group this phone leaves while linked errs the same way
            h.source.replaceGroup(ops.copy(left = true))
            assertEquals("""{"e":"group_rm","id":"$gid"}""", h.nextEvent().plaintext)
            h.pushCmd(buildJsonObject { put("c", "send_text"); put("thread", gid); put("text", "x"); put("tempId", "t3") })
            assertEquals("""{"e":"cmd_err","tempId":"t3","code":"left"}""", h.nextEvent().plaintext)
            assertTrue(h.source.sentTexts.tryReceive().isFailure, "nothing reached the service")
        } finally {
            h.close()
        }
    }

    @Test
    fun markReadOnAGroupReachesTheReadPathForNonBurnIdsOnly(): Unit = runBlocking {
        val h = Harness()
        seed(h)
        try {
            h.handshake()
            h.drainSnapshot()
            h.pushCmd(buildJsonObject {
                put("c", "mark_read"); put("thread", gid)
                put("ids", buildJsonArray { for (id in listOf("g1", "g5", "g3", "ghost", "o1")) add(JsonPrimitive(id)) })
            })
            h.pushCmd(buildJsonObject { put("c", "typing"); put("thread", gid) })
            assertEquals(gid, withTimeout(2_000) { h.source.composer.receive() })
            assertEquals("g1" to gid, h.source.visible.tryReceive().getOrNull())
            assertEquals("g3" to gid, h.source.visible.tryReceive().getOrNull())
            assertTrue(h.source.visible.tryReceive().isFailure, "the burn row, an unknown id and a left group's row never reach noteRowVisible")
            // the left group's thread is not mirrored, so nothing there is markable
            h.pushCmd(buildJsonObject { put("c", "mark_read"); put("thread", "old-0000"); put("ids", buildJsonArray { add(JsonPrimitive("o1")) }) })
            h.pushCmd(buildJsonObject { put("c", "typing"); put("thread", gid) })
            assertEquals(gid, withTimeout(2_000) { h.source.composer.receive() })
            assertTrue(h.source.visible.tryReceive().isFailure)
        } finally {
            h.close()
        }
    }
}
