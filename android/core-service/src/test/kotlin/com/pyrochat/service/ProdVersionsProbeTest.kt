package com.pyrochat.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Read-only production probe for PROTOCOL.md §"Latest versions": registers a throwaway
 * user on the relay named by `PYRO_VERSIONS_PROBE_RELAY` (default production), logs in as
 * a build-1 install and expects the advertised Android build to arrive as `updateAvailable`
 * within 15 s. Changes nothing on the relay (no admin call) and deregisters afterwards.
 * Skipped unless `PYRO_VERSIONS_PROBE_BUILD=<expected build>` is set. Run:
 *   PYRO_VERSIONS_PROBE_BUILD=10 ./gradlew :core-service:test --tests '*ProdVersionsProbeTest*'
 */
class ProdVersionsProbeTest {
    private val expectedBuild: Int? = System.getenv("PYRO_VERSIONS_PROBE_BUILD")?.trim()?.toIntOrNull()
    private val relay: String = System.getenv("PYRO_VERSIONS_PROBE_RELAY")?.takeIf { it.isNotBlank() } ?: "wss://relay.pyrochat.ca/ws"

    @Test fun loginDeliversTheAdvertisedAndroidBuild(): Unit = runBlocking {
        Assumptions.assumeTrue(expectedBuild != null, "SKIPPED: set PYRO_VERSIONS_PROBE_BUILD=<build>")
        val me = Live.makeService("vp", relayUrl = relay, versionCode = 1)
        try {
            me.svc.register(me.username)
            val arrived = Live.waitUntil(15_000) { me.svc.updateAvailable.value != null }
            assertTrue(arrived, "login must deliver a `versions` frame with an android entry within 15 s")
            val got = me.svc.updateAvailable.value!!
            println("VERSIONS PROBE: <${me.username}> on $relay sees ${got.name} (${got.build}) url=${got.url}")
            assertEquals(expectedBuild, got.build, "advertised android build")
        } finally {
            runCatching { me.svc.deregister() }   // leave nothing behind on the relay
            me.close()
        }
    }
}
