package com.pyrochat.store

import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The real iOS fixtures in `android/fixtures/ios-store`, read in place so they cannot drift from
 * what `ios/Tests/FixtureExportTests.swift` produced, plus the values `manifest.json` promises.
 * Gradle runs tests with the module directory as the working directory; `PYRO_IOS_FIXTURE_DIR`
 * overrides the location. The 600 000-iteration keys are derived once per JVM.
 */
object Fixtures {
    val iosDir: Path = System.getenv("PYRO_IOS_FIXTURE_DIR")?.let { Paths.get(it) }
        ?: Paths.get("..", "fixtures", "ios-store").toAbsolutePath().normalize()

    /**
     * Where `AndroidFixtureWriterTest` writes the reverse-gate fixture.
     *
     * **Defaults to a build directory, NOT to `android/fixtures/android-store`.** It used to
     * default to the checked-in path, and the writer deletes its output directory recursively
     * before rewriting it — so an ordinary `:core-store:test` run silently destroyed checked-in
     * test data. That is exactly what happened to the legacy `android-store` fixture on
     * 2026-09-21: it was overwritten in container form and the original bytes are gone.
     *
     * Publishing to the repo is now a deliberate act: set `PYRO_ANDROID_FIXTURE_DIR` to the
     * checked-in path. Nothing else reads this; iOS reads `android/fixtures/` directly.
     */
    val androidDir: Path = System.getenv("PYRO_ANDROID_FIXTURE_DIR")?.let { Paths.get(it) }
        ?: Paths.get("build", "tmp", "fixture-out", "android-store").toAbsolutePath().normalize()

    fun bytes(name: String): ByteArray = Files.readAllBytes(iosDir.resolve(name))
    fun json(name: String): JsonObject = Json.parseToJsonElement(bytes(name).toString(Charsets.UTF_8)).jsonObject

    val manifest: JsonObject by lazy { json("manifest.json") }
    val keychain: JsonObject by lazy { json("keychain.json") }
    val counts: JsonObject get() = manifest.obj("counts")
    val hashes: Map<String, String> get() = manifest.obj("hashes").mapValues { it.value.jsonPrimitive.content }
    val attachmentIds: Map<String, String> get() = manifest.obj("attachmentIds").mapValues { it.value.jsonPrimitive.content }

    val password: String get() = manifest.obj("store").str("password")
    val saltHex: String get() = manifest.obj("store").str("kdfSaltHex")
    val salt: ByteArray get() = hex(saltHex)
    val backupPassphrase: String get() = manifest.obj("backup").str("passphrase")
    val duressPin: String get() = keychain.obj("items").obj("duress-verifier").str("pin")
    val duressVerifier: ByteArray get() = hex(keychain.obj("items").obj("duress-verifier").str("hex"))

    val storeKey: ByteArray by lazy { StoreCrypto.deriveKey(Pbkdf2HmacSha256, password, salt) }
    val storePlaintext: ByteArray by lazy { StoreCrypto.open(storeKey, bytes("pyro.store")) }
    val blob: Blob by lazy { SwiftJson.decodeBlob(storePlaintext) }

    /** The iOS store opened through the real API (never written to; the save thread is a daemon). */
    val iosStore: SecureStore by lazy { SecureStore.open(iosDir.resolve("pyro.store"), password, secrets()) }

    /** The Keychain items as iOS had them when the fixture was written. */
    fun secrets(): InMemorySecretStore = InMemorySecretStore().apply {
        set(SecretStore.KDF_SALT, salt)
        set(SecretStore.DURESS_VERIFIER, duressVerifier)
    }

    /** Swift `Date(timeIntervalSinceReferenceDate: 780_000_000 + offset)`. */
    const val REFERENCE_BASE = 780_000_000.0
    fun d(offset: Double): Instant = SwiftDate.toInstant(REFERENCE_BASE + offset)

    /** `manifest.messageIdPattern` */
    fun mid(n: Int): String = "6d000000-0000-4000-8000-%012d".format(n)

    /** `manifest.fixedValues.attachmentPlaintext`: the LCG byte pattern of every sidecar and voice note. */
    fun lcgPattern(n: Int, seed: Int): ByteArray {
        var x = (seed.toLong() or 0x9E37_0000L).toUInt()
        return ByteArray(n) {
            x = x * 1_664_525u + 1_013_904_223u
            (x shr 24).toByte()
        }
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun sha256Hex(b: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(b))
}

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

/**
 * docs/DECOY-ACCOUNT.md §1a: [spilled] must hold exactly what [original] held, except that every
 * inline `voiceData` has become a sidecar whose bytes [readAttachment] returns unchanged.
 */
fun assertSpilledEquivalent(original: Blob, spilled: Blob, readAttachment: (String) -> ByteArray?) {
    assertEquals(
        original.withoutSlotRecord().copy(messages = emptyMap()),
        spilled.withoutSlotRecord().copy(messages = emptyMap()),
        "only the message rows may change",
    )
    assertEquals(original.messages.keys, spilled.messages.keys, "no thread appears or disappears")
    var moved = 0
    for ((thread, rows) in original.messages) {
        val after = spilled.messages.getValue(thread)
        assertEquals(rows.size, after.size, "thread $thread keeps every row")
        for ((before, now) in rows.zip(after)) {
            val audio = before.voiceData
            if (audio == null || before.attachmentId != null) {
                assertEquals(before, now, "row ${before.id} is untouched")
                continue
            }
            moved++
            val attId = now.attachmentId
            assertNotNull(attId, "row ${before.id} must name a sidecar")
            assertNull(now.voiceData, "row ${before.id} keeps no audio inline")
            assertContentEquals(audio.bytes, readAttachment(attId), "row ${before.id} is still playable")
            assertEquals(before.copy(voiceData = null, attachmentId = attId), now, "nothing else about the row changes")
        }
    }
    assertEquals(VoiceSpill.countInline(original), moved, "every inline note moved")
    assertEquals(0, VoiceSpill.countInline(spilled))
}

/** A deliberately cheap KDF for tests whose subject is not key derivation (600 000 iterations take ~0.5 s each). */
/**
 * docs/DECOY-ACCOUNT.md §1 — the slot record (`allocatedSlots` / `knownSlots`) is metadata about
 * WHERE an account sits, stamped by whatever authored it. A test comparing the CONTENT of two blobs
 * drops it first; a test about allocation asserts on it directly.
 */
fun Blob.withoutSlotRecord(): Blob = copy(allocatedSlots = null, knownSlots = null)

object FastKdf : Kdf {
    override fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(password + salt).copyOf(keyLen)
}
