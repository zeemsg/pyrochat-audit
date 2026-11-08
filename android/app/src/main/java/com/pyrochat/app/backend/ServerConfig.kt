package com.pyrochat.app.backend

import com.pyrochat.app.BuildConfig
import com.pyrochat.store.model.AppSettings

/**
 * Where the app talks to — the port of `AppSettings.defaultRelayURL` / `defaultBillingURL`
 * (`Models.swift:206-211`) and of the DEBUG-only `--demo-relay` / `--demo-socks` launch
 * arguments (`PyroChatApp.swift:205-212, 224-231`).
 *
 * Every build dials the production relay behind Cloudflare TLS; the store codec already migrates
 * the legacy IP / loopback addresses on decode (`LegacyUrlMigratingSettingsSerializer`). iOS keeps
 * an XCTest-only loopback default; the Android equivalent is the emulator gate's intent extras,
 * honoured only where `BuildConfig.LOCAL_HARNESS` is set: debug builds, and `releaseCheck`, the
 * release build as R8 leaves it, which exists to be tried against a relay on this machine:
 *
 * ```
 * am start -n com.pyrochat.app/.MainActivity \
 *    --es relay ws://10.0.2.2:7444/ws --es billing http://10.0.2.2:8088 [--es socks host:port]
 * ```
 *
 * Like iOS, an override is written INTO the store's settings at every unlock / create, so the
 * service, the mirror and the redeem client all read one value.
 *
 * `PRIVATE_SERVER_UI` mirrors the Swift compile flag of the same name: the relay picker on the
 * create screen and the relay/billing rows in `[cfg]` exist only in a private-server build. In the
 * public build the create screen's `relay` argument is always empty, so the built-in default wins.
 */
object ServerConfig {
    /** `Models.swift:206` — `wss://relay.pyrochat.ca/ws` */
    const val DEFAULT_RELAY_URL: String = AppSettings.DEFAULT_RELAY_URL

    /** `Models.swift:207` — `https://pyrochat.ca` */
    const val DEFAULT_BILLING_URL: String = AppSettings.DEFAULT_BILLING_URL

    /** `#if PRIVATE_SERVER_UI` — see `app/build.gradle.kts` (`buildConfigField`). */
    val privateServerUi: Boolean get() = BuildConfig.PRIVATE_SERVER_UI

    /** The debug launch overrides; every field optional. */
    data class Override(val relay: String? = null, val billing: String? = null, val socks: String? = null) {
        val isEmpty: Boolean get() = relay == null && billing == null && socks == null

        /** iOS `store.update { $0.settings.relayURL = relay }` (+ billing, + socks) — applied on every unlock/create. */
        fun apply(settings: AppSettings): AppSettings = settings.copy(
            relayURL = relay ?: settings.relayURL,
            billingURL = billing ?: settings.billingURL,
            socksProxy = socks ?: settings.socksProxy,
        )
    }

    /**
     * Parse the launch extras. Returns null unless [localHarness] and at least one usable value is
     * present; blank values are ignored, a relay must be `ws://` or `wss://`, a billing URL
     * `http://` or `https://`. The release build is compiled with the switch off.
     */
    fun override(relay: String?, billing: String?, socks: String?, localHarness: Boolean = BuildConfig.LOCAL_HARNESS): Override? {
        if (!localHarness) return null
        val r = relay?.trim()?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
        val b = billing?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val s = socks?.trim()?.takeIf { it.isNotEmpty() }
        val o = Override(r, b, s)
        return if (o.isEmpty) null else o
    }

    /**
     * The relay the CREATE screen typed (`submitAuth(relay:)`): honoured only in a
     * private-server build, where the field exists; the public build always keeps the default.
     */
    fun relayForNewIdentity(typed: String, privateServer: Boolean = privateServerUi): String? =
        typed.trim().takeIf { privateServer && it.isNotEmpty() }
}
