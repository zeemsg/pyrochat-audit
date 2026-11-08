package com.pyrochat.service.access

import com.pyrochat.crypto.tokens.AccessToken
import com.pyrochat.crypto.tokens.AccessTokens
import com.pyrochat.service.RelaySession
import com.pyrochat.store.model.Bytes
import java.util.Base64
import com.pyrochat.store.model.AccessToken as StoredAccessToken

/**
 * Bridges the crypto-level [AccessToken] and the persisted `Blob.accessTokens` model
 * ([StoredAccessToken] — `{"epochDay": Int64, "token": b64 40 B, "signature": b64 256 B}`, all three
 * required, SPEC.md §4.4), plus the login-time presentation. Lives here because `core-store`
 * depends on `core-crypto`, so the crypto module cannot name the store model.
 */
object StoredAccessTokens {
    fun AccessToken.toStored(): StoredAccessToken = StoredAccessToken(epochDay, Bytes(token), Bytes(signature))

    fun StoredAccessToken.toAccessToken(): AccessToken = AccessToken(epochDay, token.bytes, signature.bytes)

    fun List<StoredAccessToken>.toAccessTokens(): List<AccessToken> = map { it.toAccessToken() }

    /** `auth.token` / `auth.token_sig` — both standard base64 with padding, like every relay-facing blob. */
    fun presentation(token: AccessToken): RelaySession.AccessTokenPresentation =
        RelaySession.AccessTokenPresentation(
            token = Base64.getEncoder().encodeToString(token.token),
            tokenSig = Base64.getEncoder().encodeToString(token.signature),
        )

    /** iOS `ChatService.authenticate`: today's token if the store has one, else `auth` carries none. */
    fun todaysPresentation(stored: List<StoredAccessToken>, today: Long = AccessTokens.epochDay()): RelaySession.AccessTokenPresentation? =
        AccessTokens.todaysToken(stored.toAccessTokens(), today)?.let(::presentation)

    /** iOS `ChatService.accessDaysRemaining`. */
    fun daysRemaining(stored: List<StoredAccessToken>, today: Long = AccessTokens.epochDay()): Int =
        stored.count { it.epochDay >= today }

    /** iOS `ChatService.redeemAccessKey`'s `store.update`: prune `epochDay < today`, append the fresh 31. */
    fun afterRedeem(stored: List<StoredAccessToken>, fresh: List<AccessToken>, today: Long = AccessTokens.epochDay()): List<StoredAccessToken> =
        stored.filter { it.epochDay >= today } + fresh.map { it.toStored() }
}
