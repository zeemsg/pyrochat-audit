package com.pyrochat.mirror

import kotlinx.coroutines.flow.Flow

/** `ChatMessage.Direction` — serialized to the browser as `"in"` / `"out"`. */
enum class MirrorDirection(val wire: String) { incoming("in"), outgoing("out") }

/** `ChatMessage.Kind` raw values (the browser additionally sees `"burn"`, derived from [MirrorMessage.isBurn]). */
enum class MirrorKind { text, voice, image, file, system }

/** `ChatMessage.Status` raw values — sent verbatim in `Row.status`. */
enum class MirrorStatus { pending, sent, delivered, read, failed }

/** `Contact` as the engine reads it (`ChatService.contacts`). Times are epoch milliseconds. */
data class MirrorContact(
    val hash: String,
    val name: String,
    val verified: Boolean,
    /** `Contact.addedAt` — the `lastTs` fallback for a thread with no messages. */
    val addedAt: Long,
)

/**
 * `ChatMessage` as the engine reads it. Attachment bytes stay in the service (see
 * [MirrorSource.thumbnailJpeg]); the engine only needs the fields that make a browser Row.
 */
data class MirrorMessage(
    val id: String,
    val direction: MirrorDirection,
    val kind: MirrorKind,
    val text: String,
    /** `timestamp` in epoch milliseconds (the Row's `ts`). */
    val timestamp: Long,
    val status: MirrorStatus,
    /** `burnAfter != nil` — FLAGSHIP GUARANTEE: such a row crosses as `kind:"burn"` with no content. */
    val isBurn: Boolean = false,
    /** `viaWeb == true` — composed on a linked browser; echoed as `via:"web"`. */
    val viaWeb: Boolean = false,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fileName: String? = null,
    val fileBytes: Long? = null,
    val fileMime: String? = null,
    val voiceDuration: Double? = null,
    /** `ChatMessage.sender` — the sender's hash on INCOMING group rows (the thread key is the group, not the peer). */
    val senderHash: String? = null,
    /** `ChatMessage.receipts` — member hash → `delivered` | `read`, OUTGOING group rows only. */
    val receipts: Map<String, String>? = null,
)

/**
 * `Member` as the browser sees it (docs/MIRROR-GROUPS.md §1): the phone's own per-member colour,
 * already dimmed by lap, so the page never recomputes join order.
 */
data class MirrorMember(
    val hash: String,
    val name: String,
    /** `#rrggbb` — `GroupColours.hex(joinIndex)` over members ∪ former (GROUPS.md §5). */
    val color: String,
    /** `Member.gone` — the relay answered `unknown_user`; shown struck/dim, still in the roster. */
    val gone: Boolean = false,
    /** `Member.joinedAt` epoch ms — NEVER serialized; orders `who` by join (iOS `typingNames(in:)`). */
    val joinedAt: Long = 0,
)

/**
 * `Group` as the engine reads it (`ChatService.groups`). `name` is stored without the `#`;
 * `policy` is the wire word (`alert` | `burn` | `burn_remove`). Times are epoch milliseconds.
 */
data class MirrorGroup(
    val id: String,
    val name: String,
    /** The roster as stored (`Group.members` order, like iOS `groupJSON`), the local user included. */
    val members: List<MirrorMember>,
    val policy: String,
    /** `Group.createdAt` — the `lastTs` fallback for a group with no rows. */
    val createdAt: Long,
    /** `Group.left` — this phone was removed or left: never mirrored (the engine emits `group_rm`). */
    val left: Boolean = false,
    /**
     * `Group.former` with their colour slots — NEVER serialized; resolves `sender` (name + colour)
     * on history rows written by members who have since left or were removed.
     */
    val former: List<MirrorMember> = emptyList(),
) {
    /** A current or former member by hash. */
    fun member(hash: String): MirrorMember? = members.firstOrNull { it.hash == hash } ?: former.firstOrNull { it.hash == hash }
}

/**
 * What the mirror engine needs from the chat service — the slice of iOS `ChatService` that
 * `MirrorEngine.swift` touches. `core-service`'s ChatService implements this; the engine never
 * sees Signal keys, attachments, or the store. Every member names its iOS counterpart.
 *
 * Threading: the read members and the `note*`/[sendWebLink] callbacks are invoked on the
 * engine's serial context and must return quickly (snapshot reads); [sendText] is launched as its
 * own coroutine, like iOS `Task { await service.sendText(...) }`.
 */
interface MirrorSource {
    /** `ChatService.myUsername` — the display name in the `hello` record. */
    val myUsername: String
    /** `service.store.blob.settings.relayURL` — the mirror dials the SAME relay on a second, unauthenticated socket. */
    val relayUrl: String
    /** `settings.socksProxy` (`"host:port"` or null) — the second socket MUST follow the same proxy policy. */
    val socksProxy: String?
    /** `settings.readReceiptsOn` → `hello.showRead` (the browser renders READ only when true). */
    val showRead: Boolean

    /**
     * `ChatService.objectWillChange` — fire after ANY change to contacts, groups, messages, typing
     * peers (1:1 and group), connection state, or block list. The engine debounces 400 ms and diffs
     * against its shadow copy.
     */
    val changes: Flow<Unit>

    /** `ChatService.contacts` — insertion order, blocked ones included (the engine filters). */
    fun contacts(): List<MirrorContact>
    /** `ChatService.messages[thread] ?? []` — full thread, chronological; the engine windows the last 50. */
    fun messages(thread: String): List<MirrorMessage>
    /** `ChatService.typingPeers.keys` — hashes whose typing signal has not expired. */
    fun typingPeers(): Set<String>
    /** `ChatService.groups` — every group this phone knows, `left` ones included (the engine filters, MIRROR-GROUPS.md §3). */
    fun groups(): List<MirrorGroup>
    /** `ChatService.groupTyping` — group id → member hashes whose typing signal has not expired. */
    fun groupTyping(): Map<String, Set<String>>
    /** `ChatService.connState == .online`. */
    fun isOnline(): Boolean
    /** `ChatService.isBlocked(_:)`. */
    fun isBlocked(hash: String): Boolean

    /**
     * `ChatService.sendText(_:to:viaWeb: true)` — the browser composed it; the row must carry `viaWeb`.
     * [thread] may be a group id: the existing group fan-out send (MIRROR-GROUPS.md §3).
     */
    suspend fun sendText(text: String, thread: String)
    /**
     * `ChatService.noteRowVisible(messageID:thread:)` — read receipt + SYNCED burn start (never called
     * for burn rows). For a group id the service sends per-member receipts exactly like an on-phone read.
     */
    fun noteRowVisible(messageId: String, thread: String)
    /** `ChatService.noteComposerActivity(in:)` — typing signal (throttled inside the service); group ids take the group path. */
    fun noteComposerActivity(thread: String)
    /**
     * `service.attachmentData(for:)` + `ImageNormalizer.normalize(maxDimension: 256, quality: 0.5)`:
     * a JPEG of at most 24 000 bytes for an image row, or null (no data / too big / not an image).
     * The engine asks at most once per message id and never for burn rows.
     */
    fun thumbnailJpeg(message: MirrorMessage): ByteArray?
    /**
     * `ChatService.sendWebLink(to:up:browser:)` — the E2E `web_link` control to ONE conversation
     * (MIRROR-GROUPS.md §5, contextual disclosure): a contact hash → the peer; a group id → fanned
     * out to the members exactly like a group control, with `group:{id,epoch}`. The engine calls
     * it with `up = true` (sanitized browser label) the first time the browser touches [thread]
     * (`view`, `send_text`, `typing`, `mark_read`) and with `up = false` (null) on stop, ONLY for
     * the threads it announced. Nothing is sent at link time. A blocked contact or a group this
     * phone has left is a no-op inside the service.
     */
    fun sendWebLink(thread: String, up: Boolean, browser: String?)
}
