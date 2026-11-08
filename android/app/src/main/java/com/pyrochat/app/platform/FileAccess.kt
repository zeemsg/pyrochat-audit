package com.pyrochat.app.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.BreakIterator
import java.util.UUID

/**
 * Port of `AttachmentGate` (`ios/PyroChat/UI/Attachments.swift:11-28`) + the size constant
 * (`ChatService.swift:61`): video/audio refused outright, plaintext over **12 MiB** refused,
 * empty refused. The messages are the exact iOS flash strings.
 */
object AttachmentGate {
    const val MAX_FILE_PLAINTEXT_BYTES = 12L * 1024 * 1024

    const val ERR_VIDEO = "ERR: video files are not supported"
    const val ERR_TOO_BIG = "ERR: file exceeds 12 MiB limit"
    const val ERR_UNREADABLE = "ERR: could not read file"
    const val ERR_DENIED = "ERR: file access denied"

    /**
     * iOS blocks `.movie`, `.video` and `.audiovisualContent`; the last is the parent of audio too,
     * so standalone audio is refused as an accepted consequence (voice notes cover audio).
     * Unknown/null type ⇒ allowed as a generic file.
     */
    fun refusal(mime: String?): String? {
        val m = mime?.lowercase() ?: return null
        return if (m.startsWith("video/") || m.startsWith("audio/") || m in AV_CONTAINERS) ERR_VIDEO else null
    }

    fun refusalForSize(bytes: Long): String? = if (bytes > MAX_FILE_PLAINTEXT_BYTES) ERR_TOO_BIG else null

    private val AV_CONTAINERS = setOf("application/mp4", "application/x-matroska", "application/ogg", "application/vnd.rn-realmedia")
}

/**
 * Port of `sanitizeFilename` (`ios/PyroChat/Core/ChatService.swift:1759-1780`): `/` and `\` → `_`,
 * drop control scalars (< 0x20, 0x7F), trim spaces/tabs, strip leading dots, empty ⇒ `file`,
 * clamp to 64 characters (grapheme clusters, like Swift `String.count`) keeping an extension
 * shorter than 12 characters.
 */
fun sanitizeFilename(raw: String): String {
    val filtered = StringBuilder()
    var i = 0
    val replaced = raw.replace('/', '_').replace('\\', '_')
    while (i < replaced.length) {
        val cp = replaced.codePointAt(i)
        if (cp >= 0x20 && cp != 0x7F) filtered.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    var name = filtered.toString().trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
    while (name.startsWith(".")) name = name.substring(1)
    if (name.isEmpty()) return "file"
    if (graphemeCount(name) > 64) {
        val ext = pathExtension(name)
        name = if (ext.isNotEmpty() && graphemeCount(ext) < 12) {
            graphemePrefix(name, 64 - graphemeCount(ext) - 1) + "." + ext
        } else {
            graphemePrefix(name, 64)
        }
    }
    return name
}

/** `NSString.pathExtension`: text after the last dot, unless the dot is first or last. */
private fun pathExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
}

private fun graphemeCount(s: String): Int {
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

private fun graphemePrefix(s: String, count: Int): String {
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    var n = 0
    var end = 0
    while (n < count) {
        val next = it.next()
        if (next == BreakIterator.DONE) break
        end = next
        n++
    }
    return s.substring(0, end)
}

/**
 * The Storage Access Framework side of `ChatView` (`ios/PyroChat/UI/ChatView.swift:198-220`,
 * `545-590`): pick with `ACTION_OPEN_DOCUMENT` (iOS `fileImporter(.item)`), read with the gate,
 * export with `ACTION_CREATE_DOCUMENT` or a share sheet, preview with `ACTION_VIEW` — the last
 * two through the app's `FileProvider` over a `cacheDir/export-<uuid>/` or `preview-<msgId>/`
 * staging dir that the caller deletes on dismiss (and [StorePaths.sweepPlaintextTmp] sweeps).
 */
object FileAccess {
    /** `<provider android:authorities>` in the manifest. */
    const val AUTHORITY = "com.pyrochat.app.files"

    class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

    sealed interface PickResult {
        class Ok(val file: PickedFile) : PickResult
        /** One of the `AttachmentGate.ERR_*` strings — flash it like iOS does. */
        class Refused(val message: String) : PickResult
    }

    /** `[FILE] browse` — any openable document, single selection. */
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    /** Photo/image pick without the Photo Picker dependency: SAF restricted to images. */
    fun openImageIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }

    /**
     * Metadata, gate, read — in that order like iOS (type refusal before size refusal before the
     * read). The read itself is capped so a provider that lies about its size cannot OOM the app.
     */
    fun readPicked(resolver: ContentResolver, uri: Uri): PickResult {
        val (displayName, declaredSize) = try {
            queryMeta(resolver, uri)
        } catch (_: SecurityException) {
            return PickResult.Refused(AttachmentGate.ERR_DENIED)
        }
        val mime = mimeFor(resolver, uri, displayName)
        AttachmentGate.refusal(mime)?.let { return PickResult.Refused(it) }
        if (declaredSize != null) AttachmentGate.refusalForSize(declaredSize)?.let { return PickResult.Refused(it) }
        val input = try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return PickResult.Refused(AttachmentGate.ERR_DENIED)
        } catch (_: IOException) {
            return PickResult.Refused(AttachmentGate.ERR_UNREADABLE)
        } ?: return PickResult.Refused(AttachmentGate.ERR_UNREADABLE)
        val bytes = try {
            input.use { readCapped(it, AttachmentGate.MAX_FILE_PLAINTEXT_BYTES) }
        } catch (_: IOException) {
            return PickResult.Refused(AttachmentGate.ERR_UNREADABLE)
        } ?: return PickResult.Refused(AttachmentGate.ERR_TOO_BIG)   // provider lied about (or omitted) its size
        if (bytes.isEmpty()) return PickResult.Refused(AttachmentGate.ERR_UNREADABLE)
        val name = sanitizeFilename(displayName ?: uri.lastPathSegment ?: "file")
        return PickResult.Ok(PickedFile(bytes, name, mime ?: OCTET_STREAM))
    }

    /** `OpenableColumns` display name + size (size null when the provider doesn't say). */
    fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: return null to null)
            return f.name to (if (f.isFile) f.length() else null)
        }
        val cursor: Cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: return null to null
        cursor.use { c ->
            if (!c.moveToFirst()) return null to null
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
            return name to size
        }
    }

    /** `UTType.preferredMIMEType`: the provider's type, else from the extension, else null. */
    fun mimeFor(resolver: ContentResolver, uri: Uri, displayName: String?): String? {
        resolver.getType(uri)?.takeIf { it != OCTET_STREAM && it.isNotBlank() }?.let { return it }
        val name = displayName ?: uri.lastPathSegment ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    // ---- export / preview ---------------------------------------------------------

    /** `ChatView.swift:583-584`: images export as `img-<id8>.jpg`, files under their sanitised name. */
    fun exportFileName(isImage: Boolean, messageId: String, fileName: String?): String =
        if (isImage) "img-${messageId.take(8)}.jpg" else sanitizeFilename(fileName ?: "file")

    /** `ACTION_CREATE_DOCUMENT` — the user picks where the plaintext goes; then [writeDocument]. */
    fun createDocumentIntent(name: String, mime: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_TITLE, name)
    }

    fun writeDocument(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean = try {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); true } ?: false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // ---- scrubbing (docs/SCRUB-AND-WIPE.md §B1) -----------------------------------

    /**
     * §B1: "The original file on the sender's device is never modified. Scrubbing happens on a
     * copy." The copy lives in `cacheDir/scrub-<uuid>/`, which [StorePaths.sweepPlaintextTmp]
     * sweeps at launch, at lock and at every wipe — the caller still calls [cleanup] on the way out.
     */
    class ScrubWork(val dir: File, val cleaned: File, val result: MetadataScrubber.Result) {
        fun cleanup() { dir.deleteRecursively() }
    }

    /** Stream a picked `content://` into the staging dir and scrub it there. Null if unreadable. */
    fun stageAndScrub(context: Context, resolver: ContentResolver, uri: Uri, name: String, mime: String?): ScrubWork? {
        val dir = scrubDir(context)
        return try {
            val original = File(dir, sanitizeFilename(name))
            val input = resolver.openInputStream(uri) ?: run { dir.deleteRecursively(); return null }
            input.use { source -> original.outputStream().use { source.copyTo(it, 64 * 1024) } }
            scrubInto(dir, original, name, mime)
        } catch (e: IOException) {
            dir.deleteRecursively()
            null
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            null
        }
    }

    /** The same, for bytes the attachment gate has already read and approved. */
    fun scrubBytes(context: Context, name: String, mime: String?, bytes: ByteArray): ScrubWork? {
        val dir = scrubDir(context)
        return try {
            val original = File(dir, sanitizeFilename(name))
            original.writeBytes(bytes)
            scrubInto(dir, original, name, mime)
        } catch (e: IOException) {
            dir.deleteRecursively()
            null
        }
    }

    private fun scrubInto(dir: File, original: File, name: String, mime: String?): ScrubWork {
        val cleaned = File(dir, "clean-" + sanitizeFilename(name))
        val result = MetadataScrubber.scrub(original, cleaned, name, mime)
        original.delete()   // the staged copy of the untouched original has done its job
        return ScrubWork(dir, cleaned, result)
    }

    private fun scrubDir(context: Context): File {
        val dir = File(context.cacheDir, "$SCRUB_PREFIX${UUID.randomUUID()}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        return dir
    }

    /** Stage plaintext for a share sheet in `cacheDir/export-<uuid>/<name>` (iOS `performExport`). */
    fun stageExport(context: Context, name: String, bytes: ByteArray): Staged =
        stage(context, "export-${UUID.randomUUID()}", name, bytes)

    /** Stage plaintext for `ACTION_VIEW` in `cacheDir/preview-<msgId>/<name>` (iOS `openPreview`). */
    fun stagePreview(context: Context, messageId: String, name: String, bytes: ByteArray): Staged =
        stage(context, "preview-${sanitizeFilename(messageId)}", name, bytes)

    /** A staged plaintext file: share/view it, then [Staged.cleanup] on dismiss. */
    class Staged(val dir: File, val file: File, val uri: Uri) {
        fun cleanup() { dir.deleteRecursively() }
    }

    /** `UIActivityViewController` equivalent. */
    fun shareIntent(staged: Staged, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, staged.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** `QLPreviewController` equivalent: hand the file to whatever can display it. */
    fun viewIntent(staged: Staged, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(staged.uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun stage(context: Context, dirName: String, name: String, bytes: ByteArray): Staged {
        val dir = File(context.cacheDir, dirName)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val file = File(dir, sanitizeFilename(name))
        file.writeBytes(bytes)
        return Staged(dir, file, FileProvider.getUriForFile(context, AUTHORITY, file))
    }

    /** Reads at most [cap] bytes; null if the stream has more than that. */
    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val OCTET_STREAM = "application/octet-stream"

    /** Matched by [StorePaths.PLAINTEXT_PREFIXES] so a crash never leaves a staged plaintext behind. */
    const val SCRUB_PREFIX = "scrub-"
}
