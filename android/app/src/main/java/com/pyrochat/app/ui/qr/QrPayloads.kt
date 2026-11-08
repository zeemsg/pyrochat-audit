package com.pyrochat.app.ui.qr

import com.pyrochat.app.BuildConfig
import java.util.Base64

/**
 * The add-me contact code — `pyro-add:v1:<username>:<b64url-nopad(identityKey.serialize(), 33 B)>`
 * (`docs/android/inventory-protocol.md` §8; iOS `ChatService.myAddQRPayload` +
 * `AddByQRView.handle`, `ios/PyroChat/UI/AddByQRView.swift:130-170`). Pure Kotlin: the screen's
 * parse + status strings live here so they stay JVM-testable.
 */
object AddQrPayload {
    const val SCHEME = "pyro-add"
    const val VERSION = "v1"
    private const val PREFIX = "$SCHEME:"
    private const val LINK_PREFIX = "pyro-link:"

    /** What `AddByQRView.handle` makes of a decoded string. */
    sealed interface Parsed {
        /** `raw.hasPrefix("pyro-link:")` — explained, camera stays live. */
        data object DesktopLink : Parsed

        /** A `pyro-add:` code that fails the shape check — explained, camera stays live. */
        data object Unreadable : Parsed

        /** Someone's lunch menu — ignored silently, camera stays live. */
        data object Foreign : Parsed

        /** Well-formed: the scanner stops while the add runs. */
        class Contact(val username: String, val identityKey: ByteArray) : Parsed

        /** An access key (`pyro-key:v1:<CODE>` / `pyrochat://redeem/<CODE>`, FIRST-RUN.md §3) — the ACCESS KEY dialog takes over. */
        data class AccessKey(val code: String) : Parsed

        /** One of the key prefixes with a broken code — `! not an access key`, camera stays live. */
        data object NotAKey : Parsed
    }

    /** `ChatService.myAddQRPayload()` — byte-for-byte the string iOS puts in MY CODE. */
    fun encode(username: String, identityKey: ByteArray): String =
        "$SCHEME:$VERSION:$username:" + Base64.getUrlEncoder().withoutPadding().encodeToString(identityKey)

    /**
     * `AddByQRView.handle` lines 132-143: `pyro-link:` first; then exactly four `:`-parts (empty
     * pieces kept, like Swift `omittingEmptySubsequences: false`), `pyro-add`/`v1`, a non-empty
     * username and a non-empty key that decodes as base64url-no-pad (`Data(base64urlNoPad:)`,
     * which also accepts padded/standard base64 by construction).
     */
    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.startsWith(LINK_PREFIX)) return Parsed.DesktopLink
        if (BuildConfig.ACCESS_KEY_UI) {
            when (val key = AccessKeyPayload.parse(trimmed)) {
                is AccessKeyPayload.Parsed.Key -> return Parsed.AccessKey(key.code)
                AccessKeyPayload.Parsed.NotAKey -> return Parsed.NotAKey
                AccessKeyPayload.Parsed.Foreign -> Unit
            }
        }
        // iOS `ContactCode.parse` (FirstRun.swift): the bare code, or the whole [share] message pasted as one
        // string — the token runs from `pyro-add:` to the next whitespace; no `pyro-add:` anywhere = not ours
        val start = trimmed.indexOf(PREFIX)
        if (start < 0) return Parsed.Foreign
        val token = trimmed.substring(start).takeWhile { !it.isWhitespace() }
        val parts = token.split(":")
        val key = if (parts.size == 4) b64urlDecode(parts[3]) else null
        if (parts.size != 4 || parts[0] != SCHEME || parts[1] != VERSION || parts[2].isEmpty() || key == null || key.isEmpty()) {
            return Parsed.Unreadable
        }
        return Parsed.Contact(parts[2], key)
    }

    /** iOS `Data(base64urlNoPad:)` (`ios/PyroChat/Core/MirrorLink.swift:121-126`). */
    fun b64urlDecode(s: String): ByteArray? {
        val sb = StringBuilder(s.replace('-', '+').replace('_', '/'))
        while (sb.length % 4 != 0) sb.append('=')
        return try {
            Base64.getDecoder().decode(sb.toString())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ---- the status strings AddByQRView shows (verbatim; `!` prefix = alert colour + [ RESCAN ]) ----

    const val STATUS_DESKTOP_LINK = "! that's a DESKTOP LINK code — scan it from [cfg] → LINK DESKTOP"
    const val STATUS_UNREADABLE = "! unreadable contact code"
    const val STATUS_NOT_A_KEY = AccessKeyPayload.STATUS_NOT_A_KEY

    fun statusOpening(username: String): String = "opening channel with <$username> …"

    fun statusAdded(username: String, verified: Boolean): String =
        if (verified) "<$username> added + safety number VERIFIED ✓"
        else "<$username> added — verify the safety number when you can"

    /** `catch` clauses of `handle` — [error] is the code (`BackendException.message`, Swift `"\(error)"`). */
    fun statusFailed(username: String, error: String): String = when {
        error.contains("qr_key_mismatch") ->
            "! this code does NOT match the key you already hold for <$username>. nothing was changed — do not trust this code."
        error.contains("identity_mismatch") ->
            "! IDENTITY MISMATCH — the relay served a different key than this QR. the contact was NOT added; someone may sit between you."
        error.contains("bad_username") -> "! invalid username in code"
        else -> "! could not add <$username>: $error"
    }
}

/**
 * The scanner-side half of `LinkDesktopView.handle` (`ios/PyroChat/UI/LinkDesktopView.swift:124-152`):
 * decides whether a decoded string is even a `pyro-link` code before the backend's
 * `MirrorLink.parse` + `startMirror` run, so the camera latches only on a link that started.
 */
object LinkQrPayload {
    const val SCHEME = "pyro-link"

    /**
     * True iff `MirrorLink.parse` would NOT throw `notPyroLink`: at least five `:`-parts and
     * `parts[0] == "pyro-link"` (`ios/PyroChat/Core/MirrorLink.swift`, `MirrorLink.kt` in `mirror`).
     * Anything else is either the contact-code hint or someone's lunch QR.
     */
    fun isPyroLink(raw: String): Boolean {
        val parts = raw.split(":")
        return parts.size >= 5 && parts[0] == SCHEME
    }

    /** `raw.hasPrefix("pyro-add:")` inside the `notPyroLink` catch → the contact-code hint. */
    fun isContactCode(raw: String): Boolean = raw.startsWith("pyro-add:")

    /** `LinkDesktopView.handle`'s `notPyroLink` branch text (`Mapping.ERR_LINK_CONTACT_CODE` says the same). */
    const val ERR_CONTACT_CODE = "that's a CONTACT code — scan it from [qr] on the home screen"
}

/**
 * An access key by QR or by link (docs/FIRST-RUN.md §3) — two equivalent forms of the same
 * `PYRO-XXXX-XXXX-XXXX-XXXX` (Crockford base32, 4×4):
 *
 *   payload   `pyro-key:v1:<CODE>`
 *   deep link `pyrochat://redeem/<CODE>`
 *
 * Both are accepted from the in-app [qr] scanner, the paste field and (the link) from the OS.
 * Normalised like the redeem endpoint — trim, uppercase, keep `A–Z0–9-` — so a lowercase or
 * space-broken code still lands; a code that does not match `PYRO(-[0-9A-Z]{4}){4}` is refused
 * with [STATUS_NOT_A_KEY]. Pure Kotlin, JVM-tested (`AccessKeyPayloadTest`).
 */
object AccessKeyPayload {
    const val SCHEME = "pyro-key"
    const val VERSION = "v1"
    /** `pyro-key:v1:` */
    const val PAYLOAD_PREFIX = "$SCHEME:$VERSION:"
    /** `pyrochat://redeem/` — the manifest's VIEW filter (scheme `pyrochat`, host `redeem`). */
    const val LINK_PREFIX = "pyrochat://redeem/"
    private const val SCHEME_PREFIX = "$SCHEME:"
    /** `pyrochat:` — any other host / form of our URI scheme is ours but not a key (iOS `AccessKeyLink.parse`). */
    private const val URI_SCHEME_PREFIX = "pyrochat:"
    /** The redeem endpoint's shape check. */
    val SHAPE: Regex = Regex("PYRO(-[0-9A-Z]{4}){4}")

    sealed interface Parsed {
        /** Well-formed, normalised. */
        data class Key(val code: String) : Parsed

        /** Ours (`pyro-key:` / `pyrochat://redeem/`) but not a key → [STATUS_NOT_A_KEY]. */
        data object NotAKey : Parsed

        /** Not one of the two forms at all. */
        data object Foreign : Parsed
    }

    /** `! not an access key` */
    const val STATUS_NOT_A_KEY = "! not an access key"

    /** Trim, uppercase, keep `A–Z0–9-` (the redeem endpoint's normalisation). */
    fun normalise(raw: String): String =
        raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /**
     * Prefixes match case-insensitively (a QR in alphanumeric mode is upper-case by construction);
     * the body is normalised and must match [SHAPE] exactly.
     */
    fun parse(raw: String): Parsed {
        val s = raw.trim()
        val body = when {
            s.startsWith(PAYLOAD_PREFIX, ignoreCase = true) -> s.substring(PAYLOAD_PREFIX.length)
            s.startsWith(LINK_PREFIX, ignoreCase = true) -> s.substring(LINK_PREFIX.length)
            // pyro-key:v2:… / pyrochat://other/… — ours, not readable (the manifest routes only host `redeem` from the OS)
            s.startsWith(SCHEME_PREFIX, ignoreCase = true) || s.startsWith(URI_SCHEME_PREFIX, ignoreCase = true) -> return Parsed.NotAKey
            else -> return Parsed.Foreign
        }
        val code = normalise(body)
        return if (SHAPE.matches(code)) Parsed.Key(code) else Parsed.NotAKey
    }

    /** The payload form of a code (what a QR would carry). */
    fun payload(code: String): String = PAYLOAD_PREFIX + code

    /** The deep-link form of a code (what `web/success.html` links to). */
    fun link(code: String): String = LINK_PREFIX + code
}
