package com.pyrochat.protocol

import java.security.MessageDigest
import java.util.Locale

/**
 * `username_hash = hex(sha256(utf8(username.lowercased())))` — the relay routing address
 * (iOS `sha256Hex(username.lowercased())`, `CryptoEngine.generateIdentity`, `ChatService.addContact`).
 *
 * ## Which lowercasing, and why
 * Swift's stdlib `String.lowercased()` maps **each Unicode scalar independently** with the full
 * Unicode lowercase mapping (UnicodeData + the unconditional SpecialCasing entries) and applies
 * **no context-sensitive rules** — verified in docs/android/inventory-critique.md §1.4:
 * `"ΟΔΥΣΣΕΥΣ".lowercased() == "οδυσσευσ"` (every Σ → σ U+03C3, no final-sigma ς).
 *
 * Java's `String.toLowerCase(Locale.ROOT)` on a whole string DOES apply the Final_Sigma context
 * rule (→ `οδυσσευς`, U+03C2), so a Greek name ending in Σ would hash to a different relay
 * address than on iOS. Applying `toLowerCase(Locale.ROOT)` to **one code point at a time** removes
 * the only context rule Java applies under the root locale, while keeping Java's unconditional
 * special case for U+0130 (`İ` → `i̇` = U+0069 U+0307, which `Character.toLowerCase(int)` would
 * lose). For every other code point Java's simple mapping equals the full mapping (U+0130 is the
 * sole unconditional 1:n lowercase entry in SpecialCasing.txt), so per-code-point ROOT lowering
 * reproduces Swift for all the inventory cases: ASCII, `Σ`→`σ`, `İ`→`i̇`, `ẞ`→`ß`, Kelvin
 * `K`(U+212A)→`k`, Ohm `Ω`(U+2126)→`ω`, combining marks untouched. Never use `String.lowercase()`
 * on the whole username, and never the Turkish/Lithuanian locales.
 *
 * Note the Node clients (`relay/test/e2e.mjs`, `bot/index.mjs`) use JS `toLowerCase()`, which
 * applies Final_Sigma — they diverge from iOS on that one case; iOS is the reference.
 */
object UsernameHash {
    val HASH_REGEX = Regex("[0-9a-f]{64}")

    /** Swift `String.lowercased()` semantics on the JVM — see the class KDoc. */
    fun lowercaseLikeSwift(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            out.append(s.substring(i, i + n).lowercase(Locale.ROOT))
            i += n
        }
        return out.toString()
    }

    /** 64-char lowercase hex SHA-256 of the UTF-8 lowercased username. */
    fun of(username: String): String = sha256Hex(lowercaseLikeSwift(username).toByteArray(Charsets.UTF_8))

    /** Relay `requireHash`: exactly `[0-9a-f]{64}`. */
    fun isValid(hash: String): Boolean = HASH_REGEX.matches(hash)

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }
}

/** Shorthand for [UsernameHash.of]. */
fun usernameHash(username: String): String = UsernameHash.of(username)
