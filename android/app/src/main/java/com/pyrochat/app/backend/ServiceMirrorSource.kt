package com.pyrochat.app.backend

import com.pyrochat.app.platform.ImageNormalizer
import com.pyrochat.mirror.MirrorContact
import com.pyrochat.mirror.MirrorGroup
import com.pyrochat.mirror.MirrorMessage
import com.pyrochat.mirror.MirrorSource
import com.pyrochat.service.ChatService
import com.pyrochat.service.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * `MirrorSource` over one live [ChatService] — the slice of iOS `ChatService` that
 * `MirrorEngine.swift` touches. Snapshot reads come straight off the service's flows; the
 * suspend members are launched on [scope] (a main-immediate scope, so successive calls reach the
 * service actor in order, like `Task { await service.… }` on the main actor).
 *
 * Groups (docs/MIRROR-GROUPS.md §3): every group the service knows is offered (the engine drops
 * `left` ones), a group thread's rows carry `sender` / `receipts`, and a group id as `thread`
 * routes `sendText` / `noteRowVisible` / `noteComposerActivity` to the service's group paths.
 * The desktop link is disclosed per conversation (§5): nothing at link time, `web_link` to a
 * thread the first time the browser shows or touches it, the retraction only to those.
 */
class ServiceMirrorSource(private val svc: ChatService, private val scope: CoroutineScope) : MirrorSource {
    override val myUsername: String get() = svc.myUsername
    override val relayUrl: String get() = svc.store.blob.settings.relayURL
    override val socksProxy: String? get() = svc.store.blob.settings.socksProxy
    override val showRead: Boolean get() = svc.store.blob.settings.readReceiptsOn

    /** `objectWillChange`: any change to contacts, groups, messages, typing (1:1 or group), connection or block list. */
    override val changes: Flow<Unit> =
        merge(svc.contacts, svc.groups, svc.messages, svc.typingPeers, svc.groupTyping, svc.connState, svc.blockedHashes).map { }

    override fun contacts(): List<MirrorContact> = svc.contacts.value.map(Mapping::mirrorContact)

    /** iOS `groupJSON` names members via `displayName(for:in:)`: the local user is always `myUsername`, never the adder's nickname for me. */
    override fun groups(): List<MirrorGroup> {
        val me = svc.myHash
        val myName = svc.myUsername
        return svc.groups.value.map { g ->
            val mg = Mapping.mirrorGroup(g)
            mg.copy(members = mg.members.map { if (it.hash == me && it.name != myName) it.copy(name = myName) else it })
        }
    }

    override fun messages(thread: String): List<MirrorMessage> =
        (svc.messages.value[thread] ?: emptyList()).map(Mapping::mirrorMessage)

    override fun typingPeers(): Set<String> = svc.typingPeers.value.keys

    override fun groupTyping(): Map<String, Set<String>> = svc.groupTyping.value.mapValues { (_, m) -> m.keys }

    override fun isOnline(): Boolean = svc.connState.value == ConnState.ONLINE

    override fun isBlocked(hash: String): Boolean = svc.isBlocked(hash)

    /** A group id fans out over every member's session (`sendText(text, group)`), the row marked `viaWeb`. */
    override suspend fun sendText(text: String, thread: String) {
        svc.sendText(text, thread, viaWeb = true)
    }

    /**
     * Exactly an on-phone read: the chat screen only reports a row `needsVisibilityReport` says so
     * (incoming, not system, not already reported/read) — iOS `handleCommand` applies the same
     * gate, so a system row named by the page never flips to READ here either. For a group row the
     * service then sends the read receipt to the row's sender with `group` attached.
     */
    override fun noteRowVisible(messageId: String, thread: String) {
        val row = svc.messages.value[thread]?.firstOrNull { it.id == messageId } ?: return
        if (!svc.needsVisibilityReport(row)) return
        scope.launch { svc.noteRowVisible(messageId, thread) }
    }

    /** For a group the service fans the throttled `typing` control out to every member. */
    override fun noteComposerActivity(thread: String) {
        scope.launch { svc.noteComposerActivity(thread) }
    }

    /** `attachmentData(for:)` + `ImageNormalizer.normalize(maxDimension: 256, quality: 0.5)`, ≤ 24 000 B. */
    override fun thumbnailJpeg(message: MirrorMessage): ByteArray? {
        val row = svc.messages.value.values.asSequence().flatten().firstOrNull { it.id == message.id } ?: return null
        val bytes = svc.attachmentData(row) ?: return null
        return ImageNormalizer.mirrorThumbnail(bytes)?.jpeg
    }

    /**
     * MIRROR-GROUPS.md §5: the link is disclosed to ONE conversation at a time — a group id fans
     * `web_link` out with `group:{id,epoch}`, a contact hash goes to that peer. The engine calls
     * this on the first touch of a thread and, on stop, only for the threads it announced.
     */
    override fun sendWebLink(thread: String, up: Boolean, browser: String?) {
        scope.launch { svc.sendWebLink(thread, up, browser) }
    }
}
