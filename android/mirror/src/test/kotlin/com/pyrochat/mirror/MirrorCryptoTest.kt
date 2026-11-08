package com.pyrochat.mirror

import java.util.Base64
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The golden vectors of `ios/Tests/MirrorTests.swift`, reproduced byte-for-byte. They were generated
 * by an independent node:crypto implementation of docs/MIRROR-PROTOCOL.md and are also asserted
 * against the web client's JS — all implementations must agree.
 */
class MirrorCryptoTest {
    private val phonePriv = ByteArray(32) { 0x31 }
    private val browserPriv = ByteArray(32) { 0x32 }

    /** `testKdfGoldenVector`: phoneSeed = 32×0x31, browserSeed = 32×0x32, pairId TESTPAIRID0000000000AA. */
    @Test
    fun kdfGoldenVector() {
        val pPub = MirrorCrypto.publicKey(phonePriv)
        val bPub = MirrorCrypto.publicKey(browserPriv)
        assertEquals("BPXykWLDGo3voY5udCIk7oBvwXGKJ4voWbpWIEArjzo=", Base64.getEncoder().encodeToString(pPub))
        assertEquals("WdkiVHNFHv/+azbbyu/b97GJXeYghFCaf1tYvwHQZBg=", Base64.getEncoder().encodeToString(bPub))

        val ss = MirrorCrypto.agree(phonePriv, bPub)
        // the browser side (tweetnacl / WebCrypto) derives the same secret
        assertContentEquals(ss, MirrorCrypto.agree(browserPriv, pPub))
        val keys = MirrorCrypto.deriveKeys(ss, TEST_PAIR_ID, pPub, bPub)
        assertEquals("114703", keys.sas)

        // the exact record node:crypto sealed with k_p2b must open here
        val rec = Base64.getDecoder().decode("AwAAAAAAAAABUd93pG05sCrHg2n4I7rP4/wSceC3fdplPrLRczueeRn7ms6/MTI=")
        assertEquals(0x03, rec[0].toInt())
        assertEquals(1L, MirrorCrypto.seqOf(rec))
        val pt = MirrorCrypto.open(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 1, rec.copyOfRange(9, rec.size))
        assertEquals("""{"e":"conn","up":true}""", String(pt, Charsets.UTF_8))

        // and sealing the same plaintext reproduces it byte-for-byte
        val sealed = MirrorCrypto.seal(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 1, """{"e":"conn","up":true}""".toByteArray())
        assertContentEquals(rec, sealed)
    }

    /** `testTamperedRecordRejected` */
    @Test
    fun tamperedRecordRejected() {
        val a = MirrorCrypto.randomPrivateKey()
        val b = MirrorCrypto.randomPrivateKey()
        val aPub = MirrorCrypto.publicKey(a)
        val bPub = MirrorCrypto.publicKey(b)
        val keys = MirrorCrypto.deriveKeys(MirrorCrypto.agree(a, bPub), TEST_PAIR_ID, aPub, bPub)
        val rec = MirrorCrypto.seal(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 5, "hi".toByteArray())
        val sealed = rec.copyOfRange(9, rec.size)

        // flip one ciphertext bit
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.open(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 5, sealed) }
        sealed[0] = (sealed[0].toInt() xor 1).toByte()

        // replay under a different seq must fail (nonce+AAD bind the position)
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.open(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 6, sealed) }
        // wrong direction key/nonce must fail
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.open(keys.b2p, "b2p", TEST_PAIR_ID, 0x03, 5, sealed) }
        // wrong type in AAD must fail
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.open(keys.p2b, "p2b", TEST_PAIR_ID, 0x02, 5, sealed) }
        // wrong pair id in AAD must fail
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.open(keys.p2b, "p2b", "TESTPAIRID0000000000AB", 0x03, 5, sealed) }
        // intact original still opens
        assertContentEquals("hi".toByteArray(), MirrorCrypto.open(keys.p2b, "p2b", TEST_PAIR_ID, 0x03, 5, sealed))
    }

    /** `testB64urlRoundtrip` */
    @Test
    fun b64urlRoundtrip() {
        for (n in intArrayOf(0, 1, 2, 3, 31, 32, 33)) {
            val d = ByteArray(n) { ((it * 37) and 0xff).toByte() }
            val s = MirrorCrypto.b64url(d)
            assertFalse(s.contains('+') || s.contains('/') || s.contains('='), "b64url must be URL-safe, unpadded: $s")
            assertContentEquals(d, MirrorCrypto.b64urlDecode(s))
        }
        // Swift `Data(base64urlNoPad:)` also accepts standard, padded base64
        assertContentEquals(ByteArray(32) { 7 }, MirrorCrypto.b64urlDecode(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })))
        assertEquals(null, MirrorCrypto.b64urlDecode("not*base64"))
    }

    /** Frozen framing constants (MIRROR-PROTOCOL.md §4): nonce = dir ‖ 0 ‖ seq(8 BE); AAD = dir|pairId| ‖ type. */
    @Test
    fun nonceAndAadLayout() {
        val nonce = MirrorCrypto.nonce("p2b", 0x0102030405060708L)
        assertContentEquals(byteArrayOf(0x70, 0x32, 0x62, 0, 1, 2, 3, 4, 5, 6, 7, 8), nonce)
        val aad = MirrorCrypto.aad("b2p", TEST_PAIR_ID, 0x03)
        assertContentEquals("b2p|$TEST_PAIR_ID|".toByteArray() + byteArrayOf(0x03), aad)
        // a 0x01 record carries the raw 32-byte phone pub between the header and the GCM box
        val pub = MirrorCrypto.publicKey(phonePriv)
        val keys = MirrorCrypto.deriveKeys(MirrorCrypto.agree(phonePriv, MirrorCrypto.publicKey(browserPriv)), TEST_PAIR_ID, pub, MirrorCrypto.publicKey(browserPriv))
        val hello = MirrorCrypto.seal(keys.p2b, "p2b", TEST_PAIR_ID, 0x01, 0, "{}".toByteArray(), prefix = pub)
        assertEquals(0x01, hello[0].toInt())
        assertEquals(0L, MirrorCrypto.seqOf(hello))
        assertContentEquals(pub, hello.copyOfRange(9, 41))
        assertEquals(9 + 32 + 2 + 16, hello.size)
    }

    /** MIRROR-PROTOCOL.md §3 "reject all-zero": a low-order peer point must not yield a key. */
    @Test
    fun degeneratePeerKeyRejected() {
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.agree(phonePriv, ByteArray(32)) }
        assertFailsWith<MirrorCryptoException> { MirrorCrypto.agree(phonePriv, ByteArray(31)) }
    }

    /**
     * `%06d` is localised by the JVM / Android Formatter: an ar/fa/bn/ne/mr/my phone rendered
     * Arabic-Indic or Devanagari digits, and the browser compares the SAS byte-for-byte, so every
     * link attempt failed with a false tamper alarm. The SAS must be ASCII digits in every locale.
     */
    @Test
    fun sasIsAsciiDigitsInEveryLocale() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val pPub = MirrorCrypto.publicKey(phonePriv)
            val bPub = MirrorCrypto.publicKey(browserPriv)
            val keys = MirrorCrypto.deriveKeys(MirrorCrypto.agree(phonePriv, bPub), TEST_PAIR_ID, pPub, bPub)
            assertEquals("114703", keys.sas)
            assertTrue(keys.sas.all { it in '0'..'9' })
        } finally {
            Locale.setDefault(prev)
        }
    }
}
