package com.pyrochat.app.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * docs/SCRUB-AND-WIPE.md §B5 — "Fixture files per type, each carrying known metadata".
 *
 * Every fixture is BUILT, not checked in: a real JPEG and a real PNG out of `ImageIO`, a real zip
 * out of `java.util.zip`, a PDF with a correct cross-reference table, and ISO-BMFF files whose box
 * tree and chunk-offset table are consistent — so "the document still opens afterwards" is a
 * question the suite can actually ask.
 *
 * The values below are the known metadata the assertions look for.
 */
internal object ScrubFixtures {

    const val AUTHOR = "J. Example"
    const val PRODUCER = "Microsoft Word 16.0"
    const val CREATOR = "PyroChat Fixture"
    const val TITLE = "Quarterly Report"
    const val CREATED = "D:20240115120000Z"
    const val MODIFIED = "D:20240116080000Z"
    const val COMPANY = "PyroChat"
    const val SOFTWARE = "PyroChat Fixture 1.0"
    const val MAKE = "PyroCam"
    const val MODEL = "PC-1"
    const val EXIF_DATETIME = "2024:01:15 12:00:00"

    /** 45°30′6.12″ N, 73°34′2.28″ W — §B2's own example coordinates. */
    const val GPS = "45.5017, -73.5673"

    val XMP_PACKET = """
        <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
        xmlns:dc="http://purl.org/dc/elements/1.1/" pdf:Producer="$PRODUCER" xmp:CreatorTool="$CREATOR">
        <dc:creator><rdf:Seq><rdf:li>$AUTHOR</rdf:li></rdf:Seq></dc:creator>
        </rdf:Description></rdf:RDF></x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    // ---- PDF ----------------------------------------------------------------------------------

    /** A one-page PDF that really renders, with an Info dictionary and an XMP packet. */
    fun pdf(dst: File): File {
        val content = "BT /F1 24 Tf 20 100 Td ($TITLE) Tj ET\n"
        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R/Metadata 6 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>",
            "<</Length ${content.length}>>\nstream\n$content\nendstream",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>",
            "<</Type/Metadata/Subtype/XML/Length ${XMP_PACKET.length}>>\nstream\n$XMP_PACKET\nendstream",
            "<</Author($AUTHOR)/Creator($CREATOR)/Producer($PRODUCER)/Title($TITLE)" +
                "/CreationDate($CREATED)/ModDate($MODIFIED)>>",
        )
        val sb = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n").append("0000000000 65535 f \n")
        offsets.forEach { sb.append("%010d 00000 n \n".format(it)) }
        sb.append("trailer\n<</Size ${objects.size + 1}/Root 1 0 R/Info ${objects.size} 0 R>>\n")
        sb.append("startxref\n$xref\n%%EOF\n")
        dst.writeBytes(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return dst
    }

    // ---- OOXML --------------------------------------------------------------------------------

    private const val DOC_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>the quarterly numbers</w:t></w:r></w:p></w:body></w:document>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private val CORE_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>$TITLE</dc:title><dc:creator>$AUTHOR</dc:creator><cp:lastModifiedBy>$AUTHOR</cp:lastModifiedBy><cp:revision>7</cp:revision><dcterms:created xsi:type="dcterms:W3CDTF">2024-01-15T12:00:00Z</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">2024-01-16T08:00:00Z</dcterms:modified></cp:coreProperties>"""

    private val APP_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>$PRODUCER</Application><Company>$COMPANY</Company><Pages>3</Pages></Properties>"""

    /** The parts a real .docx has, in the order Word writes them. */
    val DOCX_ENTRIES = listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "docProps/core.xml", "docProps/app.xml")

    fun docx(dst: File): File {
        ZipOutputStream(dst.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = 1_700_000_000_000L })
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(DOCX_ENTRIES[0], CONTENT_TYPES)
            put(DOCX_ENTRIES[1], RELS)
            put(DOCX_ENTRIES[2], DOC_XML)
            put(DOCX_ENTRIES[3], CORE_XML)
            put(DOCX_ENTRIES[4], APP_XML)
        }
        return dst
    }

    // ---- EXIF ---------------------------------------------------------------------------------

    /** A TIFF block carrying the seven known values, laid out the way a camera lays them out. */
    fun tiff(): ByteArray {
        val out = ByteArrayOutputStream()
        val ascii = LinkedHashMap<Int, String>(
            linkedMapOf(
                0x010F to MAKE, 0x0110 to MODEL, 0x0131 to SOFTWARE, 0x013B to AUTHOR, 0x0132 to EXIF_DATETIME,
            ),
        )
        // header + IFD0 (5 ASCII entries + the GPS pointer) + the heap
        val entries = ascii.size + 1
        val ifd0 = 8
        val heapStart = ifd0 + 2 + entries * 12 + 4
        val heap = ByteArrayOutputStream()
        val valueOffsets = HashMap<Int, Int>()
        ascii.forEach { (tag, value) ->
            valueOffsets[tag] = heapStart + heap.size()
            heap.write(value.toByteArray(Charsets.US_ASCII))
            heap.write(0)
            if (heap.size() % 2 == 1) heap.write(0)
        }
        val gpsIfdAt = heapStart + heap.size()
        heap.write(gpsIfd(gpsIfdAt))

        out.write("II".toByteArray(Charsets.US_ASCII))
        out.write(le16(42)); out.write(le32(ifd0))
        out.write(le16(entries))
        ascii.forEach { (tag, value) ->
            out.write(le16(tag)); out.write(le16(2))
            out.write(le32(value.length + 1)); out.write(le32(valueOffsets[tag]!!))
        }
        out.write(le16(0x8825)); out.write(le16(4)); out.write(le32(1)); out.write(le32(gpsIfdAt))
        out.write(le32(0))   // no IFD1
        out.write(heap.toByteArray())
        return out.toByteArray()
    }

    /** 45°30′6.12″N, 73°34′2.28″W as EXIF rationals. */
    private fun gpsIfd(at: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val entries = 4
        val heapAt = at + 2 + entries * 12 + 4
        val lat = rationals(45, 1, 30, 1, 612, 100)
        val lon = rationals(73, 1, 34, 1, 228, 100)
        out.write(le16(entries))
        out.write(le16(0x0001)); out.write(le16(2)); out.write(le32(2)); out.write(byteArrayOf('N'.code.toByte(), 0, 0, 0))
        out.write(le16(0x0002)); out.write(le16(5)); out.write(le32(3)); out.write(le32(heapAt))
        out.write(le16(0x0003)); out.write(le16(2)); out.write(le32(2)); out.write(byteArrayOf('W'.code.toByte(), 0, 0, 0))
        out.write(le16(0x0004)); out.write(le16(5)); out.write(le32(3)); out.write(le32(heapAt + lat.size))
        out.write(le32(0))
        out.write(lat); out.write(lon)
        return out.toByteArray()
    }

    private fun rationals(vararg v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        v.forEach { out.write(le32(it)) }
        return out.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )

    // ---- JPEG ---------------------------------------------------------------------------------

    /** A genuine JPEG from ImageIO with an Exif APP1, an XMP APP1 and a COM spliced in after SOI. */
    fun jpeg(dst: File): File {
        val base = ByteArrayOutputStream().also { ImageIO.write(image(), "jpg", it) }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(base, 0, 2)                                   // SOI
        out.write(segment(0xE1, "Exif  ".toByteArray(Charsets.ISO_8859_1) + tiff()))
        out.write(segment(0xE1, "http://ns.adobe.com/xap/1.0/ ".toByteArray(Charsets.ISO_8859_1) + XMP_PACKET.toByteArray(Charsets.UTF_8)))
        out.write(segment(0xFE, "drafted on the laptop under the desk".toByteArray(Charsets.UTF_8)))
        out.write(base, 2, base.size - 2)
        dst.writeBytes(out.toByteArray())
        return dst
    }

    private fun segment(marker: Int, body: ByteArray): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + body
    }

    // ---- PNG ----------------------------------------------------------------------------------

    /** A genuine PNG from ImageIO with tEXt / zTXt / eXIf / tIME chunks spliced in before IEND. */
    fun png(dst: File): File {
        val base = ByteArrayOutputStream().also { ImageIO.write(image(), "png", it) }.toByteArray()
        val iend = lastIndexOfIend(base)
        val out = ByteArrayOutputStream()
        out.write(base, 0, iend)
        out.write(chunk("tEXt", "Author $AUTHOR".toByteArray(Charsets.ISO_8859_1)))
        out.write(chunk("tEXt", "Software $SOFTWARE".toByteArray(Charsets.ISO_8859_1)))
        out.write(chunk("zTXt", "Comment  ".toByteArray(Charsets.ISO_8859_1) + deflate("drafted on the laptop under the desk")))
        out.write(chunk("eXIf", tiff()))
        out.write(chunk("tIME", byteArrayOf(0x07, 0xE8.toByte(), 1, 15, 12, 0, 0)))
        out.write(base, iend, base.size - iend)
        dst.writeBytes(out.toByteArray())
        return dst
    }

    private fun lastIndexOfIend(b: ByteArray): Int {
        for (i in b.size - 12 downTo 8) {
            if (b[i + 4] == 'I'.code.toByte() && b[i + 5] == 'E'.code.toByte() && b[i + 6] == 'N'.code.toByte() && b[i + 7] == 'D'.code.toByte()) return i
        }
        return b.size - 12
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(data.size))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        val crc = CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(data) }.value
        out.write(be32(crc.toInt()))
        return out.toByteArray()
    }

    private fun deflate(s: String): ByteArray {
        val input = s.toByteArray(Charsets.UTF_8)
        val d = Deflater()
        d.setInput(input); d.finish()
        val buf = ByteArray(input.size * 2 + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private fun image(): BufferedImage = BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until width) for (y in 0 until height) setRGB(x, y, Color(x * 5 % 256, y * 7 % 256, 90).rgb)
    }

    // ---- ISO-BMFF -------------------------------------------------------------------------------

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { parts.forEach(it::write) }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(be32(body.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    /** `©xyz`-style QuickTime text atom: `u16 length ‖ u16 language ‖ text`. */
    private fun quickTimeText(type: String, text: String): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        return box(type, byteArrayOf(((t.size shr 8) and 0xFF).toByte(), (t.size and 0xFF).toByte(), 0, 0), t)
    }

    private fun dataBox(text: String): ByteArray =
        box("data", be32(1), be32(0), text.toByteArray(Charsets.UTF_8))

    /** `keys` + `ilst` the way an iPhone writes `com.apple.quicktime.*`. */
    private fun appleMetadata(): ByteArray {
        val keys = listOf("com.apple.quicktime.make", "com.apple.quicktime.model", "com.apple.quicktime.software")
        val values = listOf(MAKE, MODEL, SOFTWARE)
        val keysBody = ByteArrayOutputStream()
        keysBody.write(be32(0))                       // version + flags
        keysBody.write(be32(keys.size))
        keys.forEach { k ->
            val b = k.toByteArray(Charsets.UTF_8)
            keysBody.write(be32(b.size + 8)); keysBody.write("mdta".toByteArray(Charsets.US_ASCII)); keysBody.write(b)
        }
        val ilstBody = ByteArrayOutputStream()
        values.forEachIndexed { i, v ->
            val payload = dataBox(v)
            ilstBody.write(be32(payload.size + 8))
            ilstBody.write(be32(i + 1))               // the 1-based index into `keys`
            ilstBody.write(payload)
        }
        return box(
            "meta",
            be32(0),                                   // meta is a FullBox
            box("hdlr", be32(0), ByteArray(4), "mdta".toByteArray(Charsets.US_ASCII), ByteArray(12)),
            ByteArrayOutputStream().also { it.write(keysBody.toByteArray()) }.toByteArray().let { box("keys", it) },
            box("ilst", ilstBody.toByteArray()),
        )
    }

    /**
     * An mp4 whose `stco` names the real offset of the media payload — so a test can prove the
     * chunk table still points at the same bytes after the scrub.
     */
    fun mp4(dst: File, mediaBytes: ByteArray = ByteArray(2048) { (it * 31 % 251).toByte() }): File {
        val udta = box(
            "udta",
            appleMetadata(),
            quickTimeText("©xyz", "+45.5017-073.5673+010.000/"),
            quickTimeText("©swr", SOFTWARE),
            quickTimeText("©nam", TITLE),
        )
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII), be32(512), "isomiso2mp41".toByteArray(Charsets.US_ASCII))
        // build once with a placeholder to learn the sizes, then again with the true chunk offset
        fun assemble(chunkOffset: Int): ByteArray {
            val stco = box("stco", be32(0), be32(1), be32(chunkOffset))
            val moov = box(
                "moov",
                box("mvhd", be32(0), ByteArray(96)),
                box("trak", box("tkhd", be32(0), ByteArray(80)), box("mdia", box("minf", box("stbl", stco)))),
                udta,
            )
            val out = ByteArrayOutputStream()
            out.write(ftyp); out.write(moov)
            out.write(be32(mediaBytes.size + 8)); out.write("mdat".toByteArray(Charsets.US_ASCII)); out.write(mediaBytes)
            return out.toByteArray()
        }
        val probe = assemble(0)
        val mdatPayloadAt = probe.size - mediaBytes.size
        dst.writeBytes(assemble(mdatPayloadAt))
        return dst
    }

    /** Where the media payload of [mp4] starts — the value its `stco` carries. */
    fun mp4MediaOffset(file: File, mediaSize: Int): Int = (file.length() - mediaSize).toInt()

    /**
     * A HEIF still: `meta` with an `iinf`/`iloc` pair naming an image item and an `Exif` item, and
     * an `mdat` holding both payloads.
     */
    fun heic(dst: File): File {
        val exifPayload = be32(0) + "Exif  ".toByteArray(Charsets.ISO_8859_1) + tiff()
        val imagePayload = ByteArray(512) { (it * 17 % 241).toByte() }

        fun assemble(mdatAt: Int): ByteArray {
            val imageAt = mdatAt.toLong()
            val exifAt = imageAt + imagePayload.size
            val iloc = box(
                "iloc",
                byteArrayOf(1, 0, 0, 0),                          // version 1, no flags
                byteArrayOf(0x44, 0x00),                          // offset_size 4, length_size 4, base 0, index 0
                byteArrayOf(0, 2),                                // item_count
                // item 1: the picture
                byteArrayOf(0, 1) + byteArrayOf(0, 0) + byteArrayOf(0, 0) + byteArrayOf(0, 1) +
                    be32(imageAt.toInt()) + be32(imagePayload.size),
                // item 2: the Exif block
                byteArrayOf(0, 2) + byteArrayOf(0, 0) + byteArrayOf(0, 0) + byteArrayOf(0, 1) +
                    be32(exifAt.toInt()) + be32(exifPayload.size),
            )
            val infe1 = box("infe", byteArrayOf(2, 0, 0, 0), byteArrayOf(0, 1), byteArrayOf(0, 0), "hvc1".toByteArray(Charsets.US_ASCII), byteArrayOf(0))
            val infe2 = box("infe", byteArrayOf(2, 0, 0, 0), byteArrayOf(0, 2), byteArrayOf(0, 0), "Exif".toByteArray(Charsets.US_ASCII), byteArrayOf(0))
            val iinf = box("iinf", byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 2), infe1, infe2)
            val meta = box(
                "meta", be32(0),
                box("hdlr", be32(0), ByteArray(4), "pict".toByteArray(Charsets.US_ASCII), ByteArray(12)),
                box("pitm", be32(0), byteArrayOf(0, 1)),
                iinf, iloc,
            )
            val ftyp = box("ftyp", "heic".toByteArray(Charsets.US_ASCII), be32(0), "heicmif1miaf".toByteArray(Charsets.US_ASCII))
            val out = ByteArrayOutputStream()
            out.write(ftyp); out.write(meta)
            out.write(be32(imagePayload.size + exifPayload.size + 8)); out.write("mdat".toByteArray(Charsets.US_ASCII))
            out.write(imagePayload); out.write(exifPayload)
            return out.toByteArray()
        }
        val probe = assemble(0)
        dst.writeBytes(assemble(probe.size - imagePayload.size - exifPayload.size))
        return dst
    }

    /** Where [heic]'s picture item lies, so a test can prove it was not touched. */
    fun heicImageRange(file: File): IntRange {
        val n = file.length().toInt()
        val exif = (be32(0) + "Exif  ".toByteArray(Charsets.ISO_8859_1) + tiff()).size
        return (n - exif - 512) until (n - exif)
    }

    fun unknown(dst: File): File {
        dst.writeBytes("PYRO-NOT-A-DOCUMENT  author: $AUTHOR".toByteArray(Charsets.UTF_8))
        return dst
    }

    internal fun be32(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )
}
