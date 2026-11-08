package com.pyrochat.service.access

import com.pyrochat.crypto.tokens.AccessToken
import com.pyrochat.crypto.tokens.AccessTokens
import com.pyrochat.crypto.tokens.BlindSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/**
 * Port of iOS `RedeemService.redeem(code:billingURL:session:)` — turns a `PYRO-XXXX-XXXX-XXXX-XXXX`
 * access key into 31 anonymous daily tokens (RFC 9474 RSABSSA-SHA384-PSS-Deterministic):
 *
 *   1. `GET  {billing}/api/billing-pubkey`  → `{"pem": "<SPKI PEM>"}`
 *   2. build 31 messages `epochDay(8 B BE) ‖ nonce(32 B)` for today … today+30, blind each
 *   3. `POST {billing}/api/redeem` `{"code": "<key>", "blinded": ["<b64 256 B>" × 31]}`
 *      → `200 {"signatures": ["<b64 256 B>" × 31], "days": 31}` | `403 {"error":"invalid_or_used_code"}`
 *   4. unblind + verify every signature locally before trusting it
 *
 * Billing signs WITHOUT seeing the tokens; the relay later verifies them with the same public key
 * and can link nothing back to this call. [http] MUST be the app's one `RelayHttp.makeClient(socks)`
 * client — redeeming over the real IP would let billing correlate (IP, code, time) with the relay
 * login and break token unlinkability (iOS: "session MUST be a proxied session").
 *
 * Error mapping is iOS `RedeemError` one-to-one ([RedeemError]).
 */
class AccessKeyClient(
    private val http: OkHttpClient,
    private val random: SecureRandom = SecureRandom(),
    /** Unix milliseconds "now" — injectable so tests can pin `today`. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * iOS `RedeemService.RedeemError` (+ `ChatService`'s `bad_billing_url`), with the same
     * user-facing descriptions (`message`) the iOS UI shows after "✗ ".
     */
    sealed class RedeemError(val code: String, message: String, cause: Throwable? = null) : IOException(message, cause) {
        /** iOS `ChatService.redeemAccessKey`: `URL(string: billingURL)` failed → `ServiceError.relay("bad_billing_url")`. */
        class BadBillingUrl(val url: String) : RedeemError("bad_billing_url", "bad_billing_url")

        /** Public key unreachable/undecodable, reply undecodable, ≠ 31 signatures, or a signature that does not verify. */
        class BadServer(val detail: String, cause: Throwable? = null) : RedeemError("bad_server", "billing server unreachable", cause)

        /** `/api/redeem` answered but not 200 — `invalid_or_used_code` (403); iOS maps EVERY non-200 here. */
        class BadCode(val httpStatus: Int, val serverError: String?) : RedeemError("bad_code", "invalid or already-used access key")

        /** `/api/redeem` transport failure (no HTTP reply at all). */
        class Network(cause: Throwable?) : RedeemError("network", "network error", cause)
    }

    @Serializable
    private data class PubKeyReply(val pem: String)

    @Serializable
    private data class RedeemReply(val signatures: List<String>, val days: Int = 0)

    @Serializable
    private data class ErrorReply(val error: String? = null)

    /** Fetch the issuer's public key — `GET /api/billing-pubkey`. Any failure is [RedeemError.BadServer]. */
    suspend fun fetchPublicKey(billingUrl: String): BlindSigner = withContext(Dispatchers.IO) {
        val url = endpoint(billingUrl, "api/billing-pubkey")
        val body = try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RedeemError.BadServer("billing-pubkey HTTP ${resp.code}")
                resp.body.string()
            }
        } catch (e: RedeemError) {
            throw e
        } catch (e: IOException) {
            throw RedeemError.BadServer("billing-pubkey unreachable", e)
        }
        val pem = try {
            JSON.decodeFromString(PubKeyReply.serializer(), body).pem
        } catch (e: Exception) {
            throw RedeemError.BadServer("billing-pubkey reply undecodable", e)
        }
        try {
            BlindSigner.fromPem(pem)
        } catch (e: IllegalArgumentException) {
            throw RedeemError.BadServer("billing-pubkey PEM unusable", e)
        }
    }

    /**
     * Redeem [code] at [billingUrl]; returns 31 locally verified tokens for today … today+30
     * (first element = today). Throws a [RedeemError].
     */
    suspend fun redeem(code: String, billingUrl: String): List<AccessToken> {
        val redeemUrl = endpoint(billingUrl, "api/redeem") // fail on a bad URL before any traffic
        val issuer = fetchPublicKey(billingUrl)

        // one token per day, starting today (UTC epoch days)
        val today = AccessTokens.epochDay(clock())
        val messages = AccessTokens.messagesFrom(today, random)
        val prepared = messages.map(issuer::prepare)
        val blinded = prepared.map { issuer.blind(it, random) }

        val request = buildJsonObject {
            put("code", code)
            put("blinded", JsonArray(blinded.map { JsonPrimitive(B64.encodeToString(it.blindedMessage)) }))
        }.toString()

        val reply = withContext(Dispatchers.IO) {
            try {
                http.newCall(
                    Request.Builder().url(redeemUrl)
                        .post(request.toRequestBody(JSON_MEDIA_TYPE))
                        .build(),
                ).execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.code != 200) {
                        val serverError = try {
                            JSON.decodeFromString(ErrorReply.serializer(), text).error
                        } catch (_: Exception) {
                            null
                        }
                        throw RedeemError.BadCode(resp.code, serverError)
                    }
                    text
                }
            } catch (e: RedeemError) {
                throw e
            } catch (e: IOException) {
                throw RedeemError.Network(e)
            }
        }

        val signatures = try {
            JSON.decodeFromString(RedeemReply.serializer(), reply).signatures
        } catch (e: Exception) {
            throw RedeemError.BadServer("redeem reply undecodable", e)
        }
        if (signatures.size != AccessTokens.TOKENS_PER_REDEMPTION) {
            throw RedeemError.BadServer("expected ${AccessTokens.TOKENS_PER_REDEMPTION} signatures, got ${signatures.size}")
        }

        // unblind + verify each signature locally before trusting it
        return signatures.mapIndexed { i, b64 ->
            val blindSig = try {
                B64D.decode(b64)
            } catch (e: IllegalArgumentException) {
                throw RedeemError.BadServer("signature $i is not base64", e)
            }
            val sig = try {
                issuer.finalize(blindSig, prepared[i], blinded[i].inverse)
            } catch (e: Exception) {
                throw RedeemError.BadServer("signature $i does not verify: ${e.message}", e)
            }
            AccessToken(AccessTokens.epochDayOf(messages[i]), messages[i], sig)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val B64: Base64.Encoder = Base64.getEncoder()
        private val B64D: Base64.Decoder = Base64.getDecoder()

        /** iOS `billingURL.appendingPathComponent("api/…")`: appended to whatever path the base has. */
        internal fun endpoint(billingUrl: String, path: String): HttpUrl {
            val base = billingUrl.trim().toHttpUrlOrNull() ?: throw RedeemError.BadBillingUrl(billingUrl)
            return base.newBuilder().addPathSegments(path).build()
        }
    }
}
