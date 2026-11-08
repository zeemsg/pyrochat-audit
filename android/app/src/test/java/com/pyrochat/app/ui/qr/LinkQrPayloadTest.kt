package com.pyrochat.app.ui.qr

import com.pyrochat.mirror.MirrorLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The scanner's pre-check must agree with `MirrorLink.parse`'s `notPyroLink` verdict, and
 * `MirrorLink.parse` must accept exactly what the browser page's `renderQR()` emits
 * (`relay/src/main/resources/web/index.html`).
 */
class LinkQrPayloadTest {
    private val browserPub = ByteArray(32) { (0x40 + it).toByte() }

    /** `b64.url` in index.html: standard base64 with `+`→`-`, `/`→`_`, `=` stripped. */
    private fun pageB64Url(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_').trimEnd('=')

    /** `"pyro-link:v1:"+pairId+":"+b64.url(kp.pub)+":"+location.host` */
    private fun pagePayload(pairId: String, host: String): String = "pyro-link:v1:$pairId:${pageB64Url(browserPub)}:$host"

    @Test fun isPyroLinkAgreesWithMirrorLinkParse() {
        val pair = "TESTPAIRID0000000000AA"
        val good = pagePayload(pair, "relay.pyrochat.ca")
        val cases = listOf(
            good,                                        // accepted
            "pyro-link:v0:$pair:${pageB64Url(browserPub)}:h", // malformed (not notPyroLink)
            "pyro-link:v1:short:${pageB64Url(browserPub)}:h", // malformed
            "pyro-link:v1:$pair:AAAA:h",                 // malformed
            "pyro-link:v1:$pair",                        // < 5 parts → notPyroLink
            "pyro-link:",                                // notPyroLink
            "pyro-add:v1:bob:AAAA",                      // notPyroLink (contact hint)
            "https://example.com/a:b:c:d:e",             // notPyroLink
            "",
        )
        for (raw in cases) {
            val notPyroLink = try {
                MirrorLink.parse(raw, "wss://relay.pyrochat.ca/ws"); false
            } catch (_: MirrorLink.ParseError.NotPyroLink) {
                true
            } catch (_: MirrorLink.ParseError) {
                false
            }
            assertEquals(raw, !notPyroLink, LinkQrPayload.isPyroLink(raw))
        }
        assertTrue(LinkQrPayload.isContactCode("pyro-add:v1:bob:AAAA"))
        assertFalse(LinkQrPayload.isContactCode(good))
        assertEquals("that's a CONTACT code — scan it from [qr] on the home screen", LinkQrPayload.ERR_CONTACT_CODE)
    }

    @Test fun mirrorLinkParseAcceptsExactlyWhatThePageEmits() {
        val pair = "TESTPAIRID0000000000AA" // 22 chars of [A-Za-z0-9_-], as the relay mints them
        // location.host carries a port when the page is served off a non-default one
        for ((host, relay) in listOf(
            "relay.pyrochat.ca" to "wss://relay.pyrochat.ca/ws",
            "relay.pyrochat.ca:7444" to "wss://relay.pyrochat.ca:7443/ws",   // web TLS port ≠ ws port: HOST-only check
            "10.0.2.2:7444" to "ws://10.0.2.2:7444/ws",
            "72.62.171.10:7444" to "ws://72.62.171.10:7443/ws",
        )) {
            val link = MirrorLink.parse(pagePayload(pair, host), relay)
            assertEquals(pair, link.pairId)
            assertArrayEquals(browserPub, link.browserPub)
            assertEquals(host, link.host)
            assertEquals(pagePayload(pair, host), MirrorLink.encode(pair, browserPub, host))
        }
        try {
            MirrorLink.parse(pagePayload(pair, "evil.example"), "wss://relay.pyrochat.ca/ws")
            fail("a foreign host must be refused")
        } catch (e: MirrorLink.ParseError.WrongRelay) {
            assertEquals("relay.pyrochat.ca", e.expected)
            assertEquals("evil.example", e.got)
        }
    }

    /** Pin the page's payload template and `b64.url` so a page edit that changes the QR format fails here. */
    @Test fun pageSourceStillBuildsTheFrozenPayload() {
        val page = sequenceOf("../../relay/src/main/resources/web/index.html", "../relay/src/main/resources/web/index.html", "relay/src/main/resources/web/index.html")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("relay web page not found next to the android tree", page != null)
        val src = page!!.readText()
        assertTrue(src.contains("""const payload = "pyro-link:v1:"+pairId+":"+b64.url(kp.pub)+":"+location.host;"""))
        assertTrue(src.contains("""url: u8 => b64.enc(u8).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,""),"""))
        assertTrue("byte mode, correction M like the phone", src.contains("""qrcode(0, "M"); qr.addData(payload, "Byte")"""))
    }
}
