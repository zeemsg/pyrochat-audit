package com.pyrochat.app.platform

import com.pyrochat.app.platform.MetadataScrubber.Removed

/**
 * docs/SCRUB-AND-WIPE.md §B1/§B2 — the fields an XMP packet is worth naming in the receipt. XMP is
 * RDF/XML and the same field arrives in two shapes, an attribute or an element, so both are read.
 *
 * This is a reader for a receipt, not an RDF parser: it looks for the handful of properties §B2
 * has a column for and stops. A packet that held nothing recognisable is still reported — as
 * [MetadataScrubber.L_XMP] — because it WAS there and it IS gone, and §B2's rule is that nothing
 * is missed, not that everything is spelled out.
 */
internal object XmpReader {

    /** XMP property → the receipt's label. */
    private val PROPERTIES = listOf(
        "dc:creator" to MetadataScrubber.L_AUTHOR,
        "xmp:CreatorTool" to MetadataScrubber.L_CREATOR,
        "pdf:Producer" to MetadataScrubber.L_PRODUCER,
        "dc:title" to MetadataScrubber.L_TITLE,
        "dc:subject" to MetadataScrubber.L_SUBJECT,
        "dc:description" to MetadataScrubber.L_DESCRIPTION,
        "dc:rights" to MetadataScrubber.L_COPYRIGHT,
        "xmp:CreateDate" to MetadataScrubber.L_CREATED,
        "xmp:ModifyDate" to MetadataScrubber.L_MODIFIED,
        "xmp:MetadataDate" to MetadataScrubber.L_MODIFIED,
        "tiff:Make" to MetadataScrubber.L_MAKE,
        "tiff:Model" to MetadataScrubber.L_MODEL,
        "tiff:Software" to MetadataScrubber.L_SOFTWARE,
        "exif:GPSLatitude" to MetadataScrubber.L_GPS,
    )

    fun read(packet: String): List<Removed> {
        val out = ArrayList<Removed>()
        for ((property, label) in PROPERTIES) {
            val value = element(packet, property) ?: attribute(packet, property) ?: continue
            val clean = MetadataScrubber.clean(unescape(value))
            if (clean.isNotEmpty()) out += Removed(label, clean)
        }
        return MetadataScrubber.dedupe(out)
    }

    /** `<dc:creator><rdf:Alt><rdf:li …>J. Example</rdf:li></rdf:Alt></dc:creator>` */
    private fun element(packet: String, property: String): String? {
        val open = packet.indexOf("<$property")
        if (open < 0) return null
        val gt = packet.indexOf('>', open)
        if (gt < 0) return null
        if (packet.getOrNull(gt - 1) == '/') return null   // an empty element holds nothing
        val close = packet.indexOf("</$property", gt)
        if (close < 0) return null
        // strip the rdf:Alt / rdf:Seq / rdf:li wrapping the value may or may not have
        return packet.substring(gt + 1, close).replace(Regex("<[^>]*>"), " ").trim().ifEmpty { null }
    }

    /** `<rdf:Description … pdf:Producer="Microsoft Word 16.0" …>` */
    private fun attribute(packet: String, property: String): String? {
        val m = Regex("""\b${Regex.escape(property)}\s*=\s*"([^"]*)"""").find(packet)
            ?: Regex("""\b${Regex.escape(property)}\s*=\s*'([^']*)'""").find(packet)
        return m?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
