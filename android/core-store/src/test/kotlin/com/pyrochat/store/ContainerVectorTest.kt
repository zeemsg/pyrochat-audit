package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/DECOY-ACCOUNT.md §7: the cross-platform container test vector. iOS authored
 * `android/fixtures/container-vector.json` — pinned inputs and one expected SHA-256, instead of
 * checking in 12 MiB of incompressible padding. This reproduces it byte for byte.
 */
class ContainerVectorTest {

    private val vectorFile: Path = System.getenv("PYRO_CONTAINER_VECTOR")?.let { Paths.get(it) }
        ?: Paths.get("..", "fixtures", "container-vector.json").toAbsolutePath().normalize()

    private val vector: JsonObject by lazy {
        Json.parseToJsonElement(Files.readString(vectorFile)).jsonObject
    }

    private fun sha256Hex(b: ByteArray) = ContainerVector.sha256Hex(b)

    private fun requireVector() = Assumptions.assumeTrue(
        Files.isRegularFile(vectorFile),
        "SKIPPED: $vectorFile is missing — it is the cross-platform vector of docs/DECOY-ACCOUNT.md §7",
    )

    // MARK: the byte source

    @Test fun theStreamIsTheConstructionTheVectorPins() {
        val seed = ContainerVector.SEED
        assertEquals(32, seed.size)
        assertEquals(
            sha256Hex("pyrochat-decoy-fixture-v1".toByteArray(Charsets.UTF_8)),
            ContainerVector.hex(seed),
            "seed = SHA-256(UTF-8 \"pyrochat-decoy-fixture-v1\")",
        )
        val block0 = MessageDigest.getInstance("SHA-256").digest(seed + byteArrayOf(0, 0, 0, 0))
        val block1 = MessageDigest.getInstance("SHA-256").digest(seed + byteArrayOf(0, 0, 0, 1))
        assertContentEquals(block0, ContainerVector.stream(seed, 32))
        assertContentEquals(block0 + block1, ContainerVector.stream(seed, 64))
        assertContentEquals(block0.copyOf(7), ContainerVector.stream(seed, 7), "cut to length, never padded")
        // one stream per container: a longer draw extends the shorter one exactly, so a request
        // that is not a multiple of 32 keeps the remainder for the next request
        val long = ContainerVector.stream(seed, 5_000)
        assertContentEquals(long.copyOf(1_234), ContainerVector.stream(seed, 1_234))
        assertEquals(100_000, ContainerVector.stream(seed, 100_000).size)
    }

    // MARK: the vector itself

    @Test fun theVectorsInputsAreTheOnesThisBuildUses() {
        requireVector()
        val layout = vector.obj("layout")
        assertEquals("PYROVLT1", layout.str("magic"))
        assertEquals(StoreContainer.SLOT_COUNT, layout.int("slotCount"))
        assertEquals(StoreContainer.SLOT_BYTES, layout.int("slotBytes"))
        assertEquals(StoreContainer.HALF_BYTES, layout.int("halfBytes"))
        assertEquals(StoreContainer.HEADER_BYTES, layout.int("headerBytes"))
        assertEquals(StoreCrypto.NONCE_BYTES, layout.int("nonceBytes"))
        assertEquals(StoreCrypto.TAG_BYTES, layout.int("tagBytes"))
        assertEquals(StoreContainer.HALF_PLAINTEXT_BYTES, layout.int("halfCapacity"))
        assertEquals(StoreContainer.SEQ_BYTES, layout.int("seqBytes"))
        assertEquals(StoreContainer.LENGTH_BYTES, layout.int("lengthBytes"))
        assertEquals(StoreContainer.MAX_JSON_BYTES, layout.int("maxPayloadBytes"))
        assertEquals(
            (0 until StoreContainer.SLOT_COUNT).flatMap { s ->
                (0 until StoreContainer.HALVES_PER_SLOT).map { h -> StoreContainer.halfRange(s, h).first }
            },
            layout.getValue("halfOffsets").jsonArray.map { it.jsonPrimitive.int },
            "every half offset",
        )

        val kdf = vector.obj("kdf")
        assertEquals(StoreCrypto.PBKDF2_ITERATIONS, kdf.int("iterations"))
        assertEquals(StoreCrypto.KEY_BYTES, kdf.int("keyBytes"))
        assertEquals(kdf.str("saltHex"), Fixtures.saltHex, "the vector uses the fixture's pinned Keychain salt")
        assertEquals(kdf.str("password"), Fixtures.password)
        assertEquals(kdf.str("keySha256"), sha256Hex(key()), "the derived key itself")

        assertEquals(ContainerVector.SEED_LABEL, vector.obj("byteSource").str("seedPhrase"))
        assertEquals(vector.obj("byteSource").str("seedHex"), ContainerVector.hex(ContainerVector.SEED))
    }

    @Test fun reproducesTheCheckedInContainerHash() {
        requireVector()
        val payload = vector.obj("payload").str("json").toByteArray(Charsets.UTF_8)
        assertEquals(vector.obj("payload").int("bytes"), payload.size)
        assertEquals(vector.obj("payload").str("sha256"), sha256Hex(payload), "the payload, sealed verbatim")

        val container = vector.obj("container")
        val slot = container.int("occupiedSlot")
        val half = container.int("half")
        val seq = container.getValue("seq").jsonPrimitive.int.toLong()
        val built = ContainerVector.build(key(), payload, slot, half = half, seq = seq)

        assertEquals(container.int("bytes"), built.size)
        assertEquals(
            container.str("headerHex"),
            ContainerVector.hex(built.copyOfRange(0, StoreContainer.HEADER_BYTES)),
            "header",
        )
        assertEquals(
            container.str("sealedHalfSha256"),
            sha256Hex(built.sliceArray(StoreContainer.halfRange(slot, half))),
            "the sealed half",
        )
        assertEquals(container.str("sha256"), sha256Hex(built), "THE cross-platform check: the whole container")

        // and it is a container this platform really opens, holding a real Blob
        val opened = StoreContainer.open(key(), built)!!
        assertEquals(slot, opened.slot)
        assertEquals(half, opened.half)
        assertEquals(seq, opened.seq)
        assertEquals(6, opened.attempts)
        assertContentEquals(payload, opened.json)
        val blob = SwiftJson.decodeBlob(opened.json)
        assertEquals("fixture_vector", blob.usernameDisplay)
        assertEquals(4242, blob.registrationId)
        assertEquals(7, blob.nextPreKeyId)
    }

    @Test fun theVectorIsDeterministicAndTheSlotIsTheOnlyThingThatMoves() {
        requireVector()
        val payload = vector.obj("payload").str("json").toByteArray(Charsets.UTF_8)
        val once = ContainerVector.build(key(), payload, 2)
        val twice = ContainerVector.build(key(), payload, 2)
        assertContentEquals(once, twice, "same inputs, same bytes — no platform RNG anywhere")
        for (slot in 0..2) {
            val c = ContainerVector.build(key(), payload, slot)
            assertContentEquals(once.copyOfRange(0, 13), c.copyOfRange(0, 13), "the header never moves")
            assertEquals(slot, StoreContainer.open(key(), c)!!.slot)
            if (slot != 2) assertTrue(!c.contentEquals(once), "a different slot is a different file")
        }
    }

    private fun key(): ByteArray = StoreCrypto.deriveKey(Pbkdf2HmacSha256, Fixtures.password, Fixtures.salt)
}
