package com.pyrochat.app.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.pyrochat.mirror.MirrorLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** ZXing generation → ZXing decoding on the JVM, for the three payload kinds the app renders. */
class QrCodeMatrixTest {
    private val identityKey = byteArrayOf(0x05) + ByteArray(32) { (it * 13 + 1).toByte() }
    private val addPayload = AddQrPayload.encode("alice", identityKey)
    private val linkPayload = MirrorLink.encode("TESTPAIRID0000000000AA", ByteArray(32) { it.toByte() }, "relay.pyrochat.ca")

    /** Stand-in for a libsignal scannable fingerprint: a protobuf-ish blob with every byte value. */
    private val scannable = byteArrayOf(0x08, 0x02, 0x12, 0x22, 0x0a, 0x20) + ByteArray(32) { (255 - it * 8).toByte() } +
        byteArrayOf(0x1a, 0x22, 0x0a, 0x20) + ByteArray(32) { (it * 8).toByte() }

    private fun decode(matrix: BitMatrix): Result {
        val scale = 4
        val w = matrix.width * scale
        val h = matrix.height * scale
        val px = IntArray(w * h) { i -> if (matrix[(i % w) / scale, (i / w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
    }

    /** CoreImage writes plain byte mode: the first data codeword nibble is 0100, never an ECI (0111). */
    private fun assertPlainByteMode(r: Result) {
        assertEquals("BYTE mode indicator, no ECI header", 0x4, (r.rawBytes[0].toInt() shr 4) and 0xF)
    }

    @Suppress("UNCHECKED_CAST")
    private fun byteSegments(r: Result): ByteArray =
        (r.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>).fold(ByteArray(0)) { acc, b -> acc + b }

    @Test fun addPayloadRoundTripsAsText() {
        val r = decode(QrCode.matrix(addPayload, margin = 4))
        assertEquals(addPayload, r.text)
        assertPlainByteMode(r)
        assertEquals("M", r.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
        assertArrayEquals("byte mode of the UTF-8 bytes, no ECI", addPayload.toByteArray(Charsets.UTF_8), byteSegments(r))
        val parsed = AddQrPayload.parse(r.text) as AddQrPayload.Parsed.Contact
        assertArrayEquals(identityKey, parsed.identityKey)
    }

    @Test fun nonAsciiUsernameTravelsAsUtf8Bytes() {
        val payload = AddQrPayload.encode("Ünïcødé_7", identityKey)
        val r = decode(QrCode.matrix(payload, margin = 4))
        assertPlainByteMode(r)
        assertArrayEquals(payload.toByteArray(Charsets.UTF_8), byteSegments(r))
        assertEquals("ZXing guesses UTF-8 back from the bytes", payload, r.text)
    }

    @Test fun mirrorLinkRoundTripsAndParses() {
        val r = decode(QrCode.matrix(linkPayload, margin = 4))
        assertEquals(linkPayload, r.text)
        val link = MirrorLink.parse(r.text, "wss://relay.pyrochat.ca/ws")
        assertEquals("TESTPAIRID0000000000AA", link.pairId)
    }

    @Test fun safetyNumberBytesGoInRawAndComeOutRaw() {
        val r = decode(QrCode.matrix(scannable, margin = 4))
        assertPlainByteMode(r)
        assertArrayEquals("the scannable protobuf survives 1:1 (critique §1.7)", scannable, byteSegments(r))
        assertEquals("M", r.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test fun defaultMatrixHasCoreImagesOneModuleQuietZone() {
        val bare = QrCode.matrix(addPayload, margin = 0)
        val def = QrCode.matrix(addPayload)
        assertEquals(bare.width + 2, def.width)
        assertEquals(def.width, def.height)
        assertTrue("version ≥ 1 is 21+ modules", bare.width >= 21 && (bare.width - 21) % 4 == 0)
        // the quiet zone really is blank
        for (i in 0 until def.width) {
            assertTrue(!def[i, 0] && !def[i, def.height - 1] && !def[0, i] && !def[def.width - 1, i])
        }
    }

    @Test fun keyInAddPayloadIsThe33ByteSerializedIdentityKey() {
        val parts = addPayload.split(":")
        assertEquals(4, parts.size)
        val key = Base64.getUrlDecoder().decode(parts[3])
        assertEquals(33, key.size)
        assertEquals(0x05, key[0].toInt())
    }
}
