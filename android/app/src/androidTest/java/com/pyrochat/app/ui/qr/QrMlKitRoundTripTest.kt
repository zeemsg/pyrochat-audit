package com.pyrochat.app.ui.qr

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.pyrochat.mirror.MirrorLink
import com.pyrochat.service.SafetyNumbers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.concurrent.TimeUnit

/**
 * The ML Kit decoder the camera analyzer uses, fed the bitmaps [QrCode] renders — the same
 * bundled model, offline. One test per payload kind the app shows: add-me contact code, browser
 * mirror link, and a REAL libsignal scannable fingerprint (raw protobuf bytes).
 */
@RunWith(AndroidJUnit4::class)
class QrMlKitRoundTripTest {
    private val identity = IdentityKeyPair.generate()
    private val peer = IdentityKeyPair.generate()

    private fun decode(bitmap: Bitmap): List<Barcode> {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        try {
            return Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
        } finally {
            scanner.close()
        }
    }

    private fun single(payload: ByteArray): Barcode {
        val bitmap = QrCode.bitmap(payload)!!
        val codes = decode(bitmap)
        assertEquals("exactly one QR in the rendered bitmap", 1, codes.size)
        assertEquals(Barcode.FORMAT_QR_CODE, codes[0].format)
        return codes[0]
    }

    @Test fun addPayloadDecodesToTheExactString() {
        val payload = AddQrPayload.encode("alice_qr", identity.publicKey.serialize())
        val code = single(payload.toByteArray(Charsets.UTF_8))
        assertEquals(payload, code.rawValue)
        val parsed = AddQrPayload.parse(code.rawValue!!) as AddQrPayload.Parsed.Contact
        assertEquals("alice_qr", parsed.username)
        assertArrayEquals(identity.publicKey.serialize(), parsed.identityKey)
        assertEquals(33, parsed.identityKey.size)
    }

    @Test fun mirrorLinkDecodesAndParsesAgainstThePhoneRelay() {
        val payload = MirrorLink.encode("TESTPAIRID0000000000AA", ByteArray(32) { (it * 3).toByte() }, "10.0.2.2:7444")
        val code = single(payload.toByteArray(Charsets.UTF_8))
        assertEquals(payload, code.rawValue)
        val link = MirrorLink.parse(code.rawValue!!, "ws://10.0.2.2:7444/ws")
        assertEquals("TESTPAIRID0000000000AA", link.pairId)
        assertTrue(LinkQrPayload.isPyroLink(code.rawValue!!))
    }

    @Test fun safetyNumberScannableBytesDecode() {
        val sn = SafetyNumbers.compute("a".repeat(64), identity.serialize(), "b".repeat(64), peer.publicKey.serialize())
        assertEquals(60, sn.digits.length)
        val code = single(sn.scannable)
        val raw = code.rawBytes
        assertNotNull("ML Kit exposes the raw bytes of a byte-mode QR", raw)
        Log.i("QrMlKitRoundTripTest", "scannable=${sn.scannable.size} B rawBytes=${raw!!.size} B value=${code.rawValue?.length}")
        // ML Kit's rawBytes for a byte-mode QR are the payload itself (74 B in, 74 B out) — the
        // scannable protobuf comes back unchanged, so a future scan-verify can feed it to libsignal
        assertArrayEquals("scannable fingerprint bytes survive the QR 1:1", sn.scannable, raw)
    }
}
