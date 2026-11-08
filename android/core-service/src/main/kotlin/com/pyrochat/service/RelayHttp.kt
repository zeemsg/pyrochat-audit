package com.pyrochat.service

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration

/**
 * THE ONE place a network client is built — the port of iOS `RelayClient.makeSession(socksProxy:)`.
 * Every socket/HTTP call the app makes (relay, mirror, billing) must use a client from here so a
 * configured SOCKS proxy is never bypassed.
 */
object RelayHttp {
    data class ProxySpec(val host: String, val port: Int)

    /**
     * What every request says about its sender: nothing that differs between two phones
     * (docs/SEALED-SENDER.md §5.5). Left alone, OkHttp names its own version, which changes with
     * the app's build. The same two headers as iOS, always in this order.
     */
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
     * Build the shared client. With a valid [socksProxy] every connection (WebSocket included) is
     * tunnelled through a SOCKS5 proxy; OkHttp hands SOCKS proxies an UNRESOLVED target address,
     * so DNS also happens at the proxy — the same guarantee as iOS `ProxyConfiguration(socksv5Proxy:)`.
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
