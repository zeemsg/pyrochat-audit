package com.pyrochat.app.backend

import com.pyrochat.store.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `Models.swift:206-211` defaults + the DEBUG-only launch overrides (`--demo-relay` / `--demo-socks`). */
class ServerConfigTest {
    @Test fun productionDefaults() {
        assertEquals("wss://relay.pyrochat.ca/ws", ServerConfig.DEFAULT_RELAY_URL)
        assertEquals("https://pyrochat.ca", ServerConfig.DEFAULT_BILLING_URL)
        assertEquals(ServerConfig.DEFAULT_RELAY_URL, AppSettings().relayURL)
        assertEquals(ServerConfig.DEFAULT_BILLING_URL, AppSettings().billingURL)
    }

    @Test fun overrideOnlyWhereTheLocalHarnessIsCompiledIn() {
        assertNull(ServerConfig.override("ws://10.0.2.2:7444/ws", "http://10.0.2.2:8088", null, localHarness = false))
        val o = ServerConfig.override("ws://10.0.2.2:7444/ws", "http://10.0.2.2:8088", null, localHarness = true)!!
        assertEquals("ws://10.0.2.2:7444/ws", o.relay)
        assertEquals("http://10.0.2.2:8088", o.billing)
        assertNull(o.socks)
    }

    @Test fun overrideIgnoresBlankAndWrongSchemes() {
        assertNull(ServerConfig.override("", " ", "", localHarness = true))
        assertNull(ServerConfig.override(null, null, null, localHarness = true))
        val o = ServerConfig.override("http://not-a-socket", "ftp://x", " 127.0.0.1:9050 ", localHarness = true)!!
        assertNull(o.relay)
        assertNull(o.billing)
        assertEquals("127.0.0.1:9050", o.socks)
    }

    @Test fun overrideAppliesOntoStoredSettings() {
        val stored = AppSettings(theme = "amber", socksProxy = "keep:1080")
        val partial = ServerConfig.Override(relay = "ws://10.0.2.2:7444/ws")
        val next = partial.apply(stored)
        assertEquals("ws://10.0.2.2:7444/ws", next.relayURL)
        assertEquals(AppSettings.DEFAULT_BILLING_URL, next.billingURL)
        assertEquals("keep:1080", next.socksProxy)
        assertEquals("amber", next.theme)
        val full = ServerConfig.Override("ws://a/ws", "http://b", "c:1").apply(stored)
        assertEquals(AppSettings(theme = "amber", socksProxy = "c:1", relayURL = "ws://a/ws", billingURL = "http://b"), full)
    }

    @Test fun typedRelayOnlyCountsInPrivateServerBuilds() {
        assertNull(ServerConfig.relayForNewIdentity("", privateServer = true))
        assertNull(ServerConfig.relayForNewIdentity("ws://custom/ws", privateServer = false))   // public build: field hidden
        assertEquals("ws://custom/ws", ServerConfig.relayForNewIdentity(" ws://custom/ws ", privateServer = true))
    }
}
