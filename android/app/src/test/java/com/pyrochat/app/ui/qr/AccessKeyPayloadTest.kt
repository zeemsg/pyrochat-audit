package com.pyrochat.app.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** docs/FIRST-RUN.md §3 — both forms of the same key, normalised like the redeem endpoint. */
class AccessKeyPayloadTest {
    private val code = "PYRO-ABCD-EFGH-JKMN-PQRS"

    @Test fun bothFormsParseToTheSameKey() {
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("pyro-key:v1:$code"))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("pyrochat://redeem/$code"))
        assertEquals("pyro-key:v1:$code", AccessKeyPayload.payload(code))
        assertEquals("pyrochat://redeem/$code", AccessKeyPayload.link(code))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse(AccessKeyPayload.payload(code)))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse(AccessKeyPayload.link(code)))
        // digits are Crockford base32 too
        assertEquals(AccessKeyPayload.Parsed.Key("PYRO-0123-4567-89AB-CDEF"), AccessKeyPayload.parse("pyrochat://redeem/PYRO-0123-4567-89AB-CDEF"))
    }

    @Test fun lowercaseIsUppercased() {
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("pyro-key:v1:pyro-abcd-efgh-jkmn-pqrs"))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("pyrochat://redeem/pyro-abcd-efgh-jkmn-pqrs"))
        // a QR in alphanumeric mode is upper-case throughout, prefix included
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("PYRO-KEY:V1:$code"))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("PYROCHAT://REDEEM/$code"))
    }

    @Test fun whitespaceIsStripped() {
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("  pyro-key:v1:$code \n"))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("\tpyrochat://redeem/$code\r\n"))
        assertEquals(AccessKeyPayload.Parsed.Key(code), AccessKeyPayload.parse("pyro-key:v1: $code "))
        // the endpoint's normalisation (`CodeStore`: trim, uppercase, drop everything but A-Z 0-9 -) never re-inserts
        // a dash: a space INSIDE a group breaks the shape and is refused, exactly as the server would
        assertEquals("PYRO-ABCDEFGH-JKMN-PQRS", AccessKeyPayload.normalise(" pyro-abcd efgh-jkmn-pqrs\n"))
        assertSame(AccessKeyPayload.Parsed.NotAKey, AccessKeyPayload.parse("pyro-key:v1: PYRO-ABCD EFGH-JKMN-PQRS "))
        assertEquals("PYRO-ABCDEFGHJKMNPQRS", AccessKeyPayload.normalise(" pyro-abcd_efgh.jkmn pqrs\n"))
    }

    @Test fun wrongPrefixIsForeign() {
        for (raw in listOf(
            "pyro-add:v1:bob:AAAA",
            "pyro-link:v1:TESTPAIRID0000000000AA:abc:relay.pyrochat.ca",
            "https://pyrochat.ca/redeem/$code",
            "pyro-keys:v1:$code",
            code,                        // the bare key: not a payload
            "",
        )) {
            assertSame(raw, AccessKeyPayload.Parsed.Foreign, AccessKeyPayload.parse(raw))
        }
    }

    @Test fun wrongShapeIsRefusedNotForeign() {
        for (raw in listOf(
            "pyro-key:v1:",
            "pyrochat://redeem/",
            "pyro-key:v1:PYRO-ABC-EFGH-JKMN-PQRS",          // a 3-char group
            "pyro-key:v1:PYRO-ABCD-EFGH-JKMN",              // three groups
            "pyro-key:v1:PYRO-ABCD-EFGH-JKMN-PQRS-TUVW",    // five groups
            "pyro-key:v1:PYRX-ABCD-EFGH-JKMN-PQRS",         // wrong stem
            "pyro-key:v1:ABCD-EFGH-JKMN-PQRS",              // no stem
            "pyrochat://redeem/$code?utm=1",                // junk after the code
            "pyrochat://redeem/$code/extra",
            "pyro-key:v2:$code",                            // ours, unreadable version
            "pyro-key:$code",
            "pyrochat://other/$code",                       // our URI scheme, wrong host (iOS: malformed)
            "pyrochat:redeem/$code",
            "pyrochat://redeem",
        )) {
            assertSame(raw, AccessKeyPayload.Parsed.NotAKey, AccessKeyPayload.parse(raw))
        }
        assertEquals("! not an access key", AccessKeyPayload.STATUS_NOT_A_KEY)
        assertTrue(AccessKeyPayload.SHAPE.matches(code))
    }

    @Test fun theQrScreenRoutesKeysAndStillParsesContacts() {
        val identityKey = byteArrayOf(0x05) + ByteArray(32) { (it * 7 + 3).toByte() }
        val add = AddQrPayload.encode("bob", identityKey)
        val contact = AddQrPayload.parse(add) as AddQrPayload.Parsed.Contact
        assertEquals("bob", contact.username)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(identityKey), add.substringAfterLast(':'))
        // both key forms land in the ACCESS KEY dialog; broken ones explain themselves; links stay links
        assertEquals(AddQrPayload.Parsed.AccessKey(code), AddQrPayload.parse("pyro-key:v1:$code"))
        assertEquals(AddQrPayload.Parsed.AccessKey(code), AddQrPayload.parse("pyrochat://redeem/pyro-abcd-efgh-jkmn-pqrs"))
        assertSame(AddQrPayload.Parsed.NotAKey, AddQrPayload.parse("pyrochat://redeem/garbage"))
        assertSame(AddQrPayload.Parsed.NotAKey, AddQrPayload.parse("pyro-key:v1:PYRO-TEST"))
        assertSame(AddQrPayload.Parsed.DesktopLink, AddQrPayload.parse("pyro-link:v1:TESTPAIRID0000000000AA:abc:relay.pyrochat.ca"))
        assertSame(AddQrPayload.Parsed.Unreadable, AddQrPayload.parse("pyro-add:v1:bob"))
        assertSame(AddQrPayload.Parsed.Foreign, AddQrPayload.parse("https://example.com/lunch"))
        assertEquals("! not an access key", AddQrPayload.STATUS_NOT_A_KEY)
    }

    @Test fun thePasteFieldTakesTheWholeShareMessageAndStrayWhitespace() {
        val identityKey = byteArrayOf(0x05) + ByteArray(32) { (it * 3 + 1).toByte() }
        val add = AddQrPayload.encode("alice", identityKey)
        // the recipient pastes the three-line [share] text as one string (iOS `ContactCode.parse` does the same)
        val message = com.pyrochat.app.ui.HomeRules.shareText("alice", add)
        val fromMessage = AddQrPayload.parse(message) as AddQrPayload.Parsed.Contact
        assertEquals("alice", fromMessage.username)
        assertTrue(identityKey.contentEquals(fromMessage.identityKey))
        // a code with a newline or spaces around it (a chat app's copy) still parses
        val padded = AddQrPayload.parse("  $add\n") as AddQrPayload.Parsed.Contact
        assertEquals("alice", padded.username)
        // a broken code inside a message explains itself instead of vanishing
        assertSame(AddQrPayload.Parsed.Unreadable, AddQrPayload.parse("in the app: [qr] → paste this code → pyro-add:v1:alice\nget the app"))
        // a key inside stray whitespace is still a key
        assertEquals(AddQrPayload.Parsed.AccessKey(code), AddQrPayload.parse(" pyro-key:v1:$code\n"))
        assertSame(AddQrPayload.Parsed.Foreign, AddQrPayload.parse("   "))
    }
}
