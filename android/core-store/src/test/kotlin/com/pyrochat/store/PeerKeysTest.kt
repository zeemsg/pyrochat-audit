package com.pyrochat.store

import com.pyrochat.store.codec.SwiftJson
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.PeerKey
import com.pyrochat.store.model.PeerKeys
import com.pyrochat.store.model.PeerKeys.Lane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SEALED-SENDER.md §5.1 and §5.2: the delivery-key rules and the path table. The same cases
 * as iOS `SealedRulesTests` and the bot's `sealed.test.mjs`.
 */
class PeerKeysTest {
    private val hex = "ab".repeat(32)
    private fun key(byte: Int, n: Int = 32) = ByteArray(n) { byte.toByte() }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun fp(k: ByteArray) = PeerKeys.fingerprint(k)

    // ---- §5.1: what is learned, and when

    @Test
    fun aPayloadTeachesThePeersKeyAndWhatTheyHoldOfMine() {
        val theirs = key(1)
        val mine = key(2)
        val p = PeerKeys.payloadReceived(null, b64(theirs), b64(fp(mine)))
        assertEquals(PeerKey(Bytes(theirs), Bytes(fp(mine)), true), p)
        assertEquals(p, PeerKeys.payloadReceived(p, b64(theirs), b64(fp(mine))), "nothing changed")
    }

    @Test
    fun aPayloadWithoutDkhClearsTheEchoSoMyKeyIsSentAgain() {
        val mine = key(2)
        var p = PeerKeys.payloadReceived(null, null, b64(fp(mine)))
        assertNull(PeerKeys.outgoingFields(p, mine).dk, "they hold my key: nothing to send")
        p = PeerKeys.payloadReceived(p, null, null) // a restore from an old backup, say
        assertNull(p.ack)
        assertEquals(b64(mine), PeerKeys.outgoingFields(p, mine).dk)
    }

    @Test
    fun dkIsSentUntilEchoedAndAgainAfterMyKeyChangesAndDkhWhileIHoldTheirs() {
        val mine = key(2)
        val next = key(3)
        val theirs = key(1)
        assertEquals(PeerKeys.Outgoing(b64(mine), null), PeerKeys.outgoingFields(null, mine))
        val p = PeerKeys.payloadReceived(null, b64(theirs), b64(fp(mine)))
        assertEquals(PeerKeys.Outgoing(null, b64(fp(theirs))), PeerKeys.outgoingFields(p, mine))
        assertEquals(PeerKeys.Outgoing(b64(next), b64(fp(theirs))), PeerKeys.outgoingFields(p, next))
    }

    @Test
    fun malformedKeysAreIgnored() {
        assertTrue(PeerKeys.payloadReceived(null, b64(key(1, 31)), b64(key(1, 7))).isEmpty)
        assertTrue(PeerKeys.payloadReceived(null, "not base64 !!", "").isEmpty)
    }

    @Test
    fun sealedIsThreeValued() {
        val theirs = key(1)
        assertEquals(true, PeerKeys.bundleSeen(null, true).sealed)
        assertEquals(false, PeerKeys.bundleSeen(PeerKeys.bundleSeen(null, true), false).sealed)
        var p = PeerKeys.payloadReceived(PeerKeys.bundleSeen(null, false), b64(theirs), null)
        assertEquals(true, p.sealed, "a delivery key says they can receive sealed")
        p = PeerKeys.unsupported(p)
        assertEquals(false, p.sealed)
        assertEquals(Bytes(theirs), p.dk, "`unsupported` keeps the key")
        assertEquals(true, PeerKeys.sealedReceived(p).sealed)
    }

    @Test
    fun deniedForgetsTheKeyThatWasRefusedAndOnlyThatOne() {
        val old = key(1)
        val newer = key(9)
        val p = PeerKeys.payloadReceived(null, b64(old), null)
        val dropped = PeerKeys.denied(p, old)
        assertNotNull(dropped, "the first refusal drops the key: look them up again")
        assertNull(dropped.dk)
        assertEquals(true, dropped.sealed)
        assertNull(PeerKeys.denied(dropped, old), "a second refusal of the same key has nothing left to drop")
        val rekeyed = PeerKeys.payloadReceived(dropped, b64(newer), null)
        assertNull(PeerKeys.denied(rekeyed, old), "a late refusal of the old key does not cost the newer one")
        assertNull(PeerKeys.denied(rekeyed, null))
        assertNull(PeerKeys.denied(null, old))
    }

    @Test
    fun aRosterKeyIsTakenOnlyWhenNoneIsHeld() {
        val direct = key(1)
        val roster = key(5)
        assertEquals(PeerKey(Bytes(roster), null, true), PeerKeys.rosterKey(null, b64(roster)))
        val held = PeerKeys.payloadReceived(null, b64(direct), null)
        assertEquals(held, PeerKeys.rosterKey(held, b64(roster)), "a key from the person beats one from a third party")
        assertEquals(PeerKey(Bytes(roster), null, false), PeerKeys.rosterKey(PeerKeys.bundleSeen(null, false), b64(roster)),
            "known not to be capable stays so")
        assertNull(PeerKeys.rosterKey(null, b64(key(1, 12))))
        assertNull(PeerKeys.rosterKey(null, null))
    }

    // ---- §5.2: the path, first match wins

    @Test
    fun everyRowOfThePathTable() {
        val dk = Bytes(key(1))
        fun route(p: PeerKey?, offered: Boolean = true, session: Boolean = true) = PeerKeys.route(p, offered, session)
        assertEquals(Lane.LEGACY, route(PeerKey(dk, sealed = true), offered = false), "row 1: not offered")
        assertEquals(Lane.LEGACY, route(PeerKey(dk, sealed = false)), "row 2: known not capable, even holding a key")
        assertEquals(Lane.KEY, route(PeerKey(dk, sealed = true)), "row 3")
        assertEquals(Lane.KEY, route(PeerKey(dk), session = false), "row 3: a key from a roster, capability not yet known")
        assertEquals(Lane.FIRST, route(PeerKey(sealed = true)), "row 4")
        assertEquals(Lane.FIRST, route(PeerKey(sealed = true), session = false), "row 4: a new contact whose bundle said sealed")
        assertEquals(Lane.LEGACY, route(null), "row 5: an existing conversation")
        assertEquals(Lane.FETCH, route(null, session = false), "row 6: a new contact")
        assertEquals(Lane.LEGACY, route(PeerKey(sealed = false), session = false), "row 2 after the bundle said not sealed")
    }

    @Test
    fun fingerprintAndDeliveryHash() {
        val dk = key(7)
        assertEquals(32, PeerKeys.deliveryHash(dk).size)
        assertContentEquals(PeerKeys.deliveryHash(dk).copyOf(8), fp(dk))
        assertEquals(32, PeerKeys.newDeliveryKey().size)
        assertFalse(PeerKeys.newDeliveryKey().contentEquals(PeerKeys.newDeliveryKey()))
    }

    // ---- the store (§5.1): the same two keys, in the same shape, as iOS

    @Test
    fun theStoreKeepsTheTwoNewKeysAndOpensWithoutThem() {
        val blob = Blob(
            deliveryKey = Bytes(key(7)),
            peerKeys = mapOf(hex to PeerKey(Bytes(key(1)), Bytes(key(2, 8)), true), "cd".repeat(32) to PeerKey(sealed = false)),
        )
        val bytes = SwiftJson.encodeBlob(blob)
        val back = SwiftJson.decodeBlob(bytes)
        assertEquals(blob.deliveryKey, back.deliveryKey)
        assertEquals(blob.peerKeys, back.peerKeys)
        // the names and shapes on disk are a contract with iOS
        val json = Json.parseToJsonElement(String(bytes)).jsonObject
        assertEquals(b64(key(7)), json["deliveryKey"]!!.jsonPrimitive.content)
        val entry = json["peerKeys"]!!.jsonObject[hex]!!.jsonObject
        assertEquals(b64(key(1)), entry["dk"]!!.jsonPrimitive.content)
        assertEquals(b64(key(2, 8)), entry["ack"]!!.jsonPrimitive.content)
        assertEquals("true", entry["sealed"]!!.jsonPrimitive.content)
        // an entry that knows only "not capable" writes neither key
        assertEquals(setOf("sealed"), json["peerKeys"]!!.jsonObject["cd".repeat(32)]!!.jsonObject.keys)

        // a store from before sealed sender has neither key, and writes neither
        val old = SwiftJson.decodeBlob(SwiftJson.encodeBlob(Blob()))
        assertNull(old.deliveryKey)
        assertNull(old.peerKeys)
        val oldJson = Json.parseToJsonElement(String(SwiftJson.encodeBlob(Blob()))).jsonObject
        assertFalse("deliveryKey" in oldJson)
        assertFalse("peerKeys" in oldJson)

        // damage in either key never costs the store
        val broken = JsonObject(json + mapOf("peerKeys" to JsonPrimitive("not a map"), "deliveryKey" to JsonPrimitive("AAAA")))
        val opened = SwiftJson.decodeBlob(broken.toString().toByteArray())
        assertNull(opened.peerKeys)
        assertNull(opened.deliveryKey, "three bytes are not a delivery key")
        assertNotEquals(0, opened.settings.relayURL.length, "and the rest of the store is there")
    }
}
