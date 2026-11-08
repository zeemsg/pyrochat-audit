package com.pyrochat.crypto.tokens

import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessTokensTest {
    private val random = SecureRandom()

    private fun token(epochDay: Long, sigByte: Int = 7) =
        AccessToken(epochDay, AccessTokens.message(epochDay, AccessTokens.newNonce(random)), ByteArray(256) { sigByte.toByte() })

    @Test
    fun `message is epochDay 8 B big-endian then 32 B nonce`() {
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val m = AccessTokens.message(20350, nonce)
        assertEquals(40, m.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0x4F, 0x7E), m.copyOfRange(0, 8))
        assertContentEquals(nonce, m.copyOfRange(8, 40))
        assertEquals(20350, AccessTokens.epochDayOf(m))
        assertEquals(Long.MAX_VALUE, AccessTokens.epochDayOf(AccessTokens.message(Long.MAX_VALUE, nonce)))
        assertFailsWith<IllegalArgumentException> { AccessTokens.message(1, ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { AccessTokens.epochDayOf(ByteArray(39)) }
    }

    @Test
    fun `an iOS-written token from the store fixture parses to its persisted epochDay`() {
        // android/fixtures/ios-store/pyro.store.plaintext.json accessTokens[0]: {"epochDay":20350,"token":…}
        val t = Base64.getDecoder().decode("AAAAAAAAT34075q5vE4p7btlw05sCTpiBxRMfIAWacnGsboT1km8gQ==")
        assertEquals(40, t.size)
        assertEquals(20350, AccessTokens.epochDayOf(t))
        val t2 = Base64.getDecoder().decode("AAAAAAAAT380HfnMEc0Gwr+DrKxg8a9RVhzlQuE2wO4ClO2cdYXp0A==")
        assertEquals(20351, AccessTokens.epochDayOf(t2))
        assertTrue(AccessTokens.isWellFormed(AccessToken(20350, t, ByteArray(256))))
        assertFalse(AccessTokens.isWellFormed(AccessToken(20351, t, ByteArray(256))), "prefix must agree with epochDay")
        assertFalse(AccessTokens.isWellFormed(AccessToken(20350, t, ByteArray(255))))
    }

    @Test
    fun `epochDay is floor(unix div 86400) which equals the relay's LocalDate UTC toEpochDay`() {
        for (iso in listOf("2026-09-17T00:00:00Z", "2026-09-17T23:59:59.999Z", "2026-09-18T00:00:00Z", "1970-01-01T00:00:00Z", "2038-01-19T03:14:07Z")) {
            val instant = Instant.parse(iso)
            val expectIos = instant.epochSecond / 86_400 // Int64(Date().timeIntervalSince1970 / 86_400), truncation
            val expectRelay = LocalDate.ofInstant(instant, ZoneOffset.UTC).toEpochDay() // RsaPssTokenVerifier.currentEpochDay()
            assertEquals(expectIos, expectRelay, iso)
            assertEquals(expectRelay, AccessTokens.epochDay(instant.toEpochMilli()), iso)
        }
        assertEquals(20713, AccessTokens.epochDay(Instant.parse("2026-09-17T12:00:00Z").toEpochMilli()))
        assertEquals(20713, LocalDate.of(2026, 9, 17).toEpochDay())
    }

    @Test
    fun `a redemption is 31 messages for today through today+30 with distinct nonces`() {
        val today = 20713L
        val ms = AccessTokens.messagesFrom(today, random)
        assertEquals(31, ms.size)
        assertEquals((today..today + 30).toList(), ms.map(AccessTokens::epochDayOf))
        assertEquals(31, ms.map { it.copyOfRange(8, 40).toList() }.toSet().size, "fresh nonce per token")
        assertTrue(ms.all { it.size == 40 })
    }

    @Test
    fun `today's token, days remaining and merge follow ChatService`() {
        val today = 20713L
        val fresh = (0 until 31).map { token(today + it) }
        assertEquals(fresh[0], AccessTokens.todaysToken(fresh, today))
        assertEquals(fresh[5], AccessTokens.todaysToken(fresh, today + 5))
        assertNull(AccessTokens.todaysToken(fresh, today - 1), "yesterday's token was never minted")
        assertNull(AccessTokens.todaysToken(fresh, today + 31))
        assertNull(AccessTokens.todaysToken(emptyList(), today))

        assertEquals(31, AccessTokens.daysRemaining(fresh, today))
        assertEquals(26, AccessTokens.daysRemaining(fresh, today + 5))
        assertEquals(1, AccessTokens.daysRemaining(fresh, today + 30))
        assertEquals(0, AccessTokens.daysRemaining(fresh, today + 31))
        assertEquals(0, AccessTokens.daysRemaining(emptyList(), today), "0 = none / dev relay")

        // redeemAccessKey: filter epochDay >= today, then append — stale ones go, overlaps are kept twice
        val old = (0 until 31).map { token(today - 10 + it, sigByte = 1) } // 10 stale, 21 still valid
        val merged = AccessTokens.merge(old, fresh, today)
        assertEquals(21 + 31, merged.size)
        assertTrue(merged.none { it.epochDay < today })
        assertEquals(old.drop(10) + fresh, merged)
        assertEquals(52, AccessTokens.daysRemaining(merged, today), "iOS counts tokens, not distinct days")
    }

    @Test
    fun `AccessToken equality is by content`() {
        val a = token(1)
        val b = AccessToken(a.epochDay, a.token.copyOf(), a.signature.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == AccessToken(2, a.token, a.signature))
    }
}
