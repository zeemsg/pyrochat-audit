package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.PreKeys
import com.pyrochat.crypto.Sealed
import com.pyrochat.crypto.SignalState
import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.ChatMessage
import com.pyrochat.store.model.ChatMessage.Direction
import com.pyrochat.store.model.ChatMessage.Kind
import com.pyrochat.store.model.ChatMessage.Status
import com.pyrochat.store.model.PeerKey
import com.pyrochat.store.model.PeerKeys
import com.pyrochat.store.model.PeerKeys.Lane
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.metadata.certificate.ServerCertificate
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Optional
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SEALED-SENDER.md on the real engine with no relay: services that never connect hand each
 * other the frames they prepare. The port of iOS `SealedServiceTests`; the status row's wording
 * is tested with the status page, in the app module.
 */
class SealedServiceTest {
    /**
     * A stand-in for the relay's signing side, built with libsignal itself: a trust root, a server
     * certificate under it, and sender certificates and bindings under that.
     */
    private class Authority {
        private val root = ECKeyPair.generate()
        private val server = ECKeyPair.generate()
        private val serverCert = ServerCertificate(root.privateKey, 1, server.publicKey)
        val roots: List<ECPublicKey> get() = listOf(root.publicKey)

        fun issue(hash: String, identityKey: ByteArray, expires: Long): Sealed.Own {
            val bytes = Sealed.hashBytes(hash)!!
            val key = ECPublicKey(identityKey)
            val cert = serverCert.issue(server.privateKey, Sealed.uuid(bytes), Optional.empty(), 1, key, expires)
            return Sealed.Own(cert, server.privateKey.calculateSignature(Sealed.bindingMessage(bytes, key.serialize(), expires)))
        }
    }

    private class Phone(val user: TestUser, val reg: PreKeys.Registration) {
        val svc: ChatService get() = user.svc
        val hash: String get() = svc.myHash
        val deliveryKey: ByteArray get() = svc.store.blob.deliveryKey!!.bytes
        fun bundle(): Wire.In.PreKeyBundle = PreKeys.bundleFromRegister(reg.toRegisterFrame(hash))
        fun peerKey(peer: Phone): PeerKey? = svc.store.blob.peerKeys?.get(peer.hash)
    }

    private val authority = Authority()
    private val phones = ArrayList<Phone>()
    private fun now() = System.currentTimeMillis()
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @AfterTest
    fun close() = phones.forEach { it.user.close() }

    /**
     * A service with an identity and, unless [sealed] is false, everything a login on a
     * sealed-sender relay would have given it. It never connects anywhere.
     */
    private suspend fun phone(prefix: String, sealed: Boolean = true): Phone {
        val user = Live.makeService(prefix, relayUrl = "ws://127.0.0.1:9/ws") // nothing listens there
        val reg = user.svc.sealedForTest { generateIdentity(user.username) }
        val p = Phone(user, reg).also { phones += it }
        if (sealed) arm(p)
        return p
    }

    private suspend fun arm(p: Phone, usableMs: Long = 3_600_000) {
        val own = authority.issue(p.hash, p.reg.identityKey, now() + 86_400_000)
        p.svc.sealedForTest {
            roots = authority.roots
            arm(own, usableMs)
            if (p.svc.store.blob.deliveryKey == null) p.svc.store.update { it.copy(deliveryKey = Bytes(PeerKeys.newDeliveryKey())) }
        }
    }

    /**
     * `prepare` as it runs while the link is up. The link is "up" only for the call, so nothing
     * queued in the background (a receipt flush) ever tries to reach a relay.
     */
    private suspend fun prepare(p: Phone, payload: Wire.Payload, to: Phone, kind: String = "msg", id: String = ChatService.newMessageId()): ChatService.Outbound =
        p.svc.sealedForTest {
            online = true
            try {
                prepare(payload, to.hash, kind, id = id)
            } finally {
                online = false
            }
        }

    /**
     * Hand a prepared frame to its recipient the way the relay would: a sealed one arrives
     * without `from`, a legacy one names its sender.
     */
    private suspend fun deliver(out: ChatService.Outbound, from: Phone, to: Phone, ts: Long? = now()) {
        val recv = when (val f = out.frame) {
            is Wire.Out.Send -> Wire.In.Recv(f.id, from.hash, f.kind, f.body, ts)
            is Wire.Out.Deliver -> Wire.In.Recv(f.id, null, f.kind, f.body, ts)
            else -> error("not a message frame")
        }
        to.svc.routeForTest(recv)
    }

    private fun text(s: String) = Wire.Payload(kind = "text", text = s, senderName = "t")
    private fun rows(p: Phone, with: Phone): List<ChatMessage> = p.svc.messages.value[with.hash].orEmpty().filter { it.kind == Kind.TEXT }
    private suspend fun hasSession(p: Phone, with: Phone): Boolean = p.svc.sealedForTest { hasSession(with.hash) }

    /** What a frame carried, read by decrypting a copy on a throwaway twin of the recipient. */
    private fun payloadOf(out: ChatService.Outbound, recipient: Phone, sender: Phone): Wire.Payload {
        val b = recipient.svc.store.blob
        fun <K> copy(m: Map<K, Bytes>): MutableMap<K, ByteArray> = m.mapValuesTo(LinkedHashMap()) { it.value.bytes.copyOf() }
        val twin = CryptoEngine(
            SignalState(
                usernameHash = b.usernameHash, identityKeyPair = b.identityKeyPair.bytes.copyOf(), registrationId = b.registrationId,
                authPrivateKey = b.authPrivateKey.bytes.copyOf(), sessions = copy(b.sessions), preKeys = copy(b.preKeys),
                signedPreKeys = copy(b.signedPreKeys), kyberPreKeys = copy(b.kyberPreKeys), knownIdentities = copy(b.knownIdentities),
                nextPreKeyId = b.nextPreKeyId,
            ),
        )
        return when (val f = out.frame) {
            is Wire.Out.Send -> twin.decryptPayload(f.body, sender.hash)
            is Wire.Out.Deliver -> {
                val opened = twin.openSealedBody(f.body, now(), authority.roots, 1)
                assertEquals(sender.hash, opened.sender)
                twin.decryptSealed(opened)
            }
            else -> error("not a message frame")
        }
    }

    // ---------------------------------------------------------------- the whole exchange (C2, C3, C8)

    @Test
    fun `an existing conversation moves to the key lane in one legacy message`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }

        // row 5: a session, nothing known about bob. One more message the old way, carrying my key.
        val rowA = ChatService.newMessageId()
        val m1 = prepare(alice, text("one"), bob, id = rowA)
        assertEquals(Lane.LEGACY, m1.lane)
        assertEquals(rowA, assertIs<Wire.Out.Send>(m1.frame).id, "today's frame: the row id is the relay id")
        val p1 = payloadOf(m1, bob, alice)
        assertEquals(b64(alice.deliveryKey), p1.dk)
        assertNull(p1.dkh)
        assertNull(p1.refId, "the legacy path carries no row id: it never needed one")
        deliver(m1, alice, bob)
        assertEquals(listOf(rowA), rows(bob, alice).map { it.id })
        assertContentEquals(alice.deliveryKey, bob.peerKey(alice)?.dk?.bytes)
        assertEquals(true, bob.peerKey(alice)?.sealed)

        // bob answers on the key lane at once
        val rowB = ChatService.newMessageId()
        val m2 = prepare(bob, text("two"), alice, id = rowB)
        assertEquals(Lane.KEY, m2.lane)
        val f2 = assertIs<Wire.Out.Deliver>(m2.frame)
        assertNotEquals(rowB, f2.id, "the relay sees a fresh id, never the row's")
        assertEquals(b64(alice.deliveryKey), f2.access)
        assertEquals(rowB, m2.callerId)
        val p2 = payloadOf(m2, alice, bob)
        assertEquals(rowB, p2.refId, "the row id rides inside")
        assertEquals(b64(bob.deliveryKey), p2.dk)
        assertEquals(b64(PeerKeys.fingerprint(alice.deliveryKey)), p2.dkh)
        deliver(m2, bob, alice)
        assertEquals(listOf(rowB), rows(alice, bob).filter { it.direction == Direction.INCOMING }.map { it.id }, "filed under the sender's row id, not the relay's")
        assertEquals(PeerKey(Bytes(bob.deliveryKey), Bytes(PeerKeys.fingerprint(alice.deliveryKey)), true), alice.peerKey(bob))

        // from here both directions are sealed, and alice stops sending her key
        val m3 = prepare(alice, text("three"), bob)
        assertEquals(Lane.KEY, m3.lane)
        val p3 = payloadOf(m3, bob, alice)
        assertNull(p3.dk, "bob echoed it")
        assertEquals(b64(PeerKeys.fingerprint(bob.deliveryKey)), p3.dkh)

        // a retry of bob's message (its verdict was lost): a new frame, a new id, the same row
        val again = prepare(bob, text("two"), alice, id = rowB)
        val fAgain = assertIs<Wire.Out.Deliver>(again.frame)
        assertNotEquals(f2.id, fAgain.id, "no id is ever used twice")
        assertNotEquals(f2.body, fAgain.body, "and nothing sealed is ever sent twice")
        deliver(again, bob, alice)
        assertEquals(1, rows(alice, bob).count { it.direction == Direction.INCOMING }, "it arrives once")
        // the same frame delivered twice (the relay redelivers until acked) is not a second row either
        deliver(again, bob, alice)
        assertEquals(1, rows(alice, bob).count { it.direction == Direction.INCOMING })
        assertTrue(hasSession(alice, bob), "a duplicate is not ratchet damage")
    }

    @Test
    fun `a contact added after the upgrade starts on the first-contact lane`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest {
            processBundle(bob.bundle(), bob.hash)
            setPeerKey(bob.hash, PeerKeys.bundleSeen(null, sealed = true)) // what the anonymous fetch recorded
        }

        val row = ChatService.newMessageId()
        val first = prepare(alice, text("hello"), bob, id = row)
        assertEquals(Lane.FIRST, first.lane)
        val frame = assertIs<Wire.Out.Deliver>(first.frame)
        assertNull(frame.access, "no delivery key yet: that is the lane")
        assertTrue(frame.body.length <= PeerKeys.FIRST_CONTACT_MAX_BODY)
        assertNull(first.payload.refId, "the caller's payload is kept as it was passed, for a retry")

        // C9: typing would spend bob's first-contact allowance
        assertFailsWith<ServiceError> { prepare(alice, Wire.Payload(kind = "control", control = "typing"), bob, kind = "control") }
        // D1: no attachment until the first text is delivered, and it says so
        val refused = assertFailsWith<ServiceError> {
            prepare(alice, Wire.Payload(kind = "blob_manifest", subtype = "image", chunks = 1, bytes = 9), bob, kind = "blob")
        }
        assertEquals(Sealed.firstContactRefusal(alice.svc.sealedForTest { peerName(bob.hash) }), refused.code)
        assertTrue(refused.code.startsWith("ERR: send <"), refused.code)
        // a first message too long for the lane fails here, not at the relay
        val tooLong = assertFailsWith<ServiceError> { prepare(alice, text("long ".repeat(3000)), bob) }
        assertTrue(tooLong.code.startsWith("ERR: too long for a first message"), tooLong.code)
        // receipts and other controls are fine
        val receipt = Wire.Payload(kind = "control", control = "receipt", receipt = "delivered", refIds = listOf("x"))
        assertEquals(Lane.FIRST, prepare(alice, receipt, bob, kind = "control").lane)

        // bob receives it knowing nothing about alice: the frame names nobody, the certificate does
        deliver(first, alice, bob)
        assertEquals(listOf(row), rows(bob, alice).map { it.id })
        assertEquals(alice.hash, bob.svc.contacts.value.firstOrNull()?.hash)
        assertContentEquals(alice.deliveryKey, bob.peerKey(alice)?.dk?.bytes, "her key came inside")
        assertEquals(Lane.KEY, prepare(bob, text("hi"), alice).lane)
    }

    @Test
    fun `the path table on the real engine`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        val dk = Bytes(PeerKeys.newDeliveryKey())
        suspend fun lane(entry: PeerKey?): Lane {
            alice.svc.sealedForTest { setPeerKey(bob.hash, entry) }
            return prepare(alice, text("x"), bob).lane
        }
        assertEquals(Lane.LEGACY, lane(null), "row 5")
        assertEquals(Lane.LEGACY, lane(PeerKey(sealed = false)), "row 2")
        assertEquals(Lane.LEGACY, lane(PeerKey(dk = dk, sealed = false)), "row 2, even holding a key")
        assertEquals(Lane.KEY, lane(PeerKey(dk = dk, sealed = true)), "row 3")
        assertEquals(Lane.KEY, lane(PeerKey(dk = dk)), "row 3, a roster key")
        assertEquals(Lane.FIRST, lane(PeerKey(sealed = true)), "row 4")

        // row 1, three ways: no private connection, a certificate that ran out, nothing offered
        lane(PeerKey(dk = dk, sealed = true))
        alice.svc.sealedForTest { anonFailed = true }
        assertEquals(Lane.LEGACY, prepare(alice, text("x"), bob).lane)
        assertNotNull(payloadOf(prepare(alice, text("x"), bob), bob, alice).dk, "the legacy path still carries my key while this login has sealed sender")
        alice.svc.sealedForTest { anonFailed = false }
        arm(alice, usableMs = -1)
        assertEquals(Lane.LEGACY, prepare(alice, text("x"), bob).lane, "nothing is sealed with a certificate that ran out")
        arm(alice)
        assertEquals(Lane.KEY, prepare(alice, text("x"), bob).lane)
        alice.svc.sealedForTest { arm(null) }
        val off = prepare(alice, text("x"), bob)
        assertEquals(Lane.LEGACY, off.lane)
        val p = payloadOf(off, bob, alice)
        assertNull(p.dk)
        assertNull(p.dkh)
        assertNull(p.refId)
        // a forced path after a refusal
        arm(alice)
        val forced = alice.svc.sealedForTest {
            online = true
            try {
                prepare(text("x"), bob.hash, ttl = 60, id = "r", lane = Lane.LEGACY, attempt = 2)
            } finally {
                online = false
            }
        }
        assertIs<Wire.Out.Send>(forced.frame)
        assertEquals(Lane.LEGACY, alice.svc.sealedForTest { prepare(text("x"), bob.hash, ttl = 60, id = "r") }.lane, "with the link down nothing is sealed")
    }

    @Test
    fun `a build with no root offers nothing and sends today's frames`(): Unit = runBlocking {
        val alice = phone("alice", sealed = false)
        val bob = phone("bob", sealed = false)
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        assertNull(alice.svc.sealedForTest { offer() }, "no root: `register` and `auth` carry nothing new")
        assertNull(alice.svc.store.blob.deliveryKey, "and no key is even created")
        val out = prepare(alice, text("x"), bob, id = "row")
        assertEquals("row", assertIs<Wire.Out.Send>(out.frame).id)
        val p = payloadOf(out, bob, alice)
        assertNull(p.dk)
        assertNull(p.dkh)
        assertNull(p.refId)
        assertEquals(SealedStatus.NOT_OFFERED, alice.svc.sealedStatus.value)

        // with a root the offer is the hash of a key made once and kept
        val offer = assertNotNull(alice.svc.sealedForTest { roots = authority.roots; offer() })
        val key = assertNotNull(alice.svc.store.blob.deliveryKey).bytes
        assertEquals(b64(PeerKeys.deliveryHash(key)), offer)
        assertEquals(offer, alice.svc.sealedForTest { offer() })
        assertFalse(offer.contains(b64(key)), "the relay is given the hash, never the key")
    }

    // ---------------------------------------------------------------- receiving (C1's frame rules, C5)

    @Test
    fun `frames that mix the two paths are dropped and heal nothing`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        deliver(prepare(alice, text("one"), bob), alice, bob) // legacy, carries her key
        deliver(prepare(bob, text("two"), alice), bob, alice) // sealed; both now hold keys
        assertEquals(1, rows(bob, alice).size)

        val sealed = prepare(alice, text("sealed"), bob)
        val body = assertIs<Wire.Out.Deliver>(sealed.frame).body
        // a sealed body on a frame that names its sender
        bob.svc.routeForTest(Wire.In.Recv(ChatService.newMessageId(), alice.hash, "msg", body, now()))
        // today's envelope on a frame that names nobody
        alice.svc.sealedForTest { alice.svc.store.update { it.copy(peerKeys = null) } }
        val legacy = prepare(alice, text("legacy"), bob)
        bob.svc.routeForTest(Wire.In.Recv(ChatService.newMessageId(), null, "msg", assertIs<Wire.Out.Send>(legacy.frame).body, now()))
        // a sealed frame with no timestamp to judge its certificate at
        bob.svc.routeForTest(Wire.In.Recv(ChatService.newMessageId(), null, "msg", body, null))
        // a sealed frame after its sender's certificate ran out
        bob.svc.routeForTest(Wire.In.Recv(ChatService.newMessageId(), null, "msg", body, now() + 2 * 86_400_000L))
        assertEquals(1, rows(bob, alice).size, "none of them became a row")
        assertTrue(hasSession(bob, alice), "and none of them cost the session: nothing to heal")
        assertNull(bob.svc.lastError.value)
        // the sealed one, delivered properly, still opens
        deliver(sealed, alice, bob)
        assertEquals(listOf("one", "sealed"), rows(bob, alice).map { it.text })
    }

    @Test
    fun `a blocked sender is dropped before the ratchet and an unblock still decrypts`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        deliver(prepare(alice, text("one"), bob), alice, bob)
        deliver(prepare(bob, text("two"), alice), bob, alice)
        bob.svc.blockContact(alice.hash)
        val before = bob.svc.store.blob.sessions[alice.hash]
        val blocked = prepare(alice, text("while blocked"), bob)
        assertEquals(Lane.KEY, blocked.lane)
        deliver(blocked, alice, bob)
        assertEquals(1, rows(bob, alice).size)
        assertEquals(assertNotNull(before), bob.svc.store.blob.sessions[alice.hash], "the ratchet did not move")
        bob.svc.unblockContact(alice.hash)
        deliver(prepare(alice, text("after"), bob), alice, bob)
        assertEquals(listOf("one", "after"), rows(bob, alice).map { it.text })
    }

    @Test
    fun `a sealed whisper that cannot be matched heals like any failed decrypt`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        deliver(prepare(alice, text("one"), bob), alice, bob)
        deliver(prepare(bob, text("two"), alice), bob, alice)
        val whisper = prepare(alice, text("three"), bob)
        assertEquals(Lane.KEY, whisper.lane)
        // bob lost what he knew about alice
        bob.svc.sealedForTest { bob.svc.store.update { it.copy(knownIdentities = it.knownIdentities - alice.hash) } }
        deliver(whisper, alice, bob)
        assertEquals(1, rows(bob, alice).size)
        assertEquals("decrypt_failed", bob.svc.lastError.value)
        assertFalse(hasSession(bob, alice), "archived: the next send starts a new session")
    }

    // ---------------------------------------------------------------- login and status (C6)

    @Test
    fun `the certificate from ready is checked before it is used`(): Unit = runBlocking {
        val alice = phone("alice", sealed = false)
        val own = authority.issue(alice.hash, alice.reg.identityKey, now() + 86_400_000)
        val cert = b64(own.cert.serialized)
        val bind = b64(own.bind)
        val other = Authority().issue(alice.hash, alice.reg.identityKey, now() + 86_400_000)
        val status = { alice.svc.sealedStatus.value }
        alice.svc.sealedForTest {
            roots = authority.roots
            online = true

            loggedIn(Wire.In.Ready(queued = 0))
            assertFalse(armed)
            assertEquals(SealedStatus.NOT_OFFERED, status())
            assertFalse(usable)

            loggedIn(Wire.In.Ready(0, true, cert, bind, 86_400))
            assertTrue(armed)
            assertEquals(SealedStatus.ON, status())
            assertTrue(usable)

            // the private connection could not be opened: said so, and nothing is sealed
            anonFailed = true
            refreshStatus()
            assertEquals(SealedStatus.NO_PRIVATE_CONNECTION, status())
            assertFalse(usable)
            // the next login starts clean
            loggedIn(Wire.In.Ready(0, true, cert, bind, 86_400))
            assertEquals(SealedStatus.ON, status())

            // a relay with another root: its certificate fails my own check, so sealed sender stays off
            takeCertificate(b64(other.cert.serialized), b64(other.bind), 86_400)
            assertFalse(armed)
            assertEquals(SealedStatus.NOT_OFFERED, status())
            // and so does a certificate that is mine with a binding that is not
            takeCertificate(cert, b64(other.bind), 86_400)
            assertFalse(armed)

            // a renewal replaces the one held
            takeCertificate(cert, bind, 86_400)
            assertTrue(armed)
            // a ten-second certificate is usable for nine: sealing stops a tenth of its life before the end
            takeCertificate(cert, bind, 10)
            val left = assertNotNull(usableForMs)
            assertTrue(left in 8_001..9_000, "$left")
            // the link going down takes it away
            linkDown()
            assertFalse(armed)
            assertEquals(SealedStatus.NOT_OFFERED, status())
            online = false
        }
    }

    // ---------------------------------------------------------------- verdicts from the anonymous connection

    @Test
    fun `a verdict is mapped back to the caller's id`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        alice.svc.sealedForTest {
            processBundle(bob.bundle(), bob.hash)
            setPeerKey(bob.hash, PeerKey(dk = Bytes(PeerKeys.newDeliveryKey()), sealed = true))
        }
        fun link() = AnonLink(RelayClient("ws://127.0.0.1:9/ws", RelayHttp.makeClient()))
        val link = link()
        suspend fun pendingRow(id: String) = alice.svc.sealedForTest {
            appendMessage(ChatMessage(id = id, direction = Direction.OUTGOING, kind = Kind.TEXT, text = "x", timestamp = Instant.now(), status = Status.PENDING), bob.hash)
        }
        fun status(id: String): Status? = alice.svc.messages.value[bob.hash]?.firstOrNull { it.id == id }?.status

        /** A sealed attempt for row [id], in flight on [on]; returns the id the relay saw. */
        suspend fun inFlight(id: String, on: AnonLink): Pair<ChatService.Outbound, String> {
            val out = prepare(alice, text("x"), bob, id = id)
            val relayId = assertIs<Wire.Out.Deliver>(out.frame).id
            alice.svc.sealedForTest { on.attempts[relayId] = out }
            return out to relayId
        }

        // `sent` for the attempt's id marks the ROW sent
        pendingRow("row-1")
        val (_, one) = inFlight("row-1", link)
        alice.svc.routeAnonForTest(Wire.In.Sent(one), link)
        assertEquals(Status.SENT, status("row-1"))
        assertTrue(link.attempts.isEmpty())
        // a `sent` for an id this connection never used changes nothing
        pendingRow("row-2")
        alice.svc.routeAnonForTest(Wire.In.Sent("row-2"), link)
        assertEquals(Status.PENDING, status("row-2"), "a row id is never a relay id on this connection")

        // an ordinary refusal fails the row, like the same refusal on the logged-in socket
        // (refusals are handled off the receive loop, so the row changes a moment later)
        val (_, two) = inFlight("row-2", link)
        alice.svc.routeAnonForTest(Wire.In.Error("queue_full", ref = two), link)
        assertTrue(waitUntil { status("row-2") == Status.FAILED })
        assertEquals("queue_full", alice.svc.lastError.value)

        // `unsupported`: an old build has the account. The key is kept, the peer is marked, the
        // message goes again the old way, and this connection takes nothing more.
        pendingRow("row-3")
        val (three, threeId) = inFlight("row-3", link)
        alice.svc.sealedForTest { capture("row-3", three) }
        assertEquals(CapturedFrame.DELIVER, alice.svc.capturedFrame("row-3")?.t)
        alice.svc.sealedForTest { online = true }
        alice.svc.routeAnonForTest(Wire.In.Error("unsupported", ref = threeId), link)
        assertTrue(waitUntil { alice.peerKey(bob)?.sealed == false })
        assertTrue(waitUntil { alice.svc.capturedFrame("row-3")?.t == CapturedFrame.SEND }, "the details sheet shows the frame that replaced it")
        alice.svc.sealedForTest { online = false }
        assertNotNull(alice.peerKey(bob)?.dk, "`unsupported` keeps the key")
        assertTrue(link.ended, "retired, and with nothing left in flight, closed")
        val after = assertNotNull(alice.svc.capturedFrame("row-3"))
        assertEquals("row-3", after.id, "and on the legacy path the relay id is the row id again")
        assertNotEquals(threeId, after.id, "no id is used on both sockets")

        // a connection that ends with a message in flight fails it, to be retried when one is back
        val fresh = link()
        alice.svc.sealedForTest { setPeerKey(bob.hash, PeerKey(dk = Bytes(PeerKeys.newDeliveryKey()), sealed = true)) }
        pendingRow("row-4")
        inFlight("row-4", fresh)
        alice.svc.sealedForTest { anonEnd(fresh) }
        assertEquals(Status.FAILED, status("row-4"))
        assertTrue(alice.svc.sealedForTest { lostWork })

        // `intro_limit`: the relay found a first message too large after all. The row fails, in words.
        val third = link()
        alice.svc.sealedForTest { setPeerKey(bob.hash, PeerKey(sealed = true)) } // no key: the first-contact lane
        pendingRow("row-5")
        val (five, fiveId) = inFlight("row-5", third)
        assertEquals(Lane.FIRST, five.lane)
        alice.svc.routeAnonForTest(Wire.In.Error("intro_limit", ref = fiveId), third)
        assertTrue(waitUntil { status("row-5") == Status.FAILED })
        assertEquals(Sealed.firstContactTooLong(alice.svc.sealedForTest { peerName(bob.hash) }), alice.svc.lastError.value)
        assertFalse(third.ended, "a refusal that changes no path leaves the connection alone")
    }

    // ---------------------------------------------------------------- groups (§5.1)

    @Test
    fun `a roster carries delivery keys and never overrides a direct one`(): Unit = runBlocking {
        val alice = phone("alice")
        val bob = phone("bob")
        val carol = phone("carol")
        alice.svc.sealedForTest {
            assertEquals(b64(alice.deliveryKey), rosterDeliveryKey(alice.hash), "my own, for me")
            assertNull(rosterDeliveryKey(bob.hash), "none held, none given")
            setPeerKey(bob.hash, PeerKey(dk = Bytes(bob.deliveryKey), sealed = true))
            assertEquals(b64(bob.deliveryKey), rosterDeliveryKey(bob.hash))
            arm(null)
            assertNull(rosterDeliveryKey(bob.hash), "a login without sealed sender sends today's roster")
        }

        // carol learns bob's key from a roster, and only because she held none
        carol.svc.sealedForTest { learnRosterKey(b64(bob.deliveryKey), bob.hash) }
        assertEquals(PeerKey(dk = Bytes(bob.deliveryKey), sealed = true), carol.peerKey(bob))
        val other = PeerKeys.newDeliveryKey()
        carol.svc.sealedForTest {
            learnRosterKey(b64(other), bob.hash)
            learnRosterKey(b64(other), carol.hash)
        }
        assertContentEquals(bob.deliveryKey, carol.peerKey(bob)?.dk?.bytes)
        assertNull(carol.peerKey(carol), "never an entry for myself")
        // with a session she can write to bob on the key lane without ever having spoken to him
        carol.svc.sealedForTest { processBundle(bob.bundle(), bob.hash) }
        assertEquals(Lane.KEY, prepare(carol, text("hi"), bob).lane)
    }
}
