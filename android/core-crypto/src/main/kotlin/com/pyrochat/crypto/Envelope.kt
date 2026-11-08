package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import org.signal.libsignal.protocol.message.CiphertextMessage

/**
 * libsignal ciphertext ⇄ relay `body` string, exactly as `bot/index.mjs encryptFor / decryptFrom`
 * and iOS `CryptoEngine.encrypt / decrypt`: `base64(JSON {"v":1,"type":<ct.type>,"data":"<base64 ct.serialize()>"})`.
 * `type` is `CiphertextMessage.getType()` — 3 (PreKey) bootstraps a session, 2 (Whisper) continues one.
 */
object Envelope {
    fun seal(ciphertext: CiphertextMessage): String = Wire.Envelope.of(ciphertext.type, ciphertext.serialize()).toBody()

    fun open(body: String): Wire.Envelope = Wire.Envelope.fromBody(body)

    fun isPreKey(envelope: Wire.Envelope): Boolean = envelope.type == Protocol.CIPHERTEXT_PREKEY
}
