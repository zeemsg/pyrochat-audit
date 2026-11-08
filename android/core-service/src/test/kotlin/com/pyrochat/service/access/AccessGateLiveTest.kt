package com.pyrochat.service.access

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.SignalState
import com.pyrochat.crypto.tokens.AccessToken
import com.pyrochat.crypto.tokens.AccessTokens
import com.pyrochat.protocol.UsernameHash
import com.pyrochat.protocol.Wire
import com.pyrochat.service.RelayClient
import com.pyrochat.service.RelayHttp
import com.pyrochat.service.RelaySession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Port of `ios/Tests/TokenGateTests.swift`, live: buy (dev-mode mint on billing :8088) → redeem via
 * RFC 9474 blind signing → the token-enforcing relay (:7445, `token.mode=rsa-pss`) admits the
 * connection — and rejects a missing token, cross-identity token sharing, a wrong-day token and a
 * tampered signature. Skipped — not failed — unless BOTH 8088 and 7445 are listening.
 */
class AccessGateLiveTest {
    companion object {
        private const val HOST = "127.0.0.1"
        private const val BILLING_PORT = 8088
        private const val RELAY_PORT = 7445
        private const val BILLING_URL = "http://$HOST:$BILLING_PORT"
        private const val RELAY_URL = "ws://$HOST:$RELAY_PORT/ws"
        private val http: OkHttpClient by lazy { RelayHttp.makeClient() }
        private val random = SecureRandom()

        fun listening(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(HOST, port), 500); true }
        } catch (_: IOException) {
            false
        }
    }

    @BeforeEach
    fun requireHarness() {
        Assumptions.assumeTrue(
            listening(BILLING_PORT) && listening(RELAY_PORT),
            "SKIPPED: need billing (devMode) on $HOST:$BILLING_PORT and the token-enforcing relay (token.mode=rsa-pss) on $HOST:$RELAY_PORT",
        )
    }

    /** iOS `GatedUser`: a fresh identity on its own socket to :7445; register then login on that socket. */
    private class GatedUser(val client: RelayClient) {
        val engine = CryptoEngine(SignalState())
        val username = "gate" + UsernameHash.toHex(ByteArray(4).also(random::nextBytes))
        val registration = engine.generateIdentity(username)
        val hash: String get() = engine.state.usernameHash

        suspend fun register() {
            assertEquals(RelaySession.RegisterResult.Registered, RelaySession.register(client, engine, registration))
        }

        /** Attempt login; the final verdict (`Ready` or `Refused(code, "auth")`). */
        suspend fun login(token: AccessToken?): RelaySession.LoginResult =
            RelaySession.login(client, engine, token = token?.let(StoredAccessTokens::presentation))
    }

    private suspend fun gatedUser(): GatedUser = GatedUser(RelayClient(RELAY_URL, http).also { it.connect() })

    /** "buy": dev-mode mint (stands in for the Stripe/BTCPay/NOWPayments webhooks) — `POST /api/checkout/dev` → `{"code"}`. */
    private fun devMint(): String {
        val req = Request.Builder().url("$BILLING_URL/api/checkout/dev").post(ByteArray(0).toRequestBody(null)).build()
        return http.newCall(req).execute().use { resp ->
            check(resp.code == 200) { "dev mint HTTP ${resp.code} — is billing running with devMode=true?" }
            Json.parseToJsonElement(resp.body.string()).jsonObject["code"]!!.jsonPrimitive.content
        }
    }

    private fun refusedWith(code: String) = RelaySession.LoginResult.Refused(code, "auth")

    @Test
    fun `redeemed token opens the gate, missing and shared tokens do not`(): Unit = runBlocking {
        // 1. "buy"
        val code = devMint()
        assertTrue(code.startsWith("PYRO-"), code)
        assertTrue(Regex("PYRO(-[0-9A-Z]{4}){4}").matches(code), code)
        println("[live] minted $code")

        // 2. redeem: blind → sign → unblind → verified locally, and once more against the published key
        val redeemer = AccessKeyClient(http, random)
        val tokens = redeemer.redeem(code, BILLING_URL)
        assertEquals(31, tokens.size)
        val today = AccessTokens.epochDay()
        assertEquals(today, tokens.first().epochDay)
        assertEquals((today..today + 30).toList(), tokens.map { it.epochDay })
        val issuer = redeemer.fetchPublicKey(BILLING_URL)
        assertEquals(256, issuer.modulusBytes)
        for (t in tokens) {
            assertTrue(AccessTokens.isWellFormed(t))
            assertTrue(issuer.verify(t.token, t.signature), "token ${t.epochDay} verifies under the billing public key")
        }
        val todayToken = AccessTokens.todaysToken(tokens, today)!!
        println("[live] redeemed 31 tokens, epochDay $today … ${today + 30}, all verify under the billing key (n = ${issuer.modulusBytes * 8} bit)")

        // 3. the same code cannot be redeemed twice
        val again = assertFailsWith<AccessKeyClient.RedeemError.BadCode> { redeemer.redeem(code, BILLING_URL) }
        assertEquals(403, again.httpStatus)
        assertEquals("invalid_or_used_code", again.serverError)
        println("[live] second redemption → HTTP 403 ${again.serverError}")

        // 4. no token → relay refuses (the relay's `bad_token` for a missing/undecodable token)
        val anon = gatedUser()
        try {
            anon.register()
            val refused = anon.login(null)
            assertEquals(refusedWith(Wire.ErrorCode.BAD_TOKEN), refused)
            println("[live] login without token → ${(refused as RelaySession.LoginResult.Refused).code}")
        } finally {
            anon.client.close()
        }

        // 5. valid token → relay admits
        val paid = gatedUser()
        try {
            paid.register()
            val admitted = paid.login(todayToken)
            assertIs<RelaySession.LoginResult.Ready>(admitted, "paid token must open the gate")
            println("[live] login with today's token → ready (queued=${admitted.queued})")
        } finally {
            paid.client.close()
        }

        // 5b. the SAME identity reconnecting with the same token is fine (spent-set is keyed by userHash)
        val reconnect = RelayClient(RELAY_URL, http).also { it.connect() }
        try {
            val again2 = RelaySession.login(reconnect, paid.engine, token = StoredAccessTokens.presentation(todayToken))
            assertIs<RelaySession.LoginResult.Ready>(again2, "same user may reconnect with today's token")
            println("[live] same identity reconnect with the same token → ready")
        } finally {
            reconnect.close()
        }

        // 6. the SAME token presented by a different identity → token sharing, refused
        val freeloader = gatedUser()
        try {
            freeloader.register()
            val caught = freeloader.login(todayToken)
            assertEquals(refusedWith(Wire.ErrorCode.TOKEN_SPENT), caught, "cross-device token sharing must be refused")
            println("[live] another identity, same token → token_spent")
        } finally {
            freeloader.client.close()
        }

        // 7. a stale-day token → refused (relay accepts today ± 1 only)
        val stale = tokens.first { it.epochDay == today + 5 }
        val expired = gatedUser()
        try {
            expired.register()
            val refused2 = expired.login(stale)
            assertEquals(refusedWith(Wire.ErrorCode.BAD_TOKEN), refused2, "wrong-epoch token must be refused")
            println("[live] today+5 token → bad_token")
        } finally {
            expired.client.close()
        }

        // 8. a tampered signature on an otherwise fresh token (tomorrow's, never presented) → bad_token
        val tomorrow = tokens.first { it.epochDay == today + 1 }
        val tampered = AccessToken(
            tomorrow.epochDay, tomorrow.token,
            tomorrow.signature.copyOf().also { it[17] = (it[17].toInt() xor 0x40).toByte() },
        )
        val forger = gatedUser()
        try {
            forger.register()
            val refused3 = forger.login(tampered)
            assertEquals(refusedWith(Wire.ErrorCode.BAD_TOKEN), refused3, "tampered signature must be refused")
            println("[live] tampered signature → bad_token")
        } finally {
            forger.client.close()
        }

        // 8b. …and the untampered tomorrow token is within the ±1 day skew window: still accepted by a fresh identity
        val early = gatedUser()
        try {
            early.register()
            assertIs<RelaySession.LoginResult.Ready>(early.login(tomorrow), "today+1 is inside the relay's clock-skew window")
            println("[live] today+1 token (skew window) → ready")
        } finally {
            early.client.close()
        }
    }
}
