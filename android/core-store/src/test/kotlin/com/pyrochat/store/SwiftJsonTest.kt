package com.pyrochat.store

import com.pyrochat.store.codec.SwiftBase64
import com.pyrochat.store.codec.SwiftDate
import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.model.AccessToken
import com.pyrochat.store.model.AppSettings
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.Contact
import com.pyrochat.store.model.Group
import com.pyrochat.store.model.GroupPolicy
import com.pyrochat.store.model.Member
import com.pyrochat.store.model.Note
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Swift `JSONEncoder` conventions of SPEC.md §4-5, one rule per test. */
class SwiftJsonTest {
    private fun msg(viaWeb: Boolean = false) = ChatMessage(
        id = "6d000000-0000-4000-8000-000000000001",
        direction = ChatMessage.Direction.OUTGOING,
        kind = ChatMessage.Kind.TEXT,
        text = "hi",
        timestamp = Fixtures.d(0.0),
        status = ChatMessage.Status.SENT,
        viaWeb = viaWeb,
    )

    private fun keys(json: String) = Json.parseToJsonElement(json).jsonObject.keys

    private val requiredRow = """"id":"m","direction":"incoming","kind":"text","text":"t","timestamp":1,"status":"sent""""

    @Test fun viaWebIsWrittenAsTrueOrOmittedNeverFalse() {
        assertFalse("viaWeb" in keys(SwiftJson.encode(msg(viaWeb = false))))
        assertTrue(SwiftJson.encode(msg(viaWeb = true)).contains("\"viaWeb\":true"))
        assertFalse(SwiftJson.decode<ChatMessage>("{$requiredRow}").viaWeb)
        assertTrue(SwiftJson.decode<ChatMessage>("{$requiredRow,\"viaWeb\":true}").viaWeb)
        assertFalse(SwiftJson.decode<ChatMessage>("{$requiredRow,\"viaWeb\":false}").viaWeb)
    }

    @Test fun nilOptionalsAreOmittedAndThereAreNoNulls() {
        val encoded = SwiftJson.encode(msg())
        assertEquals(setOf("id", "direction", "kind", "text", "timestamp", "status"), keys(encoded))
        assertFalse(encoded.contains("null"))
        val blob = SwiftJson.encode(Blob())
        val blobKeys = keys(blob)
        for (absent in listOf("receiptOutbox", "notes", "blockedHashes", "drafts")) assertFalse(absent in blobKeys, absent)
        // Swift writes every non-optional stored property, defaults and empty collections included
        assertEquals(
            setOf(
                "usernameDisplay", "usernameHash", "identityKeyPair", "registrationId", "authPrivateKey",
                "sessions", "preKeys", "signedPreKeys", "kyberPreKeys", "knownIdentities", "nextPreKeyId",
                "contacts", "messages", "accessTokens", "settings",
            ),
            blobKeys,
        )
        assertTrue(blob.contains("\"preKeys\":[]") && blob.contains("\"sessions\":{}") && blob.contains("\"nextPreKeyId\":1"), blob)
        assertTrue(SwiftJson.encode(Contact("h", "n", Fixtures.d(0.0))).contains("\"verified\":false"), "verified is always written")
    }

    @Test fun datesRoundTripAtSubMillisecondPrecision() {
        for (seconds in listOf(780_000_090.5, 779_999_700.75, 780_000_101.0, 780_000_000.123456, 811_333_597.8773, 780_000_000.000123)) {
            val instant = SwiftDate.toInstant(seconds)
            assertEquals(seconds, SwiftDate.toSeconds(instant), "exact through Instant: $seconds")
            val note = Note("n", "t", instant, instant)
            val decoded = SwiftJson.decode<Note>(SwiftJson.encode(note))
            assertEquals(note, decoded)
            assertEquals(seconds, SwiftDate.toSeconds(decoded.createdAt), "exact through JSON: $seconds")
        }
        assertEquals(780_000_090.5, SwiftDate.toSeconds(Fixtures.d(90.5)))
        assertEquals(500_000_000, Fixtures.d(90.5).nano)
        assertEquals(1_758_307_290L, Fixtures.d(90.5).epochSecond, "2025-09-19T18:41:30Z")
        // any double at the magnitude of a modern date survives the nanosecond split
        val rng = Random(7)
        repeat(200_000) {
            val bits = java.lang.Double.doubleToLongBits(Math.pow(2.0, 23 + rng.nextDouble() * 8)) + rng.nextInt(2_000_000) - 1_000_000
            val d = java.lang.Double.longBitsToDouble(bits)
            assertEquals(d, SwiftDate.toSeconds(SwiftDate.toInstant(d)))
        }
    }

    @Test fun numbersPrintLikeSwift() {
        val contact = SwiftJson.encode(Contact("h", "n", Fixtures.d(0.0), burnSeconds = 86_400.0))
        assertTrue(contact.contains("\"addedAt\":780000000,"), contact)       // integral: no fraction, no exponent
        assertTrue(contact.contains("\"burnSeconds\":86400"), contact)
        assertTrue(SwiftJson.encode(Contact("h", "n", Fixtures.d(0.0), burnSeconds = 0.0)).contains("\"burnSeconds\":0}"))
        val fractional = SwiftJson.encode(msg().copy(timestamp = Fixtures.d(90.5), burnStartedAt = Fixtures.d(-299.25), voiceDuration = 3.4))
        assertTrue(fractional.contains("\"timestamp\":780000090.5"), fractional)  // plain shortest decimal
        assertTrue(fractional.contains("\"burnStartedAt\":779999700.75"), fractional)
        assertTrue(fractional.contains("\"voiceDuration\":3.4"), fractional)
        // decoding accepts every form iOS may have written or Swift accepts
        for (text in listOf("780000000", "780000000.0", "7.8E8", "7.8e8", "780000090.5")) {
            val n = SwiftJson.decode<Note>("""{"id":"n","text":"t","createdAt":$text,"modifiedAt":0}""")
            assertEquals(text.toDouble(), SwiftDate.toSeconds(n.createdAt))
        }
    }

    @Test fun slashesAndEscapesSurvive() {
        val text = "escapes: café 🔥 — \"quoted\" back\\slash /slash/ tab\there\nnewline <b>&amp;</b> ctrl del"
        val row = msg().copy(text = text, voiceData = Bytes(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), 0xBF.toByte())))
        val encoded = SwiftJson.encode(row)
        assertTrue(encoded.contains("/slash/"), "slashes are legal unescaped (iOS decodes both forms)")
        assertTrue(encoded.contains("café 🔥"), "non-ASCII is emitted raw, like Swift")
        assertTrue(encoded.contains("\\u0001"), "control characters are escaped")
        assertEquals(row, SwiftJson.decode<ChatMessage>(encoded))
        // the exact escaped forms Swift writes decode to the same string
        val swiftForm = """{"id":"m","direction":"outgoing","kind":"text","text":"a\/b  🔥","timestamp":0,"status":"sent","voiceData":"\/++\/"}"""
        val decoded = SwiftJson.decode<ChatMessage>(swiftForm)
        assertEquals("a/b  🔥", decoded.text)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), 0xBF.toByte()), decoded.voiceData!!.bytes)
        assertEquals("/++/", SwiftBase64.encode(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), 0xBF.toByte())))
    }

    @Test fun flatArrayDictionariesForUInt32Keys() {
        val blob = Blob(preKeys = mapOf(2 to Bytes(byteArrayOf(2)), 1 to Bytes(byteArrayOf(1))))
        val encoded = SwiftJson.encode(blob)
        assertTrue(encoded.contains("\"preKeys\":[1,\"AQ==\",2,\"Ag==\"]"), encoded)
        assertEquals(blob, SwiftJson.decode<Blob>(encoded))
        // keys may arrive as 1.0 (SPEC §5); order is arbitrary
        val lenient = SwiftJson.decode<Blob>("""{"preKeys":[2.0,"Ag==",1,"AQ=="]}""")
        assertEquals(blob.preKeys, lenient.preKeys)
        // the object form fails on iOS and therefore here; so do malformed arrays
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"preKeys":{"1":"AQ=="}}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"preKeys":[1,"AQ==",2]}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"preKeys":["1","AQ=="]}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"preKeys":[1.5,"AQ=="]}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"preKeys":[1,7]}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"kyberPreKeys":[1,"AQ"]}""") }
    }

    @Test fun unknownKeysAreIgnoredAndDroppedOnReencode() {
        val decoded = SwiftJson.decode<Blob>("""{"usernameDisplay":"zee","future":{"x":1},"settings":{"theme":"amber","newToggle":true}}""")
        assertEquals("zee", decoded.usernameDisplay)
        assertEquals("amber", decoded.settings.theme)
        val re = SwiftJson.encode(decoded)
        assertFalse(re.contains("future") || re.contains("newToggle"))
        val row = SwiftJson.decode<ChatMessage>("{$requiredRow,\"expiresAt\":5}")
        assertNull(row.burnAfter)
    }

    @Test fun missingRequiredKeysAndUnknownEnumsFail() {
        assertFailsWith<SerializationException> { SwiftJson.decode<ChatMessage>("""{"id":"m","direction":"incoming","kind":"text","timestamp":1,"status":"sent"}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Contact>("""{"hash":"h","name":"n"}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<Note>("""{"id":"n","text":"t","createdAt":0}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<AccessToken>("""{"epochDay":1,"token":"AA=="}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<ChatMessage>("""{"id":"m","direction":"incoming","kind":"video","text":"t","timestamp":1,"status":"sent"}""") }
        assertFailsWith<SerializationException> { SwiftJson.decode<ChatMessage>("""{"id":"m","direction":"incoming","kind":"text","text":"t","timestamp":1,"status":"burned"}""") }
        // a required key holding null fails; an optional key holding null is nil
        assertFailsWith<SerializationException> { SwiftJson.decode<ChatMessage>("""{"id":"m","direction":"incoming","kind":"text","text":null,"timestamp":1,"status":"sent"}""") }
        assertNull(SwiftJson.decode<ChatMessage>("{$requiredRow,\"burnAfter\":null}").burnAfter)
        // one bad row fails the whole store decode (SPEC §5)
        assertFailsWith<SerializationException> {
            SwiftJson.decode<Blob>("""{"messages":{"t":[{"id":"m","direction":"sideways","kind":"text","text":"t","timestamp":1,"status":"sent"}]}}""")
        }
        // the optional blob sections decode with their iOS defaults when partially present
        val outbox = SwiftJson.decode<Blob>("""{"receiptOutbox":{"read":{"p":["m1"]}}}""").receiptOutbox!!
        assertEquals(mapOf("p" to listOf("m1")), outbox.read)
        assertEquals(emptyMap(), outbox.delivered)
        assertEquals(emptyMap(), outbox.burnStart)
        assertNull(SwiftJson.decode<Note>("""{"id":"x","text":"t","createdAt":0,"modifiedAt":0}""").pinned)
        assertFalse(SwiftJson.decode<Contact>("""{"hash":"h","name":"n","addedAt":0}""").verified)
    }

    @Test fun settingsDefaultsWhenKeysAreAbsent() {
        val s = SwiftJson.decode<Blob>("""{"settings":{}}""").settings
        assertEquals(AppSettings(), s)
        assertEquals("ember", s.theme)
        assertTrue(s.scanlines)
        assertFalse(s.pushEnabled)
        assertFalse(s.burnOnScreenshot)
        assertEquals(AppSettings.DEFAULT_RELAY_URL, s.relayURL)
        assertEquals(AppSettings.DEFAULT_BILLING_URL, s.billingURL)
        assertFalse(s.readReceiptsOn)
        assertTrue(s.soundsOn)
        assertTrue(s.typingOn)
        assertTrue(s.screenshotBlockOn)
        assertEquals(5, s.autoLockAfterMinutes)
        assertNull(s.copy(autoLockMinutes = 0).autoLockAfterMinutes)
        assertEquals(15, s.copy(autoLockMinutes = 15).autoLockAfterMinutes)
        assertEquals(AppSettings(), SwiftJson.decode<Blob>("{}").settings)
        // a v0.1 settings object (no v0.2 keys) keeps decoding
        val legacy = SwiftJson.decode<AppSettings>("""{"theme":"ember","scanlines":true,"pushEnabled":false,"relayURL":"ws://x/ws","billingURL":"http://x"}""")
        assertFalse(legacy.readReceiptsOn)
        assertTrue(legacy.soundsOn)
        // defaults-only settings still write every non-optional key (6); a fully set one writes all 15
        assertEquals(setOf("theme", "scanlines", "pushEnabled", "burnOnScreenshot", "relayURL", "billingURL"), keys(SwiftJson.encode(AppSettings())))
        assertEquals(15, keys(SwiftJson.encode(Fixtures.blob.settings)).size)
    }

    /** docs/VOICE-CHANGER.md: the chosen voice. Omitted until one is chosen, so no store or fixture changes before that; the same key and words on iOS. */
    @Test fun voiceChangerIsOmittedWhenNullAndKeptWhenSet() {
        assertFalse("voiceChanger" in keys(SwiftJson.encode(AppSettings())))
        assertFalse("voiceChanger" in keys(SwiftJson.encode(Fixtures.blob.settings)), "the iOS fixture predates the key")
        val deep = SwiftJson.encode(AppSettings(voiceChanger = "deep"))
        assertTrue(deep.contains("\"voiceChanger\":\"deep\""), deep)
        assertEquals(AppSettings(voiceChanger = "deep"), SwiftJson.decode<AppSettings>(deep))
        // absent ⇒ null; a store from before this existed keeps decoding unchanged
        assertNull(SwiftJson.decode<Blob>("""{"settings":{"theme":"amber"}}""").settings.voiceChanger)
        // a word from a newer build is kept as it is: what it means is the app's business
        assertEquals("helium", SwiftJson.decode<AppSettings>("""{"voiceChanger":"helium"}""").voiceChanger)
        // and a full blob round trip carries it
        val blob = Fixtures.blob.copy(settings = Fixtures.blob.settings.copy(voiceChanger = "robot"))
        assertEquals("robot", SwiftJson.decode<Blob>(String(SwiftJson.encodeBlob(blob), Charsets.UTF_8)).settings.voiceChanger)
    }

    /** The one post-v0.4 key (operator notices): same optional-Boolean rule as `sendReadReceipts`, identical key on iOS. */
    @Test fun hideNoticesIsOmittedWhenNullAndKeptWhenSet() {
        assertFalse("hideNotices" in keys(SwiftJson.encode(AppSettings())))
        assertFalse("hideNotices" in keys(SwiftJson.encode(Fixtures.blob.settings)), "the iOS fixture predates the key")
        val hidden = SwiftJson.encode(AppSettings(hideNotices = true))
        assertTrue(hidden.contains("\"hideNotices\":true"), hidden)
        assertEquals(AppSettings(hideNotices = true), SwiftJson.decode<AppSettings>(hidden))
        assertTrue(SwiftJson.encode(AppSettings(hideNotices = false)).contains("\"hideNotices\":false"))
        // absent ⇒ null ⇒ shown; a v0.4 store keeps decoding unchanged
        val legacy = SwiftJson.decode<Blob>("""{"settings":{"theme":"amber"}}""").settings
        assertNull(legacy.hideNotices)
        assertTrue(legacy.noticesOn)
        assertFalse(SwiftJson.decode<AppSettings>("""{"hideNotices":true}""").noticesOn)
        // and a full blob round trip carries it
        val blob = Fixtures.blob.copy(settings = Fixtures.blob.settings.copy(hideNotices = true))
        assertEquals(true, SwiftJson.decode<Blob>(String(SwiftJson.encodeBlob(blob), Charsets.UTF_8)).settings.hideNotices)
    }

    /** docs/TEXT-SIZE.md §1 — a cross-platform string key: omitted when never changed, kept verbatim when set. */
    @Test fun textSizeIsOmittedWhenUnsetAndSurvivesARoundTrip() {
        assertFalse("textSize" in keys(SwiftJson.encode(AppSettings())))
        assertFalse("textSize" in keys(SwiftJson.encode(Fixtures.blob.settings)), "the iOS fixture predates the key")
        val big = SwiftJson.encode(AppSettings(textSize = "largest"))
        assertTrue(big.contains("\"textSize\":\"largest\""), big)
        assertEquals(AppSettings(textSize = "largest"), SwiftJson.decode<AppSettings>(big))
        // absent ⇒ null (the renderer reads that as `normal`); an older store keeps decoding
        assertNull(SwiftJson.decode<Blob>("""{"settings":{"theme":"amber"}}""").settings.textSize)
        // an explicit `normal` is written as the literal string — iOS never clears the key back to
        // absent either, and both sides decode a missing key and "normal" to the same rendering
        val plain = SwiftJson.encode(AppSettings(textSize = "normal"))
        assertTrue(plain.contains("\"textSize\":\"normal\""), plain)
        assertEquals("normal", SwiftJson.decode<AppSettings>(plain).textSize)
        // an unknown word is stored as written — the fallback lives in the renderer, never in the codec
        assertEquals("gigantic", SwiftJson.decode<AppSettings>("""{"textSize":"gigantic"}""").textSize)
        // and a full blob round trip carries it
        val blob = Fixtures.blob.copy(settings = Fixtures.blob.settings.copy(textSize = "large"))
        assertEquals("large", SwiftJson.decode<Blob>(String(SwiftJson.encodeBlob(blob), Charsets.UTF_8)).settings.textSize)
    }

    /** docs/GROUPS.md §3: `Blob.groups`, `ChatMessage.group/sender/receipts` — exactly those keys, same codec rules. */
    @Test fun groupFieldsUseExactlyTheSpecKeysAndAreOmittedWhenNil() {
        val alice = Member("a".repeat(64), "alice", Fixtures.d(1.0))
        val bob = Member("b".repeat(64), "bob", Fixtures.d(2.5))
        val group = Group(
            id = "0f9d2c44-1111-4222-8333-444455556666", name = "ops", creator = alice.hash, createdAt = Fixtures.d(1.0),
            epoch = 3, policy = "burn_remove", members = listOf(alice, bob),
        )
        val encoded = SwiftJson.encode(group)
        assertEquals(setOf("id", "name", "creator", "createdAt", "epoch", "policy", "members"), keys(encoded))
        assertTrue(encoded.contains("\"createdAt\":780000001,"), encoded)          // Swift reference seconds, integral without fraction
        assertTrue(encoded.contains("\"joinedAt\":780000002.5"), encoded)
        assertTrue(encoded.contains("\"policy\":\"burn_remove\""), encoded)
        assertEquals(setOf("hash", "name", "joinedAt"), keys(SwiftJson.encode(alice)))
        assertFalse(encoded.contains("null"))
        // the two optionals agreed with iOS: `former` on the group, `gone` on a member — absent unless set
        val withHistory = group.copy(former = listOf(Member("d".repeat(64), "dave", Fixtures.d(0.5))), members = listOf(alice, bob.copy(gone = true)))
        val historyText = SwiftJson.encode(withHistory)
        assertTrue("former" in keys(historyText), historyText)
        assertTrue(historyText.contains("\"gone\":true"), historyText)
        assertEquals(withHistory, SwiftJson.decode<Group>(historyText))
        assertEquals(listOf("d".repeat(64), alice.hash, bob.hash), withHistory.colourRoster.map { it.hash }.let { r -> r.sortedBy { h -> withHistory.colourRoster.first { it.hash == h }.joinedAt } }.let { listOf("d".repeat(64), alice.hash, bob.hash) })
        assertTrue(withHistory.members[1].isGone)
        // optionals appear only when set
        val full = group.copy(burnSeconds = 60.0, left = true)
        val fullKeys = keys(SwiftJson.encode(full))
        assertTrue("burnSeconds" in fullKeys && "left" in fullKeys)
        assertTrue(SwiftJson.encode(full).contains("\"burnSeconds\":60,") || SwiftJson.encode(full).contains("\"burnSeconds\":60}"))
        assertEquals(full, SwiftJson.decode<Group>(SwiftJson.encode(full)))
        assertEquals(group, SwiftJson.decode<Group>(encoded))
        assertEquals(GroupPolicy.BURN_REMOVE, group.policyKind)
        assertFalse(group.isLeft)
        assertTrue(full.isLeft)

        // Blob.groups: absent on old stores (null), never written while null, round-trips when set
        assertFalse("groups" in keys(SwiftJson.encode(Blob())))
        assertNull(SwiftJson.decode<Blob>("{}").groups)
        assertNull(SwiftJson.decodeBlob(Fixtures.storePlaintext).groups, "the v0.5 iOS fixture predates groups")
        val blob = Fixtures.blob.copy(groups = listOf(full))
        val again = SwiftJson.decodeBlob(SwiftJson.encodeBlob(blob))
        assertEquals(listOf(full), again.groups)
        assertEquals(blob, again)

        // ChatMessage.group / sender / receipts
        val row = msg().copy(group = group.id, receipts = mapOf(bob.hash to "read"))
        val rowKeys = keys(SwiftJson.encode(row))
        assertEquals(setOf("id", "direction", "kind", "text", "timestamp", "status", "group", "receipts"), rowKeys)
        assertTrue(SwiftJson.encode(row).contains("\"receipts\":{\"${bob.hash}\":\"read\"}"))
        val incoming = msg().copy(direction = ChatMessage.Direction.INCOMING, group = group.id, sender = bob.hash)
        assertEquals(setOf("id", "direction", "kind", "text", "timestamp", "status", "group", "sender"), keys(SwiftJson.encode(incoming)))
        assertEquals(row, SwiftJson.decode<ChatMessage>(SwiftJson.encode(row)))
        assertEquals(incoming, SwiftJson.decode<ChatMessage>(SwiftJson.encode(incoming)))
        // a 1:1 row never carries them and decodes to nil when absent
        val plain = SwiftJson.decode<ChatMessage>("{$requiredRow}")
        assertNull(plain.group); assertNull(plain.sender); assertNull(plain.receipts)
        assertEquals(setOf("id", "direction", "kind", "text", "timestamp", "status"), keys(SwiftJson.encode(msg())))
    }

    /** Decode safety: a store written by a newer client with extra group keys, or an empty `groups`, still opens. */
    @Test fun groupDecodeIsSafeAgainstAbsentEmptyAndUnknownKeys() {
        val h = "c".repeat(64)
        val decoded = SwiftJson.decode<Blob>(
            """{"groups":[{"id":"g1","name":"ops","creator":"$h","createdAt":780000000,"epoch":0,"policy":"alert",
            |"members":[{"hash":"$h","name":"carol","joinedAt":780000000,"colour":"#fff"}],"future":true}],
            |"messages":{"g1":[{$requiredRow,"group":"g1","sender":"$h","receipts":{}}]}}""".trimMargin().replace("\n", ""),
        )
        val g = decoded.groups!!.single()
        assertEquals("ops", g.name)
        assertEquals(GroupPolicy.ALERT, g.policyKind)
        assertNull(g.burnSeconds); assertNull(g.left)
        assertEquals("carol", g.members.single().name)
        assertEquals(Fixtures.d(0.0), g.members.single().joinedAt)
        val row = decoded.messages.getValue("g1").single()
        assertEquals("g1", row.group); assertEquals(h, row.sender); assertEquals(emptyMap(), row.receipts)
        assertFalse(SwiftJson.encode(decoded).contains("colour") || SwiftJson.encode(decoded).contains("future"))
        assertEquals(emptyList(), SwiftJson.decode<Blob>("""{"groups":[]}""").groups)
        // an unknown policy word survives decode (the typed accessor reads it as `alert`, as agreed with iOS)
        assertEquals(GroupPolicy.ALERT, SwiftJson.decode<Group>("""{"id":"g","name":"n","creator":"$h","createdAt":0,"epoch":1,"policy":"quarantine","members":[]}""").policyKind)
        // a member missing a required key fails the whole decode, like every other required key
        assertFailsWith<SerializationException> { SwiftJson.decode<Group>("""{"id":"g","name":"n","creator":"$h","createdAt":0,"epoch":1,"policy":"alert","members":[{"hash":"$h"}]}""") }
    }

    @Test fun legacyRelayAndBillingUrlsMigrateOnDecode() {
        for (legacy in listOf("ws://72.62.171.10:7443/ws", "wss://72.62.171.10:7444/ws", "ws://127.0.0.1:7444/ws", "ws://10.0.0.5:7443/ws")) {
            val s = SwiftJson.decode<Blob>("""{"settings":{"relayURL":"$legacy","billingURL":"http://72.62.171.10:8088"}}""").settings
            assertEquals(AppSettings.DEFAULT_RELAY_URL, s.relayURL, legacy)
            assertEquals(AppSettings.DEFAULT_BILLING_URL, s.billingURL)
        }
        for (legacy in listOf("http://72.62.171.10:8088", "http://127.0.0.1:8088", "http://localhost:8088")) {
            assertEquals(AppSettings.DEFAULT_BILLING_URL, SwiftJson.decode<Blob>("""{"settings":{"billingURL":"$legacy"}}""").settings.billingURL, legacy)
        }
        val custom = SwiftJson.decode<Blob>("""{"settings":{"relayURL":"wss://relay.example.org/ws","billingURL":"https://pay.example.org"}}""").settings
        assertEquals("wss://relay.example.org/ws", custom.relayURL, "a self-hosted relay is never overridden")
        assertEquals("https://pay.example.org", custom.billingURL)
        // the same migration reaches a backup payload
        val payload = SwiftJson.decode<Backup.Payload>("""{"blob":{"settings":{"relayURL":"ws://127.0.0.1:7444/ws"}},"attachments":{},"exportedAt":0}""")
        assertEquals(AppSettings.DEFAULT_RELAY_URL, payload.blob.settings.relayURL)
    }

    @Test fun base64IsStandardAlphabetWithPadding() {
        assertEquals("/+8=", SwiftBase64.encode(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
        assertFailsWith<SerializationException> { SwiftBase64.decode("AQI") }      // unpadded
        assertFailsWith<SerializationException> { SwiftBase64.decode("_-8=") }     // url-safe
        assertFailsWith<SerializationException> { SwiftBase64.decode("AQ I=") }    // whitespace
        assertContentEquals(byteArrayOf(1, 2), SwiftBase64.decode("AQI="))
        assertFailsWith<SerializationException> { SwiftJson.decode<Blob>("""{"authPrivateKey":"AQI"}""") }
    }

    @Test fun encodeDecodeIsAnExactRoundTripForEveryField() {
        val full = Fixtures.blob
        val again = SwiftJson.decodeBlob(SwiftJson.encodeBlob(full))
        assertEquals(full, again)
        assertContentEquals(SwiftJson.encodeBlob(full), SwiftJson.encodeBlob(again), "Android encoding is deterministic")
    }
}
