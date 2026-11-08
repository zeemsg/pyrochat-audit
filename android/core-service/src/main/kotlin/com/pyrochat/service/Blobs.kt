package com.pyrochat.service

import com.pyrochat.crypto.CryptoEngine
import com.pyrochat.protocol.Protocol
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.nio.ByteBuffer
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Milestone 2 — the blob (voice / image / file) wire pieces of `ios/PyroChat/Core/ChatService.swift`,
 * `CryptoEngine.swift` and `UI/Attachments.swift`, byte-compatible with iOS and the relay
 * (`RelayHandler.handleBinary`, PROTOCOL.md §6).
 */

/** iOS `ChatService.BlobSubtype` — raw values are the manifest `subtype` strings. */
enum class BlobSubtype(val wire: String) {
    VOICE("voice"), IMAGE("image"), FILE("file");

    companion object {
        fun fromWire(raw: String?): BlobSubtype? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * One binary WebSocket frame per chunk (`ChatService.swift:1423-1435`, PROTOCOL.md §6):
 *
 * ```
 * offset  size  field
 * 0       16    message id — the UUID's 128 bits big-endian (Swift `uuid.uuid`, Java `UUID(hi, lo)`)
 * 16      4     chunk seq, uint32 BE, 0-based
 * 20      4     chunk total, uint32 BE
 * 24      …     ciphertext chunk (≤ 64 KiB)
 * ```
 */
object BlobFrames {
    const val HEADER_BYTES = 24

    /** iOS `withUnsafeBytes(of: uuid.uuid)` — null when the id is not a UUID string (iOS: `guard let uuid … else { return }`). */
    fun header16(messageId: String): ByteArray? {
        val uuid = runCatching { UUID.fromString(messageId) }.getOrNull() ?: return null
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    fun chunk(header16: ByteArray, seq: Int, total: Int, blob: ByteArray, lo: Int, hi: Int): ByteArray {
        require(header16.size == 16)
        val frame = ByteBuffer.allocate(HEADER_BYTES + (hi - lo))
        frame.put(header16).putInt(seq).putInt(total).put(blob, lo, hi - lo)
        return frame.array()
    }

    /** The announce id, lowercase like every wire id (relay: `new UUID(hi, lo).toString()`). */
    fun messageId(frame: ByteArray): String {
        val buf = ByteBuffer.wrap(frame, 0, 16)
        return UUID(buf.long, buf.long).toString().lowercase()
    }

    /** Swift `Int(UInt32(bigEndian:))` — as a non-negative Long. */
    fun seq(frame: ByteArray): Long = ByteBuffer.wrap(frame, 16, 4).int.toLong() and 0xffff_ffffL

    fun total(frame: ByteArray): Long = ByteBuffer.wrap(frame, 20, 4).int.toLong() and 0xffff_ffffL

    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(HEADER_BYTES, frame.size)
}

/**
 * iOS `CryptoEngine.encryptBlob` / `decryptBlob`: the content bytes go through the SAME libsignal
 * session as texts (one ratchet step), but as a raw binary envelope `[1 byte message type][ciphertext]`
 * instead of the JSON+base64 body — 12 MiB plaintext stays ≈12 MiB on the wire. Built over the
 * public `CryptoEngine` surface (`stores`, `myAddress()`), so the Signal state stays where M1 put it.
 */
object BlobCrypto {
    fun encrypt(crypto: CryptoEngine, plaintext: ByteArray, peerHash: String): ByteArray {
        val ct = cipher(crypto, peerHash).encrypt(plaintext)
        val body = ct.serialize()
        val out = ByteArray(1 + body.size)
        out[0] = ct.type.toByte()
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }

    /** Throws on any failure — an empty blob, a bad type byte, or a ratchet error (the caller acks + heals). */
    fun decrypt(crypto: CryptoEngine, blob: ByteArray, peerHash: String): ByteArray {
        require(blob.size > 1) { "empty blob" }
        val type = blob[0].toInt() and 0xff
        val body = blob.copyOfRange(1, blob.size)
        val cipher = cipher(crypto, peerHash)
        return if (type == Protocol.CIPHERTEXT_PREKEY) {
            cipher.decrypt(PreKeySignalMessage(body))
        } else {
            cipher.decrypt(SignalMessage(body))
        }
    }

    // libsignal-java 0.102.2: SessionCipher(session, preKey, signedPreKey, kyber, identity, LOCAL, REMOTE)
    private fun cipher(crypto: CryptoEngine, peerHash: String): SessionCipher {
        val s = crypto.stores
        return SessionCipher(s, s, s, s, s, crypto.myAddress(), SignalProtocolAddress(peerHash, Protocol.DEVICE_ID))
    }
}

/**
 * iOS `AttachmentGate` (`UI/Attachments.swift:11-28`) — attachment admission control with the exact
 * user-facing strings. iOS refuses UTTypes conforming to `.movie`, `.video` and `.audiovisualContent`
 * (which, as an accepted consequence, also covers standalone audio — voice notes cover audio); the
 * Android analogue is the content resolver's MIME type: the `video` and `audio` MIME families
 * (any `video/…` or `audio/…`). An unknown or missing type is allowed as a generic file, exactly like iOS.
 */
object AttachmentGate {
    const val VIDEO_REFUSAL = "ERR: video files are not supported"
    const val SIZE_REFUSAL = "ERR: file exceeds 12 MiB limit"

    fun refusal(mime: String?): String? {
        val m = mime?.trim()?.lowercase(Locale.ROOT) ?: return null
        return if (m.startsWith("video/") || m.startsWith("audio/")) VIDEO_REFUSAL else null
    }

    /** `refusal(forContentTypes:)` — the first refusal among several candidate types. */
    fun refusal(mimes: List<String?>): String? = mimes.firstNotNullOfOrNull { refusal(it) }

    fun refusalForFileSize(bytes: Long): String? =
        if (bytes > ChatService.MAX_FILE_PLAINTEXT_BYTES) SIZE_REFUSAL else null
}

/**
 * iOS `sanitizeFilename` (`ChatService.swift:1757-1778`): strip path separators, control characters
 * and leading dots, clamp to 64 characters preserving a short extension; empty ⇒ "file". Characters
 * are grapheme clusters, like Swift `String.count` / `prefix`.
 */
fun sanitizeFilename(raw: String): String {
    val filtered = StringBuilder()
    raw.replace('/', '_').replace('\\', '_').codePoints().forEach { cp ->
        if (cp >= 0x20 && cp != 0x7F) filtered.appendCodePoint(cp)
    }
    // Swift `.whitespaces` = Unicode Zs + horizontal tab
    var name = filtered.toString().trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
    while (name.startsWith(".")) name = name.substring(1)
    if (name.isEmpty()) return "file"
    val clusters = graphemeClusters(name)
    if (clusters.size > 64) {
        val ext = pathExtension(name)
        val extLen = graphemeClusters(ext).size
        name = if (ext.isNotEmpty() && extLen < 12) {
            clusters.take(64 - extLen - 1).joinToString("") + "." + ext
        } else {
            clusters.take(64).joinToString("")
        }
    }
    return name
}

/** `NSString.pathExtension`: the part after the last dot of the last path component, "" when there is none. */
private fun pathExtension(name: String): String {
    val last = name.substringAfterLast('/')
    val dot = last.lastIndexOf('.')
    if (dot <= 0 || dot == last.length - 1) return ""
    val ext = last.substring(dot + 1)
    return if (ext.any { it == ' ' || it == '/' }) "" else ext
}

private fun graphemeClusters(s: String): List<String> {
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += s.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

/** iOS `byteLabel` (`UI/Attachments.swift:53-58`): `312K`, `2.4M`, `?` when nil. */
fun byteLabel(bytes: Long?): String {
    if (bytes == null) return "?"
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return "${bytes / 1024}K"
    return String.format(Locale.ROOT, "%.1fM", bytes.toDouble() / (1024 * 1024))
}

/**
 * iOS `attachmentCache = NSCache<NSString, NSData>()` with `totalCostLimit = 64 MiB`: decrypted
 * sidecar bytes stay in RAM for the UI, evicted least-recently-used by size. Thread-safe like NSCache
 * (the UI reads from any thread; the service purges from its own).
 */
internal class AttachmentCache(private val costLimit: Long) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(id: String): ByteArray? = entries[id]

    @Synchronized
    fun put(id: String, data: ByteArray) {
        if (data.size > costLimit) return
        entries.remove(id)?.let { total -= it.size }
        entries[id] = data
        total += data.size
        val it = entries.entries.iterator()
        while (total > costLimit && it.hasNext()) {
            val eldest = it.next()
            total -= eldest.value.size
            it.remove()
        }
    }

    @Synchronized
    fun remove(id: String) {
        entries.remove(id)?.let { total -= it.size }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        total = 0
    }
}
