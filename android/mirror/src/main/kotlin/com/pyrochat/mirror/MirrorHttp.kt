package com.pyrochat.mirror

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration

/**
 * COPY of `android/core-service/src/main/kotlin/com/pyrochat/service/RelayHttp.kt` (the port of iOS
 * `RelayClient.makeSession(socksProxy:)` — THE one place a network client is built).
 *
 * Why a copy: `core-service`'s ChatService implements [MirrorSource] and owns a [MirrorEngine], so
 * `core-service → mirror` is the dependency direction; `mirror` cannot depend back on `core-service`
 * without a cycle. PARITY REQUIREMENT: this must stay behaviourally identical to `RelayHttp` —
 * the mirror's second socket following a different proxy policy than the main socket would leak
 * the user's IP to the relay (the exact bug iOS guards against in `MirrorEngine.start()`).
 * `RelayHttpTest` in core-service pins `parseProxy`; `MirrorHttpTest` pins this copy to the same table.
 */
object MirrorHttp {
    data class ProxySpec(val host: String, val port: Int)

    /** The fixed handshake, as in `RelayHttp`: every socket of the app opens the same way. */
    const val USER_AGENT = "PyroChat"
    const val ACCEPT_LANGUAGE = "en"

    /**
     * iOS `RelayClient.parseProxy`: `"host:port"`, whitespace-trimmed, split at the LAST colon,
     * port 1…65535, non-empty host; anything else → null (= direct connection).
     */
    fun parseProxy(raw: String?): ProxySpec? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        val colon = s.lastIndexOf(':')
        if (colon < 0) return null
        val port = s.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val host = s.substring(0, colon)
        return if (host.isEmpty()) null else ProxySpec(host, port)
    }

    /**
     * Build the client. With a valid [socksProxy] every connection (WebSocket included) is tunnelled
     * through a SOCKS5 proxy; OkHttp hands SOCKS proxies an UNRESOLVED target address, so DNS also
     * happens at the proxy — the same guarantee as iOS `ProxyConfiguration(socksv5Proxy:)`.
     */
    fun makeClient(socksProxy: String? = null, connectTimeout: Duration = Duration.ofSeconds(15)): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeout)
            .retryOnConnectionFailure(false)
            // an application interceptor, because it also runs for a WebSocket's upgrade request
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", ACCEPT_LANGUAGE)
                        .build(),
                )
            }
        parseProxy(socksProxy)?.let { p ->
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(p.host, p.port)))
        }
        return builder.build()
    }
}
