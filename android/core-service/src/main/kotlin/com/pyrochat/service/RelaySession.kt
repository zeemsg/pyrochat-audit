package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.PreKeys
import com.pyrochat.protocol.Wire
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Base64

/**
 * The request/reply flows of iOS `ChatService.register / authenticate / topUpPreKeys` and the
 * bundle fetch, over a single [RelayClient], step by step:
 *
 *   register:  C→S register … → S→C registered | error
 *   login:     C→S login{user} → S→C challenge{nonce} → C→S auth{sig,…} → S→C ready{queued} | error
 *
 * Exactly what `relay/test/e2e.mjs Client.register/login` and `bot/index.mjs route()` do. In
 * allow-all token mode no `token` is presented (e2e.mjs); a real token rides `auth.token/token_sig`.
 * Each call consumes events from the client's channel until its reply arrives; frames that are
 * not the awaited reply go to `onOther` (M1's ChatService will own a proper routing loop).
 */
object RelaySession {
    /** Socket ended (relay closed it, or transport failure) while a reply was pending. */
    class RelayDisconnected(val event: RelayEvent.Disconnected?) :
        IOException("relay disconnected: code=${event?.code} reason=${event?.reason} cause=${event?.cause}")

    class RelayTimeout(what: String) : IOException("timed out waiting for $what")

    /** Today's RFC 9474 access token as presented on `auth` (both base64). M5 fills this in. */
    data class AccessTokenPresentation(val token: String, val tokenSig: String)

    sealed interface RegisterResult {
        data object Registered : RegisterResult
        data class Refused(val code: String) : RegisterResult
    }

    sealed interface LoginResult {
        data class Ready(val queued: Int) : LoginResult
        /** `stage` = "login" (e.g. unknown_user) or "auth" (bad_sig, bad_token, token_spent, bad_frame). */
        data class Refused(val code: String, val stage: String) : LoginResult
    }

    sealed interface BundleResult {
        data class Bundle(val bundle: Wire.In.PreKeyBundle) : BundleResult
        data class Refused(val code: String) : BundleResult
    }

    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** Send [frame]; wait for `registered` or `error`. */
    suspend fun register(
        client: RelayClient,
        frame: Wire.Out.Register,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
    ): RegisterResult {
        client.send(frame)
        return when (val reply = awaitFrame(client, "registered", timeoutMs, onOther) { it is Wire.In.Registered || it is Wire.In.Error }) {
            is Wire.In.Registered -> RegisterResult.Registered
            is Wire.In.Error -> RegisterResult.Refused(reply.code)
            else -> error("unreachable")
        }
    }

    /** Convenience: register the identity held by [engine] (already generated). */
    suspend fun register(
        client: RelayClient,
        engine: CryptoEngine,
        registration: PreKeys.Registration,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RegisterResult = register(client, engine.registerFrame(registration), timeoutMs)

    /**
     * Challenge/response login. [sign] receives the DECODED 32 nonce bytes and must return the
     * 64-byte pure-Ed25519 signature (`CryptoEngine.signChallenge`); it is injectable so tests can
     * prove the relay rejects anything else.
     */
    suspend fun login(
        client: RelayClient,
        userHash: String,
        sign: (nonce: ByteArray) -> ByteArray,
        token: AccessTokenPresentation? = null,
        pushToken: String? = null,
        platform: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
    ): LoginResult {
        client.send(Wire.Out.Login(userHash))
        val challenge = awaitFrame(client, "challenge", timeoutMs, onOther) { it is Wire.In.Challenge || it is Wire.In.Error }
        val nonce = when (challenge) {
            is Wire.In.Challenge -> Base64.getDecoder().decode(challenge.nonce)
            is Wire.In.Error -> return LoginResult.Refused(challenge.code, "login")
            else -> error("unreachable")
        }
        val sig = Base64.getEncoder().encodeToString(sign(nonce))
        client.send(
            Wire.Out.Auth(
                sig = sig,
                token = token?.token,
                tokenSig = token?.tokenSig,
                pushToken = pushToken,
                platform = platform,
            ),
        )
        return when (val reply = awaitFrame(client, "ready", timeoutMs, onOther) { it is Wire.In.Ready || it is Wire.In.Error }) {
            is Wire.In.Ready -> LoginResult.Ready(reply.queued)
            is Wire.In.Error -> LoginResult.Refused(reply.code, "auth")
            else -> error("unreachable")
        }
    }

    /** Login with the identity in [engine]. */
    suspend fun login(
        client: RelayClient,
        engine: CryptoEngine,
        token: AccessTokenPresentation? = null,
        pushToken: String? = null,
        platform: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
    ): LoginResult = login(client, engine.state.usernameHash, engine::signChallenge, token, pushToken, platform, timeoutMs, onOther)

    /** `prekey_fetch` → `prekey_bundle` | `error unknown_user` (the error names the user). Authed only. */
    suspend fun fetchPreKeyBundle(
        client: RelayClient,
        userHash: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
    ): BundleResult {
        client.send(Wire.Out.PreKeyFetch(userHash))
        val reply = awaitFrame(client, "prekey_bundle", timeoutMs, onOther) {
            (it is Wire.In.PreKeyBundle && it.user == userHash) || (it is Wire.In.Error && (it.user == null || it.user == userHash))
        }
        return when (reply) {
            is Wire.In.PreKeyBundle -> BundleResult.Bundle(reply)
            is Wire.In.Error -> BundleResult.Refused(reply.code)
            else -> error("unreachable")
        }
    }

    /** `prekey_upload` → the relay's new `prekey_count.n`. Authed only. */
    suspend fun uploadPreKeys(
        client: RelayClient,
        keys: List<Wire.PreKeyJson>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
    ): Int {
        client.send(PreKeys.uploadFrame(keys))
        return when (val reply = awaitFrame(client, "prekey_count", timeoutMs, onOther) { it is Wire.In.PreKeyCount || it is Wire.In.Error }) {
            is Wire.In.PreKeyCount -> reply.n
            is Wire.In.Error -> throw IOException("prekey_upload refused: ${reply.code}")
            else -> error("unreachable")
        }
    }

    /**
     * Read events until a frame satisfies [match]. Other frames go to [onOther]; binary and
     * undecodable events are skipped; a disconnect throws [RelayDisconnected]; [timeoutMs] throws
     * [RelayTimeout].
     */
    suspend fun awaitFrame(
        client: RelayClient,
        what: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOther: (Wire.In) -> Unit = {},
        match: (Wire.In) -> Boolean,
    ): Wire.In {
        try {
            return withTimeout(timeoutMs) {
                var found: Wire.In? = null
                while (found == null) {
                    val event = try {
                        client.events.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        throw RelayDisconnected(null)
                    }
                    when (event) {
                        is RelayEvent.Frame -> if (match(event.frame)) found = event.frame else onOther(event.frame)
                        is RelayEvent.Disconnected -> throw RelayDisconnected(event)
                        else -> {}
                    }
                }
                found
            }
        } catch (e: TimeoutCancellationException) {
            throw RelayTimeout(what)
        }
    }
}
