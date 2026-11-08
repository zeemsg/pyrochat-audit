package com.pyrochat.app.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@RunWith(AndroidJUnit4::class)
class NativeKdfAdapterTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun equalsJcaAt600k() {
        val salt = ByteArray(32) { it.toByte() }
        val pw = "correct horse battery staple"
        val jca = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, StoreCrypto.PBKDF2_ITERATIONS, 256)).encoded
        val ours = NativeKdfAdapter.derive(pw.toByteArray(), salt, StoreCrypto.PBKDF2_ITERATIONS, 32)
        assertEquals(hex(jca), hex(ours))
        assertTrue("native library should have served the call", NativeKdfAdapter.usesNative)
    }

    @Test fun equalsThePureJvmFallbackOnRawNonUtf8Bytes() {
        val pw = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x41, 0xc3.toByte())   // not valid UTF-8: only a raw-bytes KDF can match iOS here
        val salt = ByteArray(32) { (255 - it).toByte() }
        assertArrayEquals(Pbkdf2HmacSha256.derive(pw, salt, 1000, 48), NativeKdfAdapter.derive(pw, salt, 1000, 48))
    }

    @Test fun reproducesTheIosKeychainFixture() {
        // android/fixtures/ios-store/keychain.json: duress-verifier = PBKDF2(pin "2580", kdf-salt, 600000, 32)
        val salt = unhex("00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f")
        val verifier = StoreCrypto.deriveKey(NativeKdfAdapter, "2580", salt)
        assertEquals("d3a84e0385e1f81e2b205b81dbba289a8d90a8a252666a712035ae80e4377d34", hex(verifier))
    }
}
