package com.pyrochat.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileAccessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver

    private fun ok(r: FileAccess.PickResult): FileAccess.PickedFile {
        assertTrue("expected Ok, got ${(r as? FileAccess.PickResult.Refused)?.message}", r is FileAccess.PickResult.Ok)
        return (r as FileAccess.PickResult.Ok).file
    }

    private fun refused(r: FileAccess.PickResult): String {
        assertTrue("expected Refused", r is FileAccess.PickResult.Refused)
        return (r as FileAccess.PickResult.Refused).message
    }

    @Test fun stagedExportRoundTripsThroughTheFileProvider() {
        val bytes = ByteArray(12_288) { (it % 97).toByte() }
        val staged = FileAccess.stageExport(context, "notes.txt", bytes)
        try {
            assertEquals(FileAccess.AUTHORITY, staged.uri.authority)
            assertEquals("content", staged.uri.scheme)
            assertTrue(staged.dir.name.startsWith("export-"))
            assertTrue(staged.file.absolutePath.startsWith(context.cacheDir.absolutePath))
            val picked = ok(FileAccess.readPicked(resolver, staged.uri))
            assertArrayEquals(bytes, picked.bytes)
            assertEquals("notes.txt", picked.name)
            assertEquals("text/plain", picked.mime)
        } finally {
            staged.cleanup()
        }
        assertFalse(staged.dir.exists())
    }

    @Test fun videoAndAudioAreRefusedBeforeReading() {
        val v = FileAccess.stageExport(context, "clip.mp4", ByteArray(100))
        try { assertEquals(AttachmentGate.ERR_VIDEO, refused(FileAccess.readPicked(resolver, v.uri))) } finally { v.cleanup() }
        val a = FileAccess.stageExport(context, "song.mp3", ByteArray(100))
        try { assertEquals(AttachmentGate.ERR_VIDEO, refused(FileAccess.readPicked(resolver, a.uri))) } finally { a.cleanup() }
    }

    @Test fun oversizeIsRefusedByTheDeclaredSize() {
        val staged = FileAccess.stageExport(context, "big.bin", ByteArray((AttachmentGate.MAX_FILE_PLAINTEXT_BYTES + 1).toInt()))
        try {
            assertEquals(AttachmentGate.ERR_TOO_BIG, refused(FileAccess.readPicked(resolver, staged.uri)))
        } finally {
            staged.cleanup()
        }
    }

    @Test fun exactlyTwelveMiBIsAccepted() {
        val staged = FileAccess.stageExport(context, "edge.bin", ByteArray(AttachmentGate.MAX_FILE_PLAINTEXT_BYTES.toInt()))
        try {
            assertEquals(AttachmentGate.MAX_FILE_PLAINTEXT_BYTES, ok(FileAccess.readPicked(resolver, staged.uri)).bytes.size.toLong())
        } finally {
            staged.cleanup()
        }
    }

    @Test fun emptyIsRefusedAndUnknownTypeIsOctetStream() {
        val empty = FileAccess.stageExport(context, "empty.txt", ByteArray(0))
        try { assertEquals(AttachmentGate.ERR_UNREADABLE, refused(FileAccess.readPicked(resolver, empty.uri))) } finally { empty.cleanup() }
        val odd = FileAccess.stageExport(context, "blob.zqx9", ByteArray(3))
        try { assertEquals("application/octet-stream", ok(FileAccess.readPicked(resolver, odd.uri)).mime) } finally { odd.cleanup() }
    }

    @Test fun nameIsSanitisedOnTheWayIn() {
        val staged = FileAccess.stageExport(context, "weird name.pdf", ByteArray(4))
        try {
            assertEquals("weird name.pdf", ok(FileAccess.readPicked(resolver, staged.uri)).name)
            assertEquals("application/pdf", ok(FileAccess.readPicked(resolver, staged.uri)).mime)
        } finally {
            staged.cleanup()
        }
    }

    @Test fun fileUriMetaAndRead() {
        val f = File(context.cacheDir, "direct.txt").apply { writeBytes("hello".toByteArray()) }
        try {
            val (name, size) = FileAccess.queryMeta(resolver, Uri.fromFile(f))
            assertEquals("direct.txt", name)
            assertEquals(5L, size)
            val picked = ok(FileAccess.readPicked(resolver, Uri.fromFile(f)))
            assertEquals("text/plain", picked.mime)
            assertArrayEquals("hello".toByteArray(), picked.bytes)
        } finally {
            f.delete()
        }
    }

    @Test fun previewStagingIsPerMessageAndIntentsCarryTheGrant() {
        val staged = FileAccess.stagePreview(context, "msg-42", "doc.pdf", ByteArray(9))
        try {
            assertEquals("preview-msg-42", staged.dir.name)
            val view = FileAccess.viewIntent(staged, "application/pdf")
            assertEquals(Intent.ACTION_VIEW, view.action)
            assertEquals(staged.uri, view.data)
            assertEquals("application/pdf", view.type)
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val share = FileAccess.shareIntent(staged, "application/pdf")
            assertEquals(Intent.ACTION_CHOOSER, share.action)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val inner = share.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            assertNotNull(inner)
            assertEquals(Intent.ACTION_SEND, inner!!.action)
            assertEquals(staged.uri, inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        } finally {
            staged.cleanup()
        }
    }

    @Test fun pickerAndCreateIntentsHaveTheSafShape() {
        val open = FileAccess.openDocumentIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        assertTrue(open.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", open.type)
        assertEquals("image/*", FileAccess.openImageIntent().type)
        val create = FileAccess.createDocumentIntent("img-1234abcd.jpg", "image/jpeg")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals("image/jpeg", create.type)
        assertEquals("img-1234abcd.jpg", create.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun writeDocumentWritesThroughTheResolver() {
        val f = File(context.cacheDir, "written.bin")
        try {
            assertTrue(FileAccess.writeDocument(resolver, Uri.fromFile(f), byteArrayOf(1, 2, 3)))
            assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        } finally {
            f.delete()
        }
    }
}
