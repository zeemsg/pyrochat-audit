package com.pyrochat.store.model

import com.pyrochat.store.codec.SwiftDateSerializer
import com.pyrochat.store.codec.SwiftDoubleSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * `ChatMessage` (`ios/PyroChat/Core/Models.swift:32-94`). The six identity fields are required on
 * decode; everything else is optional and omitted when nil (SPEC.md §4.3). There is no persisted
 * burn deadline — [burnDeadline] is computed like `Models.swift:61-64` — and no "burned" status:
 * a burned row is deleted together with its sidecar.
 *
 * Durations (`burnAfter`, `voiceDuration`) stay `Double` seconds, mirroring Swift `TimeInterval`
 * one-to-one; instants use [Instant] via [SwiftDateSerializer].
 */
@Serializable
data class ChatMessage(
    /** lowercase UUID string — dedupe is a case-sensitive string compare */
    val id: String,
    val direction: Direction,
    val kind: Kind,
    /** text, or the placeholder `[IMG]` / `[FILE] <name>` / `[voice <n>s]`, or a system row's UI string */
    val text: String,
    @Serializable(with = SwiftDateSerializer::class)
    val timestamp: Instant,
    val status: Status,
    /** seconds; the armed self-destruct timer */
    @Serializable(with = SwiftDoubleSerializer::class)
    val burnAfter: Double? = null,
    /** nil until the countdown starts (reader: row visible; sender: `burn_start` control) */
    @Serializable(with = SwiftDateSerializer::class)
    val burnStartedAt: Instant? = null,
    /** voice notes are INLINE AAC bytes — there is no sidecar for them */
    val voiceData: Bytes? = null,
    @Serializable(with = SwiftDoubleSerializer::class)
    val voiceDuration: Double? = null,
    /** UUID string naming the sidecar file (image/file kinds) */
    val attachmentId: String? = null,
    val fileName: String? = null,
    val fileMime: String? = null,
    /** PLAINTEXT size */
    val fileBytes: Int? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /**
     * Composed on / received from a linked desktop browser. iOS writes `true` or omits the key,
     * never `false` (`ChatService.swift:834, 885`), so `false` is never encoded here either.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val viaWeb: Boolean = false,
    // --- group channels (docs/GROUPS.md §3): absent on every 1:1 row ---
    /** group id — the thread key of the row; nil for 1:1 */
    val group: String? = null,
    /** sender hash on INCOMING group rows (the thread key is the group, not the peer) */
    val sender: String? = null,
    /** OUTGOING group rows only: member hash → `"delivered"` | `"read"` (aggregated as `READ 7/9`) */
    val receipts: Map<String, String>? = null,
) {
    @Serializable
    enum class Direction {
        @SerialName("incoming") INCOMING,
        @SerialName("outgoing") OUTGOING,
    }

    @Serializable
    enum class Kind {
        @SerialName("text") TEXT,
        @SerialName("voice") VOICE,
        @SerialName("image") IMAGE,
        @SerialName("file") FILE,
        @SerialName("system") SYSTEM,
    }

    @Serializable
    enum class Status {
        @SerialName("pending") PENDING,
        @SerialName("sent") SENT,
        @SerialName("delivered") DELIVERED,
        @SerialName("read") READ,
        @SerialName("failed") FAILED;

        /** Receipt ordering (`Models.swift:96-108`): nothing ever moves backwards; failed ranks with sent. */
        val receiptRank: Int
            get() = when (this) {
                PENDING -> 0
                SENT, FAILED -> 1
                DELIVERED -> 2
                READ -> 3
            }
    }

    /** `burnStartedAt + burnAfter`, or null while either is missing (`Models.swift:61-64`). */
    val burnDeadline: Instant?
        get() {
            val after = burnAfter ?: return null
            val started = burnStartedAt ?: return null
            return started.plusNanos(Math.round(after * 1e9))
        }
}
