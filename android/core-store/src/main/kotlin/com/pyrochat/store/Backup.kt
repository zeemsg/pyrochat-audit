package com.pyrochat.store

import com.pyrochat.store.codec.SwiftDateSerializer
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant

/**
 * PYROBAK1 device-migration backups, byte-for-byte as `ios/PyroChat/Core/BackupService.swift`
 * (`android/fixtures/ios-store/backup.README.md`):
 *
 * ```
 * "PYROBAK1"(8) ‖ salt(32, random per export) ‖ nonce(12) ‖ AES-256-GCM(JSON Payload) ‖ tag(16)
 * key = PBKDF2-HMAC-SHA256(passphrase, salt, 600 000, 32)
 * Payload = {"blob": Blob, "attachments": {id: base64 PLAINTEXT}, "exportedAt": Date}
 * ```
 *
 * The Keychain salt is not involved — the file is self-contained. Since docs/DECOY-ACCOUNT.md §1a
 * voice notes are sidecars like images and files, so they ride in `attachments` with everything
 * else; a backup written before that carries its audio inline in `blob.voiceData` and is spilled
 * into sidecars by [SecureStore.restore] as it lands.
 */
object Backup {
    val MAGIC: ByteArray = "PYROBAK1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 8 + StoreCrypto.SALT_BYTES

    /** `BackupService.Payload` (`BackupService.swift:29-33`) — every key required on decode. */
    @Serializable
    data class Payload(
        val blob: Blob,
        val attachments: Map<String, Bytes>,
        @Serializable(with = SwiftDateSerializer::class)
        val exportedAt: Instant,
    )

    /** `BackupService.export(from:passphrase:)`: the live blob plus every decryptable UUID-named sidecar. */
    fun export(
        store: SecureStore,
        passphrase: String,
        kdf: Kdf = Pbkdf2HmacSha256,
        random: SecureRandom = SecureRandom(),
        exportedAt: Instant = Instant.now(),
    ): ByteArray {
        val attachments = LinkedHashMap<String, Bytes>()
        for (id in store.attachmentIds()) {
            store.readAttachment(id)?.let { attachments[id] = Bytes(it) }   // undecryptable sidecars are skipped
        }
        return seal(Payload(store.blob, attachments, exportedAt), passphrase, kdf, random)
    }

    /** Seals an explicit payload — the export primitive, useful when the blob is not in a [SecureStore]. */
    fun seal(payload: Payload, passphrase: String, kdf: Kdf = Pbkdf2HmacSha256, random: SecureRandom = SecureRandom()): ByteArray {
        val plain = SwiftJson.encode(payload).toByteArray(Charsets.UTF_8)
        val salt = StoreCrypto.randomBytes(StoreCrypto.SALT_BYTES, random)
        val key = StoreCrypto.deriveKey(kdf, passphrase, salt)
        return MAGIC + salt + StoreCrypto.seal(key, plain, random)
    }

    /**
     * `BackupService.decode(_:passphrase:)` (`BackupService.swift:62-74`): [StoreError.BadBackupFile]
     * unless `count > 40` and the magic matches, [StoreError.BadBackupPassphrase] when GCM fails,
     * [StoreError.Corrupt] when the payload does not decode.
     */
    fun decode(bytes: ByteArray, passphrase: String, kdf: Kdf = Pbkdf2HmacSha256): Payload {
        if (bytes.size <= HEADER_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw StoreError.BadBackupFile()
        }
        val salt = bytes.copyOfRange(MAGIC.size, HEADER_BYTES)
        val sealed = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        val key = StoreCrypto.deriveKey(kdf, passphrase, salt)
        val plain = try {
            StoreCrypto.open(key, sealed)
        } catch (_: GeneralSecurityException) {
            throw StoreError.BadBackupPassphrase()
        }
        return try {
            SwiftJson.decode<Payload>(plain.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw StoreError.Corrupt(e)
        } catch (e: IllegalArgumentException) {
            throw StoreError.Corrupt(e)
        }
    }
}
