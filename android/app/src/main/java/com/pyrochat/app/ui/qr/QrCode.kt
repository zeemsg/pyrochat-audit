package com.pyrochat.app.ui.qr

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Port of iOS `QRCode` (`ios/PyroChat/UI/VerifyView.swift:106-118`): `CIQRCodeGenerator` with
 * `inputCorrectionLevel = "M"` over arbitrary `Data`, scaled 8× with nearest-neighbour sampling.
 *
 * ZXing's `QRCodeWriter` is the generator here. The payload is handed over as an ISO-8859-1
 * string and NO `CHARACTER_SET` hint: ZXing's default byte-mode charset is ISO-8859-1, so the
 * chars map back 1:1 to the bytes, and without the hint no ECI segment is written — exactly what
 * CoreImage emits for `inputMessage` bytes. (Passing the hint, even as ISO-8859-1, makes ZXing
 * prepend ECI 3; an iOS scanner would then honour it and read a non-ASCII username as Latin-1
 * instead of guessing UTF-8 the way it does for the iOS-generated code.) That keeps the
 * safety-number QR the raw libsignal scannable protobuf (`docs/android/inventory-critique.md`
 * §1.7) and the `pyro-add` / `pyro-link` codes the UTF-8 bytes of their text.
 *
 * [matrix] is pure ZXing (JVM-testable); [bitmap] and [QrImage] are the Android/Compose half.
 */
object QrCode {
    /** iOS `inputCorrectionLevel = "M"` — the web page's `qrcode(0, "M")` too. */
    val CORRECTION: ErrorCorrectionLevel = ErrorCorrectionLevel.M

    /**
     * CoreImage's output image carries a one-module quiet zone on each side (the views then add
     * the white 8-pt pad); ZXing's default would be four.
     */
    const val QUIET_ZONE = 1

    /** iOS `scale: CGFloat = 8` — pixels per module before the view scales the image further. */
    const val SCALE = 8

    /** Module matrix of [data] in byte mode, `(modules + 2 * margin)` on a side, 1 cell = 1 module. */
    fun matrix(data: ByteArray, margin: Int = QUIET_ZONE): BitMatrix {
        require(data.isNotEmpty()) { "QR payload must not be empty" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to CORRECTION,
            EncodeHintType.MARGIN to margin,
        )
        // width/height 0 = the smallest rendering: one cell per module plus the margin
        return QRCodeWriter().encode(String(data, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** `Data(payload.utf8)` — a text payload goes in as its UTF-8 bytes. */
    fun matrix(text: String, margin: Int = QUIET_ZONE): BitMatrix = matrix(text.toByteArray(Charsets.UTF_8), margin)

    /** The matrix as `scale` px per module — dark modules [fg] on a [bg] ground (black on white, like CIFilter). */
    fun bitmap(matrix: BitMatrix, scale: Int = SCALE, fg: Int = AndroidColor.BLACK, bg: Int = AndroidColor.WHITE): Bitmap {
        val w = matrix.width
        val h = matrix.height
        val px = IntArray(w * scale * h * scale)
        val stride = w * scale
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (matrix[x, y]) fg else bg
                for (dy in 0 until scale) {
                    val row = (y * scale + dy) * stride + x * scale
                    for (dx in 0 until scale) px[row + dx] = c
                }
            }
        }
        return Bitmap.createBitmap(px, stride, h * scale, Bitmap.Config.ARGB_8888)
    }

    /** `QRCode.image(for:)` — null only when the payload is empty (CIFilter returns nil then too). */
    fun bitmap(data: ByteArray, margin: Int = QUIET_ZONE, scale: Int = SCALE): Bitmap? =
        if (data.isEmpty()) null else bitmap(matrix(data, margin), scale)
}

/**
 * The QR as the iOS views draw it: `Image(uiImage:).interpolation(.none).resizable().scaledToFit()`
 * inside `.padding(8).background(Color.white)` — a white 8-dp quiet pad around crisp modules.
 * Size the image with [modifier] (`frame(width:180,height:180)` / `frame(maxWidth: 260)`).
 */
@Composable
fun QrImage(data: ByteArray, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val image = remember(data) { QrCode.bitmap(data)?.asImageBitmap() } ?: return
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        modifier = Modifier.background(Color.White).padding(8.dp).then(modifier),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}
