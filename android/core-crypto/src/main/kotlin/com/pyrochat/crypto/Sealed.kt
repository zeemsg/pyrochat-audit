package com.pyrochat.crypto

import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.nio.ByteBuffer
import java.util.Base64

/**
 * The certificate half of sealed sender (docs/SEALED-SENDER.md §3): the checks a certificate
 * and its binding have to pass before the sender they name is believed. iOS `Sealed.swift` and
 * the bot's `sealed.mjs` are the same checks; the delivery-key rules that need no libsignal
 * live next to the store model (`PeerKeys`).
 */
object Sealed {
    /** `Wire.Envelope.type` of a sealed body, and the first byte of a sealed blob (§4.3). */
    const val ENVELOPE_TYPE = 6

    /**
     * A failed check, by its name in §3 (C0…C7). [heal] is true only for a failure after the
     * sender is certified that today's code would answer by rebuilding the session.
     */
    class Rejected(val check: String, val heal: Boolean = false) : Exception(check)

    /** My certificate and binding for this login. */
    class Own(val cert: SenderCertificate, val bind: ByteArray)

    /** 64 hex characters → 32 bytes; null for anything else. */
    fun hashBytes(hex: String): ByteArray? {
        if (hex.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The certificate names an account by the first 16 bytes of its username hash, as a UUID. */
    fun uuid(hash: ByteArray): String {
        val h = hex(hash.copyOf(16))
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** The 99 bytes the relay signs so the FULL username hash can be trusted (§3). */
    fun bindingMessage(hash: ByteArray, identityKey: ByteArray, expires: Long): ByteArray =
        ByteBuffer.allocate(26 + hash.size + identityKey.size + 8)
            .put("pyrochat-sender-binding-v1".toByteArray(Charsets.US_ASCII))
            .put(hash).put(identityKey).putLong(expires).array()

    /**
     * C1–C4 for a certificate and binding that claim [hash]: chained to a root at time [at], a
     * server key that has not been retired, device 1, and a binding the same relay key signed for
     * exactly this hash, this identity key and this expiry.
     */
    fun checkCertificate(cert: SenderCertificate, bind: ByteArray, hash: ByteArray, roots: List<ECPublicKey>, minKeyId: Int, at: Long) {
        if (roots.isEmpty()) throw Rejected("C1")
        try {
            CertificateValidator(roots).validate(cert, at)
        } catch (e: Exception) {
            throw Rejected("C1")
        }
        val server = cert.signer
        if (server.keyId < minKeyId) throw Rejected("C2")
        if (cert.senderDeviceId != 1) throw Rejected("C3")
        if (bind.size != 64 || hash.size != 32 || uuid(hash) != cert.senderUuid) throw Rejected("C4")
        val signed = bindingMessage(hash, cert.key.serialize(), cert.expiration)
        val verified = runCatching { server.key.verifySignature(signed, bind) }.getOrDefault(false)
        if (!verified) throw Rejected("C4")
    }

    /**
     * My own certificate and binding as the relay issued them. Checked the way a recipient will
     * check them, so a relay with another root or a retired key shows up here and not as messages
     * that silently never arrive. The time used is one millisecond before expiry: this phone's
     * clock plays no part.
     */
    fun takeOwnCertificate(
        certB64: String,
        bindB64: String,
        hash: ByteArray,
        identityKey: ByteArray,
        roots: List<ECPublicKey>,
        minKeyId: Int,
    ): Own {
        val cert = runCatching { SenderCertificate(Base64.getDecoder().decode(certB64)) }.getOrNull() ?: throw Rejected("C1")
        val bind = runCatching { Base64.getDecoder().decode(bindB64) }.getOrNull() ?: throw Rejected("C4")
        if (cert.expiration <= 0) throw Rejected("C1")
        checkCertificate(cert, bind, hash, roots, minKeyId, cert.expiration - 1)
        if (!cert.key.serialize().contentEquals(identityKey)) throw Rejected("C4")
        return Own(cert, bind)
    }

    /** §5.2: an attachment, or a message too long for it, on the first-contact lane. */
    fun firstContactRefusal(name: String): String = "ERR: send <$name> a text first — attachments work once it is delivered"
    fun firstContactTooLong(name: String): String = "ERR: too long for a first message — send <$name> a short one first"
}

/** Whose word is taken that a certificate is the relay's (docs/SEALED-SENDER.md §7). */
object SealedTrust {
    /**
     * The production trust root(s), base64, as printed by `deploy/sealed-keys.mjs root`. This one
     * was made on 2026-11-06. Its private half is `deploy/sealed/root.key` on the owner's Mac and
     * one offline copy, and nowhere else. A build with no root here never offers sealed sender and
     * behaves on the wire exactly as the builds before it.
     */
    val productionRoots: List<String> = listOf("BbCQLScxmfcklrLQwJRA7ZCFLyzoOAvxNbbu2w4DI0h+")

    /** Certificates from a relay signing key older than this are refused (§3, C2). */
    const val MIN_SERVER_KEY_ID = 1

    /** A relay on this machine: the only place a throwaway test root may be trusted. */
    fun isLocalRelay(relayUrl: String?): Boolean {
        val host = runCatching { java.net.URI(relayUrl ?: "").host }.getOrNull() ?: return false
        return host in setOf("127.0.0.1", "localhost", "::1", "[::1]", "10.0.2.2")
    }

    /**
     * The roots a certificate from this relay may chain to. [testRoots] are roots handed in by a
     * debug build or a test (the throwaway root's private half is in the repository): they count
     * only for a relay on this machine, never for a real one.
     */
    fun roots(relayUrl: String?, testRoots: List<String> = emptyList()): List<ECPublicKey> {
        val all = productionRoots + if (isLocalRelay(relayUrl)) testRoots else emptyList()
        return all.mapNotNull { runCatching { ECPublicKey(Base64.getDecoder().decode(it)) }.getOrNull() }
    }
}
