package com.pyrochat.app.platform

import com.pyrochat.store.InMemorySecretStore
import com.pyrochat.store.SecureStore
import com.pyrochat.store.StoreError
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.crypto.StoreCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * docs/BIOMETRIC-UNLOCK.md §1/§3 end to end, on a real `pyro.store`: "the password remains the only
 * thing that derives the store key; biometry merely unwraps a copy of that key". The password is
 * typed once, the 32 bytes it derives go into the vault, and the next open uses ONLY those bytes.
 */
class BiometricStoreKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 600 000 PBKDF2 rounds are not the subject here (`LiveTestSupport.FastKdf`). */
    private object FastKdf : Kdf {
        override fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password + salt).copyOf(keyLen)
    }

    /** The same in-JVM stand-in for the Keystore that [BiometricVaultTest] uses. */
    private class FakeKeys : BiometricKeyBox {
        private var key: SecretKey? = null
        override fun freshEncryptCipher(): Cipher {
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = fresh
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
        }
        override fun decryptCipher(iv: ByteArray): Cipher {
            val k = key ?: throw BiometricInvalidated("gone")
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        }
        override fun delete() { key = null }
    }

    private class RamStore : BiometricStore {
        override var wrapped: ByteArray? = null
        override var lastPasswordUnlock: Long? = null
        override var failures: Int = 0
        override var unlockFailures: Int = 0
        override fun clearKey() { wrapped = null; failures = 0 }
        override fun clearAll() { wrapped = null; lastPasswordUnlock = null; failures = 0; unlockFailures = 0 }
    }

    private val secrets = InMemorySecretStore()
    private val password = "hunter2hunter2"

    @Test fun theStoreKeyIsThirtyTwoBytesAndOpensTheStoreOnItsOwn() {
        val file = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        val key: ByteArray
        SecureStore.create(file, password, secrets, FastKdf).use { store ->
            store.update { it.copy(usernameDisplay = "vault_probe") }
            store.flush()
            key = store.storeKey
        }
        assertEquals(StoreCrypto.KEY_BYTES, key.size)
        SecureStore.openWithKey(file, key).use { cold ->
            assertEquals("vault_probe", cold.blob.usernameDisplay)
        }
    }

    @Test fun theWrongKeyIsRefusedLikeTheWrongPassword() {
        val file = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        SecureStore.create(file, password, secrets, FastKdf).use { it.flush() }
        assertThrows(StoreError.BadPassword::class.java) {
            SecureStore.openWithKey(file, ByteArray(StoreCrypto.KEY_BYTES) { 7 })
        }
    }

    @Test fun shortAndLongKeysAreRefusedOutright() {
        val file = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        SecureStore.create(file, password, secrets, FastKdf).use { it.flush() }
        assertThrows(IllegalArgumentException::class.java) { SecureStore.openWithKey(file, ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { SecureStore.openWithKey(file, ByteArray(64)) }
    }

    @Test fun passwordUnlockThenBiometricUnlockReachTheSameStore() {
        val file = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        val vault = BiometricVault(FakeKeys(), RamStore())

        // 1. the password unlock — the only thing that ever derives the key
        SecureStore.create(file, password, secrets, FastKdf).use { store ->
            store.update { it.copy(usernameDisplay = "viewer_user") }
            store.flush()
            vault.notePasswordUnlock()
            // 2. the user turns the toggle on: THAT key, and nothing else, goes into the vault
            val key = store.storeKey
            vault.finishEnable(vault.beginEnable(), key)
            key.fill(0)
        }

        // 3. reopening: biometry unwraps the copy, the store opens, no password anywhere
        val unwrapped = vault.finishUnlock(vault.beginUnlock())
        assertEquals(StoreCrypto.KEY_BYTES, unwrapped.size)
        SecureStore.openWithKey(file, unwrapped).use { cold ->
            assertEquals("viewer_user", cold.blob.usernameDisplay)
            assertArrayEquals(unwrapped, cold.storeKey)
        }
    }

    @Test fun theStoredCopyIsUselessOnceTheVaultIsShredded() {
        val file = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        val vault = BiometricVault(FakeKeys(), RamStore())
        SecureStore.create(file, password, secrets, FastKdf).use { store ->
            store.flush()
            vault.finishEnable(vault.beginEnable(), store.storeKey)
        }
        vault.shred()
        assertThrows(BiometricInvalidated::class.java) { vault.beginUnlock() }
    }

    @Test fun twoPasswordsDeriveTwoDifferentStoreKeys() {
        val a = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        val b = tmp.newFolder().toPath().resolve(SecureStore.FILE_NAME)
        val keyA = SecureStore.create(a, password, secrets, FastKdf).use { it.storeKey }
        val keyB = SecureStore.create(b, "a different password", secrets, FastKdf).use { it.storeKey }
        assertNotEquals(keyA.toList(), keyB.toList())
    }
}
