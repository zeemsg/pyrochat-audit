package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.GroupPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/GROUPS.md §3 cross-platform gate, forward direction: the store the iOS agent wrote with the
 * real `SecureStore` API (`ios/Tests/FixtureExportTests.swift testExportIOSGroupFixtures`) into
 * `android/fixtures/ios-store-groups/` — `#ops`, 3 members + 1 former, epoch 4, `burn_remove`,
 * burnSeconds 300, 6 rows including an image, receipts on both outgoing rows. Skips (with a
 * message) until the directory exists.
 */
class IosGroupFixtureTest {
    private val dir: Path = System.getenv("PYRO_IOS_GROUP_FIXTURE_DIR")?.let { Paths.get(it) }
        ?: Paths.get("..", "fixtures", "ios-store-groups").toAbsolutePath().normalize()

    @BeforeEach
    fun requireFixture() = Assumptions.assumeTrue(
        Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("pyro.store")) && Files.isRegularFile(dir.resolve("manifest.json")),
        "SKIPPED: no iOS group fixture at $dir yet (the iOS agent writes store + plaintext + manifest there; same layout as ios-store)",
    )

    private fun json(name: String): JsonObject = Json.parseToJsonElement(Files.readString(dir.resolve(name))).jsonObject
    private val manifest: JsonObject by lazy { json("manifest.json") }
    private val hashes: Map<String, String> get() = manifest.obj("hashes").mapValues { it.value.jsonPrimitive.content }

    private fun openBlob(): Pair<Blob, ByteArray> {
        val store = manifest.obj("store")
        val key = StoreCrypto.deriveKey(Pbkdf2HmacSha256, store.str("password"), Fixtures.hex(store.str("kdfSaltHex")))
        val plaintext = StoreCrypto.open(key, Files.readAllBytes(dir.resolve("pyro.store")))
        return SwiftJson.decodeBlob(plaintext) to plaintext
    }

    @Test fun storeDecryptsToThePublishedPlaintext() {
        val (_, plaintext) = openBlob()
        val files = manifest.obj("files")
        assertEquals(files.obj("pyro.store").str("sha256"), Fixtures.sha256Hex(Files.readAllBytes(dir.resolve("pyro.store"))))
        assertEquals(files.obj("pyro.store.plaintext.json").int("bytes"), plaintext.size)
        assertEquals(files.obj("pyro.store.plaintext.json").str("sha256"), Fixtures.sha256Hex(plaintext))
        assertContentEquals(Files.readAllBytes(dir.resolve("pyro.store.plaintext.json")), plaintext)
    }

    @Test fun theGroupAndItsThreadMatchTheManifest() {
        val (blob, _) = openBlob()
        val alice = hashes.getValue("alice_fixture"); val bob = hashes.getValue("bob_fixture")
        val carol = hashes.getValue("carol_fixture"); val dave = hashes.getValue("dave_fixture")
        val counts = manifest.obj("counts")
        val groups = assertNotNull(blob.groups, "Blob.groups must be present")
        assertEquals(counts.int("groups"), groups.size)
        assertEquals(counts.int("threads"), blob.messages.size)
        assertEquals(alice, blob.usernameHash, "the fixture identity is the creator")

        val m = manifest.obj("group")
        val g = groups.single()
        assertEquals(m.str("id"), g.id)
        assertEquals("ops", g.name); assertEquals(m.str("name"), g.name)
        assertEquals(m.str("creator"), g.creator); assertEquals(alice, g.creator)
        assertEquals(4, g.epoch); assertEquals(m.int("epoch"), g.epoch)
        assertEquals("burn_remove", g.policy); assertEquals(GroupPolicy.BURN_REMOVE, g.policyKind)
        assertEquals(300.0, g.burnSeconds); assertEquals(m.int("burnSeconds").toDouble(), g.burnSeconds)
        assertNull(g.left)
        assertEquals(Fixtures.d(-3600.0), g.createdAt)
        // 3 members (creator included) + 1 former, every key of §3 present, colours derive from the union
        assertEquals(3, g.members.size); assertEquals(m.int("members"), g.members.size)
        assertEquals(listOf(alice, bob, carol), g.members.map { it.hash })
        assertEquals(listOf("alice_fixture", "bob_fixture", "carol_fixture"), g.members.map { it.name })
        assertEquals(listOf(Fixtures.d(-3600.0), Fixtures.d(-3600.0), Fixtures.d(-1800.0)), g.members.map { it.joinedAt })
        assertTrue(g.members.none { it.isGone })
        val former = assertNotNull(g.former, "the optional `former` list")
        assertEquals(1, former.size); assertEquals(m.int("former"), former.size)
        assertEquals(dave, former.single().hash); assertEquals("dave_fixture", former.single().name)
        assertEquals(Fixtures.d(-3000.0), former.single().joinedAt)
        assertEquals(listOf(alice, bob, dave, carol), g.colourRoster.sortedWith(compareBy({ it.joinedAt }, { it.hash })).map { it.hash }, "join order over members ∪ former")

        // the thread: 6 rows keyed by the group id — 4 text, 1 system, 1 image
        val t = manifest.obj("thread")
        val rows = assertNotNull(blob.messages[g.id], "messages are keyed by the group id")
        assertEquals(6, rows.size); assertEquals(t.int("rows"), rows.size)
        assertEquals(mapOf("text" to 4, "system" to 1, "image" to 1), rows.groupingBy { SwiftJson.encode(it.kind).trim('"') }.eachCount())
        assertTrue(rows.all { it.group == g.id }, "every row names its group")
        assertTrue(rows.filter { it.direction == ChatMessage.Direction.INCOMING && it.kind != ChatMessage.Kind.SYSTEM }.all { it.sender != null })
        assertTrue(rows.filter { it.direction == ChatMessage.Direction.OUTGOING }.all { it.sender == null && it.receipts != null })
        assertTrue(rows.filter { it.direction == ChatMessage.Direction.INCOMING }.all { it.receipts == null })
        assertEquals(t.str("systemRow"), rows.single { it.kind == ChatMessage.Kind.SYSTEM }.text)
        assertEquals("── <carol_fixture> joined ──", rows[1].text)
        val outgoing = rows.filter { it.direction == ChatMessage.Direction.OUTGOING }
        assertEquals(t.getValue("outgoingRowIds").jsonArray.map { it.jsonPrimitive.content }, outgoing.map { it.id })
        assertEquals(mapOf(bob to "read", carol to "read"), outgoing[0].receipts)
        assertEquals(mapOf(bob to "delivered"), outgoing[1].receipts)
        assertEquals(300.0, outgoing[1].burnAfter)
        val image = rows.single { it.kind == ChatMessage.Kind.IMAGE }
        assertEquals(carol, image.sender)
        assertEquals(manifest.obj("attachmentIds").str("imageIncoming"), image.attachmentId)
        assertEquals(320, image.imageWidth); assertEquals(240, image.imageHeight); assertEquals(3200, image.fileBytes)
        val late = rows.last()
        assertEquals(dave, late.sender, "a line from a member who then left keeps its sender (a former member)")
        assertTrue(g.isMember(late.sender!!).not() && g.formerMember(late.sender!!) != null)

        // the sidecar opens with the iOS store key and matches the manifest
        val store = SecureStore.open(dir.resolve("pyro.store"), manifest.obj("store").str("password"), InMemorySecretStore().apply {
            set(SecretStore.KDF_SALT, Fixtures.hex(manifest.obj("store").str("kdfSaltHex")))
        })
        store.use {
            val bytes = assertNotNull(it.readAttachment(image.attachmentId!!), "attachments/<id> sidecar")
            assertEquals(3200, bytes.size)
            assertEquals(manifest.obj("files").obj("attachments/${image.attachmentId}").str("plaintextSha256"), Fixtures.sha256Hex(bytes))
        }

        // and it re-encodes through the Android codec without losing any key (former + gone included)
        val again = SwiftJson.decodeBlob(SwiftJson.encodeBlob(blob))
        assertEquals(blob, again)
        assertEquals(groups, again.groups)
    }
}
