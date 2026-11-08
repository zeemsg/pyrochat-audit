package com.pyrochat.app.platform

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * docs/SCRUB-AND-WIPE.md §B1 — the document scrubber. Runs on the sender's device, on a COPY, on
 * every attachment, before anything is encrypted.
 *
 * | type | what goes |
 * |---|---|
 * | JPEG | `APP1` Exif, `APP1` XMP, `APP13` (IPTC/Photoshop), `COM` |
 * | PNG | `tEXt` / `zTXt` / `iTXt` / `eXIf` / `tIME` |
 * | HEIC | the `Exif` and XMP items, blanked where they lie |
 * | PDF | the Info dictionary and any XMP packet |
 * | docx / xlsx / pptx | `docProps/core.xml` and `docProps/app.xml`, rewriting the zip |
 * | mp4 / mov / m4a | the metadata atoms: location, make, model, software, `com.apple.*` |
 * | anything else | nothing — copied byte-for-byte, and the receipt says so |
 *
 * Two shapes of removal are used, chosen by what keeps the document openable:
 *
 * - **rewrite** (JPEG, PNG, PDF, zip): the container is written afresh without the fields.
 * - **blank in place** (ISO-BMFF: mp4, mov, m4a, heic): every byte offset in those formats is
 *   recorded in a table somewhere else in the file, so removing bytes would break playback. The
 *   box header is turned into a `free` box of the same size and its payload zeroed — the file
 *   keeps its length, every chunk offset stays true, and a `free` box is what every decoder is
 *   already required to skip. It also means the scrub is one forward pass with a fixed buffer, so
 *   a 50 MB video never has to be in memory (§B5 last bullet).
 *
 * Nothing here touches Android: the whole scrubber is plain JVM so the unit suite drives it on
 * real fixture files.
 */
object MetadataScrubber {

    /** One field that was present and is not any more. [value] is shown as it was found (§B2). */
    data class Removed(val label: String, val value: String)

    /**
     * The receipt for one attachment. [supported] false is §B2's honest line: this file type is
     * not one we can clean, and the bytes went out exactly as they came in.
     */
    data class Result(
        val removed: List<Removed>,
        val supported: Boolean,
        /**
         * The largest single buffer the scrub allocated. A test hook, and the thing §B5's last
         * bullet is actually about: for the ISO-BMFF formats it is the copy buffer and nothing
         * more, whatever the file weighs.
         */
        val peakBufferBytes: Int,
    ) {
        /** §B2: a section is worth printing when something went, or when we could not clean at all. */
        val hasReceipt: Boolean get() = removed.isNotEmpty() || !supported
    }

    // ---- the label vocabulary (§B2: lower-case, one column) -----------------------------------

    const val L_AUTHOR = "author"
    const val L_CREATOR = "creator"
    const val L_PRODUCER = "producer"
    const val L_TITLE = "title"
    const val L_SUBJECT = "subject"
    const val L_KEYWORDS = "keywords"
    const val L_DESCRIPTION = "description"
    const val L_CREATED = "created"
    const val L_MODIFIED = "modified"
    const val L_LAST_MODIFIED_BY = "last modified by"
    const val L_COMPANY = "company"
    const val L_MANAGER = "manager"
    const val L_REVISION = "revision"
    const val L_GPS = "gps"
    const val L_MAKE = "make"
    const val L_MODEL = "model"
    const val L_SOFTWARE = "software"
    const val L_COPYRIGHT = "copyright"
    const val L_COMMENT = "comment"

    /** An XMP packet that held nothing the vocabulary above has a name for — but was still there. */
    const val L_XMP = "xmp"

    /** 64 KiB: the one buffer the streaming formats ever hold. */
    internal const val COPY_BUFFER = 64 * 1024

    /** What a rewrite-in-memory format is allowed to weigh. The attachment gate stops well short. */
    internal const val MAX_REWRITE_BYTES = 64L * 1024 * 1024

    /** The formats §B1 names, plus the catch-all. */
    enum class Format { JPEG, PNG, PDF, OOXML, ISO_BMFF, UNKNOWN }

    /**
     * Scrub [src] into [dst]. [src] is never written to — §B1: "The original file on the sender's
     * device is never modified." An unknown type is copied byte-for-byte.
     */
    @Throws(IOException::class)
    fun scrub(src: File, dst: File, fileName: String = src.name, mime: String? = null): Result {
        val format = detect(src, fileName, mime)
        return try {
            when (format) {
                Format.JPEG -> ImageScrubber.jpeg(src, dst)
                Format.PNG -> ImageScrubber.png(src, dst)
                Format.PDF -> PdfScrubber.scrub(src, dst)
                Format.OOXML -> OfficeScrubber.scrub(src, dst)
                Format.ISO_BMFF -> IsoBmffScrubber.scrub(src, dst)
                Format.UNKNOWN -> passThrough(src, dst)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // A malformed file is not a reason to refuse to send it, and it is REALLY not a reason
            // to claim it was cleaned. Copy it and tell the truth.
            passThrough(src, dst)
        }
    }

    /** §B5: "An unknown type passes through byte-for-byte and produces the honest line." */
    internal fun passThrough(src: File, dst: File): Result {
        val n = copy(src, dst)
        return Result(emptyList(), supported = false, peakBufferBytes = n)
    }

    /**
     * Magic bytes first, the name and the declared type only as a tie-break: a `.png` that is
     * really a JPEG is scrubbed as the JPEG it is.
     */
    fun detect(src: File, fileName: String = src.name, mime: String? = null): Format {
        val head = head(src, 16)
        when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> return Format.JPEG
            head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> return Format.PNG
            head.size >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-" -> return Format.PDF
            head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> return Format.ISO_BMFF
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                return if (OfficeScrubber.isOoxml(src)) Format.OOXML else Format.UNKNOWN
        }
        // A QuickTime .mov may open on `moov`/`wide`/`mdat` with no `ftyp` at all
        if (head.size >= 8) {
            val type = String(head, 4, 4, Charsets.US_ASCII)
            if (type in setOf("moov", "mdat", "wide", "skip", "free", "pnot")) {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext in setOf("mov", "mp4", "m4a", "m4v", "qt") || mime?.lowercase()?.startsWith("video/") == true) {
                    return Format.ISO_BMFF
                }
            }
        }
        return Format.UNKNOWN
    }

    internal fun head(src: File, n: Int): ByteArray = RandomAccessFile(src, "r").use { f ->
        val buf = ByteArray(minOf(n.toLong(), f.length()).toInt())
        f.readFully(buf)
        buf
    }

    /** A plain streamed copy. Returns the buffer size it used — never the file size. */
    internal fun copy(src: File, dst: File): Int {
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        }
        return COPY_BUFFER
    }

    /** Dedupe by label, first value wins, discovery order kept — §B2 shows values as found. */
    internal fun dedupe(found: List<Removed>): List<Removed> {
        val seen = LinkedHashMap<String, String>()
        found.forEach { if (it.value.isNotBlank()) seen.putIfAbsent(it.label, it.value) }
        return seen.map { (k, v) -> Removed(k, v) }
    }

    /** One line of a value: a receipt row is a row, never a paragraph out of the document. */
    internal fun clean(raw: String, max: Int = 120): String {
        val one = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().replace(Regex(" {2,}"), " ")
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }
}
