package com.pyrochat.store

import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Bytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The milestone-0 gate, reverse direction. Decodes the real iOS fixture, re-encodes it with THIS
 * codec, seals it with a fresh nonce under the same password + Keychain salt, re-exports the
 * PYROBAK1 backup, and writes everything to `android/fixtures/android-store/` (override with
 * `PYRO_ANDROID_FIXTURE_DIR`) for `ios/Tests/FixtureImportTests.swift` to open through the real
 * iOS `SecureStore` and `BackupService`. Everything goes through the production APIs
 * (`SecureStore.restore`, `Backup.export`, real PBKDF2), and Android re-reads its own output
 * through the same doors iOS will use before the manifest is written.
 */
class AndroidFixtureWriterTest {

    @Test fun writesAnAndroidEncodedStoreAndBackupForIosToImport() {
        val out = Fixtures.androidDir
        if (Files.exists(out)) out.toFile().deleteRecursively()
        Files.createDirectories(out)

        val ios = Fixtures.iosStore
        // docs/DECOY-ACCOUNT.md §1a: the fixture's inline voice notes become sidecars, like every
        // other attachment, BEFORE anything is sealed into a container.
        val spill = VoiceSpill.plan(ios.blob)
        val blob = spill.blob
        val attachments = ios.attachmentIds().associateWith { ios.readAttachment(it)!! } + spill.sidecars
        assertEquals(2, spill.sidecars.size, "the fixture holds two voice notes")
        assertEquals(6, attachments.size, "4 image/file sidecars + 2 voice notes")

        // --- pyro.store + attachments/ through SecureStore.restore (real KDF, pinned salt, fresh nonces)
        val file = out.resolve(SecureStore.FILE_NAME)
        val store = SecureStore.restore(file, blob, attachments, Fixtures.password, Fixtures.secrets())
        val bak = Backup.export(store, Fixtures.backupPassphrase)
        // docs/DECOY-ACCOUNT.md §1: the sealed plaintext carries the slot record the authoring
        // path stamped, so the fixture's plaintext must be the STORE's blob, not the input's
        val sealedBlob = store.blob
        store.close()
        Files.write(out.resolve("backup.pyrobak"), bak)
        val plaintext = SwiftJson.encodeBlob(sealedBlob)
        Files.write(out.resolve("pyro.store.plaintext.json"), plaintext)
        Files.copy(Fixtures.iosDir.resolve("session-probe.json"), out.resolve("session-probe.json"), StandardCopyOption.REPLACE_EXISTING)

        // --- Android reads its own output
        val storeBytes = Files.readAllBytes(file)
        assertEquals(StoreContainer.FILE_BYTES, storeBytes.size)
        val opened = StoreContainer.open(Fixtures.storeKey, storeBytes)!!
        assertContentEquals(plaintext, opened.json)
        SecureStore.open(file, Fixtures.password, Fixtures.secrets()).use { reopened ->
            assertEquals(blob, reopened.blob.withoutSlotRecord())
            assertEquals(attachments.keys.sorted(), reopened.attachmentIds())
            for ((id, bytes) in attachments) assertContentEquals(bytes, reopened.readAttachment(id), id)
        }
        val payload = Backup.decode(bak, Fixtures.backupPassphrase)
        assertEquals(blob, payload.blob.withoutSlotRecord())
        assertEquals(attachments.mapValues { Bytes(it.value) }, payload.attachments)
        assertTrue(bak.size > 40 + 12 + plaintext.size + 16, "the backup carries the blob plus the attachments")

        // --- manifest.json
        fun info(bytes: ByteArray): JsonObject = buildJsonObject {
            put("bytes", bytes.size)
            put("sha256", Fixtures.sha256Hex(bytes))
        }
        val files = LinkedHashMap<String, JsonElement>()
        for (id in attachments.keys.sorted()) {
            val sealed = Files.readAllBytes(out.resolve(SecureStore.ATTACHMENTS_DIR).resolve(id))
            files["attachments/$id"] = buildJsonObject {
                put("plaintextBytes", attachments.getValue(id).size)
                put("plaintextSha256", Fixtures.sha256Hex(attachments.getValue(id)))
                put("sealedBytes", sealed.size)
                put("sealedSha256", Fixtures.sha256Hex(sealed))
            }
        }
        files["backup.pyrobak"] = info(bak)
        files["pyro.store"] = info(storeBytes)
        files["pyro.store.plaintext.json"] = info(plaintext)
        val iosManifest = Fixtures.manifest
        val manifest = buildJsonObject {
            put("attachmentIds", iosManifest.obj("attachmentIds"))
            put("backup", buildJsonObject {
                put("exportedAt", SwiftDate.toSeconds(payload.exportedAt))
                put("file", "backup.pyrobak")
                put("iterations", StoreCrypto.PBKDF2_ITERATIONS)
                put("kdf", "PBKDF2-HMAC-SHA256")
                put("keyBytes", StoreCrypto.KEY_BYTES)
                put("layout", "magic(8) || salt(32) || nonce(12) || ciphertext || tag(16)")
                put("magic", "PYROBAK1")
                put("passphrase", Fixtures.backupPassphrase)
                put("payload", "JSON {blob: <same Blob as the store>, attachments: {id: base64 plaintext}, exportedAt: Date}")
                put("saltHex", Fixtures.toHex(bak.copyOfRange(8, 40)))
            })
            put("counts", iosManifest.obj("counts"))
            put("files", JsonObject(files))
            put("fixedValues", iosManifest.obj("fixedValues"))
            put("generatedAt", Instant.now().toString())
            put("generatedBy", "android/core-store/src/test/kotlin/com/pyrochat/store/AndroidFixtureWriterTest.kt (real SecureStore.restore / Backup.export over the decoded ios-store fixture)")
            put("hashes", iosManifest.obj("hashes"))
            put("identity", iosManifest.obj("identity"))
            put("import", "ios/Tests/FixtureImportTests.swift — copy this directory OUTSIDE ~/Desktop first (macOS TCC blocks the simulator's open() on Desktop/Documents/Downloads under xcodebuild), then TEST_RUNNER_PYRO_IMPORT_DIR=<copy> xcodebuild test … -only-testing:PyroChatTests/FixtureImportTests")
            put("peer", iosManifest.obj("peer"))
            put("regenerate", "cd android && source ./env.sh && ./gradlew :core-store:test --tests '*AndroidFixtureWriterTest*'")
            put("sourceFixture", buildJsonObject {
                put("dir", "android/fixtures/ios-store")
                put("generatedAt", iosManifest.str("generatedAt"))
                put("pyro.store.sha256", Fixtures.sha256Hex(Fixtures.bytes("pyro.store")))
            })
            put("store", buildJsonObject {
                put("aad", "none")
                put("aead", "AES-256-GCM")
                put("file", "pyro.store")
                put("iterations", StoreCrypto.PBKDF2_ITERATIONS)
                put("kdf", "PBKDF2-HMAC-SHA256")
                put("kdfSaltHex", Fixtures.saltHex)
                put("keyBytes", StoreCrypto.KEY_BYTES)
                put("layout", "\"PYROVLT1\"(8) || slotCount(1) || slotBytes(4, big-endian) || 3 * slotBytes; each slot is two halves of 2097152 bytes, each half nonce(12) || ciphertext || tag(16) sealing seq(8, big-endian) || length(4, big-endian) || JSON(Blob) || random padding; the live half is the authenticating one with the highest seq")
                put("halfBytes", StoreContainer.HALF_BYTES)
                put("occupiedHalf", opened.half)
                put("seq", opened.seq)
                put("maxJsonBytes", StoreContainer.MAX_JSON_BYTES)
                put("voiceNotes", "docs/DECOY-ACCOUNT.md §1a: voice notes are sidecar attachments like images and files; no row carries voiceData")
                put("magic", "PYROVLT1")
                put("slotCount", StoreContainer.SLOT_COUNT)
                put("slotBytes", StoreContainer.SLOT_BYTES)
                put("occupiedSlot", opened.slot)
                put("unusedSlots", "CSPRNG bytes, written once when the container was created and never rewritten; the occupied slot's second half is noise until its first save (docs/DECOY-ACCOUNT.md §1)")
                put("nonceBytes", StoreCrypto.NONCE_BYTES)
                put("password", Fixtures.password)
                put("plaintext", "pyro.store.plaintext.json — the JSON(Blob) INSIDE the occupied slot (Android SwiftJson encoding: the same JSON tree as the iOS plaintext; flat arrays sorted by id, '/' unescaped, key order differs)")
                put("tagBytes", StoreCrypto.TAG_BYTES)
            })
        }
        val pretty = Json { prettyPrint = true }
        Files.write(out.resolve("manifest.json"), (pretty.encodeToString(JsonObject.serializer(), manifest) + "\n").toByteArray())
    }
}
