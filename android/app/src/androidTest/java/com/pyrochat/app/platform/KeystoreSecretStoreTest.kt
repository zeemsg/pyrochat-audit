package com.pyrochat.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pyrochat.store.SecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.noBackupFilesDir, "secrets-test")
    private val alias = "com.pyrochat.app.secrets.test"

    private fun store() = KeystoreSecretStore(context, alias = alias, dir = dir)

    @Before fun clean() { store().wipeAll() }

    @Test fun roundTripBothIosItems() {
        val s = store()
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }
        s.set(SecretStore.KDF_SALT, salt)
        s.set(SecretStore.DURESS_VERIFIER, verifier)
        assertArrayEquals(salt, s.get(SecretStore.KDF_SALT))
        assertArrayEquals(verifier, s.get(SecretStore.DURESS_VERIFIER))
        assertNull(s.get("nothing-here"))
        s.delete(SecretStore.DURESS_VERIFIER)
        assertNull(s.get(SecretStore.DURESS_VERIFIER))
        assertArrayEquals(salt, s.get(SecretStore.KDF_SALT))
    }

    @Test fun setOverwritesLikeDeleteThenAdd() {
        val s = store()
        s.set(SecretStore.KDF_SALT, ByteArray(32) { 1 })
        s.set(SecretStore.KDF_SALT, ByteArray(32) { 2 })
        assertArrayEquals(ByteArray(32) { 2 }, s.get(SecretStore.KDF_SALT))
        assertEquals(1, dir.listFiles()!!.count { it.name == SecretStore.KDF_SALT })
    }

    @Test fun ciphertextOnDiskIsNotThePlaintextAndIsBoundToItsName() {
        val s = store()
        val salt = ByteArray(32) { (it * 7).toByte() }
        s.set(SecretStore.KDF_SALT, salt)
        val onDisk = File(dir, SecretStore.KDF_SALT).readBytes()
        assertEquals(1 + 12 + 32 + 16, onDisk.size)
        assertFalse(onDisk.toList().windowed(32).any { it.toByteArray().contentEquals(salt) })
        // the item name is GCM AAD: the same file under another name must not open
        File(dir, SecretStore.DURESS_VERIFIER).writeBytes(onDisk)
        assertNull(s.get(SecretStore.DURESS_VERIFIER))
        assertArrayEquals(salt, s.get(SecretStore.KDF_SALT))
    }

    @Test fun aFreshInstanceReadsWhatAnotherWrote() {
        val salt = ByteArray(32) { (it + 100).toByte() }
        store().set(SecretStore.KDF_SALT, salt)
        // new object → new KeyStore lookup, new Cipher; only the alias + file survive
        assertArrayEquals(salt, store().get(SecretStore.KDF_SALT))
    }

    @Test fun wipeAllRemovesEverythingAndTheKeyRegenerates() {
        val s = store()
        s.set(SecretStore.KDF_SALT, ByteArray(32) { 9 })
        s.set(SecretStore.DURESS_VERIFIER, ByteArray(32) { 8 })
        s.wipeAll()
        assertNull(s.get(SecretStore.KDF_SALT))
        assertNull(s.get(SecretStore.DURESS_VERIFIER))
        assertTrue(dir.listFiles().isNullOrEmpty())
        s.set(SecretStore.KDF_SALT, ByteArray(32) { 3 })   // works again under a new key
        assertArrayEquals(ByteArray(32) { 3 }, store().get(SecretStore.KDF_SALT))
    }

    @Test fun tamperedFileReadsAsMissing() {
        val s = store()
        s.set(SecretStore.KDF_SALT, ByteArray(32) { 5 })
        val f = File(dir, SecretStore.KDF_SALT)
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        f.writeBytes(bytes)
        assertNull(s.get(SecretStore.KDF_SALT))
    }

    @Test fun rejectsUnsafeNames() {
        val s = store()
        for (bad in listOf("", "../x", "a/b", ".hidden", "x".repeat(65))) {
            try {
                s.set(bad, ByteArray(1)); throw AssertionError("accepted $bad")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun strongBoxFlagIsQueryableWithoutThrowing() {
        val s = store()
        s.set(SecretStore.KDF_SALT, ByteArray(32))
        assertNotNull(s.isStrongBoxBacked)   // false on the emulator, true on StrongBox phones
    }
}

/**
 * Process-restart proof: every `am instrument` run is a fresh process. The first run writes a
 * fixed value under its own dir + alias; every later run finds it, decrypts it with the key that
 * only the Keystore still holds, and compares. (Run the connected suite twice to see the second
 * branch execute — see the report.)
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreRestartTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = KeystoreSecretStore(context, alias = "com.pyrochat.app.secrets.restart-probe", dir = File(context.noBackupFilesDir, "secrets-restart-probe"))
    private val probe = "restart-probe".toByteArray().copyOf(32)

    @Test fun survivesProcessRestart() {
        val existing = store.get(SecretStore.KDF_SALT)
        if (existing == null) {
            store.set(SecretStore.KDF_SALT, probe)
            assertArrayEquals(probe, store.get(SecretStore.KDF_SALT))
            android.util.Log.i("KeystoreRestart", "first run: probe written; a second instrumentation run verifies it")
        } else {
            assertArrayEquals("value written by an earlier process must decrypt unchanged", probe, existing)
            android.util.Log.i("KeystoreRestart", "second run: probe from a previous process decrypted OK")
        }
    }
}
