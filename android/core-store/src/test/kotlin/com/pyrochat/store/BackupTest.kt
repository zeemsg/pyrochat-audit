package com.pyrochat.store

import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.Note
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PYROBAK1 export / decode / restore against the documented byte layout — with a cheap KDF. */
class BackupTest {
    private val dir: Path = Files.createTempDirectory("pyro-backup-test")
    private val id1 = "a1a1a1a1-0000-4000-8000-000000000001"
    private val id2 = "a1a1a1a1-0000-4000-8000-000000000002"

    @AfterTest fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test fun exportDecodeRestoreRoundTrip() {
        val secrets = InMemorySecretStore()
        val store = SecureStore.create(dir.resolve("old").resolve("pyro.store"), "old-device-pass", secrets, FastKdf)
        store.writeAttachment(id1, Fixtures.lcgPattern(300, 0x11))
        store.writeAttachment(id2, Fixtures.lcgPattern(50, 0x12))
        Files.write(store.attachmentsDir.resolve("stray.txt"), byteArrayOf(9))
        store.update {
            it.copy(
                usernameDisplay = "zee", usernameHash = "abc", identityKeyPair = Bytes(byteArrayOf(1, 2, 3)),
                notes = listOf(Note("n1", "secret ops note", Fixtures.d(0.0), Fixtures.d(1.0), pinned = true)),
                preKeys = mapOf(1 to Bytes(byteArrayOf(9))),
            )
        }
        val exportedAt = Fixtures.d(1234.5)
        val bak = Backup.export(store, "migrate-me-123", FastKdf, exportedAt = exportedAt)
        store.close()

        assertContentEquals(Backup.MAGIC, bak.copyOfRange(0, 8))
        val raw = bak.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret ops note") || raw.contains("zee"), "sealed: no plaintext in the file")
        // layout: magic || salt || nonce || ct || tag — opened by hand with the documented KDF
        val salt = bak.copyOfRange(8, 40)
        val key = StoreCrypto.deriveKey(FastKdf, "migrate-me-123", salt)
        val plain = StoreCrypto.open(key, bak.copyOfRange(40, bak.size)).toString(Charsets.UTF_8)
        assertEquals(40 + 12 + plain.toByteArray().size + 16, bak.size)
        assertEquals(setOf("blob", "attachments", "exportedAt"), Json.parseToJsonElement(plain).jsonObject.keys)
        assertTrue(plain.contains("\"preKeys\":[1,\"CQ==\"]"), "flat array inside the backup blob")
        assertTrue(plain.contains("\"exportedAt\":780001234.5"), plain)
        assertFalse(plain.contains("stray"), "only UUID-named sidecars are exported")

        val payload = Backup.decode(bak, "migrate-me-123", FastKdf)
        assertEquals("zee", payload.blob.usernameDisplay)
        assertEquals("secret ops note", payload.blob.notes!!.first().text)
        assertEquals(exportedAt, payload.exportedAt)
        assertEquals(mapOf(id1 to Bytes(Fixtures.lcgPattern(300, 0x11)), id2 to Bytes(Fixtures.lcgPattern(50, 0x12))), payload.attachments)

        // "new phone": restore under a brand-new password, then only that password opens it
        val newSecrets = InMemorySecretStore()
        val newFile = dir.resolve("new").resolve("pyro.store")
        val restored = SecureStore.restore(newFile, payload.blob, payload.attachments.mapValues { it.value.bytes }, "brand-new-pass", newSecrets, FastKdf)
        // §1: a restored account stamps a record for THIS device; the backup's is discarded,
        // because on a new phone those indices are noise
        assertEquals(payload.blob.withoutSlotRecord(), restored.blob.withoutSlotRecord())
        assertEquals(listOf(restored.slot), restored.blob.allocatedSlots)
        assertContentEquals(Fixtures.lcgPattern(300, 0x11), restored.readAttachment(id1))
        restored.close()
        SecureStore.open(newFile, "brand-new-pass", newSecrets, FastKdf).close()
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(newFile, "old-device-pass", newSecrets, FastKdf) }
    }

    @Test fun badFileWrongPassphraseAndBadPayload() {
        val store = SecureStore.create(dir.resolve("pyro.store"), "pw", InMemorySecretStore(), FastKdf)
        val bak = Backup.export(store, "right-passphrase", FastKdf)
        store.close()
        assertFailsWith<StoreError.BadBackupFile> { Backup.decode(byteArrayOf(), "x", FastKdf) }
        assertFailsWith<StoreError.BadBackupFile> { Backup.decode(bak.copyOf(40), "right-passphrase", FastKdf) }
        assertFailsWith<StoreError.BadBackupFile> { Backup.decode("PYROBAK2".toByteArray() + bak.copyOfRange(8, bak.size), "right-passphrase", FastKdf) }
        assertFailsWith<StoreError.BadBackupPassphrase> { Backup.decode(bak, "wrong", FastKdf) }
        val tampered = bak.copyOf()
        tampered[bak.size - 1] = (tampered[bak.size - 1].toInt() xor 1).toByte()
        assertFailsWith<StoreError.BadBackupPassphrase> { Backup.decode(tampered, "right-passphrase", FastKdf) }
        // well-formed file whose payload is not decodable (BackupService.swift:72 throws the raw decoding error)
        val salt = ByteArray(32) { 7 }
        val key = StoreCrypto.deriveKey(FastKdf, "p", salt)
        val incomplete = Backup.MAGIC + salt + StoreCrypto.seal(key, """{"blob":{},"exportedAt":0}""".toByteArray())
        assertFailsWith<StoreError.Corrupt> { Backup.decode(incomplete, "p", FastKdf) }
        val objectMaps = Backup.MAGIC + salt + StoreCrypto.seal(key, """{"blob":{"preKeys":{"1":"CQ=="}},"attachments":{},"exportedAt":0}""".toByteArray())
        assertFailsWith<StoreError.Corrupt> { Backup.decode(objectMaps, "p", FastKdf) }
        val empty = Backup.MAGIC + salt + StoreCrypto.seal(key, """{"blob":{},"attachments":{},"exportedAt":0}""".toByteArray())
        assertEquals(com.pyrochat.store.model.Blob(), Backup.decode(empty, "p", FastKdf).blob)
    }
}
