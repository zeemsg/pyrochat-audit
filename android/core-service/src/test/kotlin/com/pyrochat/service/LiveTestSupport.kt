package com.pyrochat.service

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.UsernameHash
import com.pyrochat.store.InMemorySecretStore
import com.pyrochat.store.SecretStore
import com.pyrochat.store.SecureStore
import com.pyrochat.store.crypto.Kdf
import com.pyrochat.store.model.Blob
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.Request
import org.junit.jupiter.api.Assumptions
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The local dev harness relay (tests only — never compiled into the app). */
const val LOCAL_RELAY_URL = "ws://127.0.0.1:7444/ws"

/**
 * The throwaway sealed-sender trust root in `relay/src/test/resources/sealed-test-keys`. Its
 * private half is in the repository, so it is handed only to services under test, and the service
 * trusts it only for a relay on this machine (docs/SEALED-SENDER.md §7).
 */
const val SEALED_TEST_ROOT = "Ba/550LupgYMzcYETJWXYu0sm+4OU5QhjsJxvqRehokn"

/** A deliberately cheap KDF for tests whose subject is not key derivation (600 000 iterations take ~0.5 s each). */
object FastKdf : Kdf {
    override fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(password + salt).copyOf(keyLen)
}

/** One in-process client: its own encrypted store on disk + ChatService (the iOS `makeService()` helper). */
class TestUser(val svc: ChatService, val username: String, val file: Path, val secrets: SecretStore, val password: String) {
    val hash: String get() = svc.myHash

    /** Cold-open the store file from disk — proves what was persisted, not what is in RAM. */
    fun reopen(): Blob = SecureStore.open(file, password, secrets, FastKdf).use { it.blob }

    fun close() = runCatching { svc.close() }
}

object Live {
    const val HOST = "127.0.0.1"
    const val PORT = 7444
    private val random = SecureRandom()
    const val PASSWORD = "test-pass-123"

    fun relayListening(host: String = HOST, port: Int = PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 500); true }
    } catch (_: IOException) {
        false
    }

    fun requireRelay() = Assumptions.assumeTrue(
        relayListening(),
        "SKIPPED: no relay listening on $HOST:$PORT — start the local dev relay (token.mode=allow-all) to run the live M1 suite",
    )

    /** The local harness runs `bot/index.mjs` as `pyrobot` against :7444 (`pgrep -fl "node index.mjs"`). */
    fun pyrobotRunning(): Boolean = try {
        val p = ProcessBuilder("pgrep", "-f", "node index.mjs").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out.isNotBlank()
    } catch (_: Exception) {
        false
    }

    fun freshUsername(prefix: String = "an"): String = prefix + UsernameHash.toHex(ByteArray(4).also(random::nextBytes))

    fun tmpRoot(): Path = Paths.get("build", "tmp", "live-tests").toAbsolutePath().also { Files.createDirectories(it) }

    /**
     * Every store made here, removed when the test JVM ends. Left alone they add up: a suite that
     * sends attachments leaves tens of megabytes per run, and a day of runs filled a disk.
     */
    private val made = ConcurrentLinkedQueue<Path>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { for (dir in made) runCatching { dir.toFile().deleteRecursively() } })
    }

    /**
     * iOS `makeService()`: a brand-new store (registration shape) pointed at the local relay,
     * wrapped in a fresh ChatService. [configure] edits the blob before the service reads it.
     */
    fun makeService(
        prefix: String = "an",
        relayUrl: String = LOCAL_RELAY_URL,
        noticeStore: NoticeStore = InMemoryNoticeStore(),
        versionCode: Int = 0,
        linkTiming: RelayLink.Timing = RelayLink.Timing(),
        /** Be, on the wire, a build from before sealed sender. */
        legacy: Boolean = false,
        inFront: () -> Boolean = { true },
        configure: (Blob) -> Blob = { it },
    ): TestUser {
        val dir = Files.createTempDirectory(tmpRoot(), "pyro-svc-")
        made.add(dir) // not `+=`: a Path is itself an Iterable of paths
        val file = dir.resolve(SecureStore.FILE_NAME)
        val secrets = InMemorySecretStore()
        val store = SecureStore.create(file, PASSWORD, secrets, FastKdf)
        store.update { configure(it.copy(settings = it.settings.copy(relayURL = relayUrl))) }
        val svc = ChatService(
            store, noticeStore = noticeStore, versionCode = versionCode, linkTiming = linkTiming,
            sealedTestRoots = listOf(SEALED_TEST_ROOT), inFront = inFront,
        )
        svc.legacyWireOnly = legacy
        return TestUser(svc, freshUsername(prefix), file, secrets, PASSWORD)
    }

    /** What a relay says it accepted since it started: sealed on the key lane, sealed on the first-contact lane, and the old way. */
    data class RelayTotals(val sealed: Int, val firstContact: Int, val legacy: Int)

    /**
     * The relay's own count (`/admin/data`), or null when it cannot be read: a wrong admin
     * password, or a relay from before sealed sender. Its admin server closes the connection
     * after every response, so each call asks for a fresh one.
     */
    fun relayTotals(port: Int = PORT): RelayTotals? {
        val req = Request.Builder().url("http://$HOST:$port/admin/data")
            .header("Authorization", Credentials.basic("admin", "dev-admin-pw")).header("Connection", "close").build()
        repeat(3) {
            val counted = runCatching {
                RelayHttp.makeClient(null).newCall(req).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject["sealedSender"]?.jsonObject }
            }.getOrNull()
            if (counted != null) {
                fun n(key: String) = counted[key]?.jsonPrimitive?.int ?: 0
                return RelayTotals(n("sealed"), n("firstContact"), n("legacy"))
            }
        }
        return null
    }

    /** iOS `waitUntil(_:_:)` — poll every 100 ms. */
    suspend fun waitUntil(timeoutMs: Long = 8_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(100)
        }
        return cond()
    }
}

/**
 * A private relay started from `relay/build/libs/pyrochat-relay.jar` on a free port with a temp
 * `relay.properties` (the way `relay/test/e2e.mjs startRelay()` does it) — so a test can kill and
 * restart it. Never touches the harness relays on 7444/7445. The registry file survives a restart
 * (the relay saves it every 2 s), the RAM queue does not — exactly like production.
 */
class ThrowawayRelay private constructor(val port: Int, private val dir: Path) : AutoCloseable {
    private var process: Process? = null
    val url: String get() = "ws://127.0.0.1:$port/ws"

    companion object {
        fun jar(): Path = Paths.get("..", "..", "relay", "build", "libs", "pyrochat-relay.jar").toAbsolutePath().normalize()
        fun available(): Boolean = Files.isRegularFile(jar())

        fun start(): ThrowawayRelay {
            val port = ServerSocket(0).use { it.localPort }
            val dir = Files.createTempDirectory(Live.tmpRoot(), "pyro-relay-")
            Files.writeString(
                dir.resolve("relay.properties"),
                listOf(
                    "port=$port", "tls.enabled=false", "registry.path=${dir.resolve("registry.json")}",
                    "token.mode=allow-all", "debug=false", "admin.password=throwaway-admin-pw",
                    "web.enabled=false", "apns.enabled=false", "fcm.enabled=false",
                ).joinToString("\n"),
            )
            return ThrowawayRelay(port, dir).also { it.launch() }
        }
    }

    private fun launch() {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val p = ProcessBuilder(java, "-jar", jar().toString(), dir.resolve("relay.properties").toString())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        process = p
        val started = CompletableFuture<Boolean>()
        thread(isDaemon = true, name = "throwaway-relay-stdout") {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.contains("listening")) started.complete(true)
            }
            started.complete(false)
        }
        check(started.get(20, TimeUnit.SECONDS)) { "throwaway relay did not start on port $port" }
        val deadline = System.currentTimeMillis() + 10_000
        while (!Live.relayListening(port = port) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(Live.relayListening(port = port)) { "throwaway relay never accepted on $port" }
    }

    /** Simulate an outage: SIGKILL the relay (its RAM queue is gone, its registry file is not). */
    fun kill() {
        process?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS)
        process = null
        val deadline = System.currentTimeMillis() + 10_000
        while (Live.relayListening(port = port) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    /** Bring it back on the SAME port with the SAME registry. */
    fun restart() = launch()

    override fun close() {
        kill()
        runCatching { dir.toFile().deleteRecursively() }
    }
}
