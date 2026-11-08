package com.pyrochat.service

import com.pyrochat.protocol.Wire

/**
 * docs/MESSAGE-DETAILS.md §3 — ONE relay envelope exactly as it crossed the wire, so the details
 * sheet can show what the relay actually received instead of asserting it.
 *
 * The six fields here are the whole of a `send` / `deliver` / `recv` frame: there is no room in
 * this type for key material, for a session, or for plaintext, and nothing but [Wire.Out.Send],
 * [Wire.Out.Deliver] and [Wire.In.Recv] can build one ([of]). A sealed frame's `access` (the
 * recipient's delivery key) is never kept: there is nowhere to put it. [body] is the base64 envelope the relay was handed — ciphertext, never a
 * key. A frame is NEVER produced by re-encrypting: ratcheting a session to draw a screen would be
 * a real bug, so when nothing was captured there simply is no ciphertext to show.
 */
data class CapturedFrame(
    /** `send` or `deliver` (this phone → relay; `deliver` is the sealed one) or `recv` (relay → this phone). */
    val t: String,
    /** The relay frame id — for a fanned-out group row, the id of the envelope shown. */
    val id: String,
    /** `to` on a send or a deliver, `from` on a recv: the peer's 64-hex routing address. Empty on a sealed recv, which names no sender. */
    val peer: String,
    /** Seconds; sends only — a `recv` frame carries no ttl. */
    val ttl: Int?,
    /** `msg` | `blob` | `control`. */
    val kind: String,
    /** base64 of the E2E envelope — the ciphertext, exactly as it went over the wire. */
    val body: String,
) {
    val outgoing: Boolean get() = t == SEND || t == DELIVER

    /** Plain bytes of the envelope behind [body] — what `N bytes of ciphertext` counts. */
    val bodyBytes: Int get() = base64Bytes(body)

    /**
     * What this entry costs the buffer: the ASCII bytes of the envelope as the relay saw them.
     * Deliberately the wire size and not a JVM object size — the bound in §3 is about the wire.
     */
    val weight: Int get() = t.length + id.length + peer.length + kind.length + body.length + (ttl?.toString()?.length ?: 0)

    companion object {
        const val SEND = "send"
        const val DELIVER = "deliver"
        const val RECV = "recv"

        fun of(frame: Wire.Out.Send): CapturedFrame =
            CapturedFrame(t = SEND, id = frame.id, peer = frame.to, ttl = frame.ttl, kind = frame.kind, body = frame.body)

        fun of(frame: Wire.Out.Deliver): CapturedFrame =
            CapturedFrame(t = DELIVER, id = frame.id, peer = frame.to, ttl = frame.ttl, kind = frame.kind, body = frame.body)

        fun of(frame: Wire.In.Recv): CapturedFrame =
            CapturedFrame(t = RECV, id = frame.id, peer = frame.from ?: "", ttl = null, kind = frame.kind, body = frame.body)

        /** Decoded length of standard padded base64, without allocating the bytes. */
        internal fun base64Bytes(b64: String): Int {
            val n = b64.length
            if (n == 0) return 0
            val pad = when {
                n >= 2 && b64[n - 1] == '=' && b64[n - 2] == '=' -> 2
                b64[n - 1] == '=' -> 1
                else -> 0
            }
            return (n / 4) * 3 + when (n % 4) {
                2 -> 1
                3 -> 2
                else -> 0
            } - pad
        }
    }
}

/**
 * docs/MESSAGE-DETAILS.md §3 — the capture buffer: outgoing frames as they are sent, incoming
 * frames as they arrive, keyed by MESSAGE id (the row the user can long-press), bounded to the
 * most recent [maxEntries] messages or [maxBytes] of envelope, whichever comes first, oldest
 * evicted.
 *
 * RAM only, and it has no way not to be: nothing here touches the store, a backup or the mirror.
 * Its single owner is the [ChatService] of one unlocked session, which [clear]s it on shutdown —
 * lock, auto-lock, wipe and app exit all route through that — and drops the whole instance with
 * the session, so nothing survives a restart.
 *
 * Synchronized because the UI reads it from the main thread while the service actor writes.
 */
class FrameCapture(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Int = MAX_BYTES,
) {
    // access-agnostic insertion order: the eldest entry is the eldest CAPTURE, which is what
    // "oldest evicted" means here (a message is not refreshed by being looked at)
    private val entries = LinkedHashMap<String, CapturedFrame>()
    private var bytes = 0

    /** Capture [frame] for [messageId], replacing any earlier capture of that row (a retry). */
    @Synchronized
    fun put(messageId: String, frame: CapturedFrame) {
        if (frame.weight > maxBytes) return // a single frame that cannot fit is not worth evicting the rest for
        entries.remove(messageId)?.let { bytes -= it.weight }
        entries[messageId] = frame
        bytes += frame.weight
        evict()
    }

    /**
     * Capture only when the row has none yet — the group fan-out sends one envelope per member
     * and the sheet shows exactly one, so the first is kept rather than the last.
     */
    @Synchronized
    fun putIfAbsent(messageId: String, frame: CapturedFrame) {
        if (entries.containsKey(messageId)) return
        put(messageId, frame)
    }

    /** The envelope of [messageId], or null — an older message, a restart, or an evicted row. */
    @Synchronized
    operator fun get(messageId: String): CapturedFrame? = entries[messageId]

    /**
     * Move a capture to the row id it finally landed under: an incoming group row is filed under
     * the sender's `refId`, not the relay id the envelope arrived with.
     */
    @Synchronized
    fun rekey(from: String, to: String) {
        if (from == to) return
        val frame = entries.remove(from) ?: return
        bytes -= frame.weight
        put(to, frame)
    }

    /** Forget one row's envelope — the row was deleted, burned or retracted. */
    @Synchronized
    fun remove(messageId: String) {
        entries.remove(messageId)?.let { bytes -= it.weight }
    }

    /** Lock, auto-lock, wipe: the whole buffer goes. */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    /** How many rows are held. */
    @Synchronized
    fun size(): Int = entries.size

    /** How many envelope bytes are held. */
    @Synchronized
    fun byteSize(): Int = bytes

    /** Held row ids, oldest capture first — tests and nothing else. */
    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    private fun evict() {
        val it = entries.entries.iterator()
        while (it.hasNext() && (entries.size > maxEntries || bytes > maxBytes)) {
            bytes -= it.next().value.weight
            it.remove()
        }
    }

    companion object {
        /** §3: "the most recent 50 messages …" */
        const val MAX_ENTRIES = 50

        /** §3: "… or 256 KB, whichever comes first". */
        const val MAX_BYTES = 256 * 1024
    }
}
