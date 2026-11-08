package com.pyrochat.mirror

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ios/Tests/MirrorTests.swift` — QR parsing section, same inputs and outcomes. */
class MirrorLinkTest {
    private val relay = "ws://72.62.171.10:7443/ws"

    /** `testParseAcceptsDifferentPortSameHost`: web TLS listener on :7444, phone relay on :7443 — same operator. */
    @Test
    fun parseAcceptsDifferentPortSameHost() {
        val pub = MirrorCrypto.b64url(ByteArray(32) { 7 })
        val link = MirrorLink.parse("pyro-link:v1:$TEST_PAIR_ID:$pub:72.62.171.10:7444", relay)
        assertEquals("72.62.171.10:7444", link.host)
    }

    /** `testParseValidPayload` */
    @Test
    fun parseValidPayload() {
        val pub = ByteArray(32) { 0x22 }
        val raw = "pyro-link:v1:$TEST_PAIR_ID:${MirrorCrypto.b64url(pub)}:72.62.171.10:7443"
        val link = MirrorLink.parse(raw, relay)
        assertEquals(TEST_PAIR_ID, link.pairId)
        assertContentEquals(pub, link.browserPub)
        assertEquals("72.62.171.10:7443", link.host)
    }

    /** `testParseRejectsNonPyroPayloadSilently` */
    @Test
    fun parseRejectsNonPyroPayloadSilently() {
        assertFailsWith<MirrorLink.ParseError.NotPyroLink> { MirrorLink.parse("https://example.com", relay) }
        // a contact code is "not a link" too — LinkDesktopView then hints at [qr]
        assertFailsWith<MirrorLink.ParseError.NotPyroLink> { MirrorLink.parse("pyro-add:v1:bob:AAAA", relay) }
    }

    /** `testParseRejectsWrongVersionAndBadPair` */
    @Test
    fun parseRejectsWrongVersionAndBadPair() {
        val pub = MirrorCrypto.b64url(ByteArray(32) { 1 })
        assertFailsWith<MirrorLink.ParseError.Malformed> { MirrorLink.parse("pyro-link:v2:$TEST_PAIR_ID:$pub:h", relay) }
        assertFailsWith<MirrorLink.ParseError.Malformed> { MirrorLink.parse("pyro-link:v1:short:$pub:h", relay) }
        assertFailsWith<MirrorLink.ParseError.Malformed> { MirrorLink.parse("pyro-link:v1:$TEST_PAIR_ID:notakey:h", relay) }
        // 31-byte key
        assertFailsWith<MirrorLink.ParseError.Malformed> {
            MirrorLink.parse("pyro-link:v1:$TEST_PAIR_ID:${MirrorCrypto.b64url(ByteArray(31) { 1 })}:h", relay)
        }
        // pair id with a character outside letters/digits/-/_
        assertFailsWith<MirrorLink.ParseError.Malformed> { MirrorLink.parse("pyro-link:v1:TESTPAIRID000000000!AA:$pub:h", relay) }
    }

    /** `testParseRejectsWrongRelayNamingBothHosts` */
    @Test
    fun parseRejectsWrongRelayNamingBothHosts() {
        val pub = MirrorCrypto.b64url(ByteArray(32) { 1 })
        val e = assertFailsWith<MirrorLink.ParseError.WrongRelay> {
            MirrorLink.parse("pyro-link:v1:$TEST_PAIR_ID:$pub:evil.example:9999", relay)
        }
        assertEquals(MirrorLink.ParseError.WrongRelay(expected = "72.62.171.10", got = "evil.example"), e)
    }

    /** What the page renders round-trips through the phone parser (local dev relay, http web port = ws port). */
    @Test
    fun encodeRoundTripsThroughParse() {
        val browser = TestBrowser(ByteArray(32) { 0x32 })
        val raw = browser.qrPayload("127.0.0.1:7444")
        assertEquals("pyro-link:v1:$TEST_PAIR_ID:${MirrorCrypto.b64url(browser.pub)}:127.0.0.1:7444", raw)
        assertEquals(43, MirrorCrypto.b64url(browser.pub).length)
        val link = MirrorLink.parse(raw, "ws://127.0.0.1:7444/ws")
        assertEquals(MirrorLink(TEST_PAIR_ID, browser.pub, "127.0.0.1:7444"), link)
        // the production page: served from relay.pyrochat.ca (no port), phone on wss://relay.pyrochat.ca/ws
        val prod = MirrorLink.parse(browser.qrPayload("relay.pyrochat.ca"), "wss://relay.pyrochat.ca/ws")
        assertEquals("relay.pyrochat.ca", prod.host)
    }
}
