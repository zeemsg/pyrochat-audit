package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.Contact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `android/fixtures/legacy-voice/` — a pre-container store whose audio is INLINE, for
 * docs/DECOY-ACCOUNT.md §1a.
 *
 * One AES-GCM box over the whole JSON, no `PYROVLT1` magic, and two `voice` rows carrying
 * `voiceData` with no `attachmentId`: one about 2 KB, one about 2.2 MB. That second note is the
 * point — with it the blob is larger than a container half can ever hold, so **a container-first
 * implementation cannot even produce this file**, and migrating it proves §1a's ordering (unlock
 * legacy, spill the audio to sidecars, and only then write the container) against real data.
 *
 * The fixture is DETERMINISTIC — LCG audio, a pinned nonce — so this test rebuilds it and asserts
 * the checked-in bytes byte for byte. It writes the files only when they are missing, and never
 * touches any other fixture directory.
 */
class LegacyVoiceFixtureTest {

    private val dir: Path = System.getenv("PYRO_LEGACY_VOICE_FIXTURE_DIR")?.let { Paths.get(it) }
        ?: Paths.get("..", "fixtures", "legacy-voice").toAbsolutePath().normalize()

    private val password = Fixtures.password
    private val salt = Fixtures.salt
    private val key: ByteArray by lazy { StoreCrypto.deriveKey(Pbkdf2HmacSha256, password, salt) }

    /** `SHA-256("pyrochat-legacy-voice-v1")[0..12)` — pinned so the fixture is reproducible. */
    private val nonce: ByteArray = sha256("pyrochat-legacy-voice-v1".toByteArray(Charsets.UTF_8)).copyOf(12)

    private val peer = "b7".repeat(32)
    private val smallId = "7d000000-0000-4000-8000-000000000001"
    private val largeId = "7d000000-0000-4000-8000-000000000002"
    private val textId = "7d000000-0000-4000-8000-000000000003"

    private val smallAudio: ByteArray by lazy { Fixtures.lcgPattern(2_048, 0x31) }
    private val largeAudio: ByteArray by lazy { Fixtures.lcgPattern(2_200_000, 0x32) }

    private fun blob(): Blob {
        val contact = Contact(hash = peer, name = "dana", addedAt = Fixtures.d(2000.0))
        val rows = listOf(
            ChatMessage(
                id = textId, direction = ChatMessage.Direction.OUTGOING, kind = ChatMessage.Kind.TEXT,
                text = "listen to this", timestamp = Fixtures.d(2001.0), status = ChatMessage.Status.READ,
            ),
            ChatMessage(
                id = smallId, direction = ChatMessage.Direction.OUTGOING, kind = ChatMessage.Kind.VOICE,
                text = "[voice 1s]", timestamp = Fixtures.d(2002.0), status = ChatMessage.Status.DELIVERED,
                voiceData = Bytes(smallAudio), voiceDuration = 1.5,
            ),
            ChatMessage(
                id = largeId, direction = ChatMessage.Direction.INCOMING, kind = ChatMessage.Kind.VOICE,
                text = "[voice 110s]", timestamp = Fixtures.d(2003.0), status = ChatMessage.Status.READ,
                voiceData = Bytes(largeAudio), voiceDuration = 110.25,
            ),
        )
        return Blob(
            usernameDisplay = "voice_legacy",
            usernameHash = "a3".repeat(32),
            registrationId = 1337,
            nextPreKeyId = 5,
            contacts = listOf(contact),
            messages = mapOf(peer to rows),
        )
    }

    // MARK: the fixture

    @Test fun theCheckedInFixtureIsExactlyThisStore() {
        val plaintext = SwiftJson.encodeBlob(blob())
        assertTrue(
            plaintext.size > StoreContainer.MAX_JSON_BYTES,
            "the whole point: ${plaintext.size} bytes of blob cannot fit a ${StoreContainer.MAX_JSON_BYTES}-byte half",
        )
        val sealed = sealWithPinnedNonce(plaintext)
        assertFalse(StoreContainer.isContainer(sealed), "the fixture is a pre-container store, no magic")
        assertEquals(12 + plaintext.size + 16, sealed.size, "nonce(12) || ciphertext || tag(16)")

        val storeFile = dir.resolve(SecureStore.FILE_NAME)
        if (!Files.isRegularFile(storeFile)) {
            Files.createDirectories(dir)
            Files.write(storeFile, sealed)
            Files.write(dir.resolve("manifest.json"), manifest(plaintext, sealed))
            println("wrote a new fixture to $dir (${sealed.size} bytes)")
        }
        assertContentEquals(sealed, Files.readAllBytes(storeFile), "the checked-in fixture must never drift")

        val m = Json.parseToJsonElement(Files.readString(dir.resolve("manifest.json"))).jsonObject
        assertEquals(password, m.obj("store").str("password"))
        assertEquals(Fixtures.toHex(salt), m.obj("store").str("kdfSaltHex"))
        assertEquals(sha256Hex(plaintext), m.obj("store").str("plaintextSha256"))
        assertEquals(sha256Hex(smallAudio), m.obj("voiceNotes").obj(smallId).str("sha256"))
        assertEquals(sha256Hex(largeAudio), m.obj("voiceNotes").obj(largeId).str("sha256"))
    }

    // MARK: what it is for

    @Test fun migratingItSpillsTheAudioAndTheHistorySurvives() {
        val work = Files.createTempDirectory("pyro-legacy-voice")
        try {
            val file = work.resolve(SecureStore.FILE_NAME)
            Files.copy(dir.resolve(SecureStore.FILE_NAME), file)
            val secrets = InMemorySecretStore().apply { set(SecretStore.KDF_SALT, salt) }

            val store = SecureStore.open(file, password, secrets, migrateLegacy = true)
            assertTrue(store.migratedLegacyFile, "it must migrate: ${store.lastWriteError}")
            assertNull(store.tooLargeToMigrate, "2.2 MB of audio moves out, and then it fits")
            assertEquals(2, store.spilledVoiceNotes)
            assertFalse(store.legacyFormat)
            assertEquals(StoreContainer.FILE_BYTES.toLong(), Files.size(file), "a container, whatever it holds")

            assertSpilledEquivalent(blob(), store.blob, store::readAttachment)
            val rows = store.blob.messages.getValue(peer)
            assertEquals(3, rows.size, "the text row and both notes are all still there")
            for ((id, audio) in listOf(smallId to smallAudio, largeId to largeAudio)) {
                val row = rows.first { it.id == id }
                assertNull(row.voiceData)
                val attId = row.attachmentId
                assertNotNull(attId, "$id must name a sidecar")
                assertContentEquals(audio, store.readAttachment(attId), "$id must be playable, byte for byte")
                assertEquals(audio.size, Files.size(store.attachmentPath(attId)).toInt() - 28, "sealed, nonce + tag")
            }
            assertEquals(110.25, rows.first { it.id == largeId }.voiceDuration)
            assertEquals(1.5, rows.first { it.id == smallId }.voiceDuration)
            store.close()

            // and it reopens as an ordinary container afterwards
            SecureStore.open(file, password, secrets).use { reopened ->
                assertEquals(store.blob, reopened.blob)
                assertTrue(
                    SwiftJson.encodeBlob(reopened.blob).size < StoreContainer.MAX_JSON_BYTES,
                    "the blob fits a half now that the audio is beside it",
                )
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    // MARK: helpers

    private fun manifest(plaintext: ByteArray, sealed: ByteArray): ByteArray {
        val note = { id: String, audio: ByteArray, duration: Double, direction: String ->
            buildJsonObject {
                put("attachmentId", null as String?)
                put("direction", direction)
                put("durationSeconds", duration)
                put("plaintextBytes", audio.size)
                put("sha256", sha256Hex(audio))
                put("voiceDataIsInline", true)
            }
        }
        val m = buildJsonObject {
            put("what", "A pre-container `pyro.store` whose voice notes are INLINE in the blob (docs/DECOY-ACCOUNT.md §1a). Migrating it must move both notes into `attachments/` sidecars BEFORE the container is written — the blob is larger than a container half, so a container-first implementation cannot produce or migrate this file at all.")
            put("generatedBy", "android/core-store/src/test/kotlin/com/pyrochat/store/LegacyVoiceFixtureTest.kt (deterministic: LCG audio, pinned nonce — rerunning rebuilds identical bytes)")
            put("store", buildJsonObject {
                put("file", SecureStore.FILE_NAME)
                put("format", "PRE-CONTAINER: nonce(12) || AES-256-GCM(JSON(Blob)) || tag(16), no PYROVLT1 magic")
                put("aead", "AES-256-GCM")
                put("aad", "none")
                put("kdf", "PBKDF2-HMAC-SHA256")
                put("iterations", StoreCrypto.PBKDF2_ITERATIONS)
                put("keyBytes", StoreCrypto.KEY_BYTES)
                put("password", password)
                put("kdfSaltHex", Fixtures.toHex(salt))
                put("nonceHex", Fixtures.toHex(nonce))
                put("nonceNote", "SHA-256(\"pyrochat-legacy-voice-v1\")[0..12) — pinned so the fixture never drifts")
                put("sealedBytes", sealed.size)
                put("sealedSha256", sha256Hex(sealed))
                put("plaintextBytes", plaintext.size)
                put("plaintextSha256", sha256Hex(plaintext))
                put("maxPayloadBytes", StoreContainer.MAX_JSON_BYTES)
                put("exceedsHalfBy", plaintext.size - StoreContainer.MAX_JSON_BYTES)
            })
            put("blob", buildJsonObject {
                put("usernameDisplay", "voice_legacy")
                put("threadHash", peer)
                put("messages", 3)
                put("textRowId", textId)
                put("referenceBase", Fixtures.REFERENCE_BASE)
                put("timestampNote", "Swift Date(timeIntervalSinceReferenceDate: 780000000 + offset); rows at +2001, +2002, +2003")
            })
            put("voiceNotes", buildJsonObject {
                put(smallId, note(smallId, smallAudio, 1.5, "outgoing"))
                put(largeId, note(largeId, largeAudio, 110.25, "incoming"))
            })
            put("audioPattern", "LCG: x = x*1664525 + 1013904223 (mod 2^32), byte = x >> 24, x0 = seed | 0x9E370000; seeds 0x31 (2048 B) and 0x32 (2200000 B)")
            put("afterMigration", "both rows keep their id, text, direction, status and voiceDuration, lose `voiceData`, and gain an `attachmentId` naming a sidecar whose PLAINTEXT sha256 is the one above")
            put("verify", "cd android && source ./env.sh && ./gradlew :core-store:test --tests '*LegacyVoiceFixtureTest*'")
        }
        return (Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), m) + "\n").toByteArray()
    }

    private fun sealWithPinnedNonce(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun sha256Hex(b: ByteArray): String = Fixtures.toHex(sha256(b))
}
