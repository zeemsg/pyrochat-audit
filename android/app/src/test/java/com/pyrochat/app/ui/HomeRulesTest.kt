package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/FIRST-RUN.md §1–2: the empty state, the pyrobot row subtitle, the `[share]` text. */
class HomeRulesTest {
    private val t0 = 1_700_000_000_000L
    private val pyrobot = Contact(hash = FakeBackend.sha256Hex("pyrobot"), name = "pyrobot", addedAt = t0)
    private val alice = Contact(hash = FakeBackend.sha256Hex("alice"), name = "alice", addedAt = t0)
    private val group = UiGroup("g", "ops", "me", t0, 0, "alert", members = listOf(UiMember("me", "me", t0)))

    @Test fun emptyStateNeedsNoContactsAndNoGroups() {
        assertTrue(HomeRules.showsEmptyState(emptyList(), emptyList()))
        assertFalse(HomeRules.showsEmptyState(listOf(pyrobot), emptyList()))
        assertFalse(HomeRules.showsEmptyState(emptyList(), listOf(group)))
        assertFalse(HomeRules.showsEmptyState(listOf(alice), listOf(group)))
    }

    @Test fun pyrobotRowReadsTheDemoLineOnlyWhileEmpty() {
        assertEquals("demo peer — say hi for a 60-second tour", HomeRules.subtitle(pyrobot, emptyList()))
        val hi = ChatMessage("m1", Direction.outgoing, Kind.text, "hi", t0, Status.sent)
        assertEquals("you: hi", HomeRules.subtitle(pyrobot, listOf(hi)))
        val tour = ChatMessage("m2", Direction.incoming, Kind.text, "hello. i am pyrobot", t0 + 1, Status.delivered)
        assertEquals("hello. i am pyrobot", HomeRules.subtitle(pyrobot, listOf(hi, tour)))
    }

    @Test fun onlyTheContactNamedExactlyPyrobot() {
        assertEquals("channel open — no traffic", HomeRules.subtitle(alice, emptyList()))
        assertEquals("channel open — no traffic", HomeRules.subtitle(pyrobot.copy(name = "PyroBot"), emptyList()))
        assertEquals("channel open — no traffic", HomeRules.subtitle(pyrobot.copy(name = "pyrobot2"), emptyList()))
        assertEquals("channel open — no traffic", HomeRules.subtitle(pyrobot.copy(name = "bot"), emptyList())) // renamed locally
        assertEquals("channel open — no traffic", HomeRules.subtitle(pyrobot.copy(name = " pyrobot"), emptyList()))
        assertEquals("pyrobot", HomeRules.PYROBOT)
    }

    @Test fun shareTextIsTheThreeVerbatimLines() {
        val code = "pyro-add:v1:alice:BXFrZ2xuenpzZW9pZmh3ZmxrZGprc2Fsa2RqZmxrc2pkZmw"
        val text = HomeRules.shareText("alice", code)
        assertEquals(
            "add me on PyroChat — my username is alice\n" +
                "in the app: [qr] → paste this code → pyro-add:v1:alice:BXFrZ2xuenpzZW9pZmh3ZmxrZGprc2Fsa2RqZmxrc2pkZmw\n" +
                "get the app: https://pyrochat.ca",
            text,
        )
        val lines = text.split("\n")
        assertEquals(3, lines.size)
        assertEquals("add me on PyroChat — my username is alice", lines[0])
        assertEquals("in the app: [qr] → paste this code → $code", lines[1])
        assertEquals("get the app: https://pyrochat.ca", lines[2])
        assertFalse("no trailing newline", text.endsWith("\n"))
    }

    @Test fun theSharedCodeIsWhatMyCodeShowsAndWhatTheQrScreenParses() {
        val key = byteArrayOf(0x05) + ByteArray(32) { it.toByte() }
        val code = com.pyrochat.app.ui.qr.AddQrPayload.encode("alice", key)
        val pasted = HomeRules.shareText("alice", code).split("\n")[1].substringAfter("→ paste this code → ")
        assertEquals(code, pasted)
        val parsed = com.pyrochat.app.ui.qr.AddQrPayload.parse(pasted) as com.pyrochat.app.ui.qr.AddQrPayload.Parsed.Contact
        assertEquals("alice", parsed.username)
    }
}
