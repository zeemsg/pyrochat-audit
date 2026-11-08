package com.pyrochat.app.platform

import com.pyrochat.app.platform.MetadataScrubber.Removed
import java.io.File

/**
 * docs/SCRUB-AND-WIPE.md §B1 row "PDF" — the Info dictionary (author, producer, creator, dates)
 * and any XMP packet.
 *
 * The removal is **length-preserving on purpose**. A PDF is a byte-offset file: the cross-reference
 * table at the end records where every object starts, so deleting bytes anywhere before it would
 * leave a document that no reader will open — which §B5 explicitly forbids ("a scrubbed PDF must
 * still render"). So nothing moves: the Info dictionary's body is overwritten with spaces between
 * its own `<<` and `>>`, the XMP packet's bytes are overwritten with spaces between its own
 * `<?xpacket` markers, and the `/Metadata n 0 R` reference that points at the packet is overwritten
 * with spaces too, so nothing in the document points at what is left. Every offset in the xref
 * still lands exactly where it did, and every `/Length` still counts what it counted.
 *
 * No library: this is a byte scan with a matching-bracket walk that knows about PDF strings, not a
 * PDF parser, and it never has to understand a content stream.
 */
internal object PdfScrubber {

    /** The Info-dictionary keys §B1 names, in the order a receipt reads best. */
    private val INFO_KEYS = listOf(
        "Author" to MetadataScrubber.L_AUTHOR,
        "Creator" to MetadataScrubber.L_CREATOR,
        "Producer" to MetadataScrubber.L_PRODUCER,
        "Title" to MetadataScrubber.L_TITLE,
        "Subject" to MetadataScrubber.L_SUBJECT,
        "Keywords" to MetadataScrubber.L_KEYWORDS,
        "CreationDate" to MetadataScrubber.L_CREATED,
        "ModDate" to MetadataScrubber.L_MODIFIED,
    )

    fun scrub(src: File, dst: File): MetadataScrubber.Result {
        if (src.length() > MetadataScrubber.MAX_REWRITE_BYTES) return MetadataScrubber.passThrough(src, dst)
        val bytes = src.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)   // 1 char = 1 byte: every index below is a byte index
        val found = ArrayList<Removed>()
        val blanks = ArrayList<IntRange>()

        // ---- the Info dictionary ------------------------------------------------------------
        for (obj in infoObjectNumbers(text)) {
            val dict = dictionaryOf(text, obj) ?: continue
            found += fields(text.substring(dict.first, dict.last + 1))
            blanks += dict
        }

        // ---- any XMP packet -------------------------------------------------------------------
        var at = 0
        while (true) {
            val begin = text.indexOf("<?xpacket begin", at)
            if (begin < 0) break
            val endMarker = text.indexOf("<?xpacket end", begin)
            if (endMarker < 0) break
            val end = text.indexOf("?>", endMarker).let { if (it < 0) endMarker else it + 2 }
            val packet = text.substring(begin, end)
            val fields = XmpReader.read(packet)
            found += fields.ifEmpty { listOf(Removed(MetadataScrubber.L_XMP, "(packet)")) }
            blanks += begin..(end - 1)
            at = end
        }

        // ---- and the reference that pointed at it ----------------------------------------------
        for (m in Regex("""/Metadata\s+\d+\s+\d+\s+R""").findAll(text)) blanks += m.range

        if (blanks.isEmpty()) {
            MetadataScrubber.copy(src, dst)
            return MetadataScrubber.Result(emptyList(), supported = true, peakBufferBytes = bytes.size)
        }
        for (range in blanks) {
            for (i in range) if (i in bytes.indices) bytes[i] = ' '.code.toByte()
        }
        dst.writeBytes(bytes)
        return MetadataScrubber.Result(MetadataScrubber.dedupe(found), supported = true, peakBufferBytes = bytes.size)
    }

    /** Every `/Info n g R` any trailer (or cross-reference stream) names. */
    private fun infoObjectNumbers(text: String): Set<Int> =
        Regex("""/Info\s+(\d+)\s+(\d+)\s+R""").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /** The inclusive byte range BETWEEN `<<` and `>>` of object [number]'s dictionary. */
    private fun dictionaryOf(text: String, number: Int): IntRange? {
        val header = Regex("""(?<![0-9])$number\s+\d+\s+obj\b""").find(text) ?: return null
        val open = text.indexOf("<<", header.range.last)
        if (open < 0) return null
        val close = matchingClose(text, open) ?: return null
        if (close <= open + 2) return null
        return (open + 2)..(close - 1)
    }

    /** Bracket matching that knows a `)` inside a `(…)` string is not a delimiter. */
    private fun matchingClose(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length - 1) {
            when {
                text[i] == '(' -> { i = skipString(text, i); continue }
                text[i] == '<' && text[i + 1] == '<' -> { depth++; i += 2; continue }
                text[i] == '>' && text[i + 1] == '>' -> {
                    depth--
                    if (depth == 0) return i
                    i += 2
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Past the closing `)` of a literal string, honouring `\(`, `\)` and nesting. */
    private fun skipString(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    /** §B2: the values as they were found — a PDF date stays the `D:2024…` string it was written as. */
    private fun fields(dict: String): List<Removed> {
        val out = ArrayList<Removed>()
        for ((key, label) in INFO_KEYS) {
            val at = Regex("""/${Regex.escape(key)}\s*(?=[(<])""").find(dict) ?: continue
            val start = at.range.last + 1
            val value = when (dict.getOrNull(start)) {
                '(' -> literal(dict, start)
                '<' -> hex(dict, start)
                else -> null
            } ?: continue
            val clean = MetadataScrubber.clean(value)
            if (clean.isNotEmpty()) out += Removed(label, clean)
        }
        return out
    }

    private fun literal(dict: String, open: Int): String? {
        val end = skipString(dict, open)
        if (end <= open + 1) return null
        val raw = dict.substring(open + 1, end - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return decode(sb.toString())
    }

    private fun hex(dict: String, open: Int): String? {
        val close = dict.indexOf('>', open)
        if (close < 0) return null
        val digits = dict.substring(open + 1, close).filter { !it.isWhitespace() }
        if (digits.isEmpty() || digits.any { it.digitToIntOrNull(16) == null }) return null
        val padded = if (digits.length % 2 == 0) digits else digits + "0"
        val raw = ByteArray(padded.length / 2) { ((padded[it * 2].digitToInt(16) shl 4) or padded[it * 2 + 1].digitToInt(16)).toByte() }
        return decode(String(raw, Charsets.ISO_8859_1))
    }

    /** PDF text strings are PDFDocEncoding unless they open with the UTF-16BE byte-order mark. */
    private fun decode(raw: String): String {
        if (raw.length >= 2 && raw[0] == 'þ' && raw[1] == 'ÿ') {
            val bytes = ByteArray(raw.length) { raw[it].code.toByte() }
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return raw
    }
}
