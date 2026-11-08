package com.pyrochat.app.platform

import com.pyrochat.app.platform.MetadataScrubber.Removed
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * docs/SCRUB-AND-WIPE.md §B1 row "docx / xlsx / pptx" — `docProps/core.xml` and `docProps/app.xml`,
 * rewriting the zip. `java.util.zip` and nothing else; every other part is streamed through with a
 * fixed buffer and never held.
 *
 * The two parts are rewritten EMPTY rather than dropped. `_rels/.rels` and `[Content_Types].xml`
 * both name them, and Word and Excel treat a relationship whose target is missing as a damaged
 * package — §B5 requires the scrubbed document to still open. An empty `coreProperties` element
 * carries no author, no company, no revision and no dates, which is what §B1 asked for; the
 * package stays exactly as valid as it was.
 */
internal object OfficeScrubber {

    const val CORE = "docProps/core.xml"
    const val APP = "docProps/app.xml"

    /** An OOXML package is a zip whose first entry names the content types. */
    fun isOoxml(src: File): Boolean = try {
        ZipFile(src).use { zip -> zip.getEntry("[Content_Types].xml") != null && (zip.getEntry(CORE) != null || zip.getEntry(APP) != null) }
    } catch (_: Exception) {
        false
    }

    private val CORE_FIELDS = listOf(
        "dc:creator" to MetadataScrubber.L_AUTHOR,
        "cp:lastModifiedBy" to MetadataScrubber.L_LAST_MODIFIED_BY,
        "dc:title" to MetadataScrubber.L_TITLE,
        "dc:subject" to MetadataScrubber.L_SUBJECT,
        "dc:description" to MetadataScrubber.L_DESCRIPTION,
        "cp:keywords" to MetadataScrubber.L_KEYWORDS,
        "cp:revision" to MetadataScrubber.L_REVISION,
        "dcterms:created" to MetadataScrubber.L_CREATED,
        "dcterms:modified" to MetadataScrubber.L_MODIFIED,
    )

    private val APP_FIELDS = listOf(
        "Application" to MetadataScrubber.L_PRODUCER,
        "Company" to MetadataScrubber.L_COMPANY,
        "Manager" to MetadataScrubber.L_MANAGER,
    )

    /** An empty but schema-valid `core.xml`: every namespace the original declared, no values. */
    val EMPTY_CORE = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
    """.trimIndent().replace("\n", "")

    val EMPTY_APP = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"/>
    """.trimIndent().replace("\n", "")

    fun scrub(src: File, dst: File): MetadataScrubber.Result {
        val found = ArrayList<Removed>()
        var peak = MetadataScrubber.COPY_BUFFER
        val buf = ByteArray(MetadataScrubber.COPY_BUFFER)
        ZipInputStream(src.inputStream().buffered()).use { input ->
            ZipOutputStream(dst.outputStream().buffered()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val replacement = when (entry.name) {
                        CORE -> {
                            val xml = read(input, buf).also { peak = maxOf(peak, it.size) }
                            found += fields(String(xml, Charsets.UTF_8), CORE_FIELDS)
                            EMPTY_CORE.toByteArray(Charsets.UTF_8)
                        }
                        APP -> {
                            val xml = read(input, buf).also { peak = maxOf(peak, it.size) }
                            found += fields(String(xml, Charsets.UTF_8), APP_FIELDS)
                            EMPTY_APP.toByteArray(Charsets.UTF_8)
                        }
                        else -> null
                    }
                    // the entry keeps its name, its comment and its time: the scrub removes the two
                    // parts §B1 names and invents no other difference
                    val fresh = ZipEntry(entry.name)
                    entry.comment?.let { fresh.comment = it }
                    if (entry.time >= 0) fresh.time = entry.time
                    output.putNextEntry(fresh)
                    if (replacement != null) {
                        output.write(replacement)
                    } else {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
        return MetadataScrubber.Result(MetadataScrubber.dedupe(found), supported = true, peakBufferBytes = peak)
    }

    private fun read(input: ZipInputStream, buf: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 4 * 1024 * 1024) break   // a properties part is kilobytes; anything else is not one
        }
        return out.toByteArray()
    }

    private fun fields(xml: String, table: List<Pair<String, String>>): List<Removed> {
        val out = ArrayList<Removed>()
        for ((tag, label) in table) {
            val m = Regex("""<${Regex.escape(tag)}\b[^>]*>(.*?)</${Regex.escape(tag)}>""", RegexOption.DOT_MATCHES_ALL).find(xml) ?: continue
            val value = MetadataScrubber.clean(unescape(m.groupValues[1]))
            if (value.isNotEmpty()) out += Removed(label, value)
        }
        return out
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
