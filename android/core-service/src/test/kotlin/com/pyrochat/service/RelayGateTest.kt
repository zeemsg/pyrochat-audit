package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.Envelope
import com.pyrochat.crypto.PreKeys
import com.pyrochat.crypto.RelayAuth
import com.pyrochat.crypto.SignalState
import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.UsernameHash
import com.pyrochat.protocol.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Milestone-0 gate, live against the local dev relay (`ws://127.0.0.1:7444/ws`, token mode
 * allow-all). Skipped — not failed — when nothing listens on 7444.
 */
class RelayGateTest {
    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 7444
        private const val URL = LOCAL_RELAY_URL
        private val http: OkHttpClient by lazy { RelayHttp.makeClient() }
        private val random = SecureRandom()

        fun relayListening(): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), 500); true }
        } catch (_: IOException) {
            false
        }
    }

    @BeforeEach
    fun requireRelay() {
        Assumptions.assumeTrue(
            relayListening(),
            "SKIPPED: no relay listening on $HOST:$PORT — start the local dev relay (token.mode=allow-all) to run the M0 gate",
        )
    }

    private fun freshUsername(): String = "And_" + UsernameHash.toHex(ByteArray(6).also(random::nextBytes))

    private suspend fun connect(): RelayClient = RelayClient(URL, http).also { it.connect() }

    private class Account(val engine: CryptoEngine, val registration: PreKeys.Registration, val username: String) {
        val hash: String get() = engine.state.usernameHash
    }

    private fun newAccount(username: String = freshUsername()): Account {
        val engine = CryptoEngine(SignalState())
        return Account(engine, engine.generateIdentity(username), username)
    }

    private suspend fun registerFresh(client: RelayClient, account: Account = newAccount()): Account {
        assertEquals(RelaySession.RegisterResult.Registered, RelaySession.register(client, account.engine, account.registration))
        return account
    }

    /** ready → the relay always follows with `prekey_count`; return n. */
    private suspend fun loginAndDrainCount(client: RelayClient, account: Account): Int {
        val ready = RelaySession.login(client, account.engine)
        assertIs<RelaySession.LoginResult.Ready>(ready)
        val count = RelaySession.awaitFrame(client, "prekey_count") { it is Wire.In.PreKeyCount }
        return (count as Wire.In.PreKeyCount).n
    }

    @Test
    fun `1 register a fresh identity`(): Unit = runBlocking {
        val client = connect()
        try {
            val account = newAccount()
            assertEquals(64, account.hash.length)
            assertEquals(UsernameHash.of(account.username.lowercase()), account.hash)
            val result = RelaySession.register(client, account.engine, account.registration)
            assertEquals(RelaySession.RegisterResult.Registered, result)
        } finally {
            client.close()
        }
    }

    @Test
    fun `2 login on a new socket with the same identity`(): Unit = runBlocking {
        val account = connect().let { c -> try { registerFresh(c) } finally { c.close() } }

        val client = connect()
        try {
            val n = loginAndDrainCount(client, account)
            assertEquals(Protocol.ONE_TIME_PRE_KEY_BATCH, n, "relay holds our 32 one-time prekeys")
            // authed: a ping is answered and an authed-only frame is accepted
            client.send(Wire.Out.Ping)
            assertIs<Wire.In.Pong>(RelaySession.awaitFrame(client, "pong") { it is Wire.In.Pong })
        } finally {
            client.close()
        }
    }

    @Test
    fun `3 wrong key and XEdDSA proofs are rejected with bad_sig`(): Unit = runBlocking {
        val account = connect().let { c -> try { registerFresh(c) } finally { c.close() } }

        // (a) a valid Ed25519 signature by a DIFFERENT key
        connect().let { c ->
            try {
                val wrongSeed = RelayAuth.generateSeed(random)
                val r = RelaySession.login(c, account.hash, { nonce -> RelayAuth.sign(nonce, wrongSeed) })
                assertEquals(RelaySession.LoginResult.Refused(Wire.ErrorCode.BAD_SIG, "auth"), r)
                // relay closes the socket after bad_sig
                val ev = withTimeout(5_000) { c.events.receive() }
                assertIs<RelayEvent.Disconnected>(ev)
            } finally {
                c.close()
            }
        }

        // (b) THE TRAP: libsignal's calculateSignature (XEdDSA over the X25519 identity key)
        connect().let { c ->
            try {
                val xeddsa = { nonce: ByteArray -> account.engine.identity().identityKeyPair.privateKey.calculateSignature(nonce) }
                val r = RelaySession.login(c, account.hash, xeddsa)
                assertEquals(RelaySession.LoginResult.Refused(Wire.ErrorCode.BAD_SIG, "auth"), r)
            } finally {
                c.close()
            }
        }

        // (c) and the right key still works afterwards
        connect().let { c ->
            try {
                assertIs<RelaySession.LoginResult.Ready>(RelaySession.login(c, account.engine))
            } finally {
                c.close()
            }
        }
    }

    @Test
    fun `4 prekey_upload accepted, prekey_fetch parses into a PreKeyBundle, and a message flows`(): Unit = runBlocking {
        val clientA = connect()
        val clientB = connect()
        try {
            val a = registerFresh(clientA)
            assertEquals(32, loginAndDrainCount(clientA, a))
            val fresh = a.engine.generateOneTimePreKeys(Protocol.ONE_TIME_PRE_KEY_BATCH)
            assertEquals((33..64).toList(), fresh.map { it.id })
            assertEquals(64, RelaySession.uploadPreKeys(clientA, fresh))

            val b = registerFresh(clientB)
            assertEquals(32, loginAndDrainCount(clientB, b))

            val fetched = RelaySession.fetchPreKeyBundle(clientB, a.hash)
            val bundle = assertIs<RelaySession.BundleResult.Bundle>(fetched).bundle
            assertEquals(a.hash, bundle.user)
            assertEquals(a.registration.regId, bundle.regId)
            assertEquals(Base64.getEncoder().encodeToString(a.registration.identityKey), bundle.identityKey)
            assertEquals(a.registration.signedPreKey, bundle.signedPreKey)
            assertEquals(a.registration.kyberPreKey, bundle.kyberPreKey)
            assertEquals(a.registration.oneTimePreKeys.first(), bundle.oneTimePreKey, "one-time keys are popped FIFO")

            val pkb = PreKeys.parseBundle(bundle)
            assertEquals(a.registration.regId, pkb.registrationId)
            assertEquals(Protocol.DEVICE_ID, pkb.deviceId)
            assertEquals(1, pkb.preKeyId)
            assertEquals(1, pkb.signedPreKeyId)
            assertEquals(1, pkb.kyberPreKeyId)
            assertContentEquals(a.registration.identityKey, pkb.identityKey.serialize())
            assertNotNull(pkb.kyberPreKey)

            // B → A through the relay: PreKey message bootstraps A's side
            b.engine.processBundle(bundle, a.hash)
            val hello = Wire.Payload(kind = "text", text = "hello from android", senderName = b.username)
            val id1 = UUID.randomUUID().toString().lowercase()
            clientB.send(Wire.Out.Send(id1, a.hash, Protocol.TTL_MSG, "msg", b.engine.encrypt(hello, a.hash)))
            assertEquals(Wire.In.Sent(id1), RelaySession.awaitFrame(clientB, "sent") { it is Wire.In.Sent && it.id == id1 })
            val recv1 = assertIs<Wire.In.Recv>(RelaySession.awaitFrame(clientA, "recv") { it is Wire.In.Recv && it.id == id1 })
            assertEquals(b.hash, recv1.from)
            assertEquals("msg", recv1.kind)
            assertEquals(Protocol.CIPHERTEXT_PREKEY, Envelope.open(recv1.body).type)
            assertEquals(hello, a.engine.decryptPayload(recv1.body, b.hash))
            clientA.send(Wire.Out.Ack(id1))

            // A → B: Whisper on the established session
            val reply = Wire.Payload(kind = "text", text = "hi back", senderName = a.username)
            val id2 = UUID.randomUUID().toString().lowercase()
            clientA.send(Wire.Out.Send(id2, b.hash, Protocol.TTL_MSG, "msg", a.engine.encrypt(reply, b.hash)))
            assertEquals(Wire.In.Sent(id2), RelaySession.awaitFrame(clientA, "sent") { it is Wire.In.Sent && it.id == id2 })
            val recv2 = assertIs<Wire.In.Recv>(RelaySession.awaitFrame(clientB, "recv") { it is Wire.In.Recv && it.id == id2 })
            assertEquals(Protocol.CIPHERTEXT_WHISPER, Envelope.open(recv2.body).type)
            assertEquals(reply, b.engine.decryptPayload(recv2.body, a.hash))
            clientB.send(Wire.Out.Ack(id2))

            // unknown user names the hash in the error
            val nobody = UsernameHash.of("nobody-" + freshUsername())
            assertEquals(RelaySession.BundleResult.Refused(Wire.ErrorCode.UNKNOWN_USER), RelaySession.fetchPreKeyBundle(clientB, nobody))
        } finally {
            clientA.close()
            clientB.close()
        }
    }

    @Test
    fun `5 registering the same username again is username_taken`(): Unit = runBlocking {
        val first = connect().let { c -> try { registerFresh(c) } finally { c.close() } }
        val client = connect()
        try {
            val again = newAccount(first.username.uppercase()) // different case, same hash
            assertEquals(first.hash, again.hash)
            assertEquals(
                RelaySession.RegisterResult.Refused(Wire.ErrorCode.USERNAME_TAKEN),
                RelaySession.register(client, again.engine, again.registration),
            )
        } finally {
            client.close()
        }
    }
}
