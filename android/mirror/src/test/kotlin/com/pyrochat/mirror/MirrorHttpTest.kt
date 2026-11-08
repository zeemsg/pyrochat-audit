package com.pyrochat.mirror

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the copied factory to the same table as core-service `RelayHttpTest` (parity requirement in MirrorHttp.kt). */
class MirrorHttpTest {
    @Test
    fun parseProxyMirrorsIos() {
        assertEquals(MirrorHttp.ProxySpec("127.0.0.1", 9050), MirrorHttp.parseProxy("127.0.0.1:9050"))
        assertEquals(MirrorHttp.ProxySpec("tor.local", 1), MirrorHttp.parseProxy("  tor.local:1  "))
        assertEquals(MirrorHttp.ProxySpec("[::1]", 9050), MirrorHttp.parseProxy("[::1]:9050"))
        assertNull(MirrorHttp.parseProxy(null))
        assertNull(MirrorHttp.parseProxy(""))
        assertNull(MirrorHttp.parseProxy("   "))
        assertNull(MirrorHttp.parseProxy("host"))
        assertNull(MirrorHttp.parseProxy(":9050"))
        assertNull(MirrorHttp.parseProxy("host:"))
        assertNull(MirrorHttp.parseProxy("host:0"))
        assertNull(MirrorHttp.parseProxy("host:65536"))
        assertNull(MirrorHttp.parseProxy("host:abc"))
        assertEquals(MirrorHttp.ProxySpec("host", 65535), MirrorHttp.parseProxy("host:65535"))
    }

    @Test
    fun clientFactoryInstallsSocksProxyOrGoesDirect() {
        assertNull(MirrorHttp.makeClient(null).proxy)
        assertNull(MirrorHttp.makeClient("garbage").proxy)
        val proxied = MirrorHttp.makeClient("127.0.0.1:1080").proxy
        assertEquals(Proxy.Type.SOCKS, proxied?.type())
        val addr = proxied?.address() as InetSocketAddress
        assertTrue(addr.isUnresolved)
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(1080, addr.port)
    }

    /**
     * docs/SEALED-SENDER.md §5.5: every connection opens with the same handshake, which names no
     * build and no language list. Read from the bytes a server receives, for a plain request and
     * for two WebSocket upgrades.
     */
    @Test
    fun everyRequestOpensWithTheSameFixedHandshake() {
        val heads = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { s ->
                        val head = StringBuilder()
                        val input = s.getInputStream()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        heads += head.toString()
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: Exception) {
            }
        }
        try {
            val port = server.localPort
            fun head(): String = assertNotNull(heads.poll(5, java.util.concurrent.TimeUnit.SECONDS), "the server saw no request")
            MirrorHttp.makeClient().newCall(okhttp3.Request.Builder().url("http://127.0.0.1:$port/x").build()).execute().close()
            val plain = head()
            // a 200 is not an upgrade, so each socket fails; its request was sent all the same
            val sockets = List(2) {
                MirrorHttp.makeClient().newWebSocket(okhttp3.Request.Builder().url("ws://127.0.0.1:$port/ws").build(), object : okhttp3.WebSocketListener() {})
                head()
            }
            for (h in sockets + plain) {
                val lines = h.split("\r\n")
                assertTrue("User-Agent: ${MirrorHttp.USER_AGENT}" in lines, h)
                assertTrue("Accept-Language: ${MirrorHttp.ACCEPT_LANGUAGE}" in lines, h)
                assertEquals(1, lines.count { it.startsWith("User-Agent:", ignoreCase = true) }, "one User-Agent, not OkHttp's as well")
                assertTrue(lines.indexOf("User-Agent: ${MirrorHttp.USER_AGENT}") < lines.indexOf("Accept-Language: ${MirrorHttp.ACCEPT_LANGUAGE}"), "always in this order")
            }
            assertTrue(MirrorHttp.USER_AGENT.none { it.isDigit() }, "it names no build")
            assertTrue(MirrorHttp.ACCEPT_LANGUAGE.none { it == ',' || it == ';' }, "and no list of languages")
            fun shape(h: String) = h.split("\r\n").filterNot { it.startsWith("Sec-WebSocket-Key:") }
            assertEquals(shape(sockets[0]), shape(sockets[1]), "two sockets differ in nothing but the random key")
        } finally {
            server.close()
            acceptor.interrupt()
        }
    }
}
