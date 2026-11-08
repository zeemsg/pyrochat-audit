package com.pyrochat.app.platform

import com.pyrochat.app.platform.MetadataScrubber.Removed
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * docs/SCRUB-AND-WIPE.md §B1 rows "mp4 / mov / m4a" and the HEIC half of row 1 — every ISO base
 * media file shares one box structure, so one scrubber serves them all.
 *
 * **Why nothing is deleted.** `stco` / `co64` record the absolute file offset of every media chunk
 * and `iloc` records the absolute offset of every HEIC item. Remove bytes anywhere ahead of those
 * tables and the file stops playing. So the removal is done in place and at constant length:
 *
 * - a metadata BOX (`moov/udta/meta`, `moov/meta`, `©xyz`, `©mak`, `©mod`, `©swr`, …) keeps its
 *   size field, its four type bytes become `free`, and its payload becomes zeros. `free` is the
 *   box every reader is required to skip, so the result is a correct file that is simply a little
 *   emptier than it was.
 * - a HEIC `Exif` or XMP ITEM lives in `mdat`, addressed by `iloc`; its extent is zeroed where it
 *   lies. The image items beside it are untouched, so the picture still decodes.
 *
 * **Why it never loads the file.** Pass one reads box headers only (plus the few kilobytes of the
 * metadata boxes themselves, to put values in the receipt). Pass two is a copy with a
 * [MetadataScrubber.COPY_BUFFER] buffer that patches bytes as they go past. A 50 MB video is read
 * twice and held never — §B5's last bullet, by construction rather than by luck.
 */
internal object IsoBmffScrubber {

    /** ftyp brands that mean "this is a HEIF still image", not a movie. */
    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevm", "hevs", "mif1", "msf1", "avif", "avis")

    /** Whole boxes that become `free` wherever they are found under `moov`. */
    private val DROP_UNDER_MOOV = setOf("meta", "udta")

    /** QuickTime `udta` children that are metadata in their own right. */
    private val DROP_UDTA_CHILD = setOf("loci", "name", "©xyz", "©mak", "©mod", "©swr", "©day", "©nam", "©ART", "©cmt", "©too", "©des", "©cpy")

    private const val MAX_DEPTH = 8
    private const val MAX_METADATA_READ = 1 shl 20

    private class Plan {
        /** Absolute byte ranges that become zero. */
        val zeros = ArrayList<LongRange>()
        /** Absolute four-byte writes — a box type turning into `free`. */
        val writes = ArrayList<Pair<Long, ByteArray>>()
        val found = ArrayList<Removed>()
        var peak = MetadataScrubber.COPY_BUFFER

        /** Turn the box at [offset] of [size] bytes into a `free` box of the same size. */
        fun free(offset: Long, size: Long, headerBytes: Int) {
            writes += (offset + 4) to "free".toByteArray(Charsets.US_ASCII)
            if (size > headerBytes) zeros += (offset + headerBytes)..(offset + size - 1)
        }
    }

    fun scrub(src: File, dst: File): MetadataScrubber.Result {
        val plan = Plan()
        RandomAccessFile(src, "r").use { f ->
            val heif = isHeif(f)
            walk(f, 0L, f.length(), 0, plan, heif, parent = "")
        }
        if (plan.zeros.isEmpty() && plan.writes.isEmpty()) {
            MetadataScrubber.copy(src, dst)
            return MetadataScrubber.Result(emptyList(), supported = true, peakBufferBytes = MetadataScrubber.COPY_BUFFER)
        }
        apply(src, dst, plan)
        return MetadataScrubber.Result(MetadataScrubber.dedupe(plan.found), supported = true, peakBufferBytes = plan.peak)
    }

    // ---- pass one: index ----------------------------------------------------------------------

    private fun isHeif(f: RandomAccessFile): Boolean {
        if (f.length() < 12) return false
        f.seek(0)
        val header = ByteArray(12)
        f.readFully(header)
        if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        return String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT) in HEIF_BRANDS
    }

    private fun walk(f: RandomAccessFile, from: Long, to: Long, depth: Int, plan: Plan, heif: Boolean, parent: String) {
        if (depth > MAX_DEPTH) return
        var at = from
        var guard = 0
        while (at + 8 <= to && guard++ < 100_000) {
            val header = ByteArray(8)
            f.seek(at)
            f.readFully(header)
            var size = be32(header, 0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerBytes = 8
            if (size == 1L) {
                val big = ByteArray(8)
                f.readFully(big)
                size = be64(big, 0)
                headerBytes = 16
            } else if (size == 0L) {
                size = to - at
            }
            if (size < headerBytes || at + size > to) return
            val body = at + headerBytes
            val bodyEnd = at + size

            when {
                type == "moov" -> walk(f, body, bodyEnd, depth + 1, plan, heif, "moov")
                // a top-level `meta` in a HEIF file is the item table the picture needs: never freed
                type == "meta" && parent == "" -> if (heif) {
                    heifItems(f, body, bodyEnd, plan)
                } else {
                    // a top-level `meta` in a movie is metadata and nothing else
                    plan.found += describeUdta(f, body, bodyEnd, plan, type)
                    plan.free(at, size, headerBytes)
                }
                parent == "moov" && type in DROP_UNDER_MOOV -> {
                    plan.found += describeUdta(f, body, bodyEnd, plan, type)
                    plan.free(at, size, headerBytes)
                }
                parent == "moov" && type == "trak" -> walk(f, body, bodyEnd, depth + 1, plan, heif, "trak")
                parent == "trak" && type == "meta" -> {
                    plan.found += describeUdta(f, body, bodyEnd, plan, type)
                    plan.free(at, size, headerBytes)
                }
                parent == "trak" && type == "udta" -> {
                    plan.found += describeUdta(f, body, bodyEnd, plan, type)
                    plan.free(at, size, headerBytes)
                }
            }
            at = bodyEnd
        }
    }

    // ---- the values, for the receipt ------------------------------------------------------------

    /** Read the metadata block whole (kilobytes) and name what it held before it is zeroed. */
    private fun describeUdta(f: RandomAccessFile, from: Long, to: Long, plan: Plan, type: String): List<Removed> {
        val length = (to - from).coerceAtMost(MAX_METADATA_READ.toLong()).toInt()
        if (length <= 0) return emptyList()
        val buf = ByteArray(length)
        f.seek(from)
        f.readFully(buf)
        plan.peak = maxOf(plan.peak, length)
        // a `meta` box is a FullBox: four bytes of version+flags before its children
        val start = if (type == "meta") 4 else 0
        return boxes(buf, start, buf.size)
    }

    /** Walk one in-memory metadata block, gathering every value it names. */
    private fun boxes(buf: ByteArray, from: Int, to: Int): List<Removed> {
        val out = ArrayList<Removed>()
        var keys: List<String> = emptyList()
        var at = from
        var guard = 0
        while (at + 8 <= to && guard++ < 10_000) {
            val size = be32(buf, at)
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || at + size > to) break
            val body = at + 8
            val end = at + size
            when (type) {
                "meta" -> out += boxes(buf, body + 4, end)
                "udta" -> out += boxes(buf, body, end)
                "keys" -> keys = keyNames(buf, body, end)
                "ilst" -> out += ilst(buf, body, end, keys)
                in DROP_UDTA_CHILD -> quickTimeText(buf, body, end)?.let { out += named(type, it) }
            }
            at = end
        }
        return out
    }

    /** `keys`: version+flags, entry_count, then `size ‖ namespace ‖ key`. Index n of `ilst` is the nth. */
    private fun keyNames(buf: ByteArray, from: Int, to: Int): List<String> {
        if (from + 8 > to) return emptyList()
        val count = be32(buf, from + 4)
        val out = ArrayList<String>(count.coerceIn(0, 4096))
        var at = from + 8
        var i = 0
        while (i < count && at + 8 <= to) {
            val size = be32(buf, at)
            if (size < 8 || at + size > to) break
            out += String(buf, at + 8, size - 8, Charsets.UTF_8)
            at += size
            i++
        }
        return out
    }

    /** `ilst`: children keyed by a 4CC or by a 1-based index into `keys`, each holding a `data` box. */
    private fun ilst(buf: ByteArray, from: Int, to: Int, keys: List<String>): List<Removed> {
        val out = ArrayList<Removed>()
        var at = from
        var guard = 0
        while (at + 8 <= to && guard++ < 10_000) {
            val size = be32(buf, at)
            if (size < 8 || at + size > to) break
            val raw = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            val index = be32(buf, at + 4)
            val key = if (raw.isNotEmpty() && raw[0].code == 0 && index in 1..keys.size) keys[index - 1] else raw
            dataValue(buf, at + 8, at + size)?.let { out += named(key, it) }
            at += size
        }
        return out
    }

    /** `data`: version+flags, locale, payload. Text payloads are what a receipt can print. */
    private fun dataValue(buf: ByteArray, from: Int, to: Int): String? {
        var at = from
        while (at + 8 <= to) {
            val size = be32(buf, at)
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || at + size > to) return null
            if (type == "data" && size > 16) {
                return String(buf, at + 16, size - 16, Charsets.UTF_8).trim().ifEmpty { null }
            }
            at += size
        }
        return null
    }

    /** QuickTime text atom: `u16 length ‖ u16 language ‖ text`. */
    private fun quickTimeText(buf: ByteArray, from: Int, to: Int): String? {
        if (from + 4 > to) return null
        // some writers put a `data` box here instead — try that first
        dataValue(buf, from, to)?.let { return it }
        val n = ((buf[from].toInt() and 0xFF) shl 8) or (buf[from + 1].toInt() and 0xFF)
        if (n <= 0 || from + 4 + n > to) return null
        return String(buf, from + 4, n, Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** §B1: "location, make, model, software, and the Apple `com.apple.*` keys". */
    private fun named(key: String, value: String): List<Removed> {
        val label = when {
            key == "©xyz" || key.endsWith("location.ISO6709") || key.endsWith("com.apple.quicktime.location") -> MetadataScrubber.L_GPS
            key == "©mak" || key.endsWith(".make") -> MetadataScrubber.L_MAKE
            key == "©mod" || key.endsWith(".model") -> MetadataScrubber.L_MODEL
            key == "©swr" || key == "©too" || key.endsWith(".software") -> MetadataScrubber.L_SOFTWARE
            key == "©day" || key.endsWith(".creationdate") -> MetadataScrubber.L_CREATED
            key == "©nam" || key.endsWith(".title") -> MetadataScrubber.L_TITLE
            key == "©ART" || key.endsWith(".author") || key.endsWith(".artist") -> MetadataScrubber.L_AUTHOR
            key == "©cmt" || key.endsWith(".comment") || key.endsWith(".description") || key == "©des" -> MetadataScrubber.L_COMMENT
            key == "©cpy" -> MetadataScrubber.L_COPYRIGHT
            key == "loci" -> MetadataScrubber.L_GPS
            key == "name" -> MetadataScrubber.L_TITLE
            // any other `com.apple.quicktime.*` keeps its own last segment as its name
            else -> key.substringAfterLast('.').lowercase(Locale.ROOT).ifEmpty { return emptyList() }
        }
        val shown = if (label == MetadataScrubber.L_GPS) iso6709(value) ?: MetadataScrubber.clean(value) else MetadataScrubber.clean(value)
        return if (shown.isEmpty()) emptyList() else listOf(Removed(label, shown))
    }

    /** `+45.5017-073.5673+010.000/` → `45.5017, -73.5673` — the spelling §B2 prints. */
    private fun iso6709(raw: String): String? {
        val m = Regex("""([+-]\d{1,3}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)""").find(raw) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return ExifReader.format(lat, lon)
    }

    // ---- HEIF items ------------------------------------------------------------------------------

    /**
     * The `meta` box of a HEIF still: `iinf` says which item is the `Exif` (or the XMP `mime`) and
     * `iloc` says where in the file it lies. The picture's own items are left exactly as they are.
     */
    private fun heifItems(f: RandomAccessFile, from: Long, to: Long, plan: Plan) {
        val length = (to - from).coerceAtMost(MAX_METADATA_READ.toLong()).toInt()
        if (length <= 4) return
        val buf = ByteArray(length)
        f.seek(from)
        f.readFully(buf)
        plan.peak = maxOf(plan.peak, length)

        val kinds = HashMap<Int, String>()
        val extents = HashMap<Int, Pair<Long, Long>>()
        var at = 4   // the meta FullBox's version+flags
        var guard = 0
        while (at + 8 <= buf.size && guard++ < 10_000) {
            val size = be32(buf, at)
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || at + size > buf.size) break
            when (type) {
                "iinf" -> kinds.putAll(itemInfo(buf, at + 8, at + size))
                "iloc" -> extents.putAll(itemLocations(buf, at + 8, at + size))
            }
            at += size
        }
        for ((id, kind) in kinds) {
            if (kind != "Exif" && kind != "mime") continue
            val (offset, len) = extents[id] ?: continue
            if (offset <= 0 || len <= 0 || offset + len > f.length()) continue
            plan.found += payload(f, offset, len, kind, plan)
            plan.zeros += offset..(offset + len - 1)
        }
    }

    private fun payload(f: RandomAccessFile, offset: Long, len: Long, kind: String, plan: Plan): List<Removed> {
        val n = len.coerceAtMost(MAX_METADATA_READ.toLong()).toInt()
        val buf = ByteArray(n)
        f.seek(offset)
        f.readFully(buf)
        plan.peak = maxOf(plan.peak, n)
        if (kind == "mime") {
            val text = String(buf, Charsets.UTF_8)
            return XmpReader.read(text).ifEmpty { listOf(Removed(MetadataScrubber.L_XMP, "(packet)")) }
        }
        // An `Exif` item opens with a four-byte offset to the TIFF header, and writers disagree
        // about whether the `Exif\u0000\u0000` marker is counted in it. The byte-order mark is
        // unambiguous, so the TIFF block is found by its own magic instead of by their arithmetic.
        if (n < 8) return emptyList()
        val tiffAt = tiffStart(buf) ?: return emptyList()
        return ExifReader.read(buf.copyOfRange(tiffAt, n))
    }

    /** The `II*\u0000` / `MM\u0000*` that opens every TIFF block, within the item's first bytes. */
    private fun tiffStart(buf: ByteArray): Int? {
        for (at in 0..minOf(64, buf.size - 4)) {
            val a = buf[at].toInt() and 0xFF
            val b = buf[at + 1].toInt() and 0xFF
            val c = buf[at + 2].toInt() and 0xFF
            val d = buf[at + 3].toInt() and 0xFF
            if (a == 0x49 && b == 0x49 && c == 42 && d == 0) return at
            if (a == 0x4D && b == 0x4D && c == 0 && d == 42) return at
        }
        return null
    }

    /** `iinf` → item id → item type (`Exif`, `mime`, `hvc1`, `grid`, …). */
    private fun itemInfo(buf: ByteArray, from: Int, to: Int): Map<Int, String> {
        if (from + 4 > to) return emptyMap()
        val version = buf[from].toInt() and 0xFF   // FullBox: one version byte, then three flag bytes
        var at: Int
        val count: Int
        if (version == 0) {
            if (from + 6 > to) return emptyMap()
            count = ((buf[from + 4].toInt() and 0xFF) shl 8) or (buf[from + 5].toInt() and 0xFF)
            at = from + 6
        } else {
            if (from + 8 > to) return emptyMap()
            count = be32(buf, from + 4)
            at = from + 8
        }
        val out = HashMap<Int, String>()
        var i = 0
        while (i < count && at + 8 <= to) {
            val size = be32(buf, at)
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 12 || at + size > to) break
            if (type == "infe") {
                val v = buf[at + 8].toInt() and 0xFF
                if (v >= 2) {
                    val idBytes = if (v == 2) 2 else 4
                    val id = if (idBytes == 2) {
                        ((buf[at + 12].toInt() and 0xFF) shl 8) or (buf[at + 13].toInt() and 0xFF)
                    } else {
                        be32(buf, at + 12)
                    }
                    val itemTypeAt = at + 12 + idBytes + 2
                    if (itemTypeAt + 4 <= at + size) out[id] = String(buf, itemTypeAt, 4, Charsets.ISO_8859_1)
                }
            }
            at += size
            i++
        }
        return out
    }

    /** `iloc` → item id → (absolute offset, length) for the single-extent items metadata uses. */
    private fun itemLocations(buf: ByteArray, from: Int, to: Int): Map<Int, Pair<Long, Long>> {
        if (from + 6 > to) return emptyMap()
        val version = buf[from].toInt() and 0xFF
        var at = from + 4
        val offsetSize = (buf[at].toInt() and 0xF0) shr 4
        val lengthSize = buf[at].toInt() and 0x0F
        val baseSize = (buf[at + 1].toInt() and 0xF0) shr 4
        val indexSize = if (version >= 1) buf[at + 1].toInt() and 0x0F else 0
        at += 2
        val count: Int
        if (version < 2) {
            count = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
            at += 2
        } else {
            count = be32(buf, at)
            at += 4
        }
        val out = HashMap<Int, Pair<Long, Long>>()
        var i = 0
        while (i < count && at < to) {
            val id: Int
            if (version < 2) {
                if (at + 2 > to) break
                id = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
                at += 2
            } else {
                if (at + 4 > to) break
                id = be32(buf, at)
                at += 4
            }
            var construction = 0
            if (version >= 1) {
                if (at + 2 > to) break
                construction = buf[at + 1].toInt() and 0x0F
                at += 2
            }
            at += 2 // data_reference_index
            if (at + baseSize + 2 > to) break
            val base = uint(buf, at, baseSize)
            at += baseSize
            val extents = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
            at += 2
            var first: Pair<Long, Long>? = null
            for (e in 0 until extents) {
                if (at + indexSize + offsetSize + lengthSize > to) return out
                at += indexSize
                val offset = uint(buf, at, offsetSize); at += offsetSize
                val len = uint(buf, at, lengthSize); at += lengthSize
                if (first == null) first = (base + offset) to len
            }
            // construction 0 is a plain file offset; 1 (`idat`) and 2 (another item) are left alone
            if (construction == 0) first?.let { out[id] = it }
            i++
        }
        return out
    }

    private fun uint(buf: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (k in 0 until size) {
            if (at + k >= buf.size) return v
            v = (v shl 8) or (buf[at + k].toLong() and 0xFF)
        }
        return v
    }

    // ---- pass two: copy, patching as the bytes go past --------------------------------------------

    private fun apply(src: File, dst: File, plan: Plan) {
        val zeros = plan.zeros.sortedBy { it.first }
        val writes = plan.writes.sortedBy { it.first }
        src.inputStream().buffered().use { input ->
            dst.outputStream().buffered().use { output ->
                val buf = ByteArray(MetadataScrubber.COPY_BUFFER)
                var position = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val windowEnd = position + n
                    for (range in zeros) {
                        if (range.last < position) continue
                        if (range.first >= windowEnd) break
                        val from = maxOf(range.first, position) - position
                        val to = minOf(range.last, windowEnd - 1) - position
                        for (k in from..to) buf[k.toInt()] = 0
                    }
                    for ((offset, value) in writes) {
                        if (offset + value.size <= position) continue
                        if (offset >= windowEnd) break
                        for (k in value.indices) {
                            val abs = offset + k
                            if (abs in position until windowEnd) buf[(abs - position).toInt()] = value[k]
                        }
                    }
                    output.write(buf, 0, n)
                    position = windowEnd
                }
            }
        }
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
        return v
    }
}
