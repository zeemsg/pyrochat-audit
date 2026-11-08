package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `SecureStore` lifecycle, the 0.25 s debounce, atomic writes and the sidecar store — with a cheap KDF. */
class SecureStoreTest {
    private val dir: Path = Files.createTempDirectory("pyro-store-test")
    private val file: Path = dir.resolve(SecureStore.FILE_NAME)
    private val secrets = InMemorySecretStore()
    private val password = "correct horse battery staple"
    private val uuid = "a1a1a1a1-0000-4000-8000-000000000001"

    @AfterTest fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun storeKey(): ByteArray = SecureStore.deriveKey(FastKdf, password, secrets.get(SecretStore.KDF_SALT)!!)
    private fun blobOnDisk(): Blob =
        SwiftJson.decodeBlob(StoreContainer.open(storeKey(), Files.readAllBytes(file))!!.json)

    @Test fun createUpdateFlushOpen() {
        val store = SecureStore.create(file, password, secrets, FastKdf)
        assertEquals(32, secrets.get(SecretStore.KDF_SALT)!!.size, "a fresh salt is created once per install")
        store.update { it.copy(usernameDisplay = "zee", usernameHash = "abc") }
        store.flush()
        assertTrue(SecureStore.exists(file))
        val plain = SwiftJson.encodeBlob(store.blob)
        assertEquals(
            StoreContainer.FILE_BYTES,
            Files.size(file).toInt(),
            "magic(8) || slotCount(1) || slotBytes(4) || 3 * 4 MiB, whatever the account holds",
        )
        assertEquals(store.blob, blobOnDisk())
        val reopened = SecureStore.open(file, password, secrets, FastKdf)
        assertEquals(store.blob, reopened.blob)
        assertEquals(dir.resolve("attachments"), reopened.attachmentsDir)
        store.close()
        reopened.close()
    }

    @Test fun wrongPasswordMissingFileAndCorruptContents() {
        assertFailsWith<StoreError.NoStore> { SecureStore.open(file, password, secrets, FastKdf) }
        val created = SecureStore.create(file, password, secrets, FastKdf).apply { flush() }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, "wrong password", secrets, FastKdf) }
        // a tampered byte inside the occupied slot is indistinguishable from a wrong password
        val bytes = Files.readAllBytes(file)
        val at = StoreContainer.slotRange(created.slot).first + 20
        bytes[at] = (bytes[at].toInt() xor 1).toByte()
        created.close()
        Files.write(file, bytes)
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, password, secrets, FastKdf) }
        // a valid seal of invalid JSON (here in the pre-container shape, which still decodes)
        Files.write(file, StoreCrypto.seal(storeKey(), """{"contacts":[{"hash":"h"}]}""".toByteArray()))
        assertFailsWith<StoreError.Corrupt> { SecureStore.open(file, password, secrets, FastKdf) }
        // a store written under a different Keychain salt cannot be opened
        SecureStore.create(file, password, secrets, FastKdf).use { it.flush() }
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, password, InMemorySecretStore(), FastKdf) }
    }

    @Test fun updatesAreDebouncedAndCoalesced() {
        val store = SecureStore.create(file, password, secrets, FastKdf)
        store.flush()
        val before = Files.readAllBytes(file)
        repeat(5) { i -> store.update { it.copy(usernameDisplay = "v$i") } }
        Thread.sleep(60)
        assertContentEquals(before, Files.readAllBytes(file), "nothing is written inside the 0.25 s window")
        Thread.sleep(700)
        assertEquals("v4", blobOnDisk().usernameDisplay, "only the newest snapshot is written")
        assertNull(store.lastWriteError)
        store.close()
    }

    @Test fun flushWritesNowAndTheScheduledDebounceBecomesANoop() {
        val store = SecureStore.create(file, password, secrets, FastKdf)
        store.update { it.copy(usernameDisplay = "flushed") }
        store.flush()
        val written = Files.readAllBytes(file)
        assertEquals("flushed", blobOnDisk().usernameDisplay)
        Thread.sleep(600)
        assertContentEquals(written, Files.readAllBytes(file), "the pending debounce found no snapshot (a re-seal would change the nonce)")
        assertEquals(listOf(SecureStore.FILE_NAME), dir.listDirectoryEntries().map { it.fileName.toString() }, "atomic write leaves no temp files")
        store.close()
    }

    @Test fun attachmentSidecars() {
        val store = SecureStore.create(file, password, secrets, FastKdf)
        val data = Fixtures.lcgPattern(4096, 0x11)
        store.writeAttachment(uuid, data)
        val sidecar = store.attachmentPath(uuid)
        assertEquals(dir.resolve("attachments").resolve(uuid), sidecar)
        assertEquals(data.size + 28, Files.size(sidecar).toInt())
        assertContentEquals(data, store.readAttachment(uuid))
        assertContentEquals(data, StoreCrypto.open(storeKey(), Files.readAllBytes(sidecar)), "sealed under the store key, no per-attachment key")
        val firstSeal = Files.readAllBytes(sidecar)
        store.writeAttachment(uuid, data)
        assertFalse(firstSeal.contentEquals(Files.readAllBytes(sidecar)), "a rewrite uses a fresh nonce")
        assertContentEquals(data, store.readAttachment(uuid))
        Files.write(dir.resolve("attachments").resolve("notes.txt"), byteArrayOf(1))
        assertEquals(listOf(uuid), store.attachmentIds(), "only UUID-named entries count")
        store.deleteAttachment(uuid)
        assertNull(store.readAttachment(uuid))
        assertFalse(Files.exists(sidecar))
        // path-traversal guard: only canonical UUID strings are accepted
        for (bad in listOf("../pyro.store", "1-2-3-4-5", "", "a1a1a1a1-0000-4000-8000-00000000000g", "a1a1a1a100004000800000000000000001", "$uuid/x")) {
            assertFailsWith<StoreError.Io>(bad) { store.writeAttachment(bad, data) }
            assertNull(store.readAttachment(bad), bad)
            store.deleteAttachment(bad)
        }
        assertTrue(SecureStore.isValidAttachmentId("A1A1A1A1-0000-4000-8000-000000000001"), "Swift UUID(uuidString:) accepts upper case")
        store.close()
    }

    @Test fun restoreResealsAttachmentsUnderTheNewKeyAndFlushes() {
        val blob = Fixtures.blob
        val attachments = mapOf(uuid to Fixtures.lcgPattern(100, 0x11), "not-a-uuid" to byteArrayOf(1))
        val restored = SecureStore.restore(file, blob, attachments, "brand-new-pass", secrets, FastKdf)
        assertTrue(SecureStore.exists(file), "restore flushes synchronously")
        // docs/DECOY-ACCOUNT.md §1a: a backup made before the spill lands with its audio inline, and
        // restore moves it to sidecars on the way in
        assertEquals(2, restored.spilledVoiceNotes)
        assertSpilledEquivalent(blob, restored.blob, restored::readAttachment)
        assertContentEquals(attachments.getValue(uuid), restored.readAttachment(uuid))
        assertEquals(
            (listOf(uuid) + restored.blob.messages.values.flatten().mapNotNull {
                if (it.kind == com.pyrochat.store.model.ChatMessage.Kind.VOICE) it.attachmentId else null
            }).sorted(),
            restored.attachmentIds(),
            "invalid ids are skipped, not fatal; the two voice notes are sidecars now",
        )
        val spilled = restored.blob
        restored.close()
        assertEquals(spilled, SecureStore.open(file, "brand-new-pass", secrets, FastKdf).use { it.blob })
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(file, Fixtures.password, secrets, FastKdf) }
    }

    @Test fun obliterateRemovesStoreSidecarsAndSecrets() {
        val store = SecureStore.create(file, password, secrets, FastKdf)
        store.flush()
        store.writeAttachment(uuid, byteArrayOf(1, 2, 3))
        SecureStore.setDuressPin(secrets, FastKdf, "2580")
        store.close()
        val keyBefore = storeKey()
        SecureStore.obliterate(file, secrets)
        // docs/DECOY-ACCOUNT.md §5: no path ever deletes the container — wiped, never used and
        // holding two accounts are the same bytes, so the launch afterwards can tell nothing apart
        assertTrue(Files.exists(file))
        assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file))
        assertNull(StoreContainer.open(keyBefore, Files.readAllBytes(file)), "and no slot opens")
        assertFalse(Files.exists(dir.resolve("attachments")))
        assertNull(secrets.get(SecretStore.KDF_SALT))
        assertNull(secrets.get(SecretStore.DURESS_VERIFIER))
    }

    @Test fun duressPinVerifierLivesOutsideTheStore() {
        assertFalse(SecureStore.matchesDuressPin(secrets, FastKdf, "2580"), "no verifier set")
        SecureStore.setDuressPin(secrets, FastKdf, "2580")
        val salt = secrets.get(SecretStore.KDF_SALT)!!
        assertContentEquals(
            FastKdf.derive("2580".toByteArray(), salt, StoreCrypto.PBKDF2_ITERATIONS, StoreCrypto.KEY_BYTES),
            secrets.get(SecretStore.DURESS_VERIFIER),
            "the verifier is the same PBKDF2 output under the Keychain salt",
        )
        assertTrue(SecureStore.matchesDuressPin(secrets, FastKdf, "2580"))
        assertFalse(SecureStore.matchesDuressPin(secrets, FastKdf, "0000"))
        assertFalse(SecureStore.matchesDuressPin(secrets, FastKdf, "25800"))
    }

    @Test fun saltIsCreatedOnceAndReused() {
        val a = SecureStore.salt(secrets)
        val b = SecureStore.salt(secrets)
        assertEquals(StoreCrypto.SALT_BYTES, a.size)
        assertContentEquals(a, b)
    }
}
