package com.pyrochat.app.platform

import android.content.Context
import com.pyrochat.store.SecretStore
import com.pyrochat.store.SecureStore
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.model.Blob
import java.io.File
import java.nio.file.Path

/**
 * Where the store lives on Android and how the app opens it.
 *
 * iOS keeps `pyro.store` + `attachments/` in `Application Support` (`SecureStore.swift`); here they
 * sit in [Context.getFilesDir] — internal, app-private storage, never external. Backup/transfer is
 * already disabled app-wide (manifest `allowBackup=false` + `dataExtractionRules`), and the
 * Keystore-sealed secrets additionally sit in [Context.getNoBackupFilesDir]. The files are in
 * credential-encrypted storage, so — like iOS `completeFileProtection` — they are unavailable
 * before the first unlock after boot; at-rest secrecy beyond that is the AES-GCM layer itself.
 *
 * Plaintext ever written to disk (voice temp files, waveform scratch, export/preview staging) goes
 * to [Context.getCacheDir] under the same iOS prefixes (`vn-`, `waveform-`, `export-`, `preview-`)
 * so [sweepPlaintextTmp] mirrors `SecureStore.sweepPlaintextTmp` (launch / lock / wipe).
 */
class StorePaths(
    context: Context,
    /** Internal storage root; tests point this at a scratch dir. */
    val filesDir: File = context.filesDir,
    val secretsDir: File = File(context.noBackupFilesDir, "secrets"),
    secretsAlias: String = KeystoreSecretStore.DEFAULT_ALIAS,
) {
    private val appContext = context.applicationContext

    val storeFile: Path = File(filesDir, SecureStore.FILE_NAME).toPath()
    val attachmentsDir: Path = SecureStore.attachmentsDir(storeFile)
    /** Scratch for plaintext temp files (iOS `FileManager.temporaryDirectory`). */
    val tmpDir: File = appContext.cacheDir

    /** `KeychainHelper` — Keystore-wrapped items, created lazily. */
    val secrets: SecretStore by lazy { KeystoreSecretStore(appContext, alias = secretsAlias, dir = secretsDir) }
    val kdf: Kdf get() = NativeKdfAdapter

    /** `SecureStore.storeExists` */
    fun storeExists(): Boolean = SecureStore.exists(storeFile)

    /**
     * docs/DECOY-ACCOUNT.md §5 — the all-noise container every device has from first run, and that
     * no path ever deletes. Idempotent, and a no-op on a device that already has one (or a
     * pre-container store, which §7 migrates instead).
     */
    fun ensureContainer(): Boolean = SecureStore.ensureContainer(storeFile)

    /** Registration (`SecureStore.create`) — [knownFree] per §1: slots the caller KNOWS are free. */
    fun create(password: String, knownFree: List<Int>? = null, blind: Boolean = false): SecureStore =
        SecureStore.create(storeFile, password, secrets, kdf, knownFree = knownFree, blind = blind)

    /**
     * docs/DECOY-ACCOUNT.md §3 — the key a password would open a slot with, WITHOUT opening
     * anything. The one use is rejecting a decoy password equal to the current account's: the
     * comparison is between two derived keys, so the password itself is never held beside another.
     */
    fun deriveKey(password: String): ByteArray = SecureStore.deriveKey(kdf, password, SecureStore.saltForRead(secrets))

    /**
     * Unlock (`SecureStore.unlockingWithPassword`) — throws the `StoreError` family. This is the
     * one caller that opts into the one-way container migration of docs/DECOY-ACCOUNT.md §7: a
     * pre-container `pyro.store` is rewritten, verified, and replaced on first successful unlock.
     */
    fun open(password: String): SecureStore = SecureStore.open(storeFile, password, secrets, kdf, migrateLegacy = true)

    /** docs/BIOMETRIC-UNLOCK.md §3: biometry unwrapped the key — open the store with it directly. */
    fun openWithKey(key: ByteArray): SecureStore = SecureStore.openWithKey(storeFile, key, migrateLegacy = true)

    /** Backup restore (`SecureStore.restore`). */
    fun restore(blob: Blob, attachments: Map<String, ByteArray>, password: String, knownFree: List<Int>? = null, blind: Boolean = false): SecureStore =
        SecureStore.restore(storeFile, blob, attachments, password, secrets, kdf, knownFree = knownFree, blind = blind)

    fun setDuressPin(pin: String) = SecureStore.setDuressPin(secrets, kdf, pin)

    fun matchesDuressPin(candidate: String): Boolean = SecureStore.matchesDuressPin(secrets, kdf, candidate)

    fun duressPinSet(): Boolean = secrets.get(SecretStore.DURESS_VERIFIER) != null

    /** Panic wipe: store, sidecars, secrets (+ Keystore key), plaintext tmp. */
    fun obliterate() {
        SecureStore.obliterate(storeFile, secrets)
        sweepPlaintextTmp()
    }

    /** Delete every decrypted temp copy a crashed session may have left (`SecureStore.swift:226-236`). */
    fun sweepPlaintextTmp() {
        tmpDir.listFiles()?.forEach { entry ->
            if (PLAINTEXT_PREFIXES.any { entry.name.startsWith(it) }) entry.deleteRecursively()
        }
    }

    companion object {
        // `scrub-` is docs/SCRUB-AND-WIPE.md §B1's staging dir: the copy the scrubber works on
        val PLAINTEXT_PREFIXES = listOf("export-", "preview-", "waveform-", "vn-", "scrub-")
    }
}
