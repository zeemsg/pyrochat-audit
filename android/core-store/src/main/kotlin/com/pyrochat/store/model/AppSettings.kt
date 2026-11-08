package com.pyrochat.store.model

import com.pyrochat.store.codec.SwiftDoubleSerializer
import kotlinx.serialization.Serializable

/**
 * `AppSettings` (`ios/PyroChat/Core/Models.swift:171-264`). All 15 original keys are written on
 * every save (optional ones only when non-nil); each default below is what iOS substitutes for an
 * absent key (SPEC.md §4.7). The legacy relay/billing rewrite of `Models.swift:257-262` is applied
 * while decoding a [Blob] (see `LegacyUrlMigratingSettingsSerializer`).
 *
 * `hideNotices` (operator notices, v0.5) and `textSize` (docs/TEXT-SIZE.md) are the two later
 * additions — the same keys on iOS, written only when non-nil like the other optionals, so a store
 * touched by either platform still round-trips through the other.
 *
 * The URL defaults are the release values; the XCTest-only `127.0.0.1` defaults of
 * `Models.swift:206-211` exist for the iOS harness and are deliberately not mirrored.
 */
@Serializable
data class AppSettings(
    /** `ember` (green) | `amber` */
    val theme: String = DEFAULT_THEME,
    val scanlines: Boolean = true,
    val pushEnabled: Boolean = false,
    /** global self-destruct default in seconds; nil = off. Domain 5, 60, 3600, 86400. */
    @Serializable(with = SwiftDoubleSerializer::class)
    val defaultBurnSeconds: Double? = null,
    /** wipe whole threads after N idle days; nil = never. Domain 1, 7, 30. */
    val threadAutoBurnDays: Int? = null,
    val burnOnScreenshot: Boolean = false,
    /** nil ⇒ false (privacy default) */
    val sendReadReceipts: Boolean? = null,
    /** nil ⇒ true */
    val soundsEnabled: Boolean? = null,
    /** nil ⇒ true */
    val sendTyping: Boolean? = null,
    /** nil ⇒ true */
    val blockScreenshots: Boolean? = null,
    /** `"host:port"` SOCKS5 proxy for all relay traffic */
    val socksProxy: String? = null,
    /** dead-man switch: wipe after N days without an unlock; nil = off. Domain 7, 30, 90. */
    val wipeAfterDays: Int? = null,
    /** nil ⇒ 5; `0` = off. Domain 0, 1, 5, 15. */
    val autoLockMinutes: Int? = null,
    val relayURL: String = DEFAULT_RELAY_URL,
    val billingURL: String = DEFAULT_BILLING_URL,
    /** nil ⇒ false: the `<pyrochat>` operator-notices channel is shown (`[cfg]` → `system notices`). */
    val hideNotices: Boolean? = null,
    /**
     * `docs/TEXT-SIZE.md` §1 — `small` | `normal` | `large` | `larger` | `largest`: the app's own
     * type scale, on top of the phone's. The same key and spelling on iOS, written only when the
     * user has changed it, and an unknown or absent value reads as `normal` (the rule `theme`
     * already uses), so an old store and a newer build can never disagree.
     */
    val textSize: String? = null,
    /**
     * docs/STATUS-AND-CFG.md §1, the `backup` row of the status screen — when this account last
     * exported one, or absent on a phone that never has. Written only on a CONFIRMED successful
     * export, so `NEVER` is never a lie in the reassuring direction.
     *
     * **`lastBackupAt`, seconds since 1970, omitted when never** — §1 spells it once for both
     * platforms. Not the Swift 2001 reference date the other `Date` fields here use: the two
     * platforms have to agree on one number, and a platform drops blob keys it does not recognise
     * on its next save, so a mismatch means a cross-platform restore silently resets the row.
     *
     * **It lives in the blob rather than beside the biometric state on purpose.** Everything
     * outside the encrypted plaintext is device-level and therefore shared by every slot, and
     * docs/DECOY-ACCOUNT.md §4 is explicit that per-account state visible from another slot is a
     * leak: a decoy reporting the real account's backup date would be one.
     */
    @Serializable(with = SwiftDoubleSerializer::class)
    val lastBackupAt: Double? = null,
    /**
     * The voice a voice note is sent in (docs/VOICE-CHANGER.md): `deep`, `high` or `robot`; absent
     * = as spoken. **The same key and the same words on iOS**, optional and omitted when unset,
     * like [lastBackupAt]. It is in the blob because it is a safety setting for whoever relies on
     * it: it has to survive a restore, and a decoy has its own.
     */
    val voiceChanger: String? = null,
) {
    val readReceiptsOn: Boolean get() = sendReadReceipts ?: false
    val noticesOn: Boolean get() = hideNotices != true
    val soundsOn: Boolean get() = soundsEnabled ?: true
    val typingOn: Boolean get() = sendTyping ?: true
    val screenshotBlockOn: Boolean get() = blockScreenshots ?: true

    /** nil = auto-lock off; otherwise the timeout in minutes (`Models.swift:227-233`). */
    val autoLockAfterMinutes: Int?
        get() = when (autoLockMinutes) {
            null -> DEFAULT_AUTO_LOCK_MINUTES
            0 -> null
            else -> autoLockMinutes
        }

    companion object {
        const val DEFAULT_THEME = "ember"
        const val DEFAULT_AUTO_LOCK_MINUTES = 5

        /** `Models.swift:206` */
        const val DEFAULT_RELAY_URL = "wss://relay.pyrochat.ca/ws"

        /** `Models.swift:207` */
        const val DEFAULT_BILLING_URL = "https://pyrochat.ca"

        /**
         * Pre-domain addresses (a raw server IP, or the loopback dev harness) are migrated to the
         * defaults on decode (`Models.swift` `isLegacyAddress`). Deliberately a RULE, not a list:
         * no server address is compiled into the app, so the binary reveals nothing about where
         * the relay actually runs.
         */
        fun isLegacyAddress(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return true
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
    }
}
