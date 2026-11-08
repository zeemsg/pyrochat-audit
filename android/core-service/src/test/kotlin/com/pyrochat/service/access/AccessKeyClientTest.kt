package com.pyrochat.service.access

import com.pyrochat.crypto.tokens.AccessTokens
import com.pyrochat.crypto.tokens.BlindSigner
import com.pyrochat.service.RelayHttp
import com.pyrochat.service.access.StoredAccessTokens.toAccessToken
import com.pyrochat.service.access.StoredAccessTokens.toStored
import com.pyrochat.store.codec.SwiftJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.pyrochat.store.model.AccessToken as StoredAccessToken

/**
 * Offline port of `RedeemService`'s contract against a fake `billing/Main.java` on the JDK's own
 * HttpServer (the very server class billing uses): same routes, same JSON, same status codes.
 */
class AccessKeyClientTest {
    private val random = SecureRandom()
    private val http = RelayHttp.makeClient()
    private val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = kp.public as RSAPublicKey
    private val priv = kp.private as RSAPrivateKey
    private val n: BigInteger = pub.modulus

    /** `BlindSigner.publicKeyPem()` verbatim: 64-column MIME base64 between PUBLIC KEY armor, trailing newline. */
    private val pem = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pub.encoded) +
        "\n-----END PUBLIC KEY-----\n"

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // (method path, body)
    private val codes = mutableMapOf<String, Boolean>() // code -> redeemed
    private var pemReply: String = ""
    private var signatureCount: Int? = null // override count in the reply
    private var corruptSignature = false
    private var dropRedeem = false

    private fun base(): String = "http://127.0.0.1:${server.address.port}"

    /** billing `BlindSigner.blindSign`'s left-pad / trim to exactly k bytes. */
    private fun toFixed(v: BigInteger, k: Int): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(k)
        val copy = minOf(raw.size, k)
        System.arraycopy(raw, raw.size - copy, out, k - copy, copy)
        return out
    }
    private fun goodPemReply(): String = """{"pem":${JsonPrimitive(pem)}}"""

    @BeforeEach
    fun fakeBilling() {
        pemReply = goodPemReply()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/billing-pubkey") { ex -> respond(ex, 200, pemReply) }
        server.createContext("/api/redeem") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += "${ex.requestMethod} ${ex.requestURI.path}" to body
            if (dropRedeem) { ex.close(); return@createContext } // no HTTP reply at all: a transport failure
            if (ex.requestMethod != "POST") return@createContext respond(ex, 400, """{"error":"bad_request"}""")
            val req = Json.parseToJsonElement(body).jsonObject
            val code = req["code"]!!.jsonPrimitive.content
            val blinded = req["blinded"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (blinded.isEmpty() || blinded.size > 31) return@createContext respond(ex, 400, """{"error":"bad_request"}""")
            // CodeStore.redeem: unknown or already redeemed → -1 → 403
            val norm = code.trim().uppercase().replace(Regex("[^A-Z0-9-]"), "")
            if (codes[norm] != false) return@createContext respond(ex, 403, """{"error":"invalid_or_used_code"}""")
            codes[norm] = true
            val sigs = blinded.map { b64 ->
                val m = Base64.getDecoder().decode(b64)
                require(m.size == 256)
                val s = toFixed(BigInteger(1, m).modPow(priv.privateExponent, n), 256)
                if (corruptSignature) s[0] = (s[0].toInt() xor 1).toByte()
                "\"" + Base64.getEncoder().encodeToString(s) + "\""
            }.let { if (signatureCount != null) it.take(signatureCount!!) else it }
            respond(ex, 200, """{"signatures":[${sigs.joinToString(",")}],"days":31}""")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** `CodeStore.mint`: PYRO + 4 groups of 4 Crockford-base32 chars, remembered as unredeemed. */
    private fun mint(): String {
        val code = "PYRO-" + (1..4).joinToString("-") {
            (1..4).map { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[random.nextInt(32)] }.joinToString("")
        }
        codes[code] = false
        return code
    }

    private fun relayVerify(token: ByteArray, sig: ByteArray): Boolean {
        val v = Signature.getInstance("RSASSA-PSS")
        v.setParameter(PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1))
        v.initVerify(pub)
        v.update(token)
        return v.verify(sig)
    }

    private val fixedNow = Instant.parse("2026-09-17T12:00:00Z").toEpochMilli()
    private fun client() = AccessKeyClient(http, random, clock = { fixedNow })

    @Test
    fun `redeem sends the iOS request shape and returns 31 verified tokens for today onward`(): Unit = runBlocking {
        val code = mint()
        val tokens = client().redeem(code, base())

        // request: POST /api/redeem {"code":…,"blinded":[31 × b64 of 256 B]}
        val (line, body) = requests.single()
        assertEquals("POST /api/redeem", line)
        val req = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("code", "blinded"), req.keys)
        assertEquals(code, req["code"]!!.jsonPrimitive.content)
        val blinded = req["blinded"]!!.jsonArray.map { Base64.getDecoder().decode(it.jsonPrimitive.content) }
        assertEquals(31, blinded.size)
        assertTrue(blinded.all { it.size == 256 })
        assertEquals(31, blinded.map { it.toList() }.toSet().size, "every blinded value distinct")

        // tokens: today … today+30, 40 B ‖ 256 B, each verifies exactly the way the relay verifies
        val today = AccessTokens.epochDay(fixedNow)
        assertEquals(20713, today)
        assertEquals(31, tokens.size)
        assertEquals((today..today + 30).toList(), tokens.map { it.epochDay })
        for (t in tokens) {
            assertTrue(AccessTokens.isWellFormed(t))
            assertEquals(t.epochDay, AccessTokens.epochDayOf(t.token))
            assertTrue(BlindSigner.fromPem(pem).verify(t.token, t.signature))
            assertTrue(relayVerify(t.token, t.signature), "relay RSASSA-PSS accepts the unblinded signature")
            // the issuer never saw this token: it appears in no blinded value
            val tok = String(t.token, Charsets.ISO_8859_1)
            assertTrue(blinded.none { String(it, Charsets.ISO_8859_1).contains(tok) })
        }
        assertEquals(tokens[0], AccessTokens.todaysToken(tokens, today))
        assertEquals(31, AccessTokens.daysRemaining(tokens, today))
    }

    @Test
    fun `a used or unknown code is BadCode with the billing error, like iOS badCode`(): Unit = runBlocking {
        val code = mint()
        client().redeem(code, base())
        val again = assertFailsWith<AccessKeyClient.RedeemError.BadCode> { client().redeem(code, base()) }
        assertEquals(403, again.httpStatus)
        assertEquals("invalid_or_used_code", again.serverError)
        assertEquals("invalid or already-used access key", again.message)
        assertEquals("bad_code", again.code)

        val unknown = assertFailsWith<AccessKeyClient.RedeemError.BadCode> { client().redeem("PYRO-0000-0000-0000-0000", base()) }
        assertEquals(403, unknown.httpStatus)

        // billing normalizes the code (trim/uppercase/strip): a lowercase code redeems the same key
        val c2 = mint()
        assertEquals(31, client().redeem("  " + c2.lowercase() + "  ", base()).size)
    }

    @Test
    fun `network failures and a bad server map like iOS`(): Unit = runBlocking {
        // unreachable billing: the public-key fetch fails first → badServer ("billing server unreachable")
        val dead = "http://127.0.0.1:1"
        val e1 = assertFailsWith<AccessKeyClient.RedeemError.BadServer> { client().redeem(mint(), dead) }
        assertEquals("billing server unreachable", e1.message)

        // unparseable URL → bad_billing_url before any traffic
        assertFailsWith<AccessKeyClient.RedeemError.BadBillingUrl> { client().redeem(mint(), "not a url") }
        assertFailsWith<AccessKeyClient.RedeemError.BadBillingUrl> { client().redeem(mint(), "") }
        assertTrue(requests.isEmpty())

        // pubkey reachable but garbage → badServer
        pemReply = """{"pem":"-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"}"""
        assertFailsWith<AccessKeyClient.RedeemError.BadServer> { client().redeem(mint(), base()) }
        pemReply = """{"nope":1}"""
        assertFailsWith<AccessKeyClient.RedeemError.BadServer> { client().redeem(mint(), base()) }
        pemReply = goodPemReply()

        // 30 signatures instead of 31 → badServer (iOS `reply.signatures.count == 31`)
        signatureCount = 30
        val short = assertFailsWith<AccessKeyClient.RedeemError.BadServer> { client().redeem(mint(), base()) }
        assertTrue(short.detail.contains("31"), short.detail)
        signatureCount = null

        // a signature that does not unblind to a valid PSS signature → badServer, nothing returned
        corruptSignature = true
        val forged = assertFailsWith<AccessKeyClient.RedeemError.BadServer> { client().redeem(mint(), base()) }
        assertTrue(forged.detail.contains("does not verify"), forged.detail)
        corruptSignature = false

        // transport failure on the redeem POST itself (pubkey OK, connection dropped mid-request) → network
        dropRedeem = true
        val net = assertFailsWith<AccessKeyClient.RedeemError.Network> { client().redeem(mint(), base()) }
        assertEquals("network error", net.message)
        assertEquals("network", net.code)
        dropRedeem = false

        // a configured-but-dead SOCKS proxy means NOTHING reaches billing (the pubkey GET fails first → badServer)
        val proxied = AccessKeyClient(RelayHttp.makeClient("127.0.0.1:1"), random)
        val before = requests.size
        val viaProxy = assertFailsWith<AccessKeyClient.RedeemError.BadServer> { proxied.redeem(mint(), base()) }
        assertEquals("billing server unreachable", viaProxy.message)
        assertEquals(before, requests.size, "no request may bypass the proxy")
        val issuer = client().fetchPublicKey(base())
        assertEquals(n, issuer.modulus)
    }

    @Test
    fun `endpoint building matches URL appendingPathComponent`() {
        assertEquals("http://h:8088/api/redeem", AccessKeyClient.endpoint("http://h:8088", "api/redeem").toString())
        assertEquals("http://h:8088/api/redeem", AccessKeyClient.endpoint("http://h:8088/", "api/redeem").toString())
        assertEquals("https://pyrochat.ca/api/billing-pubkey", AccessKeyClient.endpoint("https://pyrochat.ca", "api/billing-pubkey").toString())
        assertEquals("https://x/base/api/redeem", AccessKeyClient.endpoint(" https://x/base ", "api/redeem").toString())
    }

    @Test
    fun `stored model round trip has exactly the iOS shape and the fixture decodes`(): Unit = runBlocking {
        val tokens = client().redeem(mint(), base())
        val stored = tokens.map { it.toStored() }
        val json = SwiftJson.encode(stored)
        val arr = Json.parseToJsonElement(json).jsonArray
        assertEquals(31, arr.size)
        for ((i, el) in arr.withIndex()) {
            val o = el as JsonObject
            assertEquals(setOf("epochDay", "token", "signature"), o.keys, "all three keys, nothing else")
            assertEquals(tokens[i].epochDay.toString(), o["epochDay"]!!.jsonPrimitive.content)
            assertEquals(Base64.getEncoder().encodeToString(tokens[i].token), o["token"]!!.jsonPrimitive.content, "padded standard base64")
            assertEquals(Base64.getEncoder().encodeToString(tokens[i].signature), o["signature"]!!.jsonPrimitive.content)
        }
        val back: List<StoredAccessToken> = SwiftJson.decode(json)
        assertEquals(stored, back)
        assertEquals(tokens, back.map { it.toAccessToken() })

        // presentation = what rides auth.token / auth.token_sig
        val p = StoredAccessTokens.presentation(tokens[0])
        assertContentEquals(tokens[0].token, Base64.getDecoder().decode(p.token))
        assertContentEquals(tokens[0].signature, Base64.getDecoder().decode(p.tokenSig))
        val today = AccessTokens.epochDay(fixedNow)
        assertEquals(p, StoredAccessTokens.todaysPresentation(stored, today))
        assertNull(StoredAccessTokens.todaysPresentation(stored, today - 1))
        assertEquals(31, StoredAccessTokens.daysRemaining(stored, today))
        assertEquals(30, StoredAccessTokens.daysRemaining(stored, today + 1))

        // redeemAccessKey's store update: stale pruned, fresh appended
        val stale = StoredAccessToken(today - 1, stored[0].token, stored[0].signature)
        val after = StoredAccessTokens.afterRedeem(listOf(stale, stored[3]), tokens.take(2), today)
        assertEquals(listOf(stored[3], stored[0], stored[1]), after)

        // the real iOS file: both persisted tokens decode and their prefixes agree with epochDay
        val fixture = Paths.get("..", "fixtures", "ios-store", "pyro.store.plaintext.json").toAbsolutePath().normalize()
        Assumptions.assumeTrue(Files.exists(fixture), "SKIPPED: iOS fixture not found at $fixture")
        val blob = Json.parseToJsonElement(Files.readString(fixture)).jsonObject
        val iosTokens: List<StoredAccessToken> = SwiftJson.decode(blob["accessTokens"]!!.toString())
        assertEquals(2, iosTokens.size)
        assertEquals(listOf(20350L, 20351L), iosTokens.map { it.epochDay })
        for (t in iosTokens) {
            val ct = t.toAccessToken()
            assertTrue(AccessTokens.isWellFormed(ct), "40 B token ‖ 256 B sig, prefix == epochDay")
            assertNotNull(ct)
        }
        // re-encoding an iOS token reproduces its exact strings
        val reencoded = Json.parseToJsonElement(SwiftJson.encode(iosTokens[0])).jsonObject
        val original = blob["accessTokens"]!!.jsonArray[0].jsonObject
        assertEquals(original, reencoded)
    }
}
