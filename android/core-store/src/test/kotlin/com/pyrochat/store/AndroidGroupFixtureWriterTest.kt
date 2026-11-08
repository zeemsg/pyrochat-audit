package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction.INCOMING
import com.pyrochat.store.model.ChatMessage.Direction.OUTGOING
import com.pyrochat.store.model.ChatMessage.Kind.SYSTEM
import com.pyrochat.store.model.ChatMessage.Kind.TEXT
import com.pyrochat.store.model.ChatMessage.Status.DELIVERED
import com.pyrochat.store.model.ChatMessage.Status.READ
import com.pyrochat.store.model.ChatMessage.Status.SENT
import com.pyrochat.store.model.Group
import com.pyrochat.store.model.Member
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/GROUPS.md §3 cross-platform gate, reverse direction: an Android-encoded store holding a
 * group thread for `ios/Tests` to import — `android/fixtures/android-store-groups/` (override with
 * `PYRO_ANDROID_GROUP_FIXTURE_DIR`), same layout as `android-store` (pyro.store + plaintext +
 * manifest; the iOS fixture's password + Keychain salt so the existing import path opens it).
 *
 * The group: `#ops`, 4 members (bob_fixture = creator, carol_fixture, the fixture owner, and
 * dave_fixture marked `gone`) + 1 former member (ghost_fixture), policy `burn_remove`, epoch 2,
 * one incoming row from carol, one outgoing row with receipts from both.
 */
class AndroidGroupFixtureWriterTest {
    /** Defaults to a build directory; see the note on `Fixtures.androidDir`. Writing into
     *  `android/fixtures/` is deliberate and requires PYRO_ANDROID_GROUP_FIXTURE_DIR. */
    private val out: Path = System.getenv("PYRO_ANDROID_GROUP_FIXTURE_DIR")?.let { Paths.get(it) }
        ?: Paths.get("build", "tmp", "fixture-out", "android-store-groups").toAbsolutePath().normalize()

    @Test fun writesAnAndroidEncodedStoreWithAGroupThreadForIosToImport() {
        if (Files.exists(out)) out.toFile().deleteRecursively()
        Files.createDirectories(out)
        val base = Fixtures.blob
        val me = base.usernameHash
        val bob = Fixtures.hashes.getValue("bob_fixture")
        val carol = Fixtures.hashes.getValue("carol_fixture")
        val gid = "6f6f7073-0000-4000-8000-000000000001"
        val group = Group(
            id = gid, name = "ops", creator = bob, createdAt = Fixtures.d(1000.0), epoch = 2, policy = "burn_remove",
            burnSeconds = 3600.0,
            members = listOf(
                Member(bob, "bob", Fixtures.d(1000.0)),
                Member(carol, "carol", Fixtures.d(1000.0)),
                Member(me, base.usernameDisplay, Fixtures.d(1000.0)),
                Member(Fixtures.hashes.getValue("dave_fixture"), "dave", Fixtures.d(1100.0), gone = true), // deleted their account
            ),
            former = listOf(Member(Fixtures.hashes.getValue("ghost_fixture"), "ghost", Fixtures.d(1050.0))), // left; keeps colour slot 3
        )
        val rows = listOf(
            ChatMessage(id = "6d000000-0000-4000-8000-000000000101", direction = INCOMING, kind = SYSTEM, text = "── carol joined ──", timestamp = Fixtures.d(1001.0), status = DELIVERED, group = gid),
            ChatMessage(id = "6d000000-0000-4000-8000-000000000102", direction = INCOMING, kind = TEXT, text = "ops channel up", timestamp = Fixtures.d(1002.0), status = READ, group = gid, sender = carol),
            ChatMessage(id = "6d000000-0000-4000-8000-000000000103", direction = OUTGOING, kind = TEXT, text = "ack", timestamp = Fixtures.d(1003.5), status = SENT, burnAfter = 3600.0, group = gid, receipts = mapOf(bob to "delivered", carol to "read")),
        )
        // §1a: no inline audio ever reaches a container
        val spill = VoiceSpill.plan(base.copy(groups = listOf(group), messages = base.messages + (gid to rows)))
        val blob = spill.blob

        val file = out.resolve(SecureStore.FILE_NAME)
        val store = SecureStore.restore(file, blob, spill.sidecars, Fixtures.password, Fixtures.secrets())
        store.close()
        val plaintext = SwiftJson.encodeBlob(blob)
        Files.write(out.resolve("pyro.store.plaintext.json"), plaintext)
        SecureStore.open(file, Fixtures.password, Fixtures.secrets()).use { reopened ->
            assertEquals(blob, reopened.blob.withoutSlotRecord())
            assertEquals(listOf(group), reopened.blob.groups)
            assertEquals(rows, reopened.blob.messages[gid])
        }
        val manifest = buildJsonObject {
            put("generator", "android core-store AndroidGroupFixtureWriterTest")
            put("spec", "docs/GROUPS.md §3")
            put("store", buildJsonObject {
                put("file", "pyro.store"); put("plaintext", "pyro.store.plaintext.json")
                put("password", Fixtures.password); put("kdfSaltHex", Fixtures.saltHex)
                put("kdf", "PBKDF2-HMAC-SHA256"); put("iterations", 600_000); put("keyBytes", 32)
                put("sha256", Fixtures.sha256Hex(Files.readAllBytes(file)))
                put("plaintextSha256", Fixtures.sha256Hex(plaintext))
            })
            put("group", buildJsonObject {
                put("id", gid); put("name", "ops"); put("creator", bob); put("policy", "burn_remove"); put("epoch", 2)
                put("burnSeconds", 3600); put("members", 4); put("membersGone", 1); put("former", 1); put("messages", rows.size)
                put("outgoingWithReceipts", "6d000000-0000-4000-8000-000000000103")
                put("incomingSender", carol)
            })
        }
        Files.writeString(out.resolve("manifest.json"), manifest.toString())
    }
}
