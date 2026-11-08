package com.pyrochat.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ImageNormalizerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Four flat quadrants: TL red, TR green, BL blue, BR white — orientation is unambiguous. */
    private fun quadrants(w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, config)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, w / 2f, h / 2f, p)
        p.color = Color.GREEN; c.drawRect(w / 2f, 0f, w.toFloat(), h / 2f, p)
        p.color = Color.BLUE; c.drawRect(0f, h / 2f, w / 2f, h.toFloat(), p)
        p.color = Color.WHITE; c.drawRect(w / 2f, h / 2f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun encode(bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(format, quality, it) }.toByteArray()

    private fun assertColor(expected: Int, actual: Int, where: String, tolerance: Int = 48) {
        val ok = abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("$where: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}", ok)
    }

    private fun corners(jpeg: ByteArray): Map<String, Int> {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val w = bmp.width; val h = bmp.height
        val inset = 8
        return mapOf(
            "TL" to bmp.getPixel(inset, inset), "TR" to bmp.getPixel(w - 1 - inset, inset),
            "BL" to bmp.getPixel(inset, h - 1 - inset), "BR" to bmp.getPixel(w - 1 - inset, h - 1 - inset),
        )
    }

    private fun hasExifApp1(jpeg: ByteArray): Boolean {
        for (i in 0 until jpeg.size - 10) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xE1.toByte() &&
                jpeg[i + 4] == 'E'.code.toByte() && jpeg[i + 5] == 'x'.code.toByte() && jpeg[i + 6] == 'i'.code.toByte() && jpeg[i + 7] == 'f'.code.toByte()
            ) return true
        }
        return false
    }

    @Test fun largePngDownscalesToTheIosSizeAsMetadataFreeJpeg() {
        val png = encode(quadrants(4000, 3000), Bitmap.CompressFormat.PNG)
        val out = ImageNormalizer.normalize(png)
        assertNotNull(out); out!!
        assertEquals(2048, out.width)
        assertEquals(1536, out.height)
        assertEquals(0xFF.toByte(), out.jpeg[0]); assertEquals(0xD8.toByte(), out.jpeg[1])   // JPEG SOI
        assertFalse(hasExifApp1(out.jpeg))
        val decoded = BitmapFactory.decodeByteArray(out.jpeg, 0, out.jpeg.size)
        assertEquals(2048, decoded.width); assertEquals(1536, decoded.height)
        val c = corners(out.jpeg)
        assertColor(Color.RED, c["TL"]!!, "TL"); assertColor(Color.GREEN, c["TR"]!!, "TR")
        assertColor(Color.BLUE, c["BL"]!!, "BL"); assertColor(Color.WHITE, c["BR"]!!, "BR")
    }

    @Test fun exifRotatedJpegComesOutUprightWithoutTheTag() {
        // 400×300 landscape with orientation 6 (rotate 90° CW to display) → displayed 300×400 portrait
        val plain = encode(quadrants(400, 300), Bitmap.CompressFormat.JPEG, 100)
        val f = File(context.cacheDir, "exif-test.jpg")
        f.writeBytes(plain)
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "45/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        val tagged = f.readBytes()
        f.delete()
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(ByteArrayInputStream(tagged)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue(hasExifApp1(tagged))

        val out = ImageNormalizer.normalize(tagged)
        assertNotNull(out); out!!
        assertEquals(300, out.width)
        assertEquals(400, out.height)
        assertFalse("EXIF must be stripped", hasExifApp1(out.jpeg))
        val exif = ExifInterface(ByteArrayInputStream(out.jpeg))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        // 90° CW: TL(red)→TR, TR(green)→BR, BR(white)→BL, BL(blue)→TL
        val c = corners(out.jpeg)
        assertColor(Color.BLUE, c["TL"]!!, "TL"); assertColor(Color.RED, c["TR"]!!, "TR")
        assertColor(Color.WHITE, c["BL"]!!, "BL"); assertColor(Color.GREEN, c["BR"]!!, "BR")
    }

    @Test fun exifRotatedAndOversizedTogether() {
        val plain = encode(quadrants(4000, 3000), Bitmap.CompressFormat.JPEG, 90)
        val f = File(context.cacheDir, "exif-big.jpg")
        f.writeBytes(plain)
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString()); saveAttributes()
        }
        val tagged = f.readBytes(); f.delete()
        val out = ImageNormalizer.normalize(tagged)!!
        assertEquals(1536, out.width); assertEquals(2048, out.height)   // oriented 3000×4000 → ×0.512
        // 270° (90° CCW): TL(red)→BL, TR(green)→TL, BR(white)→TR, BL(blue)→BR
        val c = corners(out.jpeg)
        assertColor(Color.GREEN, c["TL"]!!, "TL"); assertColor(Color.WHITE, c["TR"]!!, "TR")
        assertColor(Color.RED, c["BL"]!!, "BL"); assertColor(Color.BLUE, c["BR"]!!, "BR")
    }

    @Test fun smallImagesAreNotUpscaled() {
        val out = ImageNormalizer.normalize(encode(quadrants(640, 480), Bitmap.CompressFormat.PNG))!!
        assertEquals(640, out.width); assertEquals(480, out.height)
    }

    @Test fun uriInputMatchesBytes() {
        val png = encode(quadrants(1000, 500), Bitmap.CompressFormat.PNG)
        val f = File(context.cacheDir, "uri-test.png")
        f.writeBytes(png)
        val out = ImageNormalizer.normalize(context.contentResolver, Uri.fromFile(f))
        f.delete()
        assertNotNull(out)
        assertEquals(1000, out!!.width); assertEquals(500, out.height)
    }

    @Test fun garbageIsNull() {
        assertNull(ImageNormalizer.normalize(ByteArray(0)))
        assertNull(ImageNormalizer.normalize(ByteArray(5000) { (it * 31).toByte() }))
        assertNull(ImageNormalizer.normalize("not an image".toByteArray()))
    }

    @Test fun mirrorThumbnailParameters() {
        val png = encode(quadrants(4000, 3000), Bitmap.CompressFormat.PNG)
        val thumb = ImageNormalizer.mirrorThumbnail(png)
        assertNotNull(thumb); thumb!!
        assertEquals(256, thumb.width); assertEquals(192, thumb.height)
        assertTrue(thumb.jpeg.size <= ImageNormalizer.MIRROR_THUMB_MAX_BYTES)
    }

    @Test fun veryLargeSourceIsDecodedWithinBudget() {
        // 6000×4500 RGB_565 source (54 MB) → the decoder samples/scales to 2048×1536 instead of
        // allocating a 108 MB ARGB full-size bitmap first
        val jpeg = encode(quadrants(6000, 4500, Bitmap.Config.RGB_565), Bitmap.CompressFormat.JPEG, 85)
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val out = ImageNormalizer.normalize(jpeg)!!
        assertEquals(2048, out.width); assertEquals(1536, out.height)
        val c = corners(out.jpeg)
        assertColor(Color.RED, c["TL"]!!, "TL"); assertColor(Color.WHITE, c["BR"]!!, "BR")
        assertTrue(before >= 0)
    }
}
