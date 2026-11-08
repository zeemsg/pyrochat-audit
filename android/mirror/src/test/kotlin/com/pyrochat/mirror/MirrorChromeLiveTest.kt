package com.pyrochat.mirror

import com.pyrochat.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The way the iOS engine was verified: headless Chrome loads the REAL page the relay serves at
 * `http://127.0.0.1:7444/web#test`, the test reads `window.__qrPayload` over CDP, pairs the Kotlin
 * engine with it, and drives the page's own `doSend()` / `[UNLINK]`. Skipped without a relay or a
 * Chrome binary.
 */
class MirrorChromeLiveTest {
    private val alice = MirrorContact("a".repeat(64), "alice", verified = true, addedAt = 1_000)
    private val http = OkHttpClient()

    companion object {
        fun chromeBinary(): String? = listOf(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
        ).firstOrNull { File(it).canExecute() }
    }

    @BeforeEach
    fun requireRelayAndChrome() {
        Assumptions.assumeTrue(relayListening(), "SKIPPED: no relay listening on $RELAY_HOST:$RELAY_PORT")
        Assumptions.assumeTrue(chromeBinary() != null, "SKIPPED: no headless Chrome/Chromium binary found")
    }

    /** Minimal Chrome DevTools Protocol client: `Runtime.evaluate` with returnByValue. */
    private class Cdp(url: String, http: OkHttpClient) {
        private val ids = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val opened = CompletableDeferred<Unit>()
        private val ws: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = json.parseToJsonElement(text).jsonObject
                val id = m["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                pending.remove(id)?.complete(m)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.completeExceptionally(t) }
        })

        suspend fun connect() = withTimeout(10_000) { opened.await() }

        suspend fun eval(expression: String): JsonElement {
            val id = ids.incrementAndGet()
            val d = CompletableDeferred<JsonObject>()
            pending[id] = d
            ws.send(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("id", id); put("method", "Runtime.evaluate")
                put("params", buildJsonObject { put("expression", expression); put("returnByValue", true) })
            }))
            val reply = withTimeout(10_000) { d.await() }
            val result = reply["result"]?.jsonObject ?: fail("CDP error: $reply")
            result["exceptionDetails"]?.let { fail("page threw for `$expression`: $it") }
            return result["result"]?.jsonObject?.get("value") ?: JsonNull
        }

        suspend fun evalString(expression: String): String? = (eval(expression) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Poll until the expression yields a non-null, non-empty, non-"false" value. */
        suspend fun until(expression: String, timeoutMs: Long = 20_000, what: String = expression): JsonElement {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val v = eval(expression)
                val s = (v as? JsonPrimitive)?.content
                if (v !is JsonNull && s != "" && s != "false" && s != "null") return v
                if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what (last: $v)")
                delay(150)
            }
        }

        fun close() = ws.close(1000, null)
    }

    private suspend fun findPageTarget(port: Int, profile: File): String {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            val body = try {
                http.newCall(Request.Builder().url("http://127.0.0.1:$port/json").build()).execute().use { it.body.string() }
            } catch (_: Exception) {
                null
            }
            val target = body?.let { json.parseToJsonElement(it).jsonArray.map { t -> t.jsonObject } }
                ?.firstOrNull { it.strOrNull("type") == "page" && (it.strOrNull("url") ?: "").contains("/web") }
            if (target != null) return target.str("webSocketDebuggerUrl")
            if (System.currentTimeMillis() > deadline) fail("Chrome never exposed the page target (see $profile/chrome.log)")
            delay(200)
        }
    }

    @Test
    fun realWebPagePairsWithTheKotlinEngine(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val profile = File("build/tmp/chrome-${UUID.randomUUID()}").absoluteFile.also { it.mkdirs() }
        val pageUrl = "http://$RELAY_HOST:$RELAY_PORT/web#test"
        val chrome = ProcessBuilder(
            chromeBinary()!!, "--headless=new", "--remote-debugging-port=$port", "--user-data-dir=${profile.path}",
            "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--disable-extensions",
            "--disable-background-networking", "--window-size=1200,800", pageUrl,
        ).redirectErrorStream(true).redirectOutput(File(profile, "chrome.log")).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var cdp: Cdp? = null
        try {
            // ---- find the page target ----
            val debuggerUrl = findPageTarget(port, profile)
            val c = Cdp(debuggerUrl, http).also { cdp = it }
            c.connect()
            println("[chrome] page target up: $pageUrl (CDP $debuggerUrl)")
            // the target can be listed before navigation commits — wait for the real document
            c.until(
                """document.readyState === "complete" && location.href.includes("/web") && window.isSecureContext && !!crypto.subtle ? "ok" : """"",
                what = "the page to load in a secure context",
            )

            // ---- the page opened a pair and rendered its QR: read the payload from the DOM/JS ----
            val payload = c.until("window.__qrPayload", what = "window.__qrPayload (page must reach pair_opened)").jsonPrimitive.content
            println("[chrome] QR payload from the page: $payload")
            val link = MirrorLink.parse(payload, LOCAL_RELAY_URL)
            assertEquals("$RELAY_HOST:$RELAY_PORT", link.host)

            // ---- phone joins ----
            val source = FakeSource()
            source.addContact(alice)
            source.append(alice.hash, textRow("m1", MirrorDirection.incoming, "hello from the phone side", 10_000))
            source.append(alice.hash, MirrorMessage("m2", MirrorDirection.incoming, MirrorKind.text, "burn secret", 11_000, MirrorStatus.delivered, isBurn = true))
            val engine = MirrorEngine(scope, MirrorEngine.Options(debounceMs = 50))
            val sas = engine.start(link, source)
            println("[phone] scanned → joining ${link.pairId}, SAS = $sas")
            val linked = withTimeout(20_000) { engine.state.first { it is MirrorEngine.State.Linked && it.browserName != null } } as MirrorEngine.State.Linked
            println("[phone] hello_ack received: browserName=${linked.browserName}")
            assertNotNull(linked.browserName)
            assertTrue(linked.browserName!!.startsWith("Chrome"), "headless Chrome must label itself Chrome: ${linked.browserName}")
            assertTrue(source.webLinks.tryReceive().isFailure, "NOTHING is announced at link time (MIRROR-GROUPS.md §5)")

            // ---- the page verified the SAS and shows the same code ----
            val info = c.until("""document.getElementById("linkinfo").textContent""").jsonPrimitive.content
            println("[chrome] header: \"$info\"")
            assertEquals("linked to <andro> - code $sas", info)
            c.until("""store && store.threads.get("${alice.hash}") && store.threads.get("${alice.hash}").order.length === 2 ? "ok" : """"", what = "the mirrored thread (2 rows)")
            assertEquals("PHONE:ONLINE", c.evalString("""document.getElementById("phonestate").textContent"""))
            assertEquals("burn", c.evalString("""store.threads.get("${alice.hash}").rows.get("m2").kind"""))
            assertEquals("undefined", c.eval("""typeof store.threads.get("${alice.hash}").rows.get("m2").text""").jsonPrimitive.content)
            assertEquals("hello from the phone side", c.evalString("""store.threads.get("${alice.hash}").rows.get("m1").text"""))
            println("[chrome] snapshot applied: 2 rows, burn row has kind=burn and no text")

            // ---- open alice on the page (the composer is off until a thread is open): `view` → the
            // phone discloses the link to alice only, before anything is sent (MIRROR-GROUPS.md §5) ----
            c.eval("""openThread("${alice.hash}"); "ok"""")
            assertEquals(Triple(alice.hash, true, linked.browserName), withTimeout(10_000) { source.webLinks.receive() })
            println("[chrome] openThread(alice) → view → [phone] web_link {linkUp:true, browser:${linked.browserName}} → alice (contextual disclosure)")

            // ---- type in the page and press its send: doSend() → emitCmd(send_text) ----
            c.eval("""document.getElementById("input").value = "hello from chrome"; doSend(); "ok"""")
            println("[chrome] doSend() with \"hello from chrome\"")
            assertEquals("hello from chrome" to alice.hash, withTimeout(10_000) { source.sentTexts.receive() })
            println("[phone] send_text decrypted → sendText(\"hello from chrome\", alice)")
            assertTrue(source.webLinks.tryReceive().isFailure, "alice was told on the view: never twice per link")
            // openThread() and doSend() both call markRead() for the open thread: only m1 (m2 is burn) ever reaches the phone
            assertEquals("m1" to alice.hash, withTimeout(10_000) { source.visible.receive() })
            while (true) {
                val again = withTimeoutOrNull(1_000) { source.visible.receive() } ?: break
                assertEquals("m1" to alice.hash, again, "the burn row m2 must never be marked read from the browser")
            }
            println("[phone] mark_read → noteRowVisible(m1) only")
            // the echo carrying the tempId replaces the page's local row
            c.until("""pendingTemp.size === 0 && [...store.threads.get("${alice.hash}").order].every(id => !id.startsWith("local-")) ? "ok" : """"", what = "the tempId echo replacing the local row")
            val last = c.eval("""(() => { const t = store.threads.get("${alice.hash}"); const r = t.rows.get(t.order[t.order.length-1]); return JSON.stringify({text:r.text, dir:r.dir, via:r.via, status:r.status, tempId:r.tempId}); })()""").jsonPrimitive.content
            println("[chrome] last row after echo: $last")
            val lastRow = json.parseToJsonElement(last).jsonObject
            assertEquals("hello from chrome", lastRow.str("text"))
            assertEquals("out", lastRow.str("dir"))
            assertEquals("web", lastRow.str("via"))
            assertEquals("pending", lastRow.str("status"))

            // ---- [UNLINK] on the page: {c:"bye"} then it closes its socket → relay pair_closed → browser_gone ----
            c.eval("""document.getElementById("unlink").onclick(); "ok"""")
            val closed = withTimeout(20_000) { engine.state.first { it is MirrorEngine.State.Closed } } as MirrorEngine.State.Closed
            println("[phone] page unlinked → state Closed(${closed.reason})")
            assertEquals("browser_gone", closed.reason)
            assertEquals(Triple(alice.hash, false, null), withTimeout(2_000) { source.webLinks.receive() }, "the unlink notice goes only to the announced thread")
            assertTrue(source.webLinks.tryReceive().isFailure)
            assertTrue((c.evalString("""document.getElementById("ovc").textContent""") ?: "").contains("LINK LOST"))
        } finally {
            cdp?.close()
            scope.cancel()
            chrome.destroy()
            chrome.waitFor()
            profile.deleteRecursively()
        }
    }
}
