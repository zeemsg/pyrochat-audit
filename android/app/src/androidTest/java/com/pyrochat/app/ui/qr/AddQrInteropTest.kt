package com.pyrochat.app.ui.qr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.app.platform.StorePaths
import com.pyrochat.service.ChatService
import com.pyrochat.store.model.Bytes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File

/**
 * MY CODE interop: what the REAL service layer puts in the QR (`ChatService.myAddQRPayload`, the
 * string iOS `AddByQRView` scans) is byte-for-byte the documented
 * `pyro-add:v1:<usernameDisplay>:<b64url-nopad(identityKey.serialize(), 33 B)>` and parses back
 * through the screen's [AddQrPayload]. No relay involved — a store with a generated identity.
 */
@RunWith(AndroidJUnit4::class)
class AddQrInteropTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "qr-interop-test")
    private val paths = StorePaths(
        context,
        filesDir = root,
        secretsDir = File(context.noBackupFilesDir, "secrets-qr-interop-test"),
        secretsAlias = "com.pyrochat.app.secrets.qr-interop-test",
    )

    @Before fun clean() {
        paths.obliterate()
        root.deleteRecursively()
        root.mkdirs()
    }

    @After fun wipe() {
        paths.obliterate()
        root.deleteRecursively()
    }

    @Test fun serviceMyCodeMatchesTheDocumentedFormatByteForByte() {
        val identity = IdentityKeyPair.generate()
        val store = paths.create("qr-interop-pass-1234")
        store.update { it.copy(usernameDisplay = "Qr_Alice", usernameHash = "0".repeat(64), identityKeyPair = Bytes(identity.serialize())) }
        val payload = ChatService(store).use { it.myAddQRPayload() }
        store.close()

        val expected = AddQrPayload.encode("Qr_Alice", identity.publicKey.serialize())
        assertEquals(expected, payload)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), payload!!.toByteArray(Charsets.UTF_8))
        assertTrue(Regex("^pyro-add:v1:Qr_Alice:[A-Za-z0-9_-]{44}$").matches(payload))

        val parsed = AddQrPayload.parse(payload) as AddQrPayload.Parsed.Contact
        assertEquals("Qr_Alice", parsed.username)
        assertArrayEquals("33-byte IdentityKey.serialize(): 0x05 ‖ point", identity.publicKey.serialize(), parsed.identityKey)
        assertEquals(33, parsed.identityKey.size)
        assertEquals(0x05, parsed.identityKey[0].toInt())
    }
}
