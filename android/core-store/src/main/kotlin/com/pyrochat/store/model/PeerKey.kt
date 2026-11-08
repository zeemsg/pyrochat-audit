package com.pyrochat.store.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * What is known about one peer's delivery key (docs/SEALED-SENDER.md §5.1) — one entry of
 * `Blob.peerKeys`, with exactly the keys iOS writes (`Sealed.PeerKey`).
 */
@Serializable
data class PeerKey(
    /** Their delivery key, once learned. */
    val dk: Bytes? = null,
    /** The fingerprint of MY key that their latest payload echoed; absent if it had none. */
    val ack: Bytes? = null,
    /** true: can receive sealed. false: known not to. absent: not known yet. */
    val sealed: Boolean? = null,
) {
    val isEmpty: Boolean get() = dk == null && ack == null && sealed == null
}

/**
 * The rules of docs/SEALED-SENDER.md §5.1 and §5.2 that need no socket and no libsignal: what a
 * peer's delivery key is and when to send mine, and which path a message takes. iOS `Sealed` and
 * the bot's `sealed.mjs` are the same rules; the three are tested against the same cases.
 */
object PeerKeys {
    /** The path for a message (§5.2). [FETCH]: fetch the bundle, which sets `sealed`, then ask again. */
    enum class Lane { LEGACY, KEY, FIRST, FETCH }

    /** A first-contact `body` may be this long, in characters (§4.5). */
    const val FIRST_CONTACT_MAX_BODY = 16_384

    /** A sealed message makes at most this many attempts, each one step down (§5.2). */
    const val MAX_ATTEMPTS = 3

    /** What the relay stores for an account: the hash of its delivery key, never the key. */
    fun deliveryHash(deliveryKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(deliveryKey)

    /** The 8 bytes a peer echoes (`dkh`) to say which of my delivery keys it holds. */
    fun fingerprint(deliveryKey: ByteArray): ByteArray = deliveryHash(deliveryKey).copyOf(8)

    fun newDeliveryKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String?, size: Int): Bytes? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.size == size }?.let(::Bytes)

    /**
     * Every payload received from a peer, on any path. A payload without `dkh` means the peer does
     * not hold my key (any more), so the echo is forgotten and my key goes out again.
     */
    fun payloadReceived(entry: PeerKey?, dk: String?, dkh: String?): PeerKey {
        var p = entry ?: PeerKey()
        decode(dk, 32)?.let { p = p.copy(dk = it, sealed = true) }
        return p.copy(ack = decode(dkh, 8))
    }

    /** A sealed message from them passed every check. */
    fun sealedReceived(entry: PeerKey?): PeerKey = (entry ?: PeerKey()).copy(sealed = true)

    /** Every prekey bundle reply on the anonymous connection says whether the account can receive sealed messages. */
    fun bundleSeen(entry: PeerKey?, sealed: Boolean): PeerKey = (entry ?: PeerKey()).copy(sealed = sealed)

    /** The relay said `unsupported`. The key is kept. */
    fun unsupported(entry: PeerKey?): PeerKey = (entry ?: PeerKey()).copy(sealed = false)

    /**
     * The relay said `denied` to a message that carried [access]: that key is no longer theirs.
     * null when it is not the key held now (another refusal already dropped it, or a newer key
     * arrived meanwhile); only a non-null answer is a reason to look the peer up again.
     */
    fun denied(entry: PeerKey?, access: ByteArray?): PeerKey? {
        val held = entry?.dk ?: return null
        if (access == null || !held.bytes.contentEquals(access)) return null
        return entry.copy(dk = null)
    }

    /**
     * A delivery key from a group roster: taken only when none is held for that member. A key
     * learned from the person beats one learned from a third party.
     */
    fun rosterKey(entry: PeerKey?, dk: String?): PeerKey? {
        if (entry?.dk != null) return entry
        val key = decode(dk, 32) ?: return entry
        val p = entry ?: PeerKey()
        return p.copy(dk = key, sealed = p.sealed != false)
    }

    data class Outgoing(val dk: String?, val dkh: String?)

    /** What every payload I send carries: `dk` until they echo my current key, `dkh` while I hold theirs. */
    fun outgoingFields(entry: PeerKey?, myDeliveryKey: ByteArray): Outgoing {
        val echoed = entry?.ack?.bytes?.contentEquals(fingerprint(myDeliveryKey)) == true
        return Outgoing(
            dk = if (echoed) null else b64(myDeliveryKey),
            dkh = entry?.dk?.let { b64(fingerprint(it.bytes)) },
        )
    }

    /**
     * The path for a message to a peer; the first match wins. [offered]: the relay offered sealed
     * sender at this login, my certificate passed its own check and has not run out, and the
     * anonymous connection is usable.
     */
    fun route(entry: PeerKey?, offered: Boolean, hasSession: Boolean): Lane = when {
        !offered -> Lane.LEGACY                              // row 1
        entry?.sealed == false -> Lane.LEGACY                // row 2
        entry?.dk != null -> Lane.KEY                        // row 3
        entry?.sealed == true -> Lane.FIRST                  // row 4
        hasSession -> Lane.LEGACY                            // row 5
        else -> Lane.FETCH                                   // row 6
    }
}
