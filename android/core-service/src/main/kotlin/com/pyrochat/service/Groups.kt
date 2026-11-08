package com.pyrochat.service

import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.Member
import java.text.BreakIterator
import kotlin.math.roundToInt

/**
 * docs/GROUPS.md §5 — member colours by join order. Pure; both platforms compute the same
 * `#rrggbb` for the same roster. Index = position in the roster ordered by `(joinedAt, hash)`;
 * palette entry `index % 10`, dimmed to 70 % for the second lap (`index / 10 == 1`) and 50 % from
 * the third on. The local user is never coloured (`<you>` reads in flame).
 */
object GroupColours {
    /** Index 0–9, chosen for distinctness on `#050805`; none equals alert `#ff5940` or flame `#ffaf32`. */
    val PALETTE: IntArray = intArrayOf(
        0x3ef573, 0x4fd8ff, 0xc792ff, 0xffd166, 0xff8fab, 0x7ee8fa, 0xb5e550, 0xffa64d, 0xe0b3ff, 0x8be9fd,
    )

    /** The roster in join order: `(joinedAt, hash)` ascending — the order colours are dealt in. */
    fun joinOrder(members: List<Member>): List<Member> =
        members.sortedWith(compareBy<Member> { it.joinedAt }.thenBy { it.hash })

    /** The member's join index, or null when the hash is not in the roster. */
    fun joinIndex(members: List<Member>, hash: String): Int? =
        joinOrder(members).indexOfFirst { it.hash == hash }.takeIf { it >= 0 }

    /** `0xrrggbb` for a join index (never null: the palette wraps forever). */
    fun rgb(joinIndex: Int): Int {
        require(joinIndex >= 0)
        val base = PALETTE[joinIndex % PALETTE.size]
        val factor = when (joinIndex / PALETTE.size) {
            0 -> 1.0
            1 -> 0.7
            else -> 0.5
        }
        return dim(base, factor)
    }

    /** `#rrggbb` text form of [rgb]. */
    fun hex(joinIndex: Int): String = "#%06x".format(rgb(joinIndex))

    /** Colour of a roster member, or null for a hash that is not (or no longer) in the roster. */
    fun colour(members: List<Member>, hash: String): Int? = joinIndex(members, hash)?.let(::rgb)

    /** Brightness scaling = every channel × factor (HSB value scales linearly with the channels). */
    internal fun dim(rgb: Int, factor: Double): Int {
        fun ch(shift: Int) = (((rgb shr shift) and 0xff) * factor).roundToInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Naming, roster and status rules of GROUPS.md §1, §4, §7 that need no service state. */
object GroupRules {
    /** Roster cap, creator included (§1). */
    const val MAX_MEMBERS = 20

    /** How many others a creator may pick: the cap minus the creator. */
    const val MAX_PICKED = MAX_MEMBERS - 1

    /** Errors, verbatim (§7). */
    const val ERR_GROUP_FULL = "ERR: group is full (20)"
    const val ERR_ONLY_CREATOR = "ERR: only the creator can remove members"
    const val ERR_NOT_IN_GROUP = "ERR: you are no longer in this group"

    /** Roster changes need the link up (agreed with iOS: an `ERR: link down — …` prefix). */
    const val ERR_LINK_DOWN = "ERR: link down — roster changes need the link up"
    const val ERR_BAD_NAME = "ERR: name must be 1–32 characters"

    /**
     * §1 naming: trimmed, no leading `#`, 1–32 graphemes; stored without the `#`. Null when the
     * result would be empty or too long (the UI refuses, the service ignores such a control).
     */
    fun normalizeName(raw: String): String? {
        var s = raw.trim()
        while (s.startsWith("#")) s = s.substring(1).trimStart()
        s = s.trim()
        if (s.isEmpty()) return null
        val clusters = graphemes(s)
        if (clusters.size > 32) return null
        if (s.any { it == '\n' || it == '\r' || it.isISOControl() }) return null
        return s
    }

    /**
     * A member name arriving in a roster control: letters, digits, `_`, `-` and single spaces,
     * trimmed, at most 24 graphemes; null when nothing is left (the receiver then labels the
     * member `anon-<hash prefix>`). Looser than the username rule — the adder's local nickname
     * may carry a space or a dash — but no format/control characters ever reach a `<name>` tag.
     */
    fun sanitizeMemberName(raw: String?): String? {
        if (raw == null) return null
        val kept = StringBuilder()
        var lastSpace = true
        for (c in graphemes(raw)) {
            val cp = c.codePointAt(0)
            val ok = cp == '_'.code || cp == '-'.code || Character.isAlphabetic(cp) || isNumber(cp)
            if (ok) {
                kept.append(c); lastSpace = false
            } else if (cp == ' '.code && !lastSpace) {
                kept.append(' '); lastSpace = true
            }
        }
        val clipped = graphemes(kept.toString().trim()).take(24).joinToString("")
        return clipped.ifEmpty { null }
    }

    /** `anon-` + the first 8 hex digits — the label for a member whose name did not survive sanitizing. */
    fun anonName(hash: String): String = "anon-" + hash.take(8)

    /**
     * §7 typing strip: `alice is typing…`, `alice, bob are typing…`, and beyond three names
     * `alice, bob, carol … and N more are typing…`. Names in the order given; empty → null.
     */
    fun typingStrip(names: List<String>): String? {
        if (names.isEmpty()) return null
        if (names.size == 1) return "${names[0]} is typing…"
        val shown = names.take(3).joinToString(", ")
        val more = names.size - 3
        return if (more > 0) "$shown … and $more more are typing…" else "$shown are typing…"
    }

    private fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    private fun graphemes(s: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out += s.substring(start, end)
            start = end
            end = it.next()
        }
        return out
    }
}

/**
 * §1/§7 receipt aggregation for an OUTGOING group row: `receipts` (member hash → `delivered` |
 * `read`) over `recipients` (members except self at display time). `READ r/n` once anyone read,
 * else `DELIVERED d/n` once anyone got it, else the plain fan-out status word. Reciprocity as in
 * 1:1: without the local read-receipt toggle a read counts as delivered.
 */
object GroupReceipts {
    const val DELIVERED = "delivered"
    const val READ = "read"

    fun aggregate(status: Status, receipts: Map<String, String>?, recipients: Int, showRead: Boolean): String {
        val map = receipts ?: emptyMap()
        val read = if (showRead) map.values.count { it == READ } else 0
        val delivered = map.values.count { it == DELIVERED || it == READ }
        return when {
            status == Status.PENDING -> "…"
            status == Status.FAILED -> "FAILED"
            read > 0 -> "READ $read/$recipients"
            delivered > 0 -> "DELIVERED $delivered/$recipients"
            else -> "SENT"
        }
    }

    /** Never downgrade: a member's `read` survives a late `delivered`. */
    fun merge(current: Map<String, String>?, member: String, kind: String): Map<String, String> {
        val map = current ?: emptyMap()
        if (map[member] == READ) return map
        return map + (member to kind)
    }
}

/**
 * §4 fan-out bookkeeping for ONE outgoing group row: which recipients still await the relay's
 * verdict, which errored. The row is `sent` when every recipient's `sent` arrived, `failed` if any
 * recipient errored; a member the relay reports as `unknown_user` is dropped from the count
 * (marked `gone` in the roster by the caller) rather than failing the row. Pure and testable.
 */
class GroupFanOut(val messageId: String, val groupId: String, recipients: Collection<String>) {
    val pending: MutableSet<String> = LinkedHashSet(recipients)
    val sent: MutableSet<String> = LinkedHashSet()
    val failed: MutableSet<String> = LinkedHashSet()

    fun sent(recipient: String) {
        if (pending.remove(recipient)) sent += recipient
    }

    fun failed(recipient: String) {
        if (pending.remove(recipient)) failed += recipient
    }

    fun gone(recipient: String) {
        pending.remove(recipient)
        failed.remove(recipient)
    }

    /** Re-arm the failed set for another attempt (retry re-sends only to those). */
    fun retry(): Set<String> {
        val again = LinkedHashSet(failed)
        failed.clear()
        pending += again
        return again
    }

    val done: Boolean get() = pending.isEmpty()

    val status: Status
        get() = when {
            !done -> Status.PENDING
            failed.isEmpty() -> Status.SENT
            else -> Status.FAILED
        }
}
