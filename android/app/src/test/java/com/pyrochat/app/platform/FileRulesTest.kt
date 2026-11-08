package com.pyrochat.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sanitizeFilename` (`ChatService.swift:1759-1780`), `AttachmentGate` (`Attachments.swift:11-28`), `ImageNormalizer.targetSize` (`Attachments.swift:40-42`). */
class FileRulesTest {
    @Test fun sanitizeSeparatorsControlCharsAndDots() {
        assertEquals("a_b_c.txt", sanitizeFilename("a/b\\c.txt"))
        assertEquals("report.pdf", sanitizeFilename("reportpdf".replace("pdf", ".pdf")))
        assertEquals("hidden", sanitizeFilename("...hidden"))
        assertEquals("x y", sanitizeFilename("  x y \t"))
        assertEquals("file", sanitizeFilename(""))
        assertEquals("file", sanitizeFilename("..."))
        assertEquals("file", sanitizeFilename("\n\r"))
    }

    @Test fun sanitizeClampsTo64KeepingShortExtension() {
        val long = "a".repeat(100) + ".tar.gz"
        val out = sanitizeFilename(long)
        assertEquals(64, out.length)
        assertEquals("a".repeat(61) + ".gz", out)
    }

    @Test fun sanitizeClampsPlainWhenExtensionIsLong() {
        val long = "b".repeat(70) + ".averyveryverylongext"
        assertEquals("b".repeat(64), sanitizeFilename(long))
        assertEquals("c".repeat(64), sanitizeFilename("c".repeat(80)))
    }

    @Test fun sanitizeCountsGraphemeClustersLikeSwift() {
        val flag = "🇨🇦"      // regional-indicator pair = one Swift Character, 4 UTF-16 units
        val name = flag.repeat(70) + ".txt"
        val out = sanitizeFilename(name)
        assertEquals(flag.repeat(60) + ".txt", out)   // 60 clusters + "." + "txt" = 64 characters
    }

    @Test fun gateRefusesAudioVisualOnly() {
        assertEquals(AttachmentGate.ERR_VIDEO, AttachmentGate.refusal("video/mp4"))
        assertEquals(AttachmentGate.ERR_VIDEO, AttachmentGate.refusal("VIDEO/quicktime"))
        assertEquals(AttachmentGate.ERR_VIDEO, AttachmentGate.refusal("audio/mpeg"))   // .audiovisualContent covers audio
        assertNull(AttachmentGate.refusal("application/pdf"))
        assertNull(AttachmentGate.refusal("image/png"))
        assertNull(AttachmentGate.refusal(null))   // unknown type ⇒ generic file
    }

    @Test fun gateSizeCapIs12MiB() {
        assertNull(AttachmentGate.refusalForSize(12L * 1024 * 1024))
        assertEquals(AttachmentGate.ERR_TOO_BIG, AttachmentGate.refusalForSize(12L * 1024 * 1024 + 1))
    }

    @Test fun exportNames() {
        assertEquals("img-0123abcd.jpg", FileAccess.exportFileName(true, "0123abcd-ffff", "ignored.png"))
        assertEquals("notes.txt", FileAccess.exportFileName(false, "id", "notes.txt"))
        assertEquals("file", FileAccess.exportFileName(false, "id", null))
    }

    @Test fun targetSizeMatchesIosRounding() {
        assertEquals(2048 to 1536, ImageNormalizer.targetSize(4000, 3000))
        assertEquals(1536 to 2048, ImageNormalizer.targetSize(3000, 4000))
        assertEquals(1000 to 500, ImageNormalizer.targetSize(1000, 500))      // scale capped at 1
        assertEquals(2048 to 2048, ImageNormalizer.targetSize(2048, 2048))
        assertEquals(2048 to 1365, ImageNormalizer.targetSize(3001, 2001))    // floor(2001 * 2048/3001) = 1365.5… → 1365
        assertEquals(256 to 192, ImageNormalizer.targetSize(4000, 3000, 256))
    }

    /**
     * docs/SCRUB-AND-WIPE.md §B1 — the scrubber works on a COPY in the cache, so that copy has to
     * be swept by the same launch/lock/wipe sweep every other staged plaintext is.
     */
    @Test fun theScrubStagingDirIsSweptWithTheOtherPlaintext() {
        assertTrue(FileAccess.SCRUB_PREFIX in StorePaths.PLAINTEXT_PREFIXES)
        assertTrue("scrub-1234".startsWith(FileAccess.SCRUB_PREFIX))
    }
}
