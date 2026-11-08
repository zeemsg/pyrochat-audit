package com.pyrochat.service

import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Member
import java.time.Instant
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/GROUPS.md §1, §4, §5, §7 — the pure rules both platforms must agree on. */
class GroupRulesTest {
    private val t0 = Instant.parse("2026-09-18T12:00:00Z")
    private fun m(hash: Char, name: String, offset: Long = 0) = Member(hash.toString().repeat(64), name, t0.plusSeconds(offset))

    @Test fun paletteAndDimming() {
        assertEquals(listOf("#3ef573", "#4fd8ff", "#c792ff", "#ffd166", "#ff8fab", "#7ee8fa", "#b5e550", "#ffa64d", "#e0b3ff", "#8be9fd"), (0..9).map(GroupColours::hex))
        assertTrue(GroupColours.PALETTE.none { it == 0xff5940 || it == 0xffaf32 }, "never alert or flame")
        // second lap 70 %, third lap and beyond 50 %, then wraps
        assertEquals(GroupColours.dim(0x3ef573, 0.7), GroupColours.rgb(10))
        assertEquals("#2bac51", GroupColours.hex(10))
        assertEquals(GroupColours.dim(0x4fd8ff, 0.5), GroupColours.rgb(21))
        assertEquals("#286c80", GroupColours.hex(21))
        assertEquals(GroupColours.rgb(25), GroupColours.rgb(35))
        assertEquals(0x000000, GroupColours.dim(0x000000, 0.7))
        assertEquals(0xb3b3b3, GroupColours.dim(0xffffff, 0.7))
    }

    @Test fun joinOrderIsJoinedAtThenHash() {
        val roster = listOf(m('c', "carol", 5), m('b', "bob", 0), m('a', "alice", 0), m('d', "dave", -1))
        assertEquals(listOf("dave", "alice", "bob", "carol"), GroupColours.joinOrder(roster).map { it.name })
        assertEquals(0, GroupColours.joinIndex(roster, "d".repeat(64)))
        assertEquals(1, GroupColours.joinIndex(roster, "a".repeat(64)))
        assertEquals(3, GroupColours.joinIndex(roster, "c".repeat(64)))
        assertNull(GroupColours.joinIndex(roster, "z".repeat(64)))
        assertEquals(0x4fd8ff, GroupColours.colour(roster, "a".repeat(64)))
        assertNull(GroupColours.colour(roster, "z".repeat(64)))
        // removing dave shifts nobody's colour only if the caller keeps him around — the rule itself is positional
        assertEquals(0, GroupColours.joinIndex(roster.filter { it.name != "dave" }, "a".repeat(64)))
    }

    @Test fun groupNamesAreTrimmedWithoutHashAndBounded() {
        assertEquals("ops", GroupRules.normalizeName("  #ops  "))
        assertEquals("ops", GroupRules.normalizeName("##  ops"))
        assertEquals("ops room", GroupRules.normalizeName("ops room"))
        assertNull(GroupRules.normalizeName("   "))
        assertNull(GroupRules.normalizeName("#"))
        assertNull(GroupRules.normalizeName("a\nb"))
        assertEquals("a".repeat(32), GroupRules.normalizeName("a".repeat(32)))
        assertNull(GroupRules.normalizeName("a".repeat(33)))
        assertEquals("🔥".repeat(32), GroupRules.normalizeName("🔥".repeat(32)), "graphemes, not code units")
        assertNull(GroupRules.normalizeName("🔥".repeat(33)))
    }

    @Test fun memberNamesAreSanitizedForTheNameTag() {
        assertEquals("bob", GroupRules.sanitizeMemberName("bob"))
        assertEquals("bob smith", GroupRules.sanitizeMemberName("  bob   smith  "))
        assertEquals("mom-2", GroupRules.sanitizeMemberName("mom-2 ❤️"))
        assertNull(GroupRules.sanitizeMemberName("<>‮\n"))
        assertNull(GroupRules.sanitizeMemberName(null))
        assertEquals("a".repeat(24), GroupRules.sanitizeMemberName("a".repeat(40)))
        assertEquals("anon-abcdef01", GroupRules.anonName("abcdef01" + "0".repeat(56)))
    }

    @Test fun typingStripWording() {
        assertNull(GroupRules.typingStrip(emptyList()))
        assertEquals("alice is typing…", GroupRules.typingStrip(listOf("alice")))
        assertEquals("alice, bob are typing…", GroupRules.typingStrip(listOf("alice", "bob")))
        assertEquals("alice, bob, carol are typing…", GroupRules.typingStrip(listOf("alice", "bob", "carol")))
        assertEquals("alice, bob, carol … and 2 more are typing…", GroupRules.typingStrip(listOf("alice", "bob", "carol", "dave", "eve")))
    }

    @Test fun receiptAggregation() {
        val b = "b".repeat(64); val c = "c".repeat(64); val d = "d".repeat(64)
        assertEquals("…", GroupReceipts.aggregate(Status.PENDING, null, 3, true))
        assertEquals("FAILED", GroupReceipts.aggregate(Status.FAILED, mapOf(b to "read"), 3, true))
        assertEquals("SENT", GroupReceipts.aggregate(Status.SENT, emptyMap(), 3, true))
        assertEquals("DELIVERED 1/3", GroupReceipts.aggregate(Status.SENT, mapOf(b to "delivered"), 3, true))
        assertEquals("DELIVERED 2/3", GroupReceipts.aggregate(Status.SENT, mapOf(b to "delivered", c to "delivered"), 3, true))
        assertEquals("READ 1/3", GroupReceipts.aggregate(Status.SENT, mapOf(b to "read", c to "delivered"), 3, true))
        assertEquals("READ 3/3", GroupReceipts.aggregate(Status.SENT, mapOf(b to "read", c to "read", d to "read"), 3, true))
        // reciprocity: without my read-receipt toggle a read counts as delivered
        assertEquals("DELIVERED 2/3", GroupReceipts.aggregate(Status.SENT, mapOf(b to "read", c to "delivered"), 3, false))
        // merge never downgrades
        assertEquals(mapOf(b to "read"), GroupReceipts.merge(mapOf(b to "read"), b, "delivered"))
        assertEquals(mapOf(b to "read"), GroupReceipts.merge(mapOf(b to "delivered"), b, "read"))
        assertEquals(mapOf(b to "delivered"), GroupReceipts.merge(null, b, "delivered"))
    }

    /** The iOS agent's fixture publishes the join indices / hex colours and the status words it expects — both platforms must agree. */
    @Test fun iosFixtureColoursAndStatusWordsAgree() {
        val dir = java.nio.file.Paths.get("..", "fixtures", "ios-store-groups").toAbsolutePath().normalize()
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(dir.resolve("pyro.store.plaintext.json")), "SKIPPED: no iOS group fixture yet")
        val blob = com.pyrochat.store.codec.SwiftJson.decodeBlob(java.nio.file.Files.readAllBytes(dir.resolve("pyro.store.plaintext.json")))
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(java.nio.file.Files.readString(dir.resolve("manifest.json"))).jsonObject
        val g = blob.groups!!.single()
        val colours = manifest["group"]!!.jsonObject["colours"]!!.jsonObject
        assertEquals(4, colours.size)
        for ((hash, spec) in colours) {
            val idx = spec.jsonObject["joinIndex"]!!.jsonPrimitive.int
            assertEquals(idx, GroupColours.joinIndex(g.colourRoster, hash), "join index of $hash")
            assertEquals(spec.jsonObject["hex"]!!.jsonPrimitive.content, GroupColours.hex(idx), "hex of $hash")
        }
        val expected = manifest["thread"]!!.jsonObject["expectedStatusWords"]!!.jsonObject
        val rows = blob.messages.getValue(g.id)
        val recipients = g.members.count { it.hash != blob.usernameHash && !it.isGone }
        for ((id, word) in expected) {
            val row = rows.first { it.id == id }
            assertEquals(word.jsonPrimitive.content, GroupReceipts.aggregate(row.status, row.receipts, recipients, showRead = true), id)
        }
    }

    @Test fun fanOutVerdicts() {
        val b = "b".repeat(64); val c = "c".repeat(64); val d = "d".repeat(64)
        val t = GroupFanOut("m1", "g1", listOf(b, c, d))
        assertEquals(Status.PENDING, t.status)
        t.sent(b)
        assertEquals(Status.PENDING, t.status)
        t.failed(c)
        assertEquals(Status.PENDING, t.status)
        t.sent(d)
        assertTrue(t.done)
        assertEquals(Status.FAILED, t.status, "any recipient error fails the row")
        // retry re-arms only the failed recipient
        assertEquals(setOf(c), t.retry())
        assertEquals(Status.PENDING, t.status)
        t.sent(c)
        assertEquals(Status.SENT, t.status)
        // a gone member (unknown_user) is dropped from the count, not counted as a failure
        val u = GroupFanOut("m2", "g1", listOf(b, c))
        u.gone(c)
        u.sent(b)
        assertEquals(Status.SENT, u.status)
        // nobody to send to: sent
        assertEquals(Status.SENT, GroupFanOut("m3", "g1", emptyList()).status)
        // a duplicate verdict is ignored
        u.failed(b)
        assertEquals(Status.SENT, u.status)
    }
}
