package com.pyrochat.mirror

import com.pyrochat.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live against the local dev relay's pair channel (`ws://127.0.0.1:7444/ws`): a fake browser written
 * here (same crypto as the page) opens the pair, the REAL engine joins over the REAL OkHttp transport
 * (through `MirrorHttp.makeClient`), and both directions are checked byte-exact. Skipped when
 * nothing listens on 7444.
 */
class MirrorRelayLiveTest {
    private val alice = MirrorContact("a".repeat(64), "alice", verified = true, addedAt = 1_000)

    @BeforeEach
    fun requireRelay() {
        Assumptions.assumeTrue(relayListening(), "SKIPPED: no relay listening on $RELAY_HOST:$RELAY_PORT — start the local dev relay (web.enabled=true)")
    }

    @Test
    fun pairChannelRoundTripAgainstLocalRelay(): Unit = runBlocking {
        val http = MirrorHttp.makeClient()
        val browserWs = WsText(LOCAL_RELAY_URL, http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = FakeSource()
        source.addContact(alice)
        source.append(alice.hash, textRow("m1", MirrorDirection.incoming, "hello phone", 10_000))
        source.append(alice.hash, MirrorMessage("m2", MirrorDirection.incoming, MirrorKind.text, "burn secret", 11_000, MirrorStatus.delivered, isBurn = true))
        val engine = MirrorEngine(scope, MirrorEngine.Options(debounceMs = 50))   // default OkHttp transport
        try {
            // ---- browser: pair_open → QR ----
            browserWs.connect()
            browserWs.send(buildJsonObject { put("t", "pair_open") })
            val opened = browserWs.expect("pair_opened")
            val browser = TestBrowser().also { it.pairId = opened.str("pair") }
            assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(browser.pairId))
            val payload = browser.qrPayload("$RELAY_HOST:$RELAY_PORT")
            println("[browser] pair_opened pair=${browser.pairId} ttl=${opened["ttl"]}; QR payload = $payload")

            // ---- phone: scan → join ----
            val link = MirrorLink.parse(payload, source.relayUrl)
            val sas = engine.start(link, source)
            println("[phone] joining pair, SAS shown on phone = $sas")
            browserWs.expect("pair_joined")
            println("[relay→browser] pair_joined")

            // ---- 0x01 hello ----
            val helloFrame = browserWs.expect("pair_msg")
            assertEquals(browser.pairId, helloFrame.str("pair"))
            val hello = browser.onRecord(helloFrame.str("body")) as TestBrowser.Decoded.Hello
            println("[phone→browser] 0x01 hello: ${hello.plaintext}")
            assertEquals(sas, browser.keys!!.sas, "SAS derived by the browser must match the phone's")
            assertEquals("""{"v":1,"name":"andro","showRead":true,"sas":"$sas"}""", hello.plaintext)
            println("[browser] derived SAS = ${browser.keys!!.sas} ✓ matches")

            // ---- 0x02 ack ----
            browserWs.send(buildJsonObject { put("t", "pair_msg"); put("pair", browser.pairId); put("body", browser.ackBody("Test on JVM")) })
            println("[browser→phone] 0x02 hello_ack {\"v\":1,\"browser\":\"Test on JVM\"}")
            val linked = withTimeout(10_000) { engine.state.first { it is MirrorEngine.State.Linked && it.browserName != null } } as MirrorEngine.State.Linked
            assertEquals("Test on JVM", linked.browserName)
            assertTrue(source.webLinks.tryReceive().isFailure, "NOTHING is announced at link time (MIRROR-GROUPS.md §5)")
            println("[phone] linked, browserName=${linked.browserName}, no web_link yet (contextual disclosure)")

            // ---- snapshot ----
            val events = ArrayList<TestBrowser.Decoded.Event>()
            while (true) {
                val f = browserWs.expect("pair_msg")
                val e = browser.onRecord(f.str("body")) as TestBrowser.Decoded.Event
                println("[phone→browser] 0x03 seq=${e.seq} ${e.plaintext}")
                events.add(e)
                if (e.obj.str("e") == "snapshot_end") break
            }
            assertEquals(listOf("snapshot_begin", "thread", "conn", "snapshot_end"), events.map { it.obj.str("e") })
            assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.seq })
            val rows = events[1].obj["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals("""{"id":"m1","dir":"in","kind":"text","ts":10000,"status":"delivered","text":"hello phone"}""", json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), rows[0]))
            assertEquals("""{"id":"m2","dir":"in","kind":"burn","ts":11000,"status":"delivered"}""", json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), rows[1]))
            assertEquals("""{"e":"conn","up":true}""", events[2].plaintext)

            // ---- browser → phone: view (the page opened alice) → web_link to alice, once ----
            browserWs.send(buildJsonObject { put("t", "pair_msg"); put("pair", browser.pairId); put("body", browser.cmdBody(buildJsonObject { put("c", "view"); put("thread", alice.hash) })) })
            assertEquals(Triple(alice.hash, true, "Test on JVM"), withTimeout(5_000) { source.webLinks.receive() })
            println("[browser→phone] view alice → [phone] sendWebLink(alice, up, \"Test on JVM\")")

            // ---- browser → phone: send_text ----
            val cmd = buildJsonObject { put("c", "send_text"); put("thread", alice.hash); put("text", "hello from the browser"); put("tempId", "tmp1-live") }
            browserWs.send(buildJsonObject { put("t", "pair_msg"); put("pair", browser.pairId); put("body", browser.cmdBody(cmd)) })
            println("[browser→phone] 0x03 seq=${browser.txSeq} ${json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), cmd)}")
            assertEquals("hello from the browser" to alice.hash, withTimeout(5_000) { source.sentTexts.receive() })
            assertTrue(source.webLinks.tryReceive().isFailure, "alice was announced by the view: never twice per link")
            println("[phone] decrypted send_text → ChatService.sendText(\"hello from the browser\", alice, viaWeb: true)")
            val echo = browser.onRecord(browserWs.expect("pair_msg").str("body")) as TestBrowser.Decoded.Event
            println("[phone→browser] 0x03 seq=${echo.seq} ${echo.plaintext}")
            assertEquals(5L, echo.seq)
            val row = echo.obj["row"]!!.jsonObject
            assertEquals("msg", echo.obj.str("e"))
            assertEquals("tmp1-live", row.str("tempId"))
            assertEquals("web", row.str("via"))
            assertEquals("hello from the browser", row.str("text"))

            // ---- mark_read only reaches non-burn mirrored ids ----
            browserWs.send(buildJsonObject {
                put("t", "pair_msg"); put("pair", browser.pairId)
                put("body", browser.cmdBody(buildJsonObject { put("c", "mark_read"); put("thread", alice.hash); put("ids", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("m1")); add(kotlinx.serialization.json.JsonPrimitive("m2")) }) }))
            })
            assertEquals("m1" to alice.hash, withTimeout(5_000) { source.visible.receive() })
            assertTrue(source.visible.tryReceive().isFailure, "burn row m2 must not be marked read from the browser")
            println("[phone] mark_read [m1,m2] → noteRowVisible(m1) only (m2 is a burn row)")

            // ---- a second joiner is refused by the relay ----
            val crasher = WsText(LOCAL_RELAY_URL, http)
            crasher.connect()
            crasher.send(buildJsonObject { put("t", "pair_join"); put("pair", browser.pairId) })
            assertEquals("unknown_pair", crasher.expect("error").str("code"))
            crasher.close()
            println("[relay] second pair_join for the same pair → error unknown_pair (slot already taken)")

            // ---- unlink ----
            engine.stop()
            val bye = browser.onRecord(browserWs.expect("pair_msg").str("body")) as TestBrowser.Decoded.Event
            assertEquals("""{"e":"bye","reason":"unlink"}""", bye.plaintext)
            val closed = browserWs.expect("pair_closed")
            println("[phone→browser] 0x03 seq=${bye.seq} ${bye.plaintext}; [relay→browser] pair_closed reason=${closed.strOrNull("reason")}")
            assertEquals("closed", closed.strOrNull("reason"))
            assertEquals(Triple(alice.hash, false, null), withTimeout(2_000) { source.webLinks.receive() }, "the unlink notice goes only to the announced thread")
            assertTrue(source.webLinks.tryReceive().isFailure)
            assertNull(engine.sas)
        } finally {
            browserWs.close()
            scope.cancel()
        }
    }
}
