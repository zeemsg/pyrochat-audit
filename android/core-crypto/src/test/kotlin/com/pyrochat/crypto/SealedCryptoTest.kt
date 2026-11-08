package com.pyrochat.crypto

import com.pyrochat.protocol.GroupMember
import com.pyrochat.protocol.Wire
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SEALED-SENDER.md §9, C1 and C7: sealing, opening, and every check of §3 with its negative
 * case. The same list as iOS `SealedCryptoTests` and the bot's `sealed.test.mjs`.
 */
class SealedCryptoTest {
    private val authority = SealedTestAuthority()
    private val now: Long get() = System.currentTimeMillis()
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun text(s: String) = Wire.Payload(kind = "text", text = s)

    private fun open(me: SealedParty, body: String, ts: Long = now, roots: List<ECPublicKey> = authority.roots, minKeyId: Int = 1) =
        me.crypto.openSealedBody(body, ts, roots, minKeyId)

    private fun openBytes(me: SealedParty, sealed: ByteArray) = me.crypto.openSealed(sealed, now, authority.roots, 1)

    private fun assertRejected(check: String, heal: Boolean = false, message: String = "", block: () -> Unit) {
        val e = assertFailsWith<Sealed.Rejected>(message) { block() }
        assertEquals(check, e.check, message)
        assertEquals(heal, e.heal, message)
    }

    @Test
    fun myOwnCertificateIsCheckedTheWayARecipientWillCheckIt() {
        val p = SealedParty("self", authority)
        val own = p.own!!
        val cert = b64(own.cert.serialized)
        val bind = b64(own.bind)
        fun take(hash: ByteArray = p.hashBytes, identity: ByteArray = p.reg.identityKey, roots: List<ECPublicKey> = authority.roots,
                 minKeyId: Int = 1, c: String = cert, b: String = bind) = Sealed.takeOwnCertificate(c, b, hash, identity, roots, minKeyId)
        take()
        assertRejected("C1", message = "a relay with another root") { take(roots = listOf(ECKeyPair.generate().publicKey)) }
        assertRejected("C1", message = "a build with no root accepts nothing") { take(roots = emptyList()) }
        assertRejected("C2", message = "a server key the apps have retired") { take(minKeyId = 2) }
        assertRejected("C4") { take(hash = ByteArray(32) { 9 }) }
        assertRejected("C4", message = "a certificate for someone else's key") { take(identity = ECKeyPair.generate().publicKey.serialize()) }
        val bad = own.bind.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertRejected("C4") { take(b = b64(bad)) }
        assertRejected("C1", message = "not a certificate at all") { take(c = "AAAA") }
        val device2 = authority.issue(p.hashBytes, p.identity, p.expires, device = 2)
        assertRejected("C3") { take(c = b64(device2.cert.serialized), b = b64(device2.bind)) }
        // the check uses the certificate's own last millisecond, never this phone's clock
        val past = authority.issue(p.hashBytes, p.identity, 1_000)
        take(c = b64(past.cert.serialized), b = b64(past.bind))
    }

    /** The bytes the RELAY writes (its JUnit golden vector) pass this phone's own check under the test root. */
    @Test
    fun aCertificateTheRelayIssuedIsAccepted() {
        val hash = Sealed.hashBytes("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")!!
        val identity = Base64.getDecoder().decode("BQ8mfA3SWlXeq6SvVOeJjaQeWCH/ZXs0EVaUHEcBHCED")
        val cert = "Cr8BEAEZAKyCHaMBAAAiIQUPJnwN0lpV3qukr1TniY2kHlgh/2V7NBFWlBxHARwhAyppCiUIARIhBc7OugViy6hWpAKt+d/OYEapfYI9yC55FVnKTCN9rSlmEkDDI71XRqp5X/I1/mbG8Vlsw9ClVRxXlZCPqd6DS31QdxrJky5NV7EHKQXonVUvaYS3kX5eToyWzkklteyteNKDMiQyYmQ4MDZjOS03ZjBlLTAwYWYtMWExZi1jMzMyOGZhNzYzYTkSQIQO8UJP1tWhW4HNnGtMD0jViVQqSUDli4ncjpafXukDOIrh8gYD5ROMGGGY8nVTarXD3TfdCvCr43fMdpE27w4="
        val bind = "jQ5YtsyAXkRPskb5yhE/W2r26OBp2QMIeh9JyPdxPaxKt8js14ii8sEaCs6XFBFxvXJ6okBnIt20+amA5R5DCA=="
        val testRoot = "Ba/550LupgYMzcYETJWXYu0sm+4OU5QhjsJxvqRehokn"
        val local = SealedTrust.roots("ws://127.0.0.1:7444/ws", listOf(testRoot))
        assertEquals(2, local.size) // the production root and the throwaway one
        val own = Sealed.takeOwnCertificate(cert, bind, hash, identity, local, 1)
        assertEquals(1_800_086_400_000, own.cert.expiration)
        assertEquals("2bd806c9-7f0e-00af-1a1f-c3328fa763a9", own.cert.senderUuid)
        assertEquals("2bd806c9-7f0e-00af-1a1f-c3328fa763a9", Sealed.uuid(hash))
        assertRejected("C1") { Sealed.takeOwnCertificate(cert, bind, hash, identity, listOf(ECKeyPair.generate().publicKey), 1) }
        // The production root is the one made on 2026-11-06 (deploy/sealed-keys.mjs root) and counts
        // for any relay: changing it cuts every installed app off from sealed sender. The throwaway
        // root counts for a relay on this machine and nowhere else.
        assertEquals(listOf("BbCQLScxmfcklrLQwJRA7ZCFLyzoOAvxNbbu2w4DI0h+"), SealedTrust.productionRoots)
        assertEquals(2, SealedTrust.roots("ws://10.0.2.2:7444/ws", listOf(testRoot)).size)
        val real = SealedTrust.roots("wss://relay.pyrochat.ca/ws", listOf(testRoot))
        assertEquals(1, real.size)
        assertEquals(SealedTrust.productionRoots[0], Base64.getEncoder().encodeToString(real[0].serialize()), "and the one root is a key libsignal reads")
        assertEquals(1, SealedTrust.roots(null, listOf(testRoot)).size)
    }

    @Test
    fun smallPieces() {
        val hash = Sealed.hashBytes("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")!!
        assertEquals("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90", Sealed.hex(hash))
        assertNull(Sealed.hashBytes("2bd806"))
        assertNull(Sealed.hashBytes("zz".repeat(32)))
        val signed = Sealed.bindingMessage(hash, ByteArray(33) { 5 }, 0x0102030405060708)
        assertEquals(99, signed.size)
        assertEquals("pyrochat-sender-binding-v1", String(signed.copyOf(26), Charsets.US_ASCII))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), signed.copyOfRange(91, 99), "the expiry is big-endian")
    }

    @Test
    fun sealAndOpenBothInnerMessageTypesAndEveryRefusal() {
        val alice = SealedParty("alice", authority)
        val bob = SealedParty("bob", authority)
        val mallory = SealedParty("mallory", authority)
        val carol = SealedParty("carol", authority)
        alice.crypto.processBundle(bob.bundle(), bob.hash)

        // a first message is a PreKey message inside; the reply is a Whisper
        val first = alice.crypto.seal(text("hello"), bob.hash, alice.own!!)
        assertEquals(6, bob.crypto.envelopeType(first))
        val o1 = open(bob, first)
        assertEquals(alice.hash, o1.sender)
        assertEquals("hello", bob.crypto.decryptSealed(o1).text)
        val reply = bob.crypto.seal(text("hi"), alice.hash, bob.own!!)
        assertEquals("hi", alice.crypto.decryptSealed(open(alice, reply)).text)

        val next = alice.crypto.seal(text("again"), bob.hash, alice.own!!)
        assertRejected("C0", message = "not sealed to her") { open(mallory, next) }
        assertRejected("C1", message = "expired at the relay's time") { open(bob, next, ts = alice.expires + 1) }
        open(bob, next, ts = alice.expires - 1) // valid until its last millisecond
        assertRejected("C1") { open(bob, next, roots = listOf(ECKeyPair.generate().publicKey)) }
        assertRejected("C1", message = "a build with no root accepts nothing") { open(bob, next, roots = emptyList()) }
        assertRejected("C2") { open(bob, next, minKeyId = 2) }
        val tampered = Envelope.open(next).ciphertext().also { it[it.size - 30] = (it[it.size - 30].toInt() xor 1).toByte() }
        assertRejected("C0", message = "altered in transit") { openBytes(bob, tampered) }
        // today's envelope is not a sealed one
        carol.crypto.processBundle(bob.bundle(), bob.hash)
        val legacy = carol.crypto.encrypt(text("legacy"), bob.hash)
        assertEquals(3, bob.crypto.envelopeType(legacy))
        assertRejected("C0") { open(bob, legacy) }
        assertNull(bob.crypto.envelopeType("not an envelope"))

        // mallory, with her own valid certificate
        mallory.crypto.processBundle(bob.bundle(), bob.hash)
        val mal = mallory.own!!
        val toBob = bob.hashBytes
        assertRejected("C4", message = "alice's hash") { openBytes(bob, mallory.rawSeal(bob.hash, mal.cert, alice.hashBytes + mal.bind + toBob)) }
        val twin = mallory.hashBytes.copyOf(16) + ByteArray(16) { 0x5a }
        assertRejected("C4", message = "a made-up hash sharing her UUID") { openBytes(bob, mallory.rawSeal(bob.hash, mal.cert, twin + mal.bind + toBob)) }
        assertRejected("C0", message = "alice's certificate") {
            openBytes(bob, mallory.rawSeal(bob.hash, alice.own!!.cert, alice.hashBytes + alice.own!!.bind + toBob))
        }
        assertRejected("C4", message = "no recipient") { openBytes(bob, mallory.rawSeal(bob.hash, mal.cert, mallory.hashBytes + mal.bind)) }
        val otherExpiry = authority.issue(mallory.hashBytes, mallory.identity, mallory.expires, bindExpires = mallory.expires + 1)
        assertRejected("C4", message = "a binding for another expiry") {
            openBytes(bob, mallory.rawSeal(bob.hash, otherExpiry.cert, mallory.hashBytes + otherExpiry.bind + toBob))
        }
        val device2 = authority.issue(mallory.hashBytes, mallory.identity, mallory.expires, device = 2)
        assertRejected("C3") { openBytes(bob, mallory.rawSeal(bob.hash, device2.cert, mallory.hashBytes + device2.bind + toBob)) }

        // the case C6 exists for: an account registered with carol's PUBLIC keys. What alice writes
        // to that account can be handed to carol, who must not read it as written to her.
        val clone = "c1".repeat(32)
        alice.crypto.processBundle(carol.bundle(asUser = clone), clone)
        val forClone = alice.crypto.seal(text("for the clone only"), clone, alice.own!!)
        assertRejected("C6") { open(carol, forClone) }

        // after all that, the real message still opens, once
        assertEquals("again", bob.crypto.decryptSealed(open(bob, next)).text)
        assertFailsWith<DuplicateMessageException>("a replay fails in the ratchet") { bob.crypto.decryptSealed(open(bob, next)) }
    }

    @Test
    fun aMessageThatClaimsToBeFromMeIsRefused() {
        // C5: only reachable with my own identity key, so it is my own message played back
        val alice = SealedParty("alice", authority)
        alice.crypto.processBundle(alice.bundle(), alice.hash)
        val toSelf = alice.rawSeal(alice.hash, alice.own!!.cert, alice.hashBytes + alice.own!!.bind + alice.hashBytes)
        assertRejected("C5") { openBytes(alice, toSelf) }
    }

    @Test
    fun someoneElsesPreKeyMessageInMyEnvelopeIsRefused() {
        val dave = SealedParty("dave", authority)
        val erin = SealedParty("erin", authority)
        val mal = SealedParty("mal", authority)
        dave.crypto.processBundle(erin.bundle(), erin.hash)
        mal.crypto.processBundle(erin.bundle(), erin.hash)
        // dave's own PreKey message for erin, as it would cross the relay on today's path
        val stolen = dave.legacyCiphertext(erin.hash, text("dave wrote this").encode())
        assertEquals(CiphertextMessage.PREKEY_TYPE, stolen.type)
        // mal wraps it in an envelope of her own, certificate and all
        val wrapped = mal.rawSeal(erin.hash, mal.own!!.cert, mal.hashBytes + mal.own!!.bind + erin.hashBytes, inner = stolen)
        val opened = openBytes(erin, wrapped)
        assertEquals(mal.hash, opened.sender, "C0–C6 pass: it really is mal's envelope")
        assertRejected("C7", message = "but the message inside is not hers") { erin.crypto.decryptSealed(opened) }
        assertFalse(erin.crypto.hasSession(mal.hash), "and no session was created for it")
        assertFalse(erin.crypto.hasSession(dave.hash))
    }

    @Test
    fun aWhisperFromASenderWhoseKeyIsNotTheOneOnRecordHeals() {
        val alice = SealedParty("alice", authority)
        val bob = SealedParty("bob", authority)
        alice.crypto.processBundle(bob.bundle(), bob.hash)
        bob.crypto.decryptSealed(open(bob, alice.crypto.seal(text("one"), bob.hash, alice.own!!)))
        alice.crypto.decryptSealed(open(alice, bob.crypto.seal(text("two"), alice.hash, bob.own!!)))
        val whisper = alice.crypto.seal(text("three"), bob.hash, alice.own!!)
        bob.crypto.state.knownIdentities.remove(alice.hash) // bob lost what he knew about alice
        val opened = open(bob, whisper)
        assertRejected("C7", heal = true, message = "the sender is certified, so this one heals like any failed decrypt") {
            bob.crypto.decryptSealed(opened)
        }
    }

    @Test
    fun aCertificateAndABindingForTwoAccountsThatShareAKeyDoNotCombine() {
        // The relay refuses to register one identity key under two names; this is the check behind that rule.
        val alice = SealedParty("alice", authority)
        val bob = SealedParty("bob", authority)
        alice.crypto.processBundle(bob.bundle(), bob.hash)
        val second = ByteArray(32) { 0x77 }
        val forSecond = authority.issue(second, alice.identity, alice.expires)
        // her certificate names the first account; the binding is a genuine one for the second
        assertRejected("C4") { openBytes(bob, alice.rawSeal(bob.hash, alice.own!!.cert, second + forSecond.bind + bob.hashBytes)) }
    }

    @Test
    fun aSealedBlobTakesItsSenderAndItsClockFromItsManifest() {
        val alice = SealedParty("alice", authority)
        val bob = SealedParty("bob", authority)
        val carol = SealedParty("carol", authority)
        alice.crypto.processBundle(bob.bundle(), bob.hash)
        val content = ByteArray(200_000) { (it * 31).toByte() }
        val blob = alice.crypto.sealBlob(content, bob.hash, alice.own!!)
        assertEquals(6, blob[0].toInt())
        fun decrypt(from: String, ts: Long, b: ByteArray = blob) = bob.crypto.decryptSealedBlob(b, from, ts, authority.roots, 1)
        assertRejected("C4", message = "not the manifest's sender") { decrypt(carol.hash, now) }
        assertRejected("C1") { decrypt(alice.hash, alice.expires + 1) }
        assertRejected("C0", message = "a blob that is not sealed") { decrypt(alice.hash, now, byteArrayOf(3) + blob.copyOfRange(1, blob.size)) }
        // judged at the manifest's time, not at the moment it is opened
        assertContentEquals(content, decrypt(alice.hash, alice.expires - 1))
    }

    // ---- wire (§4.4): the new fields, decoded leniently

    @Test
    fun thePayloadFieldsRoundTripAndDecodeLeniently() {
        val p = Wire.Payload(kind = "text", text = "hi", refId = "row-1", dk = "RElLRVk=", dkh = "REtI")
        assertEquals(p, Wire.Payload.decode(p.encode()))
        // a wrong shape drops the field, never the payload: a failed payload decode is taken for ratchet damage
        val odd = Wire.Payload.decode("""{"kind":"text","text":"x","dk":5,"dkh":{"a":1}}""".toByteArray())
        assertNull(odd.dk)
        assertNull(odd.dkh)
        assertEquals("x", odd.text)
        // and a payload without them encodes exactly as before
        assertFalse(String(Wire.Payload(kind = "text", text = "hi").encode()).contains("dk"))
        val member = Wire.json.decodeFromString(GroupMember.serializer(), """{"hash":"aa","dk":"AAAA"}""")
        assertEquals("AAAA", member.dk)
        assertNull(Wire.json.decodeFromString(GroupMember.serializer(), """{"hash":"aa","dk":[1]}""").dk)
        assertFalse(Wire.json.encodeToString(GroupMember.serializer(), GroupMember("aa", "n", 1.0)).contains("dk"))
    }

    @Test
    fun theNewFramesEncodeOnlyWhatTheyCarry() {
        val first = Wire.encode(Wire.Out.Deliver("i", "p", 60, "msg", "AAAA"))
        assertEquals("""{"t":"deliver","id":"i","to":"p","ttl":60,"kind":"msg","body":"AAAA"}""", first, "the first-contact lane carries no delivery key")
        assertTrue(Wire.encode(Wire.Out.Deliver("i", "p", 60, "msg", "AAAA", access = "KEY")).contains(""""access":"KEY""""))
        assertEquals("""{"t":"anon"}""", Wire.encode(Wire.Out.Anon))
        assertEquals("""{"t":"cert"}""", Wire.encode(Wire.Out.Cert))
        assertFalse(Wire.encode(Wire.Out.Auth(sig = "s")).contains("delivery"), "a build with no root logs in exactly as before")
        assertTrue(Wire.encode(Wire.Out.Auth(sig = "s", delivery = "H")).contains(""""delivery":"H""""))
        val ready = Wire.decodeIn("""{"t":"ready","queued":0,"sealed":true,"cert":"C","bind":"B","cert_ttl":86400}""") as Wire.In.Ready
        assertEquals(true, ready.sealed)
        assertEquals(86_400, ready.certTtl)
        val old = Wire.decodeIn("""{"t":"ready","queued":2}""") as Wire.In.Ready
        assertNull(old.sealed)
        assertNull(old.cert)
        assertEquals(Wire.In.AnonOk, Wire.decodeIn("""{"t":"anon_ok"}"""))
        assertEquals("C", (Wire.decodeIn("""{"t":"cert","cert":"C","bind":"B","cert_ttl":60}""") as Wire.In.Cert).cert)
        // a sealed frame names no sender; today's frame still does
        assertNull((Wire.decodeIn("""{"t":"recv","id":"i","kind":"msg","body":"AAAA","ts":5}""") as Wire.In.Recv).from)
        assertEquals("f", (Wire.decodeIn("""{"t":"recv","id":"i","from":"f","kind":"msg","body":"AAAA"}""") as Wire.In.Recv).from)
        assertEquals(true, (Wire.decodeIn("""{"t":"prekey_bundle","user":"u","reg_id":1,"identity_key":"k","signed_prekey":{"id":1,"pub":"p"},"sealed":true}""") as Wire.In.PreKeyBundle).sealed)
    }

    /**
     * libsignal-java has no public call that opens only the outer layer. `SealedBridge` uses the
     * first two lines of the library's own decrypt; a libsignal upgrade that moves either entry
     * point fails here, in the build, instead of in the app.
     */
    @Test
    fun theTwoLibraryEntryPointsExistWithTheExpectedSignatures() {
        val native = Class.forName("org.signal.libsignal.internal.Native")
        val internalStore = Class.forName("org.signal.libsignal.protocol.state.internal.IdentityKeyStore")
        val open = native.getMethod("SealedSessionCipher_DecryptToUsmc", ByteArray::class.java, internalStore)
        assertTrue(Modifier.isStatic(open.modifiers) && Modifier.isPublic(open.modifiers))
        assertEquals(Long::class.javaPrimitiveType, open.returnType)
        val bridge = SessionCipher::class.java.getMethod("_bridge", IdentityKeyStore::class.java)
        assertTrue(Modifier.isStatic(bridge.modifiers) && Modifier.isPublic(bridge.modifiers))
        assertEquals(internalStore, bridge.returnType)
        assertTrue(Modifier.isPublic(UnidentifiedSenderMessageContent::class.java.getConstructor(Long::class.javaPrimitiveType).modifiers))
    }
}
