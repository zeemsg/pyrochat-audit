package com.pyrochat.kdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@RunWith(AndroidJUnit4::class)
class NativeKdfTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun rfc7914Vectors() {
        // RFC 7914 §11 PBKDF2-HMAC-SHA-256 test vectors
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(NativeKdf.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)))
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(NativeKdf.pbkdf2("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)))
    }

    @Test fun matchesJcaAtStoreParameters() {
        val salt = ByteArray(32) { it.toByte() }
        val pw = "correct horse battery staple"
        val jca = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 600_000, 256)).encoded
        assertEquals(hex(jca), hex(NativeKdf.pbkdf2(pw.toByteArray(), salt, 600_000, 32)))
    }
}
