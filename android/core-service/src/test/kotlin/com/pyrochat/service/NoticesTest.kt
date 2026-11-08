package com.pyrochat.service

import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Operator notices (docs/PROTOCOL.md §"Operator notices"): posted through the dev relay's admin
 * API (`op:dev-admin-pw` on :7444), received live and at login, removed by `notice_gone`, seen ids
 * persisted through [NoticeStore], malformed frames ignored. Every posted notice is deleted in
 * [cleanup]; notices other agents may have posted on the shared dev relay are left alone (the
 * assertions are relative to what was there at login).
 */
class NoticesTest {
    // the relay's admin server closes the connection after every response and makeClient() disables
    // OkHttp's silent retry, so each admin call asks for a fresh connection (`Connection: close`)
    private val client = RelayHttp.makeClient(null)
    private val auth = Credentials.basic("op", "dev-admin-pw")
    private val admin = "http://${Live.HOST}:${Live.PORT}/admin/notices"
    private val posted = ArrayList<String>()

    private fun post(text: String, url: String? = null, ttl: Long = 300): String {
        val body = buildJsonObject { put("text", text); if (url != null) put("url", url); put("ttl", ttl) }.toString()
        val req = Request.Builder().url(admin).header("Authorization", auth).header("Connection", "close").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val json = resp.body.string()
            assertEquals(200, resp.code, json)
            val frame = Json.parseToJsonElement(json).jsonObject
            assertEquals("notice", frame["t"]?.jsonPrimitive?.content)
            return frame.getValue("id").jsonPrimitive.content.also { posted += it }
        }
    }

    private fun delete(id: String): Int {
        val req = Request.Builder().url("$admin/$id").header("Authorization", auth).header("Connection", "close").delete().build()
        return client.newCall(req).execute().use { it.code }
    }

    @AfterEach
    fun cleanup() {
        for (id in posted) runCatching { delete(id) }
        posted.clear()
    }

    @Test
    fun `a logged-in service receives a posted notice live and counts it unseen`(): Unit = runBlocking {
        Live.requireRelay()
        val u = Live.makeService("nt")
        try {
            u.svc.register(u.username)
            delay(500) // the login replay (`ready`, `prekey_count`, notices) has landed
            val baseline = u.svc.notices.value.size
            assertEquals(baseline, u.svc.unseenNoticeCount.value, "nothing was opened yet")
            val tones = ArrayList<Tone>()
            u.svc.soundHook = { tones += it }

            val id = post("relay maintenance tonight 02:00–02:10 UTC. messages queue, nothing is lost.", "https://pyrochat.ca/", ttl = 600)
            assertTrue(waitUntil { u.svc.notices.value.any { it.id == id } }, "a live notice reaches a connected client within seconds")
            val n = u.svc.notices.value.first { it.id == id }
            assertEquals("relay maintenance tonight 02:00–02:10 UTC. messages queue, nothing is lost.", n.text)
            assertEquals("https://pyrochat.ca/", n.url)
            assertTrue(n.ts.isAfter(Instant.now().minusSeconds(60)) && !n.ts.isAfter(Instant.now().plusSeconds(5)), "ts = when it was posted: ${n.ts}")
            assertEquals(600L, n.expires.epochSecond - n.ts.epochSecond, "expires = ts + ttl")
            assertEquals(baseline + 1, u.svc.notices.value.size)
            assertEquals(baseline + 1, u.svc.unseenNoticeCount.value, "one unseen notice (== 1 on a clean relay)")
            assertEquals(listOf(Tone.RX), tones, "a live arrival blips like an incoming message")
            assertEquals(u.svc.notices.value.sortedBy { it.ts }, u.svc.notices.value, "oldest first")

            // the relay replaying it (a reconnect) is an upsert, not a second row and not a second blip
            u.svc.goOffline(); u.svc.goOnline()
            delay(800)
            assertEquals(1, u.svc.notices.value.count { it.id == id })
            assertEquals(listOf(Tone.RX), tones)
            u.svc.goOffline()
        } finally {
            u.close()
        }
    }

    @Test
    fun `a service logging in after the post gets it at login`(): Unit = runBlocking {
        Live.requireRelay()
        val id = post("new version 0.5 is out. update from the store.", ttl = 600)
        val u = Live.makeService("nt")
        try {
            assertTrue(u.svc.notices.value.none { it.id == id }, "fresh device: nothing before login")
            u.svc.register(u.username)
            assertTrue(waitUntil { u.svc.notices.value.any { it.id == id } }, "replayed right after ready + prekey_count")
            assertNull(u.svc.notices.value.first { it.id == id }.url, "url omitted by the relay ⇒ null")
            assertTrue(u.svc.unseenNoticeCount.value >= 1)
            u.svc.goOffline()
        } finally {
            u.close()
        }
    }

    @Test
    fun `DELETE removes the notice everywhere`(): Unit = runBlocking {
        Live.requireRelay()
        val u = Live.makeService("nt")
        try {
            u.svc.register(u.username)
            val id = post("payments open tomorrow", ttl = 600)
            assertTrue(waitUntil { u.svc.notices.value.any { it.id == id } })
            u.svc.markNoticesSeen()
            assertEquals(200, delete(id))
            assertTrue(waitUntil { u.svc.notices.value.none { it.id == id } }, "notice_gone drops it live")
            assertEquals(0, u.svc.unseenNoticeCount.value)
            // and a later login never sees it
            val late = Live.makeService("nt")
            try {
                late.svc.register(late.username)
                delay(800)
                assertTrue(late.svc.notices.value.none { it.id == id })
                late.svc.goOffline()
            } finally {
                late.close()
            }
            u.svc.goOffline()
        } finally {
            u.close()
        }
    }

    @Test
    fun `seen ids and notices persist across a new ChatService on the same NoticeStore`(): Unit = runBlocking {
        Live.requireRelay()
        val store = InMemoryNoticeStore()
        val first = Live.makeService("nt", noticeStore = store)
        val id: String
        try {
            first.svc.register(first.username)
            id = post("maintenance window", "https://pyrochat.ca/", ttl = 600)
            assertTrue(waitUntil { first.svc.notices.value.any { it.id == id } })
            assertTrue(first.svc.unseenNoticeCount.value >= 1)
            first.svc.markNoticesSeen()
            assertEquals(0, first.svc.unseenNoticeCount.value)
            first.svc.goOffline()
        } finally {
            first.close()
        }
        assertNotNull(store.getNotices()); assertTrue(id in store.getSeen())

        // relaunch: the channel is populated BEFORE any login, and nothing is unseen
        val second = Live.makeService("nt", noticeStore = store)
        try {
            assertEquals("maintenance window", second.svc.notices.value.first { it.id == id }.text)
            assertEquals("https://pyrochat.ca/", second.svc.notices.value.first { it.id == id }.url)
            assertEquals(0, second.svc.unseenNoticeCount.value, "seen survived the relaunch")
            // the login replay upserts the same id: still one row, still seen
            second.svc.register(second.username)
            delay(800)
            assertEquals(1, second.svc.notices.value.count { it.id == id })
            assertEquals(0, second.svc.unseenNoticeCount.value)
            second.svc.goOffline()
        } finally {
            second.close()
        }

        // a corrupt preference file is not fatal: empty channel, next login repopulates
        store.putNotices("{not json")
        val third = Live.makeService("nt", noticeStore = store)
        try {
            assertTrue(third.svc.notices.value.isEmpty())
        } finally {
            third.close()
        }
    }

    @Test
    fun `malformed notice frames are ignored, notice_gone for an unknown id is a no-op`(): Unit = runBlocking {
        val u = Live.makeService("nt")
        try {
            val now = Instant.now().epochSecond
            val bad = listOf(
                """{"t":"notice","id":"m1","text":"   ","ts":$now,"expires":${now + 600}}""",             // blank text
                """{"t":"notice","id":"m2","ts":$now,"expires":${now + 600}}""",                          // no text at all
                """{"t":"notice","id":"m3","text":"x","url":"http://pyrochat.ca/","ts":$now,"expires":${now + 600}}""", // not https
                """{"t":"notice","id":"m4","text":"x","url":"https://pyro chat.ca/","ts":$now,"expires":${now + 600}}""", // whitespace in url
                """{"t":"notice","id":"m5","text":"x","ts":$now,"expires":${now - 1}}""",                 // already expired
                """{"t":"notice","id":"m6","text":"x","ts":$now,"expires":$now}""",                       // expires == now
                """{"t":"notice","id":"m7","text":"x","ts":$now}""",                                      // no expiry
                """{"t":"notice","id":"","text":"x","ts":$now,"expires":${now + 600}}""",                 // blank id
                """{"t":"notice","id":"m8","text":"${"y".repeat(501)}","ts":$now,"expires":${now + 600}}""", // over the relay cap
            )
            for (json in bad) {
                u.svc.routeForTest(Wire.decodeIn(json))
                assertTrue(u.svc.notices.value.isEmpty(), json)
            }
            assertEquals(0, u.svc.unseenNoticeCount.value)
            // structurally broken frames never even decode (the link drops them as Undecodable)
            assertFailsWith<SerializationException> { Wire.decodeIn("""{"t":"notice","text":"x"}""") }
            u.svc.routeForTest(Wire.In.NoticeGone("never-seen"))
            assertTrue(u.svc.notices.value.isEmpty())

            // the same path accepts a well-formed frame, trims the text, and upserts by id
            u.svc.routeForTest(Wire.decodeIn("""{"t":"notice","id":"ok1","text":"  hello  ","ts":${now - 10},"expires":${now + 600},"extra":1}"""))
            assertEquals(listOf("hello"), u.svc.notices.value.map { it.text })
            assertEquals(1, u.svc.unseenNoticeCount.value)
            u.svc.routeForTest(Wire.In.Notice("ok1", "hello again", "https://pyrochat.ca/", now - 10, now + 600))
            assertEquals(listOf("hello again"), u.svc.notices.value.map { it.text })
            assertEquals(1, u.svc.unseenNoticeCount.value, "an upsert is not a new unseen notice")
            // oldest first, whatever the arrival order
            u.svc.routeForTest(Wire.In.Notice("ok0", "older", null, now - 100, now + 600))
            assertEquals(listOf("ok0", "ok1"), u.svc.notices.value.map { it.id })
            assertEquals(2, u.svc.unseenNoticeCount.value)
            u.svc.markNoticesSeen()
            assertEquals(0, u.svc.unseenNoticeCount.value)
            // The sweeper drops a notice at its expiry (1 s tick). The clock is read again for
            // this one: `now` is a whole second from the top of the test, and a notice that is
            // already at its expiry when it arrives is dropped on arrival. With `now + 1` that
            // happened whenever the test began just before a second turned.
            val soon = Instant.now().epochSecond + 2
            u.svc.routeForTest(Wire.In.Notice("soon", "gone in a moment", null, now, soon))
            assertEquals(3, u.svc.notices.value.size)
            assertTrue(waitUntil(6_000) { u.svc.notices.value.none { it.id == "soon" } }, "swept at expires")
            assertFalse(u.svc.notices.value.any { it.id == "soon" })
            u.svc.routeForTest(Wire.In.NoticeGone("ok1"))
            assertEquals(listOf("ok0"), u.svc.notices.value.map { it.id })
        } finally {
            u.close()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
