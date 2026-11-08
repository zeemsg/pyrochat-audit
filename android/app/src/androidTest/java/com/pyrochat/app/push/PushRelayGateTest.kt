package com.pyrochat.app.push

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.backend.DeadmanSwitch
import com.pyrochat.app.backend.PushRegistration
import com.pyrochat.app.backend.RealBackend
import com.pyrochat.app.backend.ServerConfig
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.app.ui.ConnState
import com.pyrochat.app.ui.Stage
import com.pyrochat.crypto.RelayAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay registration path end to end over the REAL service layer, with the debug stand-in
 * token (`--es fcmToken`): create an account on the host's FCM-enabled test relay
 * (`10.0.2.2:7447`, see the session scratchpad `push-relay/`), turn the wake-push setting on
 * (`syncPushToken` → `{t:"push_token", platform:"fcm"}`), drop the socket, then have a raw peer
 * message this account: the relay must wake it — on the host that is the log line
 * `fcm 401 UNAUTHENTICATED … for <hash8>` (bogus bearer from the OAuth stub, so Google refuses;
 * the relay only gets that far when it HOLDS an fcm token for the recipient). Skips when the
 * harness relay is not up.
 */
@RunWith(AndroidJUnit4::class)
class PushRelayGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "push-gate")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-push-gate"),
        secretsAlias = "com.pyrochat.app.secrets.push-gate",
    )
    private val deadman = DeadmanSwitch(context.getSharedPreferences("pyro-push-gate", Context.MODE_PRIVATE))

    @Test fun fakeTokenReachesTheRelayAndAPeerMessageWakesIt(): Unit = runBlocking {
        assumeTrue("SKIPPED: no FCM test relay at $RELAY_HOST:$RELAY_PORT (scratchpad push-relay harness)", relayListening())
        paths.obliterate(); root.deleteRecursively(); root.mkdirs(); deadman.disarm()
        val backend = RealBackend(context, paths, deadman, haptics = null)
        backend.serverOverride = ServerConfig.Override(relay = RELAY, billing = BILLING)
        val user = "pushgate_" + hex(3)
        try {
            backend.finishBoot()
            assertNull(backend.createAccount(user, "push-gate-pass-123", relay = ""))
            assertEquals(Stage.Main, backend.stage.value)
            withTimeout(15_000) { while (backend.connState.value != ConnState.online) delay(100) }
            val appHash = backend.myHash
            println("PUSH-GATE app user=$user hash8=${appHash.take(8)}")

            // the debug launch extra, exactly as MainActivity applies it, then the [cfg] toggle
            PushRegistration.debugInject(Intent().putExtra("fcmToken", FAKE_TOKEN))
            assertEquals(FAKE_TOKEN, PushRegistration.currentToken)
            backend.updateSettings { it.copy(pushEnabled = true) }
            assertTrue(backend.settings.value.pushEnabled)
            backend.syncPushToken()            // → ChatService.sendPushToken(FAKE) → {t:"push_token", platform:"fcm"}
            delay(500)                          // in flight on the actor; no ack frame by design (PROTOCOL.md §11)
            backend.goOffline()                 // no live socket ⇒ the next msg for us must wake us
            withTimeout(10_000) { while (backend.connState.value != ConnState.offline) delay(100) }
            delay(300)

            // a raw peer (random keys, opaque prekeys — the relay never looks inside) messages the app user
            val peer = RawPeer("pushpeer_" + hex(3))
            peer.connect()
            assertEquals("registered", peer.register().getString("t"))
            assertEquals("ready", peer.login().getString("t"))
            val id = UUID.randomUUID().toString()
            peer.send(JSONObject().put("t", "send").put("id", id).put("to", appHash).put("ttl", 3600).put("kind", "msg").put("body", b64(random(160))))
            val verdict = peer.expect { it.optString("t") == "sent" && it.optString("id") == id || it.optString("t") == "error" }
            assertEquals("sent", verdict.getString("t"))
            println("PUSH-GATE peer msg to $user queued → relay wakes hash8=${appHash.take(8)} (see host relay.log: 'fcm 401 … for ${appHash.take(8)}')")
            peer.close()
            delay(1_500) // let the relay's async wake reach the host log before the wipe unregisters the token
        } finally {
            PushRegistration.debugClear()
            runCatching { backend.goOnline() }
            withTimeout(15_000) { while (backend.connState.value != ConnState.online && backend.connState.value != ConnState.offline) delay(100) }
            runCatching { backend.totalWipe() } // deregisters (relay forgets keys, queue, token) and obliterates this test's store
            backend.close()
        }
    }

    /** The relay's wire protocol with random keys — enough to be a sender (`relay/test/e2e.mjs` Client). */
    private class RawPeer(username: String) {
        val hash: String = sha256Hex(username.lowercase())
        private val seed = RelayAuth.generateSeed()
        private val authPub = RelayAuth.publicKey(seed)
        private val inbox = LinkedBlockingQueue<JSONObject>()
        private lateinit var ws: WebSocket
        private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()

        fun connect() {
            val opened = LinkedBlockingQueue<Boolean>()
            ws = client.newWebSocket(Request.Builder().url(RELAY).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { opened.put(true) }
                override fun onMessage(webSocket: WebSocket, text: String) { inbox.put(JSONObject(text)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { opened.put(false) }
            })
            assertTrue("peer socket", opened.poll(5, TimeUnit.SECONDS) == true)
        }

        fun send(o: JSONObject) { ws.send(o.toString()) }

        fun expect(timeoutMs: Long = 5_000, pred: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val m = inbox.poll(maxOf(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) ?: error("timeout waiting for frame")
                if (pred(m)) return m
            }
        }

        fun register(): JSONObject {
            send(
                JSONObject().put("t", "register").put("user", hash).put("auth_pub", b64(authPub)).put("reg_id", 1)
                    .put("identity_key", b64(random(33)))
                    .put("signed_prekey", JSONObject().put("id", 1).put("pub", b64(random(33))).put("sig", b64(random(64))))
                    .put("kyber_prekey", JSONObject().put("id", 1).put("pub", b64(random(1568))).put("sig", b64(random(64))))
                    .put("one_time_prekeys", JSONArray().apply { repeat(3) { i -> put(JSONObject().put("id", i).put("pub", b64(random(33)))) } }),
            )
            return expect { it.optString("t") == "registered" || it.optString("t") == "error" }
        }

        fun login(): JSONObject {
            send(JSONObject().put("t", "login").put("user", hash))
            val ch = expect { it.optString("t") == "challenge" || it.optString("t") == "error" }
            if (ch.optString("t") == "error") return ch
            val sig = RelayAuth.sign(Base64.getDecoder().decode(ch.getString("nonce")), seed)
            send(JSONObject().put("t", "auth").put("sig", b64(sig)))
            return expect { it.optString("t") == "ready" || it.optString("t") == "error" }
        }

        fun close() { ws.close(1000, null) }
    }

    private fun relayListening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), 1_500); true }
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val RELAY_HOST = "10.0.2.2"
        const val RELAY_PORT = 7447
        const val RELAY = "ws://$RELAY_HOST:$RELAY_PORT/ws"
        const val BILLING = "http://$RELAY_HOST:8088"
        /** relay shape `[A-Za-z0-9_:.\-]{20,4096}` (PushToken.FCM) */
        const val FAKE_TOKEN = "fake-fcm-token-droid-push-gate:APA91bStandIn"

        fun hex(n: Int): String = random(n).joinToString("") { "%02x".format(it) }
        fun random(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
