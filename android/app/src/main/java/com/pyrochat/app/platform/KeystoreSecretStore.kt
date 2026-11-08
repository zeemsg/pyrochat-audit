package com.pyrochat.app.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.pyrochat.store.SecretStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android counterpart of `KeychainHelper` (`ios/PyroChat/Core/KeychainHelper.swift`): the two raw
 * secrets that must live OUTSIDE the password-encrypted store — `kdf-salt` and `duress-verifier`
 * (`SecretStore.KDF_SALT`, `SecretStore.DURESS_VERIFIER`) — plus the same `set` / `get` /
 * `delete` / `wipeAll` surface.
 *
 * Each value is sealed with AES-256-GCM under a key that lives in the Android Keystore and is
 * never exportable; the ciphertext (`version(1) ‖ iv(12) ‖ ciphertext ‖ tag(16)`, item name as
 * AAD so a file can't be swapped for another item's) sits in one app-private file per item under
 * `<no_backup>/secrets/`.
 *
 * ### Equivalence to `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (KeychainHelper.swift:19)
 * - **WhenUnlocked** → [KeyGenParameterSpec.Builder.setUnlockedDeviceRequired] `(true)`: the
 *   Keystore refuses to use the key while the device is locked behind a secure lock screen, so
 *   the items are unreadable until the user unlocks — exactly when iOS lets `SecItemCopyMatching`
 *   through. `setUserAuthenticationRequired(false)`: like the iOS item, no biometric/PIN prompt
 *   is tied to the item itself (the store password is the authentication).
 * - **ThisDeviceOnly** → Keystore key material never leaves the secure hardware (TEE, or StrongBox
 *   when the device has it) and is never included in any backup or device-to-device transfer;
 *   the ciphertext files sit in `Context.getNoBackupFilesDir()` on top of the manifest's
 *   `allowBackup=false` + `dataExtractionRules` excluding everything. Even if a file were copied
 *   elsewhere it is useless without the key.
 * - **No iCloud sync** → Android has no Keystore sync at all.
 * - **Deviation (documented, accepted)**: an iOS Keychain item survives app uninstall; a Keystore
 *   key and the `no_backup` dir are deleted on uninstall / clear-data. PyroChat has no recovery
 *   path by design (the store file is deleted alongside), so nothing is lost that was usable.
 *
 * StrongBox is requested first on devices that advertise it and silently falls back to the TEE
 * key ([StrongBoxUnavailableException]); the key is created on first use and recreated after
 * [wipeAll]. Thread-safe (every operation is serialised on the instance).
 */
class KeystoreSecretStore(
    context: Context,
    private val alias: String = DEFAULT_ALIAS,
    private val dir: File = File(context.noBackupFilesDir, "secrets"),
    private val unlockedDeviceRequired: Boolean = true,
) : SecretStore {
    private val appContext = context.applicationContext

    @Synchronized
    override fun get(name: String): ByteArray? {
        val file = fileFor(name)
        if (!file.isFile) return null
        val sealed = try {
            file.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "secret $name unreadable", e)
            return null
        }
        return try {
            open(name, sealed)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "secret $name does not decrypt (Keystore key gone or file corrupt)", e)
            null
        } catch (e: ProviderException) {
            Log.w(TAG, "Keystore refused to decrypt $name", e)
            null
        }
    }

    /** iOS `set` = delete-then-add; here an atomic overwrite (temp file + rename). */
    @Synchronized
    override fun set(name: String, value: ByteArray) {
        val file = fileFor(name)
        dir.mkdirs()
        val sealed = seal(name, value)
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not write secret $name")
        }
    }

    @Synchronized
    override fun delete(name: String) {
        fileFor(name).delete()
    }

    /** Panic wipe (`KeychainHelper.wipeAll`): every item file AND the wrapping key itself. */
    @Synchronized
    override fun wipeAll() {
        dir.listFiles()?.forEach { it.delete() }
        try {
            keyStore().deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "could not delete Keystore key", e)
        }
    }

    /** Where the wrapping key lives — for diagnostics (`KeyInfo.securityLevel`). */
    val isStrongBoxBacked: Boolean
        @Synchronized get() = try {
            val key = key()
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware && appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            }
        } catch (_: GeneralSecurityException) {
            false
        }

    // ---- crypto ------------------------------------------------------------------

    private fun seal(name: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())          // Keystore picks the random IV
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val body = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + iv + body
    }

    private fun open(name: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) {
            throw GeneralSecurityException("bad secret file layout")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = sealed.copyOfRange(1, 1 + IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                return generateKey(strongBox = true, unlockedRequired = unlockedDeviceRequired)
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox advertised but unavailable, falling back to TEE", e)
            } catch (e: ProviderException) {
                Log.i(TAG, "StrongBox key generation failed, falling back to TEE", e)
            }
        }
        return try {
            generateKey(strongBox = false, unlockedRequired = unlockedDeviceRequired)
        } catch (e: ProviderException) {
            if (!unlockedDeviceRequired) throw e
            // Some OEM keymasters reject UNLOCKED_DEVICE_REQUIRED outright; a usable key beats none.
            Log.w(TAG, "keymaster rejected unlockedDeviceRequired, generating without it", e)
            generateKey(strongBox = false, unlockedRequired = false)
        }
    }

    private fun generateKey(strongBox: Boolean, unlockedRequired: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setUnlockedDeviceRequired(unlockedRequired)
            spec.setIsStrongBoxBacked(strongBox)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun fileFor(name: String): File {
        require(NAME.matches(name)) { "secret name must match ${NAME.pattern}" }
        return File(dir, name)
    }

    companion object {
        private const val TAG = "KeystoreSecretStore"
        const val DEFAULT_ALIAS = "com.pyrochat.app.secrets.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
