package com.pyrochat.service

import com.pyrochat.protocol.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Latest versions (docs/PROTOCOL.md §"Latest versions"): the `versions` frame decodes with both
 * platforms optional, the client rule shows the banner for a strictly greater build only, a
 * lower/equal/absent entry clears it, the last accepted entry survives a relaunch through the
 * [NoticeStore] (same wipe as the notices), and a malformed frame is ignored as a whole. No relay
 * needed: frames go through [ChatService.routeForTest] like the malformed-notice test.
 */
class VersionsTest {
    private val url = "https://pyrochat.ca/android"

    // ---------------------------------------------------------------- wire

    @Test
    fun `the versions frame decodes with optional platforms and ignores unknown keys`() {
        val full = Wire.decodeIn("""{"t":"versions","ios":{"build":24,"name":"0.1.0","url":"https://x/"},"android":{"build":9,"name":"0.1.0","url":"$url","beta":true},"linux":{"build":1,"name":"x"}}""")
        assertIs<Wire.In.Versions>(full)
        assertEquals(Wire.VersionEntry(24, "0.1.0", "https://x/"), full.ios)
        assertEquals(Wire.VersionEntry(9, "0.1.0", url), full.android)

        val empty = Wire.decodeIn("""{"t":"versions"}""")
        assertIs<Wire.In.Versions>(empty)
        assertNull(empty.ios); assertNull(empty.android)

        val partial = Wire.decodeIn("""{"t":"versions","android":{"build":9,"name":"0.1.0"}}""")
        assertIs<Wire.In.Versions>(partial)
        assertNull(partial.ios); assertNull(partial.android?.url)

        val nulled = Wire.decodeIn("""{"t":"versions","ios":null,"android":null}""")
        assertIs<Wire.In.Versions>(nulled)
        assertNull(nulled.android)

        // a missing field decodes to the default and fails validation later; a wrong TYPE never decodes
        assertEquals(Wire.VersionEntry(0, "", null), (Wire.decodeIn("""{"t":"versions","android":{}}""") as Wire.In.Versions).android)
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"versions","android":{"build":"nine","name":"0.1.0"}}""") }
        assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"versions","android":"9"}""") }
    }

    // ---------------------------------------------------------------- the pure rule

    @Test
    fun `entry validates build and name, clamps the name and drops a non-https url`() {
        assertEquals(LatestVersion(24, "0.1.0", url), UpdateRules.entry(24, "0.1.0", url))
        assertEquals(LatestVersion(1, "0.1.0", null), UpdateRules.entry(1, " 0.1.0 ", null), "trimmed, no url")
        assertNull(UpdateRules.entry(0, "0.1.0", url), "build must be ≥ 1")
        assertNull(UpdateRules.entry(-5, "0.1.0", url))
        assertNull(UpdateRules.entry(24, "   ", url), "blank name")
        assertEquals("x".repeat(32), UpdateRules.entry(24, "x".repeat(40), null)?.name, "name clamped to 32")
        assertNull(UpdateRules.entry(24, "0.1.0", "http://pyrochat.ca/")?.url, "http dropped, entry kept")
        assertNull(UpdateRules.entry(24, "0.1.0", "https://pyro chat.ca/")?.url, "whitespace in url ⇒ dropped")
        assertNull(UpdateRules.entry(24, "0.1.0", "https://pyrochat.ca/" + "a".repeat(300))?.url, "over 300 chars ⇒ dropped")
        assertEquals(url, UpdateRules.entry(24, "0.1.0", "  $url  ")?.url, "url trimmed")
        assertNull(UpdateRules.entry(24, "0.1.0", "")?.url)
    }

    @Test
    fun `update shows for a strictly greater build only`() {
        val v = LatestVersion(24, "0.1.0", url)
        assertEquals(v, UpdateRules.update(v, 9))
        assertEquals(v, UpdateRules.update(v, 23))
        assertNull(UpdateRules.update(v, 24), "equal ⇒ current")
        assertNull(UpdateRules.update(v, 25), "lower ⇒ current (a dev build ahead of the relay)")
        assertNull(UpdateRules.update(null, 9), "absent ⇒ clear")
        assertEquals(v, UpdateRules.update(v, 0), "0 = unknown running build: every advertised build is newer")
    }

    @Test
    fun `fromFrame reads our platform only and ignores a malformed frame as a whole`() {
        val ok = Wire.In.Versions(ios = Wire.VersionEntry(24, "0.1.0", "https://x/"), android = Wire.VersionEntry(10, "0.1.0", url))
        assertEquals(UpdateRules.Verdict.Accepted(LatestVersion(10, "0.1.0", url), LatestVersion(10, "0.1.0", url)), UpdateRules.fromFrame(ok, 9))
        assertEquals(UpdateRules.Verdict.Accepted(LatestVersion(10, "0.1.0", url), null), UpdateRules.fromFrame(ok, 10), "equal: remembered, no banner")
        assertEquals(UpdateRules.Verdict.Accepted(null, null), UpdateRules.fromFrame(Wire.In.Versions(), 9), "both absent: clear")
        assertEquals(UpdateRules.Verdict.Accepted(null, null), UpdateRules.fromFrame(Wire.In.Versions(ios = Wire.VersionEntry(99, "9.9")), 9), "ios only: nothing for us")
        assertEquals(UpdateRules.Verdict.Malformed, UpdateRules.fromFrame(Wire.In.Versions(android = Wire.VersionEntry(0, "0.1.0")), 9))
        assertEquals(UpdateRules.Verdict.Malformed, UpdateRules.fromFrame(Wire.In.Versions(android = Wire.VersionEntry(24, "")), 9))
        assertEquals(UpdateRules.Verdict.Malformed, UpdateRules.fromFrame(Wire.In.Versions(ios = Wire.VersionEntry(0, "x"), android = Wire.VersionEntry(24, "0.1.0")), 9), "a malformed ios entry sinks the whole frame")
    }

    @Test
    fun `the codec round-trips and never throws`() {
        val v = LatestVersion(24, "0.1.0", url)
        assertEquals(v, VersionCodec.decode(VersionCodec.encode(v)))
        assertEquals(LatestVersion(24, "0.1.0", null), VersionCodec.decode(VersionCodec.encode(LatestVersion(24, "0.1.0", null))))
        assertEquals("""{"build":24,"name":"0.1.0","url":"$url"}""", VersionCodec.encode(v), "the wire entry shape, so iOS could read it")
        assertNull(VersionCodec.decode(null)); assertNull(VersionCodec.decode("")); assertNull(VersionCodec.decode("{not json"))
        assertNull(VersionCodec.decode("""{"build":0,"name":"x"}"""), "re-validated on the way in")
        assertNull(VersionCodec.decode("""{"build":24,"name":"0.1.0","url":"http://x/"}""")?.url)
        assertEquals(v, VersionCodec.decode("""{"build":24,"name":"0.1.0","url":"$url","extra":1}"""), "unknown keys ignored")
    }

    // ---------------------------------------------------------------- ChatService: ingest, persistence, clear

    @Test
    fun `a versions frame sets, replaces and clears the banner and remembers the entry`(): Unit = runBlocking {
        val store = InMemoryNoticeStore()
        val u = Live.makeService("vt", noticeStore = store, versionCode = 9)
        try {
            val tones = ArrayList<Tone>()
            u.svc.soundHook = { tones += it }
            assertNull(u.svc.updateAvailable.value, "nothing before the first frame")

            u.svc.routeForTest(Wire.decodeIn("""{"t":"versions","ios":{"build":24,"name":"0.1.0","url":"https://testflight.apple.com/x"},"android":{"build":24,"name":"0.1.0","url":"$url"}}"""))
            assertEquals(LatestVersion(24, "0.1.0", url), u.svc.updateAvailable.value)
            assertEquals("""{"build":24,"name":"0.1.0","url":"$url"}""", store.getVersions(), "remembered next to the notices")
            assertTrue(tones.isEmpty(), "never a blip: it is one row, not a notice")
            assertTrue(u.svc.notices.value.isEmpty(), "never a notice row either")

            // the operator bumps it: replaced; drops the url: not tappable but still shown
            u.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(25, "0.1.1")))
            assertEquals(LatestVersion(25, "0.1.1", null), u.svc.updateAvailable.value)
            assertEquals("""{"build":25,"name":"0.1.1"}""", store.getVersions())

            // malformed frames change nothing — as a whole, even when our own entry is fine
            for (json in listOf(
                """{"t":"versions","android":{"build":0,"name":"0.1.0"}}""",                                  // build < 1
                """{"t":"versions","android":{"name":"0.1.0"}}""",                                            // no build
                """{"t":"versions","android":{"build":30}}""",                                                // no name
                """{"t":"versions","android":{"build":30,"name":"  "}}""",                                    // blank name
                """{"t":"versions","ios":{"build":0,"name":"x"},"android":{"build":30,"name":"0.2.0"}}""",     // sibling entry malformed
            )) {
                u.svc.routeForTest(Wire.decodeIn(json))
                assertEquals(LatestVersion(25, "0.1.1", null), u.svc.updateAvailable.value, json)
                assertEquals("""{"build":25,"name":"0.1.1"}""", store.getVersions(), json)
            }

            // name clamped, http url dropped, unknown keys ignored
            u.svc.routeForTest(Wire.decodeIn("""{"t":"versions","android":{"build":26,"name":"${"n".repeat(40)}","url":"http://pyrochat.ca/","x":1},"linux":{"build":1,"name":"x"}}"""))
            assertEquals(LatestVersion(26, "n".repeat(32), null), u.svc.updateAvailable.value)

            // equal build: the banner clears, the entry is still remembered (it is the latest the relay knows)
            u.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(9, "0.0.9", url)))
            assertNull(u.svc.updateAvailable.value)
            assertEquals("""{"build":9,"name":"0.0.9","url":"$url"}""", store.getVersions())
            // lower build: same
            u.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(3, "0.0.3", null)))
            assertNull(u.svc.updateAvailable.value)
            assertNotNull(store.getVersions())

            // back up, then a frame without our platform (`{"t":"versions"}`, or ios only) clears everything
            u.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(24, "0.1.0", url)))
            assertEquals(LatestVersion(24, "0.1.0", url), u.svc.updateAvailable.value)
            u.svc.routeForTest(Wire.decodeIn("""{"t":"versions"}"""))
            assertNull(u.svc.updateAvailable.value, "absent ⇒ clear")
            assertNull(store.getVersions(), "nothing remembered: a stale banner must not come back offline")
            u.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(24, "0.1.0", url)))
            u.svc.routeForTest(Wire.In.Versions(ios = Wire.VersionEntry(24, "0.1.0")))
            assertNull(u.svc.updateAvailable.value, "ios only ⇒ clear")
            assertNull(store.getVersions())
            assertTrue(tones.isEmpty())
        } finally {
            u.close()
        }
    }

    @Test
    fun `the remembered entry shows offline on a new ChatService, re-checked against the running build, and dies with the wipe`(): Unit = runBlocking {
        val store = InMemoryNoticeStore()
        val first = Live.makeService("vt", noticeStore = store, versionCode = 9)
        try {
            first.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(24, "0.1.0", url)))
            assertEquals(LatestVersion(24, "0.1.0", url), first.svc.updateAvailable.value)
        } finally {
            first.close()
        }
        assertNotNull(store.getVersions())

        // relaunch, still on build 9, no login: the banner is there before any frame
        val second = Live.makeService("vt", noticeStore = store, versionCode = 9)
        try {
            assertEquals(LatestVersion(24, "0.1.0", url), second.svc.updateAvailable.value)
        } finally {
            second.close()
        }

        // the user updated to 24: the same remembered entry no longer qualifies
        val updated = Live.makeService("vt", noticeStore = store, versionCode = 24)
        try {
            assertNull(updated.svc.updateAvailable.value, "equal build ⇒ current")
        } finally {
            updated.close()
        }
        val ahead = Live.makeService("vt", noticeStore = store, versionCode = 30)
        try {
            assertNull(ahead.svc.updateAvailable.value)
        } finally {
            ahead.close()
        }

        // a corrupt preference is not fatal: no banner until the next login
        store.putVersions("{not json")
        val corrupt = Live.makeService("vt", noticeStore = store, versionCode = 9)
        try {
            assertNull(corrupt.svc.updateAvailable.value)
            corrupt.svc.routeForTest(Wire.In.Versions(android = Wire.VersionEntry(24, "0.1.0", url)))
            assertEquals(LatestVersion(24, "0.1.0", url), corrupt.svc.updateAvailable.value)
        } finally {
            corrupt.close()
        }

        // the local wipe clears the notices AND the remembered version together
        store.putNotices("[]"); store.putSeen(setOf("n1"))
        store.clear()
        assertNull(store.getVersions()); assertNull(store.getNotices()); assertTrue(store.getSeen().isEmpty())
        val wiped = Live.makeService("vt", noticeStore = store, versionCode = 9)
        try {
            assertNull(wiped.svc.updateAvailable.value)
        } finally {
            wiped.close()
        }
    }
}
