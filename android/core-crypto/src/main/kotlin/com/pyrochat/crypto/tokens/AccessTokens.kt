package com.pyrochat.crypto.tokens

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * One anonymous daily access token — iOS `AccessToken` (`Models.swift:110-114`).
 * [token] = `epochDay (8 B big-endian) ‖ nonce (32 B)`; [signature] = its unblinded RSA-PSS
 * signature by the billing key (256 B). Content equality, like the store model.
 */
class AccessToken(val epochDay: Long, val token: ByteArray, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is AccessToken && epochDay == other.epochDay &&
                token.contentEquals(other.token) && signature.contentEquals(other.signature)
            )

    override fun hashCode(): Int = 31 * (31 * epochDay.hashCode() + token.contentHashCode()) + signature.contentHashCode()
    override fun toString(): String = "AccessToken(epochDay=$epochDay, token=${token.size} B, signature=${signature.size} B)"
}

/**
 * The token message format and the day arithmetic shared by iOS `RedeemService`/`ChatService`,
 * `billing/Main.java` and `relay/RsaPssTokenVerifier.java`:
 *
 *   message  = epochDay(Int64, big-endian, 8 B) ‖ nonce(32 random B)      — 40 B, what gets blinded/signed
 *   epochDay = floor(unixSeconds / 86_400)                                  — UTC days since 1970
 *              (iOS `Int64(Date().timeIntervalSince1970 / 86_400)`; relay `LocalDate.now(UTC).toEpochDay()`)
 *   redeem   = 31 messages for today … today+30
 *   login    = the token whose epochDay == today (relay accepts today ± 1 for clock skew, then spends it)
 *   remaining= count of tokens with epochDay ≥ today; a redeem prunes epochDay < today and appends
 */
object AccessTokens {
    const val EPOCH_BYTES = 8
    const val NONCE_BYTES = 32
    const val TOKEN_BYTES = EPOCH_BYTES + NONCE_BYTES
    /** RSA-2048 signature. */
    const val SIGNATURE_BYTES = BlindSigner.RSA2048_BYTES
    /** `billing/Main.java` SUBSCRIPTION_DAYS = MAX_TOKENS = 31; iOS `RedeemService` insists on exactly 31 back. */
    const val TOKENS_PER_REDEMPTION = 31
    const val SECONDS_PER_DAY = 86_400L

    /** UTC epoch day of a Unix time in milliseconds (default: now). */
    fun epochDay(unixMillis: Long = System.currentTimeMillis()): Long =
        Math.floorDiv(Math.floorDiv(unixMillis, 1000L), SECONDS_PER_DAY)

    /** `epochDay(8 B BE) ‖ nonce(32 B)`. */
    fun message(epochDay: Long, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        return ByteBuffer.allocate(TOKEN_BYTES).putLong(epochDay).put(nonce).array()
    }

    /** The epoch day encoded in a token's first 8 bytes (iOS `tokens[i].prefix(8).reduce(...)`). */
    fun epochDayOf(token: ByteArray): Long {
        require(token.size == TOKEN_BYTES) { "token must be $TOKEN_BYTES bytes, got ${token.size}" }
        return ByteBuffer.wrap(token, 0, EPOCH_BYTES).long
    }

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    /** The 31 messages of one redemption: one per day, `today … today + 30`, each with a fresh nonce. */
    fun messagesFrom(today: Long, random: SecureRandom = SecureRandom(), count: Int = TOKENS_PER_REDEMPTION): List<ByteArray> =
        List(count) { day -> message(today + day, newNonce(random)) }

    /** iOS `ChatService.currentAccessToken()`: the token for today, or null (→ `auth` without a token). */
    fun todaysToken(tokens: List<AccessToken>, today: Long = epochDay()): AccessToken? =
        tokens.firstOrNull { it.epochDay == today }

    /** iOS `ChatService.accessDaysRemaining`. */
    fun daysRemaining(tokens: List<AccessToken>, today: Long = epochDay()): Int =
        tokens.count { it.epochDay >= today }

    /** iOS `ChatService.redeemAccessKey`'s store update: drop stale tokens, append the fresh 31. */
    fun merge(existing: List<AccessToken>, fresh: List<AccessToken>, today: Long = epochDay()): List<AccessToken> =
        existing.filter { it.epochDay >= today } + fresh

    /** Structural sanity of a persisted token: 40-byte token whose prefix agrees with [AccessToken.epochDay], 256-byte signature. */
    fun isWellFormed(token: AccessToken): Boolean =
        token.token.size == TOKEN_BYTES && token.signature.size == SIGNATURE_BYTES && epochDayOf(token.token) == token.epochDay
}
