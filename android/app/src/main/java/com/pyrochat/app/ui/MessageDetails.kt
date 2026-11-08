package com.pyrochat.app.ui

import java.time.ZoneId
import java.util.Locale

/**
 * docs/MESSAGE-DETAILS.md §2 — one rendered line of the details sheet. The renderer emits these
 * and nothing else, so the whole sheet is testable without a single Compose class.
 */
sealed interface DetailLine {
    /** `── what happened ──` — the section rules, verbatim from the spec. */
    data class Heading(val text: String) : DetailLine

    /** A two-column row: `sent          14:32:07`. */
    data class Field(val label: String, val value: String) : DetailLine

    /** A group receipt row: `<alice>` in her colour, value `delivered` / `read` / `—`. */
    data class Member(val name: String, val value: String, val rgb: Int?) : DetailLine

    /** One line of the relay envelope, drawn as code. */
    data class Frame(val text: String) : DetailLine

    /** A prose line — the envelope count, the byte count, the two closing sections. */
    data class Text(val text: String) : DetailLine

    /** The closing disclaimer, verbatim. */
    data class Footer(val text: String) : DetailLine
}

/**
 * Everything the sheet is allowed to know. Deliberately a closed list: a session key, the store
 * key, another row's plaintext and the raw attachment bytes have no field to arrive in, and
 * `MessageDetailsTest` pins the list so a future field cannot smuggle one in.
 */
data class DetailsInput(
    /** The row that was long-pressed — the ONLY message the sheet ever sees. */
    val message: ChatMessage,
    /** §3: the captured envelope, or null when there is none. */
    val frame: UiFrame? = null,
    /** docs/SCRUB-AND-WIPE.md §B2: what the scrubber took out of this row's attachment, or null. */
    val scrub: UiScrubReceipt? = null,
    /** The group this row belongs to, or null for a 1:1 row. */
    val group: UiGroup? = null,
    /** Extra session history for members who have left (names + colour slots). */
    val former: List<UiMember> = emptyList(),
    /** Contacts, for naming a group sender who is not in the roster. */
    val contacts: List<Contact> = emptyList(),
    /** The 1:1 peer's display name. */
    val peerName: String = "",
    /** This phone's routing address — the one member never shown a receipt row. */
    val myHash: String = "",
    /** Reciprocity: without MY read-receipt toggle a peer's read reads as delivered. */
    val showRead: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * docs/MESSAGE-DETAILS.md §2 — the details sheet as a pure function from (message, optional
 * captured frame, group roster) to lines. Every heading and every full sentence below is verbatim
 * from the spec; both platforms render the same words.
 *
 * What it never renders: the message text, the attachment bytes, a key of any kind. The envelope
 * is shown only when it was captured on the wire — §3 forbids re-encrypting a message to draw
 * this screen, so "no capture" is rendered as the true statement that nothing was kept.
 */
object MessageDetails {
    // ---- headings, verbatim (§2) ------------------------------------------------------
    const val H_HAPPENED = "── what happened ──"
    const val H_WHAT = "── what it is ──"

    /** docs/SCRUB-AND-WIPE.md §B2, verbatim — directly after [H_WHAT], and only when there is one. */
    const val H_REMOVED = "── what was removed before sending ──"
    const val H_RELAY = "── what the relay received ──"
    const val H_CAN = "── what it can still work out ──"
    const val H_CANNOT = "── what it cannot ──"

    /** Footer, verbatim (§2). */

    /**
     * §3, verbatim. It goes on its own line UNDERNEATH the closed envelope — exactly where the
     * byte count goes when a frame was captured — and never inside the braces: a malformed-looking
     * envelope on the one screen whose whole purpose is to be believed would undo the screen.
     */
    const val NO_BODY = "body: not kept — PyroChat never stores ciphertext"

    /**
     * A system row is written by this app on this phone; it was never handed to a relay, so there
     * is no envelope to elide and none to apologise for. (Spec silent — see the report.)
     */
    const val NEVER_SENT = "nothing — this row never left the phone"

    /** §2 row labels. */
    const val L_SENT = "sent"
    const val L_RECEIVED = "received"
    const val L_STATUS = "status"
    const val L_KIND = "kind"
    const val L_BURNS = "burns"
    const val L_WRITTEN_ON = "written on"

    /** §2 — the `written on` value; the row carries the web tag. */
    const val WEB = "a linked desktop"

    /** §2 — a member with no receipt yet. */
    const val NO_RECEIPT = "—"

    // ---- docs/SCRUB-AND-WIPE.md §B2/§B4, verbatim --------------------------------------

    /** §B2: the honest line when the file type is not one we can scrub. */
    const val NOTHING_REMOVED = "nothing — this file type is not one we can clean"

    /** §B4, verbatim, as this section's footnote. */
    const val REMOVED_FOOTNOTE =
        "this cleans the file's hidden fields, not its contents: a name written inside the document, " +
            "or a face in a photo, is still there."

    /** §B3 — the compose-bar line's frame lives in strings.xml; its list grammar lives here. */
    const val REMOVED_MORE = "more"

    /** §3 group line, verbatim apart from N. */
    fun envelopeCount(n: Int): String =
        "sent as $n separate envelopes, one per member — each is different ciphertext, " +
            "and the relay is never told they belong together."

    // ---- the sheet --------------------------------------------------------------------

    fun render(input: DetailsInput): List<DetailLine> {
        val out = ArrayList<DetailLine>()
        out += whatHappened(input)
        out += whatItIs(input)
        out += whatWasRemoved(input)
        out += whatTheRelayReceived(input)
        out += DetailLine.Heading(H_CAN)
        out += DetailLine.Text(canLine(input))
        out += DetailLine.Heading(H_CANNOT)
        out += DetailLine.Text(cannotLine(input))
        return out
    }

    // ---- ── what happened ── -----------------------------------------------------------

    private fun whatHappened(input: DetailsInput): List<DetailLine> {
        val m = input.message
        val out = ArrayList<DetailLine>()
        out += DetailLine.Heading(H_HAPPENED)
        // the store keeps ONE timestamp per row (§2 forbids inventing per-status ones): the
        // moment this phone sent the row, or the moment it took delivery of it
        out += DetailLine.Field(if (m.direction == Direction.outgoing) L_SENT else L_RECEIVED, Format.clock(m.timestamp, input.zone))
        out += DetailLine.Field(L_STATUS, statusValue(input))
        out += memberRows(input)
        return out
    }

    private fun statusValue(input: DetailsInput): String {
        val m = input.message
        val g = input.group
        return if (g != null && m.direction == Direction.outgoing) GroupUi.statusWord(m, g, input.myHash, input.showRead)
        else Format.statusWord(m.status, input.showRead)
    }

    /**
     * §2: for a group message, `status` is followed by one row per member in that member's
     * colour, from the receipts already stored. Only an OUTGOING row carries receipts — an
     * incoming one would be a column of `—` that says nothing true.
     */
    private fun memberRows(input: DetailsInput): List<DetailLine> {
        val g = input.group ?: return emptyList()
        val m = input.message
        if (m.direction != Direction.outgoing) return emptyList()
        val receipts = m.receipts ?: emptyMap()
        return recipients(g, input.myHash).map { member ->
            DetailLine.Member(
                name = "<${member.name}>",
                value = receiptWord(receipts[member.hash], input.showRead),
                rgb = GroupUi.colour(g, member.hash),
            )
        }
    }

    /** Roster order — `(joinedAt, hash)`, the order colours are dealt in — minus this phone. */
    private fun recipients(g: UiGroup, myHash: String): List<UiMember> =
        g.members.sortedWith(compareBy<UiMember> { it.joinedAt }.thenBy { it.hash }).filter { it.hash != myHash }

    private fun receiptWord(stored: String?, showRead: Boolean): String = when (stored) {
        "read" -> if (showRead) "read" else "delivered" // reciprocity, exactly as the status word
        "delivered" -> "delivered"
        else -> NO_RECEIPT
    }

    // ---- ── what it is ── ---------------------------------------------------------------

    private fun whatItIs(input: DetailsInput): List<DetailLine> {
        val m = input.message
        val out = ArrayList<DetailLine>()
        out += DetailLine.Heading(H_WHAT)
        out += DetailLine.Field(L_KIND, kindValue(m))
        m.burnAfter?.let { out += DetailLine.Field(L_BURNS, "${durationLabel(it)} after you read it") }
        if (m.viaWeb == true) out += DetailLine.Field(L_WRITTEN_ON, WEB)
        return out
    }

    /** `text` / `voice · 4s` / `image 640x480` / `file report.pdf 42 KB` / `system` (§2). */
    fun kindValue(m: ChatMessage): String = when (m.kind) {
        Kind.text -> "text"
        Kind.system -> "system"
        Kind.voice -> m.voiceDuration?.let { "voice · ${Math.round(it)}s" } ?: "voice"
        Kind.image -> {
            val w = m.imageWidth
            val h = m.imageHeight
            if (w != null && h != null) "image ${w}x$h" else "image"
        }
        Kind.file -> listOfNotNull("file", m.fileName?.takeIf { it.isNotBlank() }, m.fileBytes?.let(::sizeLabel)).joinToString(" ")
    }

    /** `42 KB` — the plaintext size, spelled as §2 spells it. */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    }

    /** `5m`, `1h`, `1d`, `1m 30s` — whole units, largest first, zero parts dropped. */
    fun durationLabel(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val parts = ArrayList<String>()
        var left = seconds
        listOf(86_400L to "d", 3_600L to "h", 60L to "m", 1L to "s").forEach { (unit, suffix) ->
            val n = left / unit
            if (n > 0) {
                parts += "$n$suffix"
                left -= n * unit
            }
        }
        return parts.joinToString(" ")
    }

    // ---- ── what was removed before sending ── (docs/SCRUB-AND-WIPE.md §B2) ---------------

    /**
     * §B2: "A new section in the message details sheet, only when something was removed, placed
     * directly after `── what it is ──`."
     *
     * Three states, and the third is the point of the section:
     * - fields were removed → they are listed, label and value, as they were found;
     * - the type is not one we can scrub → §B2's honest line and nothing else;
     * - the type IS one we can scrub and it held nothing → NO section at all. There is nothing to
     *   report, and printing "not one we can clean" over a file we cleaned perfectly well would be
     *   the exact false claim §B2 forbids. (Spec silent on this third case — see the report.)
     *
     * A row with no attachment never gets a section: nothing was staged, so nothing was scrubbed.
     */
    private fun whatWasRemoved(input: DetailsInput): List<DetailLine> = removedSection(input.scrub)

    /** The section on its own — §B3's compose-bar line opens exactly this, word for word. */
    fun removedSection(scrub: UiScrubReceipt?): List<DetailLine> {
        if (scrub == null || !scrub.hasReceipt) return emptyList()
        val out = ArrayList<DetailLine>()
        out += DetailLine.Heading(H_REMOVED)
        if (scrub.removed.isEmpty()) {
            out += DetailLine.Text(NOTHING_REMOVED)
        } else {
            scrub.removed.forEach { out += DetailLine.Field(it.label, it.value) }
        }
        out += DetailLine.Footer(REMOVED_FOOTNOTE)
        return out
    }

    /**
     * §B3: `removed author, gps and 1 more from report.pdf` — the list half of the compose-bar
     * line. One label reads on its own, two are joined with `and`, three or more name the first
     * two and count the rest, exactly as the spec prints it.
     */
    fun removedSummary(labels: List<String>): String = when {
        labels.isEmpty() -> ""
        labels.size == 1 -> labels[0]
        labels.size == 2 -> "${labels[0]} and ${labels[1]}"
        else -> "${labels[0]}, ${labels[1]} and ${labels.size - 2} $REMOVED_MORE"
    }

    // ---- ── what the relay received ── ---------------------------------------------------

    private fun whatTheRelayReceived(input: DetailsInput): List<DetailLine> {
        val m = input.message
        val out = ArrayList<DetailLine>()
        out += DetailLine.Heading(H_RELAY)
        if (m.kind == Kind.system) {
            out += DetailLine.Text(NEVER_SENT)
            return out
        }
        val g = input.group
        if (g != null && m.direction == Direction.outgoing) {
            out += DetailLine.Text(envelopeCount(g.recipients(input.myHash)))
        }
        out += frameLines(input).map(DetailLine::Frame)
        val frame = input.frame
        if (frame == null) out += DetailLine.Text(NO_BODY)
        else out += DetailLine.Text("${frame.bodyBytes} bytes of ciphertext")
        return out
    }

    /**
     * §3 — the envelope, wrapped over two lines the way the spec prints it. With no capture the
     * fields still known (`t`, `id`, `to`, `ttl`, `kind`) are shown, the object is CLOSED properly
     * with `}` and no `body` field, and [NO_BODY] stands alone beneath it: the JSON on screen is
     * never a JSON that did not exist, and never a malformed one.
     */
    fun frameLines(input: DetailsInput): List<String> {
        val m = input.message
        val frame = input.frame
        val outgoing = frame?.outgoing ?: (m.direction == Direction.outgoing)
        // the word the wire carried: a sealed message left as `deliver`
        val t = frame?.t ?: if (outgoing) "send" else "recv"
        val id = frame?.id ?: m.id
        val peer = frame?.peer ?: envelopePeerHash(input)
        val kind = frame?.kind ?: wireKind(m)
        // a recv frame never carried a ttl and one is never invented for it
        val ttl = if (outgoing) frame?.ttl ?: WIRE_TTL else null
        val head = StringBuilder("{").append(field("t", t)).append(",").append(field("id", elide(id, 8)))
        // a sealed frame arrives with no `from`: the line shows it as it came
        if (outgoing) head.append(",").append(field("to", elidePeer(peer)))
        else if (frame?.peer?.isEmpty() != true) head.append(",").append(field("from", elidePeer(peer)))
        val tail = StringBuilder(" ")
        if (ttl != null) tail.append("\"ttl\":").append(ttl).append(",")
        tail.append(field("kind", kind))
        if (frame != null) tail.append(",").append(field("body", elide(frame.body, 9)))
        tail.append("}")
        return listOf("$head,", tail.toString())
    }

    /** `msg` for text, `blob` for anything with bytes behind it — the word the relay was given. */
    private fun wireKind(m: ChatMessage): String = if (m.kind == Kind.text) "msg" else "blob"

    /** Which peer the shown envelope was addressed to / arrived from, as a routing address. */
    private fun envelopePeerHash(input: DetailsInput): String {
        val m = input.message
        val g = input.group ?: return hashOf(input.peerName, input.contacts)
        return if (m.direction == Direction.outgoing) recipients(g, input.myHash).firstOrNull()?.hash ?: ""
        else m.sender ?: ""
    }

    private fun hashOf(name: String, contacts: List<Contact>): String =
        contacts.firstOrNull { it.name == name }?.hash ?: ""

    private fun field(key: String, value: String): String = "\"$key\":\"$value\""

    /** `7f3c9a12…` — enough to recognise, never enough to be the thing itself. */
    internal fun elide(s: String, keep: Int): String = if (s.length <= keep) s else s.take(keep) + "…"

    /** `9a1fd4…c2` — a routing address, head and tail. */
    internal fun elidePeer(s: String): String = if (s.length <= 9) s else s.take(6) + "…" + s.takeLast(2)

    // ---- the two closing sections --------------------------------------------------------

    /** §2, verbatim; §3 flips the clause for a received message. */
    fun canLine(input: DetailsInput): String {
        val who = "<${envelopePeerName(input)}>"
        return if (input.message.direction == Direction.outgoing) {
            "your address, the time, the size, and that you wrote to $who"
        } else {
            "your address, the time, the size, and that $who wrote to you"
        }
    }

    /**
     * §2, verbatim. `that #ops exists` is a claim about a group, so it is made only where there
     * is one — a 1:1 row closes on the burn timer. (Spec silent — see the report.)
     */
    fun cannotLine(input: DetailsInput): String {
        val g = input.group
        return if (g != null) "the words, your name, the burn timer, or that #${g.name} exists"
        else "the words, your name, or the burn timer"
    }

    /** The name behind the envelope's peer: the roster first, then former members, then contacts. */
    private fun envelopePeerName(input: DetailsInput): String {
        val g = input.group ?: return input.peerName
        val hash = envelopePeerHash(input)
        g.member(hash)?.let { return it.name }
        (g.formerMember(hash) ?: input.former.firstOrNull { it.hash == hash })?.let { return it.name }
        input.contacts.firstOrNull { it.hash == hash }?.let { return it.name }
        return com.pyrochat.service.GroupRules.anonName(hash)
    }

    /** What a row without a capture must still have been sent with (`Protocol.TTL_MSG`/`TTL_BLOB`). */
    private const val WIRE_TTL = com.pyrochat.protocol.Protocol.TTL_MSG
}
