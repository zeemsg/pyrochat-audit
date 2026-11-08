package com.pyrochat.store

import com.pyrochat.store.codec.SwiftBase64
import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.crypto.DuressVerifier
import com.pyrochat.store.crypto.Pbkdf2HmacSha256
import com.pyrochat.store.crypto.StoreCrypto
import com.pyrochat.store.model.AppSettings
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction.INCOMING
import com.pyrochat.store.model.ChatMessage.Direction.OUTGOING
import com.pyrochat.store.model.ChatMessage.Kind.FILE
import com.pyrochat.store.model.ChatMessage.Kind.IMAGE
import com.pyrochat.store.model.ChatMessage.Kind.SYSTEM
import com.pyrochat.store.model.ChatMessage.Kind.TEXT
import com.pyrochat.store.model.ChatMessage.Kind.VOICE
import com.pyrochat.store.model.ChatMessage.Status.DELIVERED
import com.pyrochat.store.model.ChatMessage.Status.FAILED
import com.pyrochat.store.model.ChatMessage.Status.PENDING
import com.pyrochat.store.model.ChatMessage.Status.READ
import com.pyrochat.store.model.ChatMessage.Status.SENT
import com.pyrochat.store.model.Note
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The milestone-0 gate, forward direction: the REAL files iOS wrote (`android/fixtures/ios-store`)
 * decrypt, decode and re-encode exactly as `manifest.json` / `SPEC.md` promise — the Kotlin twin of
 * `verify.py`, plus the checks only a typed codec can make.
 */
class IosStoreFixtureTest {
    private val f = Fixtures
    private val blob: Blob get() = Fixtures.blob
    private val rows: List<ChatMessage> get() = blob.messages.values.flatten()
    private val bob: String get() = f.hashes.getValue("bob_fixture")
    private val carol: String get() = f.hashes.getValue("carol_fixture")
    private val dave: String get() = f.hashes.getValue("dave_fixture")
    private val stranger: String get() = f.hashes.getValue("stranger_fixture")
    private val ghost: String get() = f.hashes.getValue("ghost_fixture")

    private inline fun <reified T> raw(value: T): String = SwiftJson.encode(value).trim('"')
    private fun tally(obj: JsonObject): Map<String, Int> = obj.mapValues { it.value.jsonPrimitive.int }

    @Test fun storeDecryptsToTheExactPlaintextBytes() {
        val sealed = f.bytes("pyro.store")
        val expected = f.bytes("pyro.store.plaintext.json")
        val files = f.manifest.obj("files")
        assertEquals(files.obj("pyro.store").int("bytes"), sealed.size)
        assertEquals(files.obj("pyro.store").str("sha256"), f.sha256Hex(sealed))
        assertEquals(files.obj("pyro.store.plaintext.json").str("sha256"), f.sha256Hex(expected))
        val store = f.manifest.obj("store")
        assertEquals("PBKDF2-HMAC-SHA256", store.str("kdf"))
        assertEquals(StoreCrypto.PBKDF2_ITERATIONS, store.int("iterations"))
        assertEquals(StoreCrypto.KEY_BYTES, store.int("keyBytes"))
        assertContentEquals(expected, f.storePlaintext, "PBKDF2(password, kdf-salt) + AES-GCM open == the Swift JSONEncoder bytes")
        assertEquals(12 + expected.size + 16, sealed.size, "nonce(12) || ciphertext || tag(16)")
    }

    @Test fun countsMatchTheManifest() {
        val c = f.counts
        assertEquals(c.int("contacts"), blob.contacts.size)
        assertEquals(c.int("contactsVerified"), blob.contacts.count { it.verified })
        assertEquals(c.int("threads"), blob.messages.size)
        assertEquals(c.int("emptyThreads"), blob.messages.values.count { it.isEmpty() })
        assertEquals(c.int("messages"), rows.size)
        assertEquals(tally(c.obj("messagesByKind")), rows.groupingBy { raw(it.kind) }.eachCount())
        assertEquals(tally(c.obj("messagesByDirection")), rows.groupingBy { raw(it.direction) }.eachCount())
        assertEquals(tally(c.obj("messagesByStatus")), rows.groupingBy { raw(it.status) }.eachCount())
        assertEquals(c.int("messagesWithBurnAfter"), rows.count { it.burnAfter != null })
        assertEquals(c.int("messagesWithBurnStartedAt"), rows.count { it.burnStartedAt != null })
        assertEquals(c.int("messagesViaWeb"), rows.count { it.viaWeb })
        assertEquals(c.int("messagesWithAttachment"), rows.count { it.attachmentId != null })
        assertEquals(c.int("messagesWithVoiceData"), rows.count { it.voiceData != null })
        assertEquals(c.int("attachments"), rows.mapNotNull { it.attachmentId }.distinct().size)
        assertEquals(c.int("notes"), blob.notes!!.size)
        assertEquals(c.int("notesPinned"), blob.notes!!.count { it.isPinned })
        assertEquals(c.int("drafts"), blob.drafts!!.size)
        assertEquals(c.int("blockedHashes"), blob.blockedHashes!!.size)
        assertEquals(c.int("accessTokens"), blob.accessTokens.size)
        assertEquals(c.int("preKeys"), blob.preKeys.size)
        assertEquals((1..32).toList(), blob.preKeys.keys.sorted(), "one-time prekey ids 1…32")
        assertEquals(c.int("signedPreKeys"), blob.signedPreKeys.size)
        assertEquals(setOf(1), blob.signedPreKeys.keys)
        assertEquals(c.int("kyberPreKeys"), blob.kyberPreKeys.size)
        assertEquals(setOf(1), blob.kyberPreKeys.keys)
        assertEquals(c.int("sessions"), blob.sessions.size)
        assertEquals(c.int("knownIdentities"), blob.knownIdentities.size)
        assertEquals(c.int("nextPreKeyId"), blob.nextPreKeyId)
        val outbox = blob.receiptOutbox!!
        val lanes = c.obj("receiptOutbox")
        assertEquals(lanes.int("delivered"), outbox.delivered.values.sumOf { it.size })
        assertEquals(lanes.int("read"), outbox.read.values.sumOf { it.size })
        assertEquals(lanes.int("burnStart"), outbox.burnStart.values.sumOf { it.size })
    }

    @Test fun identityAndLibsignalMaterial() {
        val identity = f.manifest.obj("identity")
        val peer = f.manifest.obj("peer")
        assertEquals(identity.str("usernameDisplay"), blob.usernameDisplay)
        assertEquals(identity.str("usernameHash"), blob.usernameHash)
        assertEquals(f.sha256Hex(blob.usernameDisplay.lowercase().toByteArray()), blob.usernameHash, "usernameHash = sha256(lowercased username)")
        assertEquals(identity.int("registrationId"), blob.registrationId)
        assertEquals(identity.int("authPrivateKeyBytes"), blob.authPrivateKey.size)
        assertEquals(32, blob.authPrivateKey.size, "Ed25519 seed")
        assertEquals(identity.int("identityKeyPairBytes"), blob.identityKeyPair.size)
        val pair = IdentityKeyPair(blob.identityKeyPair.bytes)
        assertEquals(identity.str("identityPublicKeyB64"), SwiftBase64.encode(pair.publicKey.serialize()))
        assertEquals(setOf(bob), blob.sessions.keys, "sessions keyed by peer hash")
        assertEquals(peer.str("usernameHash"), bob)
        val sessionBytes = blob.sessions.getValue(bob)
        assertEquals(peer.int("sessionRecordBytes"), sessionBytes.size)
        val known = blob.knownIdentities.getValue(bob)
        assertEquals(peer.str("identityKeyB64"), SwiftBase64.encode(known.bytes))
        assertEquals(peer.int("identityKeyBytes"), known.size)
        assertEquals(0x05, known.bytes[0].toInt(), "DJB type prefix")
        // every persisted record is loadable by libsignal-java and keyed by its own id
        val session = SessionRecord(sessionBytes.bytes)
        assertTrue(session.hasSenderChain(), "hasSession on iOS ⇔ a current state")
        assertEquals(peer.int("registrationId"), session.remoteRegistrationId)
        assertEquals(IdentityKey(known.bytes), session.remoteIdentityKey)
        assertEquals(pair.publicKey, session.localIdentityKey)
        for ((id, bytes) in blob.preKeys) {
            assertEquals(id, PreKeyRecord(bytes.bytes).id)
            assertEquals(71, bytes.size)
        }
        for ((id, bytes) in blob.signedPreKeys) {
            assertEquals(id, SignedPreKeyRecord(bytes.bytes).id)
            assertEquals(146, bytes.size)
        }
        for ((id, bytes) in blob.kyberPreKeys) {
            assertEquals(id, KyberPreKeyRecord(bytes.bytes).id)
            assertEquals(4821, bytes.size)
        }
    }

    @Test fun contactsBlockListAndPlaceholder() {
        val byName = blob.contacts.associateBy { it.name }
        assertEquals(listOf("bob_fixture", "carol_fixture", "dave_fixture", "anon-c11801ff"), blob.contacts.map { it.name }, "array order is preserved")
        val bobContact = byName.getValue("bob_fixture")
        assertEquals(bob, bobContact.hash)
        assertTrue(bobContact.verified)
        assertNull(bobContact.burnSeconds, "absent = use the global default")
        assertEquals(f.d(-1000.0), bobContact.addedAt)
        val carolContact = byName.getValue("carol_fixture")
        assertEquals(carol, carolContact.hash)
        assertFalse(carolContact.verified)
        assertEquals(0.0, carolContact.burnSeconds, "0 = explicitly off")
        assertEquals(listOf(carol), blob.blockedHashes)
        val daveContact = byName.getValue("dave_fixture")
        assertEquals(86_400.0, daveContact.burnSeconds)
        assertEquals(f.d(-800.0), daveContact.addedAt)
        val anon = byName.getValue("anon-" + stranger.take(8))
        assertEquals(stranger, anon.hash)
        assertEquals("anon-c11801ff", anon.name)
        assertFalse(anon.verified)
        assertNull(anon.burnSeconds)
        assertEquals(f.d(320.0), anon.addedAt)
        // a stranger's burn_thread leaves an empty thread and no contact
        assertEquals(emptyList(), blob.messages[ghost])
        assertNull(blob.contacts.find { it.hash == ghost })
        assertEquals(setOf(bob, carol, dave, stranger, ghost), blob.messages.keys)
    }

    @Test fun messageRowsCarryEveryFieldExactly() {
        val thread = blob.messages.getValue(bob)
        assertEquals(24, thread.size)
        fun row(n: Int): ChatMessage = thread[n - 1].also { assertEquals(f.mid(n), it.id) }
        val ids = f.attachmentIds
        assertEquals(
            ChatMessage(f.mid(1), INCOMING, TEXT, "hey alice — first contact from bob", f.d(0.0), DELIVERED),
            row(1),
        )
        assertEquals(OUTGOING, row(2).direction)
        assertEquals(READ, row(2).status)
        assertEquals(SENT, row(3).status)
        assertEquals(PENDING, row(4).status)
        assertEquals("still typing this… (never left the device)", row(4).text)
        assertEquals(FAILED, row(5).status)
        assertEquals(READ, row(6).status)
        assertTrue(row(7).viaWeb && row(8).viaWeb)
        assertEquals(OUTGOING, row(7).direction)
        assertEquals(INCOMING, row(8).direction)
        assertEquals(2, thread.count { it.viaWeb })
        assertEquals(60.0, row(9).burnAfter)
        assertNull(row(9).burnStartedAt)
        assertNull(row(9).burnDeadline)
        assertEquals(3600.0, row(10).burnAfter)
        assertEquals(f.d(90.5), row(10).burnStartedAt, "sub-second burnStartedAt")
        assertEquals(f.d(3690.5), row(10).burnDeadline)
        assertEquals(86_400.0, row(11).burnAfter)
        assertEquals(f.d(101.0), row(11).burnStartedAt)
        val voiceOut = row(12)
        assertEquals(VOICE, voiceOut.kind)
        assertEquals("[voice 3s]", voiceOut.text, "Int(duration) truncates")
        assertEquals(3.4, voiceOut.voiceDuration)
        assertEquals(Bytes(f.lcgPattern(1536, 0x21)), voiceOut.voiceData, "voice bytes are inline")
        assertNull(voiceOut.attachmentId)
        val voiceIn = row(13)
        assertEquals(Bytes(f.lcgPattern(2048, 0x22)), voiceIn.voiceData)
        assertEquals(7.0, voiceIn.voiceDuration)
        assertEquals(5.0, voiceIn.burnAfter)
        val img = row(14)
        assertEquals(IMAGE, img.kind)
        assertEquals("[IMG]", img.text)
        assertEquals(ids.getValue("imageOutgoing"), img.attachmentId)
        assertEquals("image/jpeg", img.fileMime)
        assertEquals(4096, img.fileBytes)
        assertEquals(640, img.imageWidth)
        assertEquals(480, img.imageHeight)
        assertNull(img.fileName, "images carry no fileName")
        assertNull(img.voiceData)
        val imgIn = row(15)
        assertEquals(ids.getValue("imageIncoming"), imgIn.attachmentId)
        assertEquals(READ, imgIn.status)
        assertEquals(60.0, imgIn.burnAfter)
        assertEquals(f.d(141.0), imgIn.burnStartedAt)
        assertEquals(1024, imgIn.imageWidth)
        assertEquals(768, imgIn.imageHeight)
        assertEquals(3000, imgIn.fileBytes)
        val fileOut = row(16)
        assertEquals(FILE, fileOut.kind)
        assertEquals("[FILE] report.pdf", fileOut.text)
        assertEquals(ids.getValue("fileOutgoing"), fileOut.attachmentId)
        assertEquals("report.pdf", fileOut.fileName)
        assertEquals("application/pdf", fileOut.fileMime)
        assertEquals(2500, fileOut.fileBytes)
        assertEquals(SENT, fileOut.status)
        assertNull(fileOut.imageWidth)
        val fileIn = row(17)
        assertEquals(ids.getValue("fileIncoming"), fileIn.attachmentId)
        assertEquals("notes.txt", fileIn.fileName)
        assertEquals("text/plain", fileIn.fileMime)
        assertEquals(700, fileIn.fileBytes)
        for (n in 18..23) {
            assertEquals(SYSTEM, row(n).kind, "row $n")
            assertEquals(INCOMING, row(n).direction)
            assertEquals(DELIVERED, row(n).status)
        }
        assertEquals("⚠ peer took a screenshot", row(18).text)
        assertEquals("⚠ peer may be recording the screen", row(19).text)
        assertEquals("⚠ peer linked a desktop browser (Chrome) — this conversation may be viewed there", row(20).text)
        assertEquals("peer unlinked the desktop browser", row(21).text)
        assertEquals("⚠ <bob_fixture>'s identity key changed — re-verify before trusting", row(22).text)
        assertEquals("⚠ <bob_fixture> deleted their account — messages can no longer be delivered.", row(23).text)
        assertEquals(
            "escapes: café 🔥 — \"quoted\" back\\slash /slash/ tab\there\nnewline <b>&amp;</b> ctrl del",
            row(24).text,
            "every JSON escape Swift emits decodes back",
        )
        assertEquals(TEXT, row(24).kind)
        assertEquals((0 until 24).map { f.d(it * 10.0) }, thread.map { it.timestamp }, "timestamps 10 s apart")
        val daveRow = blob.messages.getValue(dave).single()
        assertEquals("🔥 thread burned by peer", daveRow.text)
        assertEquals(SYSTEM, daveRow.kind)
        assertEquals(f.mid(25), daveRow.id)
        assertEquals("message received before carol was blocked", blob.messages.getValue(carol).single().text)
        val strangerRow = blob.messages.getValue(stranger).single()
        assertEquals("first contact from a stranger — auto-created anon contact", strangerRow.text)
        assertEquals(f.d(320.0), strangerRow.timestamp)
    }

    @Test fun settingsAreAllNonDefault() {
        assertEquals(
            AppSettings(
                theme = "amber", scanlines = false, pushEnabled = true, defaultBurnSeconds = 3600.0,
                threadAutoBurnDays = 7, burnOnScreenshot = true, sendReadReceipts = true, soundsEnabled = false,
                sendTyping = false, blockScreenshots = false, socksProxy = "127.0.0.1:9050", wipeAfterDays = 30,
                autoLockMinutes = 15, relayURL = "wss://relay.example.test/ws", billingURL = "https://billing.example.test",
            ),
            blob.settings,
        )
        with(blob.settings) {
            assertTrue(readReceiptsOn)
            assertFalse(soundsOn)
            assertFalse(typingOn)
            assertFalse(screenshotBlockOn)
            assertEquals(15, autoLockAfterMinutes)
        }
    }

    @Test fun notesDraftsTokensAndReceiptOutbox() {
        val notes = blob.notes!!
        assertEquals(
            Note("6e000000-0000-4000-8000-000000000001", "ops checklist\n\n- rotate keys\n- verify bob", f.d(-500.0), f.d(-400.0), pinned = true),
            notes[0],
        )
        assertEquals(false, notes[1].pinned)
        assertEquals("second note\nwith a preview line", notes[1].text)
        assertEquals(f.d(-450.0), notes[1].modifiedAt)
        assertNull(notes[2].pinned, "absent pinned")
        assertFalse(notes[2].isPinned)
        assertEquals("  \n\nleading blank lines then the title", notes[2].text)
        assertEquals(f.d(-300.0), notes[2].createdAt)
        assertEquals(f.d(-299.25), notes[2].modifiedAt)
        assertEquals(750_000_000, notes[2].modifiedAt.nano)
        assertEquals(mapOf(bob to "unsent draft for bob 🔥", dave to "draft for dave"), blob.drafts)
        val tokens = blob.accessTokens
        assertEquals(listOf(20350L, 20351L), tokens.map { it.epochDay })
        for (token in tokens) {
            assertEquals(40, token.token.size)
            assertEquals(256, token.signature.size)
            assertEquals(token.epochDay, ByteBuffer.wrap(token.token.bytes).long, "token = epochDay big-endian || nonce")
        }
        assertEquals(Bytes(f.lcgPattern(32, 0x31)), Bytes(tokens[0].token.bytes.copyOfRange(8, 40)))
        assertEquals(Bytes(f.lcgPattern(256, 0x32)), tokens[0].signature)
        assertEquals(Bytes(f.lcgPattern(256, 0x34)), tokens[1].signature)
        val outbox = blob.receiptOutbox!!
        assertEquals(mapOf(bob to listOf(f.mid(1))), outbox.delivered)
        assertEquals(mapOf(bob to listOf(f.mid(6), f.mid(10))), outbox.read)
        assertEquals(mapOf(dave to listOf(f.mid(99))), outbox.burnStart)
        assertFalse(outbox.isEmpty)
    }

    @Test fun reencodeIsSemanticallyIdenticalToTheSwiftBytesAndRoundTrips() {
        val reencoded = SwiftJson.encodeBlob(blob)
        assertEquals(blob, SwiftJson.decodeBlob(reencoded), "decode ∘ encode is the identity")
        assertFalse(reencoded.toString(Charsets.UTF_8).contains("null"))
        // the same JSON tree Swift wrote, once the order-free flat arrays are normalised to maps
        assertEquals(normalize(f.storePlaintext), normalize(reencoded), "Android bytes parse to the iOS tree (numbers, base64, escapes included)")
    }

    private fun normalize(bytes: ByteArray): JsonObject {
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.toMutableMap()
        for (key in listOf("preKeys", "signedPreKeys", "kyberPreKeys")) {
            val flat = obj.getValue(key).jsonArray
            assertEquals(0, flat.size % 2)
            obj[key] = JsonObject(flat.chunked(2).associate { (k, v) -> k.jsonPrimitive.content.toDouble().toInt().toString() to v })
        }
        return JsonObject(obj)
    }

    @Test fun opensThroughSecureStoreAndRejectsAWrongPassword() {
        val store = f.iosStore
        assertEquals(blob, store.blob)
        assertEquals(f.iosDir.resolve("attachments"), store.attachmentsDir)
        assertFailsWith<StoreError.BadPassword> { SecureStore.open(f.iosDir.resolve("pyro.store"), "wrong password", f.secrets()) }
        assertFailsWith<StoreError.NoStore> { SecureStore.open(f.iosDir.resolve("missing.store"), f.password, f.secrets()) }
    }

    @Test fun sidecarsDecryptToTheManifestHashes() {
        val ids = f.attachmentIds
        assertEquals(ids.values.sorted(), f.iosStore.attachmentIds())
        val sidecars = f.manifest.obj("files").filterKeys { it.startsWith("attachments/") }
        assertEquals(4, sidecars.size)
        for ((name, element) in sidecars) {
            val id = name.removePrefix("attachments/")
            val info = element.jsonObject
            val sealed = f.bytes(name)
            assertEquals(info.int("sealedBytes"), sealed.size)
            assertEquals(info.str("sealedSha256"), f.sha256Hex(sealed))
            val plain = StoreCrypto.open(f.storeKey, sealed)
            assertEquals(info.int("plaintextBytes"), plain.size)
            assertEquals(info.str("plaintextSha256"), f.sha256Hex(plain), name)
            assertEquals(plain.size + 28, sealed.size)
            assertContentEquals(plain, f.iosStore.readAttachment(id), "readAttachment(id) through the opened store")
        }
        assertContentEquals(f.lcgPattern(4096, 0x11), f.iosStore.readAttachment(ids.getValue("imageOutgoing")))
        assertContentEquals(f.lcgPattern(3000, 0x12), f.iosStore.readAttachment(ids.getValue("imageIncoming")))
        assertContentEquals(f.lcgPattern(2500, 0x13), f.iosStore.readAttachment(ids.getValue("fileOutgoing")))
        assertContentEquals(f.lcgPattern(700, 0x14), f.iosStore.readAttachment(ids.getValue("fileIncoming")))
        assertEquals(ids.values.toSet(), rows.mapNotNull { it.attachmentId }.toSet(), "every attachmentId has a sidecar and vice versa")
        for (row in rows.filter { it.attachmentId != null }) {
            assertEquals(row.fileBytes, f.iosStore.readAttachment(row.attachmentId!!)!!.size, "fileBytes = plaintext size")
        }
        assertNull(f.iosStore.readAttachment("a1a1a1a1-0000-4000-8000-0000000000ff"))
    }

    @Test fun backupDecodesAndMatchesTheStore() {
        val bk = f.manifest.obj("backup")
        val bak = f.bytes("backup.pyrobak")
        assertEquals(f.manifest.obj("files").obj("backup.pyrobak").str("sha256"), f.sha256Hex(bak))
        assertContentEquals(Backup.MAGIC, bak.copyOfRange(0, 8))
        val salt = bak.copyOfRange(8, 40)
        assertEquals(bk.str("saltHex"), f.toHex(salt))
        val key = StoreCrypto.deriveKey(Pbkdf2HmacSha256, f.backupPassphrase, salt)
        val plain = StoreCrypto.open(key, bak.copyOfRange(40, bak.size))
        assertContentEquals(f.bytes("backup.plaintext.json"), plain, "backup decrypts to backup.plaintext.json byte-for-byte")
        assertEquals(40 + 12 + plain.size + 16, bak.size)
        val payload = SwiftJson.decode<Backup.Payload>(plain.toString(Charsets.UTF_8))
        assertEquals(blob, payload.blob, "backup.blob == store blob")
        assertEquals(bk.getValue("exportedAt").jsonPrimitive.double, SwiftDate.toSeconds(payload.exportedAt))
        val sidecars = f.attachmentIds.values.associateWith { Bytes(f.iosStore.readAttachment(it)!!) }
        assertEquals(sidecars, payload.attachments, "backup.attachments == sidecar plaintexts")
        // through the real API
        assertEquals(payload, Backup.decode(bak, f.backupPassphrase))
        assertFailsWith<StoreError.BadBackupPassphrase> { Backup.decode(bak, "wrong-passphrase") }
        assertFailsWith<StoreError.BadBackupFile> { Backup.decode(bak.copyOfRange(1, bak.size), f.backupPassphrase) }
    }

    @Test fun duressVerifierMatchesTheKeychain() {
        val item = f.keychain.obj("items").obj("duress-verifier")
        assertEquals(32, f.duressVerifier.size)
        assertEquals(item.str("base64"), SwiftBase64.encode(f.duressVerifier))
        assertEquals(f.saltHex, f.keychain.obj("items").obj("kdf-salt").str("hex"))
        assertContentEquals(f.duressVerifier, DuressVerifier.compute(Pbkdf2HmacSha256, f.duressPin, f.salt), "verifier = PBKDF2(pin, kdf-salt, 600k, 32)")
        assertTrue(SecureStore.matchesDuressPin(f.secrets(), Pbkdf2HmacSha256, f.duressPin))
        assertFalse(SecureStore.matchesDuressPin(f.secrets(), Pbkdf2HmacSha256, "0000"))
        assertFalse(SecureStore.matchesDuressPin(InMemorySecretStore().apply { set(SecretStore.KDF_SALT, f.salt) }, Pbkdf2HmacSha256, f.duressPin), "no verifier ⇒ never matches")
    }
}
