package com.pyrochat.service

import com.pyrochat.service.Live.waitUntil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Update-banner live gate (docs/PROTOCOL.md §"Latest versions"), the counterpart of
 * `ios/Tests/UpdateBannerTests.testGateRelayAdvertisesBuild99ThenClearsLive`. The coordinator
 * starts a throwaway relay (token.mode=allow-all) and PUTs `/admin/versions` with
 * `{"android":{"build":99,"name":"9.9.9","url":"https://pyrochat.ca/"}, "ios":{…}}`; a REAL
 * ChatService registers and must see that banner within 10 s, then the operator lowers Android
 * to build 1 and the banner must clear within 10 s. Whatever the relay advertised before is
 * restored afterwards. Skipped unless the relay is named:
 *
 *   cd android && ./gradlew :core-service:test --tests '*VersionsLiveTest*' \
 *       -PversionsRelay=127.0.0.1:<port> -PversionsAdminPw=<password>
 *
 * (or the PYRO_VERSIONS_RELAY / PYRO_VERSIONS_ADMIN_PW environment variables).
 */
class VersionsLiveTest {
    private val relay: String? = (System.getProperty("pyro.versions.relay") ?: System.getenv("PYRO_VERSIONS_RELAY"))?.takeIf { it.isNotBlank() }
    private val adminPw: String = (System.getProperty("pyro.versions.admin.pw") ?: System.getenv("PYRO_VERSIONS_ADMIN_PW"))?.takeIf { it.isNotBlank() } ?: "dev-admin-pw"
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun adminUri() = URI("http://$relay/admin/versions")
    private fun auth() = "Basic " + Base64.getEncoder().encodeToString("op:$adminPw".toByteArray())

    /** GET /admin/versions → the raw body (the frame without `t`). */
    private fun getVersions(): String {
        val req = HttpRequest.newBuilder(adminUri()).header("Authorization", auth()).header("Accept", "application/json").GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET /admin/versions → ${res.statusCode()}: ${res.body()}" }
        return res.body()
    }

    /** PUT /admin/versions — body = the frame without `t`. */
    private fun putVersions(body: String) {
        val req = HttpRequest.newBuilder(adminUri()).header("Authorization", auth()).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() in 200..299) { "PUT /admin/versions → ${res.statusCode()}: ${res.body()}" }
    }

    @Test
    fun `live gate - the relay advertises build 99 at login and a build-1 PUT clears the banner`(): Unit = runBlocking {
        Assumptions.assumeTrue(relay != null, "SKIPPED: no relay named — pass -PversionsRelay=host:port (PYRO_VERSIONS_RELAY) to run the update-banner live gate")
        val (host, port) = relay!!.substringBeforeLast(':') to relay!!.substringAfterLast(':').toInt()
        Assumptions.assumeTrue(Live.relayListening(host, port), "SKIPPED: nothing listening on $relay")

        val original = getVersions()
        val expected = LatestVersion(99, "9.9.9", "https://pyrochat.ca/")
        assertTrue(
            Regex(""""android"\s*:\s*\{[^}]*"build"\s*:\s*99[^}]*\}""").containsMatchIn(original),
            "the gate relay must be pre-set to android build 99 before this test runs; got $original",
        )
        val notices = InMemoryNoticeStore()
        val me = Live.makeService("av", relayUrl = "ws://$relay/ws", noticeStore = notices, versionCode = 9)
        try {
            assertNull(me.svc.updateAvailable.value, "a fresh install has no banner")
            me.svc.register(me.username)
            val arrived = waitUntil(10_000) { me.svc.updateAvailable.value == expected }
            assertTrue(arrived, "login must deliver `versions` (build 99) within 10 s; got ${me.svc.updateAvailable.value}")
            assertEquals(expected, VersionCodec.decode(notices.getVersions()), "remembered next to the notices for the offline open")
            println("VERSIONS GATE: build 99 banner arrived at login for <${me.username}> = ${me.svc.updateAvailable.value}")

            // the operator lowers Android to build 1 (≤ our running 9): every connected client clears live
            putVersions("""{"ios":{"build":99,"name":"9.9.9","url":"https://pyrochat.ca/"},"android":{"build":1,"name":"9.9.9","url":"https://pyrochat.ca/"}}""")
            val cleared = waitUntil(10_000) { me.svc.updateAvailable.value == null }
            assertTrue(cleared, "a frame with build 1 must clear the banner within 10 s; still ${me.svc.updateAvailable.value}")
            assertEquals(LatestVersion(1, "9.9.9", "https://pyrochat.ca/"), VersionCodec.decode(notices.getVersions()), "the last frame is remembered even when it shows no banner")
            println("VERSIONS GATE: build 1 PUT cleared the banner live")
            me.svc.goOffline()
        } finally {
            runCatching { putVersions(original) }          // put build 99 back for the iOS gate
            me.close()
        }
    }
}
