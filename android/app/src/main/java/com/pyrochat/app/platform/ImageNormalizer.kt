package com.pyrochat.app.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * Port of `ImageNormalizer` (`ios/PyroChat/UI/Attachments.swift:30-50`): decode any picked or
 * pasted image (JPEG/PNG/HEIF/WebP/GIF/BMP…), bake the EXIF orientation in, downscale so
 * `max(w, h) ≤ 2048` with the exact iOS rounding (`scale = min(1, 2048 / max(w, h))`,
 * `floor(w·scale) × floor(h·scale)`), re-encode as JPEG at quality 0.8 (`80` here).
 *
 * PRIVACY: `Bitmap.compress` writes pixels only — no EXIF, XMP, ICC or GPS survives — and
 * [ImageDecoder] applies the orientation while decoding, so the output is upright with no
 * orientation tag. The decoder is told the target size up front ([ImageDecoder.setTargetSize],
 * sampling + scaling inside the codec, the `inSampleSize` equivalent) so a 12 000-pixel-wide
 * source never has to be fully allocated.
 *
 * The mirror thumbnail parameters (`MirrorEngine.swift:399-400`) are [THUMB_MAX_DIMENSION] /
 * [THUMB_JPEG_QUALITY], sent only if the result is at most [MIRROR_THUMB_MAX_BYTES].
 */
object ImageNormalizer {
    const val MAX_DIMENSION = 2048
    /** iOS `compressionQuality: 0.8` on the 0…1 scale = 80 on Android's 0…100 scale. */
    const val JPEG_QUALITY = 80
    const val THUMB_MAX_DIMENSION = 256
    const val THUMB_JPEG_QUALITY = 50
    const val MIRROR_THUMB_MAX_BYTES = 24_000

    class Normalized(val jpeg: ByteArray, val width: Int, val height: Int)

    /** `ImageNormalizer.normalize(data)` — null when the bytes are not a decodable image. */
    fun normalize(bytes: ByteArray, maxDimension: Int = MAX_DIMENSION, quality: Int = JPEG_QUALITY): Normalized? =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxDimension, quality)

    /** Same, straight from a picker / clipboard `content://` (or `file://`) Uri — no full read into memory first. */
    fun normalize(resolver: ContentResolver, uri: Uri, maxDimension: Int = MAX_DIMENSION, quality: Int = JPEG_QUALITY): Normalized? =
        decode(ImageDecoder.createSource(resolver, uri), maxDimension, quality)

    /** Same, from a file already on disk — the scrubbed copy docs/SCRUB-AND-WIPE.md §B1 stages. */
    fun normalize(file: java.io.File, maxDimension: Int = MAX_DIMENSION, quality: Int = JPEG_QUALITY): Normalized? =
        decode(ImageDecoder.createSource(file), maxDimension, quality)

    /** Desktop-mirror thumbnail (`maxDimension: 256, quality: 0.5`); null if undecodable or over the 24 000-byte budget. */
    fun mirrorThumbnail(bytes: ByteArray): Normalized? =
        normalize(bytes, THUMB_MAX_DIMENSION, THUMB_JPEG_QUALITY)?.takeIf { it.jpeg.size <= MIRROR_THUMB_MAX_BYTES }

    /**
     * `Attachments.swift:40-42`: `scale = min(1, maxDimension / max(w, h))`, target =
     * `(w·scale).rounded(.down) × (h·scale).rounded(.down)` in Double like CGFloat.
     */
    fun targetSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height).toDouble())
        val w = floor(width * scale).toInt().coerceAtLeast(1)
        val h = floor(height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    private fun decode(source: ImageDecoder.Source, maxDimension: Int, quality: Int): Normalized? {
        var width = 0
        var height = 0
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // info.size is the ORIENTED size — ImageDecoder rotates per EXIF while decoding
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) throw IOException("empty image")
                val (tw, th) = targetSize(w, h, maxDimension)
                width = tw
                height = th
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // compress()/getPixel() need CPU-side pixels
                decoder.isMutableRequired = false
                if (tw != w || th != h) decoder.setTargetSize(tw, th)
            }
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        try {
            if (bitmap.width != width || bitmap.height != height) {
                // defensive: a decoder that ignored the target still yields exact iOS dimensions
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                return encode(scaled, quality).also { if (scaled !== bitmap) scaled.recycle() }
            }
            return encode(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): Normalized? {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4 + 1024)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return Normalized(out.toByteArray(), bitmap.width, bitmap.height)
    }
}
