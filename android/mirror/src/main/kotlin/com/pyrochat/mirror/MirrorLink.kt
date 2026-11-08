package com.pyrochat.mirror

import java.net.URI
import java.net.URISyntaxException

/**
 * The desktop-link QR payload — port of iOS `MirrorLink` (`ios/PyroChat/Core/MirrorLink.swift`).
 *
 * `pyro-link:v1:<pairId 22 chars>:<b64url(browserPub 32 B) no-pad, 43 chars>:<host[:port]>`
 *
 * The browser key travels ONLY in the QR (never through the relay); the phone always dials its OWN
 * configured relay and refuses a payload naming another host. The host comparison is HOST-ONLY —
 * the page is served from the web TLS port while the phone talks ws on the relay port.
 */
class MirrorLink(val pairId: String, val browserPub: ByteArray, val host: String) {

    /** iOS `MirrorLink.ParseError` — same three cases, same meaning for the scanner UI. */
    sealed class ParseError(message: String) : Exception(message) {
        /** Wrong prefix — someone's lunch QR; keep scanning silently. */
        object NotPyroLink : ParseError("not a pyro-link code") {
            private fun readResolve(): Any = NotPyroLink
        }
        object Malformed : ParseError("malformed pyro-link code") {
            private fun readResolve(): Any = Malformed
        }
        data class WrongRelay(val expected: String, val got: String) :
            ParseError("QR targets relay $got, this phone is on $expected")
    }

    override fun equals(other: Any?): Boolean =
        other is MirrorLink && other.pairId == pairId && other.host == host && other.browserPub.contentEquals(browserPub)

    override fun hashCode(): Int = 31 * (31 * pairId.hashCode() + host.hashCode()) + browserPub.contentHashCode()

    override fun toString(): String = "MirrorLink(pairId=$pairId, host=$host)"

    companion object {
        const val SCHEME = "pyro-link"
        const val VERSION = "v1"
        const val PAIR_ID_LENGTH = 22

        /**
         * iOS `MirrorLink.parse(_:expectedRelay:)`. [expectedRelayUrl] is `settings.relayURL`
         * (e.g. `wss://relay.pyrochat.ca/ws`); only its host takes part in the check.
         *
         * @throws ParseError.NotPyroLink wrong scheme (fewer than 5 `:`-parts or `parts[0] != "pyro-link"`)
         * @throws ParseError.Malformed wrong version, pair id not 22 letters/digits/`-`/`_`, pub not 32 bytes
         * @throws ParseError.WrongRelay host differs from the phone's relay host
         */
        fun parse(raw: String, expectedRelayUrl: String): MirrorLink {
            val parts = raw.split(":") // keeps empty pieces, like Swift omittingEmptySubsequences: false
            if (parts.size < 5 || parts[0] != SCHEME) throw ParseError.NotPyroLink
            if (parts[1] != VERSION) throw ParseError.Malformed
            val pairId = parts[2]
            val pairOk = pairId.codePointCount(0, pairId.length) == PAIR_ID_LENGTH &&
                pairId.codePoints().allMatch { cp ->
                    UnicodeText.isLetter(cp) || UnicodeText.isNumber(cp) || cp == '-'.code || cp == '_'.code
                }
            val pub = MirrorCrypto.b64urlDecode(parts[3])
            if (!pairOk || pub == null || pub.size != MirrorCrypto.KEY_BYTES) throw ParseError.Malformed
            val got = parts.subList(4, parts.size).joinToString(":")
            // anti-phishing check compares HOST only: strip a trailing ":<digits>" (an empty suffix
            // after the last colon also counts as "all digits", exactly like Swift's allSatisfy)
            var gotHost = got
            val colon = got.lastIndexOf(':')
            if (colon >= 0) {
                val suffix = got.substring(colon + 1)
                if (suffix.codePoints().allMatch { UnicodeText.isNumber(it) }) gotHost = got.substring(0, colon)
            }
            val expected = relayHost(expectedRelayUrl)
            if (gotHost != expected) throw ParseError.WrongRelay(expected, gotHost)
            return MirrorLink(pairId, pub, got)
        }

        /** What the web page puts in the QR (`renderQR()` in `index.html`): `location.host` as the last part. */
        fun encode(pairId: String, browserPub: ByteArray, host: String): String {
            require(browserPub.size == MirrorCrypto.KEY_BYTES) { "browser pub must be 32 raw bytes" }
            return "$SCHEME:$VERSION:$pairId:${MirrorCrypto.b64url(browserPub)}:$host"
        }

        /** Swift `URL(string: relayURL)?.host ?? ""`. */
        fun relayHost(relayUrl: String): String = try {
            URI(relayUrl).host ?: ""
        } catch (_: URISyntaxException) {
            ""
        }
    }
}

/**
 * Swift `Character.isLetter` / `Character.isNumber` on the JVM: `isLetter` is the Unicode
 * Alphabetic property, `isNumber` is Numeric_Type != None (general categories Nd, Nl, No).
 */
internal object UnicodeText {
    fun isLetter(cp: Int): Boolean = Character.isAlphabetic(cp)

    fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}
