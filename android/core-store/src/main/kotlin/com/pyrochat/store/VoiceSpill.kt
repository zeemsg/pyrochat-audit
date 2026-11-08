package com.pyrochat.store

import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.ChatMessage
import java.util.UUID

/**
 * docs/DECOY-ACCOUNT.md §1a — get the audio out of the blob.
 *
 * `voiceData` was the one binary held INLINE in the blob: a 120-second AAC note is a quarter to
 * half a megabyte and base64 inflates it by a third, so twenty of them overflow a 4 MiB slot. Voice
 * notes now live in sidecar attachments, exactly where images and files already are — the row keeps
 * `attachmentId` and `voiceDuration`, the audio sits beside the store under the same key.
 *
 * **This is pure.** It computes the rewritten [Blob] and the sidecars that must exist for it; the
 * caller writes them and can roll them back. The ORDER is the whole point (§1a): a legacy file has
 * no size limit and a container does, so the spill happens while the store is still legacy —
 * unlock, spill in memory, and only then write the container.
 *
 * Decoding stays tolerant of `voiceData`: it still arrives from an older backup, and
 * [ChatMessage.voiceData] is still read everywhere audio is played. Nothing new ever writes it.
 */
object VoiceSpill {

    /** The rewritten blob and the sidecar files it now depends on — `attachmentId -> AAC bytes`. */
    data class Plan(val blob: Blob, val sidecars: Map<String, ByteArray>) {
        val isEmpty: Boolean get() = sidecars.isEmpty()
    }

    /** How many rows still hold their audio inline. */
    fun countInline(blob: Blob): Int =
        blob.messages.values.sumOf { rows -> rows.count { it.voiceData != null && it.attachmentId == null } }

    /** Total inline audio in bytes — what the spill takes out of the sealed plaintext. */
    fun inlineBytes(blob: Blob): Long =
        blob.messages.values.sumOf { rows ->
            rows.sumOf { (if (it.attachmentId == null) it.voiceData?.bytes?.size else null)?.toLong() ?: 0L }
        }

    /**
     * Move every inline `voiceData` to a sidecar id. A row that already has an [ChatMessage.attachmentId]
     * is left exactly as it is — its bytes may or may not already be on disk, and guessing is how
     * audio gets lost.
     *
     * [newId] must produce canonical lowercase UUID strings ([SecureStore.isValidAttachmentId]).
     */
    fun plan(blob: Blob, newId: () -> String = { UUID.randomUUID().toString() }): Plan {
        if (countInline(blob) == 0) return Plan(blob, emptyMap())
        val sidecars = LinkedHashMap<String, ByteArray>()
        val messages = LinkedHashMap<String, List<ChatMessage>>(blob.messages.size)
        for ((thread, rows) in blob.messages) {
            messages[thread] = rows.map { row ->
                val audio = row.voiceData
                if (audio == null || row.attachmentId != null) return@map row
                val id = newId()
                require(SecureStore.isValidAttachmentId(id)) { "a sidecar id must be a UUID string" }
                sidecars[id] = audio.bytes
                // fileBytes stays null on a voice row, as it always has been on both platforms.
                row.copy(voiceData = null, attachmentId = id)
            }
        }
        return Plan(blob.copy(messages = messages), sidecars)
    }
}
