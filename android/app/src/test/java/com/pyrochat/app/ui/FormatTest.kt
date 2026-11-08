package com.pyrochat.app.ui

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    private val t0 = 1_700_000_000_000L   // 2023-11-14T22:13:20Z

    @Test fun clockIsHHmmss() {
        assertEquals("22:13:20", Format.clock(t0, ZoneOffset.UTC))
        assertEquals("22:13", Format.shortClock(t0, ZoneOffset.UTC))
    }

    @Test fun statusWordsFollowReciprocity() {
        assertEquals("…", Format.statusWord(Status.pending, showRead = true))
        assertEquals("SENT", Format.statusWord(Status.sent, showRead = true))
        assertEquals("DELIVERED", Format.statusWord(Status.delivered, showRead = true))
        assertEquals("READ", Format.statusWord(Status.read, showRead = true))
        assertEquals("DELIVERED", Format.statusWord(Status.read, showRead = false))   // READ hidden until my toggle is on
        assertEquals("FAILED", Format.statusWord(Status.failed, showRead = false))
    }

    @Test fun burnCountdownFloorsAtZero() {
        assertEquals("🔥" + "42", Format.burnCountdown(deadlineMillis = t0 + 42_900, nowMillis = t0))
        assertEquals("🔥" + "0", Format.burnCountdown(deadlineMillis = t0 - 5_000, nowMillis = t0))
        assertEquals("🔥…", Format.BURN_ARMED)
    }

    @Test fun burnDeadlineNeedsBothFields() {
        val armed = ChatMessage("a", Direction.outgoing, Kind.text, "x", t0, Status.sent, burnAfter = 300)
        assertEquals(null, armed.burnDeadline)
        val started = armed.copy(burnStartedAt = t0)
        assertEquals(t0 + 300_000, started.burnDeadline)
    }

    @Test fun noticeRowSpeaksForTheNewestNoticeOnOneLine() {
        val older = UiNotice("a", "old news", null, t0 - 60_000, t0 + 86_400_000)
        val newest = UiNotice("b", "relay maintenance tonight\n02:00–02:10 UTC.   messages queue,\tnothing is lost. ", "https://pyrochat.ca/", t0, t0 + 86_400_000)
        assertEquals(newest, Format.newestNotice(listOf(older, newest)))
        assertEquals(newest, Format.newestNotice(listOf(newest, older)))
        assertEquals("relay maintenance tonight 02:00–02:10 UTC. messages queue, nothing is lost.", Format.noticeSubtitle(listOf(older, newest)))
        assertEquals("", Format.noticeSubtitle(emptyList()))
        assertEquals(null, Format.newestNotice(emptyList()))
        assertEquals("<pyrochat>", Format.NOTICES_CHANNEL)
        // the row clock is the newest notice's post time, formatted like every other row
        assertEquals("22:13", Format.shortClock(Format.newestNotice(listOf(older, newest))!!.ts, ZoneOffset.UTC))
    }

    @Test fun burnLabelsRoundTrip() {
        for (label in listOf("off", "5s", "1m", "1h", "1d")) assertEquals(label, Format.burnLabel(Format.burnSeconds(label)))
        assertEquals("42s", Format.burnLabel(42))
    }

    @Test fun byteLabelMatchesIOS() {
        assertEquals("?", Format.byteLabel(null))
        assertEquals("512B", Format.byteLabel(512))
        assertEquals("312K", Format.byteLabel(312 * 1024L))
        assertEquals("2.4M", Format.byteLabel((2.4 * 1024 * 1024).toLong()))
    }

    @Test fun previewLinesMatchHome() {
        assertEquals("channel open — no traffic", Format.previewLine(null))
        val voice = ChatMessage("v", Direction.incoming, Kind.voice, "", t0, Status.read, hasVoice = true)
        assertEquals("[voice note]", Format.previewLine(voice))
        val file = ChatMessage("f", Direction.outgoing, Kind.file, "", t0, Status.sent, fileName = "a.pdf")
        assertEquals("you: [file] a.pdf", Format.previewLine(file))
    }

    @Test fun unreadCountsIncomingUnreadNonSystem() {
        val log = listOf(
            ChatMessage("1", Direction.incoming, Kind.text, "a", t0, Status.delivered),
            ChatMessage("2", Direction.incoming, Kind.system, "b", t0, Status.delivered),
            ChatMessage("3", Direction.incoming, Kind.text, "c", t0, Status.read),
            ChatMessage("4", Direction.outgoing, Kind.text, "d", t0, Status.sent),
        )
        assertEquals(1, Format.unreadCount(log))
    }

    @Test fun autoLockLabels() {
        assertEquals("5m", Format.autoLockLabel(null))
        assertEquals("off", Format.autoLockLabel(0))
        assertEquals(15, Format.autoLockMinutes("15m"))
        assertEquals(0, Format.autoLockMinutes("off"))
    }

    @Test fun safetyNumberGroups() {
        val digits = (0 until 60).joinToString("") { (it % 10).toString() }
        val lines = Format.safetyNumberLines(digits)
        assertEquals(4, lines.size)
        assertEquals("01234  56789  01234", lines[0])
    }

    @Test fun terminalLogMath() {
        assertEquals(100, TerminalLogMath.grown(60, total = 500))
        assertEquals(70, TerminalLogMath.grown(60, total = 70))
        assertEquals(60, TerminalLogMath.appended(60, appended = 3, atBottom = true))
        assertEquals(63, TerminalLogMath.appended(60, appended = 3, atBottom = false))
    }

    @Test fun newestActivityFirst() {
        val old = Contact(hash = "a", name = "old", addedAt = 1_000L)
        val mid = Contact(hash = "b", name = "mid", addedAt = 2_000L)
        val new = Contact(hash = "c", name = "new", addedAt = 3_000L)
        val msg = ChatMessage(id = "m1", direction = Direction.incoming, kind = Kind.text, text = "hi", timestamp = 9_000L, status = Status.delivered)
        // "old" just received a message: it jumps to the top; the others rank by when they were added
        assertEquals(listOf("old", "new", "mid"), orderedByActivity(listOf(old, mid, new), mapOf("a" to listOf(msg))).map { it.name })
    }
}
