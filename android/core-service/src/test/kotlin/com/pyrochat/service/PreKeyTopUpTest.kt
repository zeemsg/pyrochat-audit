package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.crypto.SignalState
import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import com.pyrochat.service.Live.waitUntil
import com.pyrochat.store.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ChatService.swift:175-176, 1596-1599`: the relay reports `prekey_count` after every login and
 * after every upload; below 10 the client generates 32 fresh one-time prekeys from `nextPreKeyId`
 * and uploads them. A raw relay client drains the pool to make the relay say n = 9.
 */
class PreKeyTopUpTest {
    @BeforeEach
    fun requireRelay() = Live.requireRelay()

    @Test
    fun `prekey_count below 10 on login triggers one persisted 32-key top-up`(): Unit = runBlocking {
        val alice = Live.makeService("al")
        val uploads = CopyOnWriteArrayList<Wire.Out.PreKeyUpload>()
        try {
            alice.svc.wireTap = { if (it is Wire.Out.PreKeyUpload) uploads += it }
            alice.svc.register(alice.username)
            assertEquals(Protocol.ONE_TIME_PRE_KEY_BATCH + 1, alice.svc.store.blob.nextPreKeyId)
            delay(1_000)
            assertTrue(uploads.isEmpty(), "32 keys on the relay after signup: no top-up")

            // mallory (a raw relay client) drains 23 of alice's 32 one-time prekeys — each fetch pops one
            val mallory = RelayClient(LOCAL_RELAY_URL, RelayHttp.makeClient()).also { it.connect() }
            try {
                val engine = CryptoEngine(SignalState())
                val registration = engine.generateIdentity(Live.freshUsername("ma"))
                assertEquals(RelaySession.RegisterResult.Registered, RelaySession.register(mallory, engine, registration))
                assertIs<RelaySession.LoginResult.Ready>(RelaySession.login(mallory, engine))
                repeat(23) { assertIs<RelaySession.BundleResult.Bundle>(RelaySession.fetchPreKeyBundle(mallory, alice.hash)) }
            } finally {
                mallory.close()
            }

            // alice's next login: the relay says n = 9 → top-up of 32 (ids 33…64) → relay answers n = 41
            alice.svc.goOffline()
            alice.svc.goOnline()
            assertTrue(waitUntil { uploads.size == 1 }, "exactly one prekey_upload after the low count")
            assertEquals(Protocol.ONE_TIME_PRE_KEY_BATCH, uploads.first().oneTimePreKeys.size)
            assertEquals((33..64).toList(), uploads.first().oneTimePreKeys.map { it.id })
            assertEquals(65, alice.svc.store.blob.nextPreKeyId)
            assertEquals(64, alice.svc.store.blob.preKeys.size, "private halves of all 64 keys are kept until consumed")
            delay(SecureStore.DEBOUNCE_MS + 200)
            assertEquals(65, alice.reopen().nextPreKeyId, "the counter is persisted (a reused id would break a peer's bootstrap)")

            // 41 on the relay now: a further login must NOT top up again
            alice.svc.goOffline()
            alice.svc.goOnline()
            delay(1_500)
            assertEquals(1, uploads.size)
            alice.svc.goOffline()
        } finally {
            alice.close()
        }
    }
}
