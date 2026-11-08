package com.pyrochat.app.ui.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AddQrPayloadTest {
    /** A 33-byte `IdentityKey.serialize()`: 0x05 ‖ 32 raw bytes (docs/android/inventory-critique.md §1.8). */
    private val key = byteArrayOf(0x05) + ByteArray(32) { (it * 7 + 3).toByte() }

    @Test fun encodeMatchesTheDocumentedFormatByteForByte() {
        // docs/android/inventory-protocol.md §8: pyro-add:v1:<usernameDisplay>:<b64url-nopad(identityKey.serialize(), 33 B)>
        val payload = AddQrPayload.encode("Alice_01", key)
        val expectedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        assertEquals("pyro-add:v1:Alice_01:$expectedKey", payload)
        assertEquals(44, expectedKey.length)                       // 33 bytes → 44 chars, no '='
        assertTrue(Regex("^pyro-add:v1:[A-Za-z0-9_]{3,24}:[A-Za-z0-9_-]{44}$").matches(payload))
        assertTrue("username travels as typed (display form)", payload.contains(":Alice_01:"))
        // byte-for-byte: pure ASCII, no padding, url alphabet only
        assertArrayEquals(payload.toByteArray(Charsets.US_ASCII), payload.toByteArray(Charsets.UTF_8))
    }

    @Test fun parseRoundTripsAndAcceptsStandardBase64Too() {
        val payload = AddQrPayload.encode("bob", key)
        val parsed = AddQrPayload.parse(payload) as AddQrPayload.Parsed.Contact
        assertEquals("bob", parsed.username)
        assertArrayEquals(key, parsed.identityKey)
        // iOS Data(base64urlNoPad:) pads and maps -/_ → also decodes a padded standard string
        val standard = "pyro-add:v1:bob:" + Base64.getEncoder().encodeToString(key)
        val p2 = AddQrPayload.parse(standard) as AddQrPayload.Parsed.Contact
        assertArrayEquals(key, p2.identityKey)
    }

    @Test fun desktopLinkCodesAreExplainedNotParsed() {
        assertSame(AddQrPayload.Parsed.DesktopLink, AddQrPayload.parse("pyro-link:v1:TESTPAIRID0000000000AA:abc:relay.pyrochat.ca"))
        assertSame(AddQrPayload.Parsed.DesktopLink, AddQrPayload.parse("pyro-link:"))
    }

    @Test fun brokenContactCodesAreUnreadable() {
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        for (raw in listOf(
            "pyro-add:v1:bob",                 // 3 parts
            "pyro-add:v1:bob:$b64:extra",      // 5 parts
            "pyro-add:v2:bob:$b64",            // wrong version
            "pyro-add:v1::$b64",               // empty username
            "pyro-add:v1:bob:",                // empty key
            "pyro-add:v1:bob:!!!",             // not base64
            "pyro-add:",                       // bare prefix
        )) {
            assertSame(raw, AddQrPayload.Parsed.Unreadable, AddQrPayload.parse(raw))
        }
    }

    @Test fun foreignCodesStaySilent() {
        for (raw in listOf("https://example.com/lunch", "WIFI:S:cafe;;", "", "pyro:add:v1:x", "PYRO-ADD:v1:bob:AAAA")) {
            assertSame(raw, AddQrPayload.Parsed.Foreign, AddQrPayload.parse(raw))
        }
    }

    @Test fun statusStringsAreTheIosOnes() {
        assertEquals("! that's a DESKTOP LINK code — scan it from [cfg] → LINK DESKTOP", AddQrPayload.STATUS_DESKTOP_LINK)
        assertEquals("! unreadable contact code", AddQrPayload.STATUS_UNREADABLE)
        assertEquals("opening channel with <bob> …", AddQrPayload.statusOpening("bob"))
        assertEquals("<bob> added + safety number VERIFIED ✓", AddQrPayload.statusAdded("bob", verified = true))
        assertEquals("<bob> added — verify the safety number when you can", AddQrPayload.statusAdded("bob", verified = false))
        assertEquals(
            "! this code does NOT match the key you already hold for <bob>. nothing was changed — do not trust this code.",
            AddQrPayload.statusFailed("bob", "qr_key_mismatch"),
        )
        assertEquals(
            "! IDENTITY MISMATCH — the relay served a different key than this QR. the contact was NOT added; someone may sit between you.",
            AddQrPayload.statusFailed("bob", "identity_mismatch"),
        )
        assertEquals("! invalid username in code", AddQrPayload.statusFailed("bob", "bad_username"))
        assertEquals("! could not add <bob>: unknown_user", AddQrPayload.statusFailed("bob", "unknown_user"))
        assertEquals("! could not add <bob>: cannot_add_self", AddQrPayload.statusFailed("bob", "cannot_add_self"))
    }
}
