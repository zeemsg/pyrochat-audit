package com.pyrochat.service

import com.pyrochat.protocol.Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The newest build the relay advertises for THIS platform (docs/PROTOCOL.md §"Latest versions").
 * `build` = Play's `versionCode`; `name` = the marketing version (display only, ≤ [UpdateRules.MAX_NAME]);
 * `url` = where to get it, `https://` only, null when the relay gave none (the banner is then not tappable).
 */
data class LatestVersion(val build: Int, val name: String, val url: String?)

/**
 * The client rule of PROTOCOL.md §"Latest versions", identical on both platforms: read our own
 * platform's entry; the banner shows when its `build` is greater than the running build, and a
 * frame with a lower or equal build — or without this platform at all — CLEARS it. Pure; the
 * running build is injected (core-service has no `BuildConfig`).
 */
object UpdateRules {
    /** The key of our entry in the frame — `"android"` (`"ios"` on the other side). */
    const val PLATFORM = "android"

    /** The relay caps `name` at 32 characters; anything longer is clamped for display. */
    const val MAX_NAME = 32

    /** The relay caps `url` at 300 characters; a longer or non-`https://` url is dropped, never shown. */
    const val MAX_URL = 300

    /** What one frame means for the banner. */
    sealed interface Verdict {
        /** Some entry was malformed: the frame is ignored AS A WHOLE and the banner stays as it was. */
        data object Malformed : Verdict

        /** [latest] = this platform's validated entry (null when absent) — persisted; [update] = the banner (null clears). */
        data class Accepted(val latest: LatestVersion?, val update: LatestVersion?) : Verdict
    }

    /**
     * Validate one platform entry: `build` must be an integer ≥ 1 and `name` non-blank (trimmed),
     * else null (malformed). `name` is clamped to [MAX_NAME]; `url` survives only as a
     * whitespace-free `https://` link of at most [MAX_URL] characters, otherwise it is dropped.
     */
    fun entry(build: Int, name: String, url: String?): LatestVersion? {
        if (build < 1) return null
        val label = name.trim().take(MAX_NAME)
        if (label.isEmpty()) return null
        val link = url?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL && it.startsWith("https://") && it.none(Char::isWhitespace) }
        return LatestVersion(build = build, name = label, url = link)
    }

    /** The banner for [latest] against [runningBuild]: greater only; lower, equal or absent ⇒ null (clear). */
    fun update(latest: LatestVersion?, runningBuild: Int): LatestVersion? = latest?.takeIf { it.build > runningBuild }

    /**
     * The verdict for a whole `versions` frame: every platform entry present must validate (one
     * malformed entry ignores the frame), then our own entry decides the banner via [update].
     */
    fun fromFrame(f: Wire.In.Versions, runningBuild: Int): Verdict {
        val entries = mapOf("ios" to f.ios, PLATFORM to f.android)
        val validated = HashMap<String, LatestVersion>()
        for ((platform, e) in entries) {
            if (e == null) continue
            validated[platform] = entry(e.build, e.name, e.url) ?: return Verdict.Malformed
        }
        val mine = validated[PLATFORM]
        return Verdict.Accepted(latest = mine, update = update(mine, runningBuild))
    }
}

/**
 * How the last accepted entry is remembered between launches (next to the notices, outside the
 * encrypted store, same wipe): one small JSON object, or nothing when the last frame had no entry
 * for this platform. Decoding never throws — a corrupt preference just means "no banner until the
 * next login".
 */
object VersionCodec {
    @Serializable
    private data class Stored(val build: Int, val name: String, val url: String? = null)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun encode(v: LatestVersion): String = json.encodeToString(Stored.serializer(), Stored(v.build, v.name, v.url))

    /** The stored entry re-validated through [UpdateRules.entry], so what comes back always obeys the rule. */
    fun decode(text: String?): LatestVersion? {
        if (text.isNullOrBlank()) return null
        val s = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() ?: return null
        return UpdateRules.entry(s.build, s.name, s.url)
    }
}
