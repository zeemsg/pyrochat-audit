package com.pyrochat.service

import com.pyrochat.protocol.Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Where the operator notices live between launches (docs/PROTOCOL.md §"Operator notices").
 *
 * Deliberately OUTSIDE the encrypted store: notices are the relay's public announcements — the
 * same list for every user, no account involved — so they are not secret, and the `<pyrochat>`
 * channel must be readable the moment the app unlocks (the relay replays them at login anyway).
 * The app binds this to `SharedPreferences`; tests use [InMemoryNoticeStore]. Whatever wipes the
 * store (total wipe, duress, dead-man) also calls [clear].
 */
interface NoticeStore {
    /** The stored notices as the JSON array [NoticeCodec] writes; null when nothing was saved yet. */
    fun getNotices(): String?

    fun putNotices(json: String)

    /** Ids of notices the user has already opened (the `[N]` marker counts the rest). */
    fun getSeen(): Set<String>

    fun putSeen(ids: Set<String>)

    /**
     * The last accepted `versions` entry for this platform as the JSON object [VersionCodec]
     * writes (PROTOCOL.md §"Latest versions"); null when nothing was saved yet or the last frame
     * had no entry for this platform. Same home as the notices so the update banner also shows
     * when the app opens offline.
     */
    fun getVersions(): String?

    /** null = the last frame carried no entry for this platform (a stale banner must not survive). */
    fun putVersions(json: String?)

    /** Forget everything — notices, seen ids and the remembered version — part of the local wipe. */
    fun clear()
}

/** Tests and the fake shell: notices live for the life of the instance. */
class InMemoryNoticeStore : NoticeStore {
    @Volatile private var notices: String? = null
    @Volatile private var seen: Set<String> = emptySet()
    @Volatile private var versions: String? = null

    override fun getNotices(): String? = notices
    override fun putNotices(json: String) { notices = json }
    override fun getSeen(): Set<String> = seen
    override fun putSeen(ids: Set<String>) { seen = ids.toSet() }
    override fun getVersions(): String? = versions
    override fun putVersions(json: String?) { versions = json }
    override fun clear() { notices = null; seen = emptySet(); versions = null }
}

/**
 * The persisted shape: the wire frame minus `t` (`ts`/`expires` epoch seconds), one JSON array.
 * Decoding never throws — a corrupt preference file just means "no notices until the next login".
 */
object NoticeCodec {
    @Serializable
    private data class Stored(val id: String, val text: String, val url: String? = null, val ts: Long, val expires: Long)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val listSerializer = ListSerializer(Stored.serializer())
    private val idsSerializer = SetSerializer(String.serializer())

    fun encode(notices: List<ChatService.Notice>): String =
        json.encodeToString(listSerializer, notices.map { Stored(it.id, it.text, it.url, it.ts.epochSecond, it.expires.epochSecond) })

    fun decode(text: String?): List<ChatService.Notice> {
        if (text.isNullOrBlank()) return emptyList()
        val stored = runCatching { json.decodeFromString(listSerializer, text) }.getOrNull() ?: return emptyList()
        return stored.mapNotNull { NoticeRules.accept(it.id, it.text, it.url, it.ts, it.expires, now = null) }
    }

    fun encodeIds(ids: Set<String>): String = json.encodeToString(idsSerializer, ids)

    fun decodeIds(text: String?): Set<String> =
        if (text.isNullOrBlank()) emptySet() else runCatching { json.decodeFromString(idsSerializer, text) }.getOrDefault(emptySet())
}

/** What a client accepts (PROTOCOL.md): dedupe by id, `https://` only, never an already-expired notice. */
object NoticeRules {
    /** The relay caps the text at 500 characters; anything longer did not come from it. */
    const val MAX_TEXT = 500

    /** A validated [ChatService.Notice] from a relay frame, or null when the frame is malformed. */
    fun fromFrame(f: Wire.In.Notice, now: Instant): ChatService.Notice? = accept(f.id, f.text, f.url, f.ts, f.expires, now)

    /**
     * [now] = null skips the expiry check (loading from disk keeps the sweeper in charge). Rules:
     * non-blank id and text (trimmed), text ≤ [MAX_TEXT], url absent/blank → null else it must be
     * `https://` with no whitespace, `expires` after `now`.
     */
    fun accept(id: String, text: String, url: String?, ts: Long, expires: Long, now: Instant?): ChatService.Notice? {
        if (id.isBlank()) return null
        val body = text.trim()
        if (body.isEmpty() || body.length > MAX_TEXT) return null
        val link = url?.trim()?.takeIf { it.isNotEmpty() }
        if (link != null && (!link.startsWith("https://") || link.any { it.isWhitespace() })) return null
        if (expires <= 0) return null
        val expiresAt = Instant.ofEpochSecond(expires)
        if (now != null && !expiresAt.isAfter(now)) return null
        return ChatService.Notice(id = id, text = body, url = link, ts = Instant.ofEpochSecond(ts), expires = expiresAt)
    }
}
