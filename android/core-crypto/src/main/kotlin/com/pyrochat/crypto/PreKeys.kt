package com.pyrochat.crypto

import com.pyrochat.protocol.Protocol
import com.pyrochat.protocol.Wire
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64

/**
 * Prekey material and the relay payloads built from it — iOS `CryptoEngine.generateIdentity`
 * (signed + Kyber + 32 one-time), `generateOneTimePreKeys` (top-up), `processBundle`
 * (bundle → libsignal `PreKeyBundle`), and `bot/index.mjs buildRegistration / makePreKeys`.
 */
object PreKeys {
    /** The public half of a signup — iOS `CryptoEngine.RegistrationBundle`. */
    class Registration(
        val regId: Int,
        /** 33-byte `IdentityKey.serialize()`. */
        val identityKey: ByteArray,
        val signedPreKey: Wire.PreKeyJson,
        val kyberPreKey: Wire.PreKeyJson,
        val oneTimePreKeys: List<Wire.PreKeyJson>,
        /** raw 32-byte Ed25519 public key. */
        val authPub: ByteArray,
    ) {
        /** The exact `register` frame iOS `ChatService.register` sends. */
        fun toRegisterFrame(userHash: String): Wire.Out.Register = Wire.Out.Register(
            user = userHash,
            authPub = b64(authPub),
            regId = regId,
            identityKey = b64(identityKey),
            signedPreKey = signedPreKey,
            kyberPreKey = kyberPreKey,
            oneTimePreKeys = oneTimePreKeys,
        )
    }

    /** iOS: `PrivateKey.generate()`, `sig = identity.privateKey.generateSignature(spkPub.serialize())`
     *  (XEdDSA over the 33-byte serialized public key — the right signature HERE, unlike relay auth). */
    fun generateSignedPreKey(identity: IdentityKeyPair, timestampMs: Long, id: Int = Protocol.SIGNED_PRE_KEY_ID): SignedPreKeyRecord {
        val pair = ECKeyPair.generate()
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return SignedPreKeyRecord(id, timestampMs, pair, signature)
    }

    /** iOS: `KEMKeyPair.generate()` = Kyber-1024, signed like the signed prekey; last-resort, never consumed. */
    fun generateKyberPreKey(identity: IdentityKeyPair, timestampMs: Long, id: Int = Protocol.KYBER_PRE_KEY_ID): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return KyberPreKeyRecord(id, timestampMs, pair, signature)
    }

    /**
     * iOS `generateOneTimePreKeys(count)`: ids come from `state.nextPreKeyId` (advanced per key),
     * records go to the prekey store, and `{id, pub}` (no `sig`) is what the relay gets.
     */
    fun generateOneTimePreKeys(state: SignalState, store: PreKeyStore, count: Int = Protocol.ONE_TIME_PRE_KEY_BATCH): List<Wire.PreKeyJson> {
        val out = ArrayList<Wire.PreKeyJson>(count)
        repeat(count) {
            val id = state.nextPreKeyId
            state.nextPreKeyId = id + 1
            val record = PreKeyRecord(id, ECKeyPair.generate())
            store.storePreKey(id, record)
            out += json(record)
        }
        return out
    }

    /** `prekey_upload` top-up frame (iOS `topUpPreKeys`, bot `prekey_count` handler). */
    fun uploadFrame(keys: List<Wire.PreKeyJson>): Wire.Out.PreKeyUpload = Wire.Out.PreKeyUpload(keys)

    fun json(record: SignedPreKeyRecord): Wire.PreKeyJson =
        Wire.PreKeyJson(record.id, b64(record.keyPair.publicKey.serialize()), b64(record.signature))

    fun json(record: KyberPreKeyRecord): Wire.PreKeyJson =
        Wire.PreKeyJson(record.id, b64(record.keyPair.publicKey.serialize()), b64(record.signature))

    fun json(record: PreKeyRecord): Wire.PreKeyJson =
        Wire.PreKeyJson(record.id, b64(record.keyPair.publicKey.serialize()))

    /** iOS `SignalError.invalidArgument("incomplete bundle")`. */
    class IncompleteBundle(message: String) : IllegalArgumentException(message)

    /**
     * iOS `processBundle` guard + `PreKeyBundle(...)` construction: `reg_id`, `identity_key`,
     * `signed_prekey{id,pub,sig}` and `kyber_prekey{id,pub,sig}` are mandatory and must decode;
     * the one-time prekey is optional (`NULL_PRE_KEY_ID` + null key when the relay omits it).
     */
    fun parseBundle(b: Wire.In.PreKeyBundle): PreKeyBundle {
        val kyber = b.kyberPreKey ?: throw IncompleteBundle("incomplete bundle: no kyber_prekey")
        val kyberSig = kyber.sig ?: throw IncompleteBundle("incomplete bundle: no kyber_prekey.sig")
        val spkSig = b.signedPreKey.sig ?: throw IncompleteBundle("incomplete bundle: no signed_prekey.sig")
        try {
            val otk = b.oneTimePreKey
            return PreKeyBundle(
                b.regId,
                Protocol.DEVICE_ID,
                otk?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
                otk?.let { ECPublicKey(unb64(it.pub)) },
                b.signedPreKey.id,
                ECPublicKey(unb64(b.signedPreKey.pub)),
                unb64(spkSig),
                IdentityKey(unb64(b.identityKey)),
                kyber.id,
                KEMPublicKey(unb64(kyber.pub)),
                unb64(kyberSig),
            )
        } catch (e: IllegalArgumentException) {
            throw IncompleteBundle("incomplete bundle: ${e.message}")
        }
    }

    /** What the relay would serve for a `register` frame (`RelayHandler.onPreKeyFetch`) — for tests and local loops. */
    fun bundleFromRegister(r: Wire.Out.Register, oneTimePreKey: Wire.PreKeyJson? = r.oneTimePreKeys.firstOrNull()): Wire.In.PreKeyBundle =
        Wire.In.PreKeyBundle(
            user = r.user,
            regId = r.regId,
            identityKey = r.identityKey,
            signedPreKey = r.signedPreKey,
            kyberPreKey = r.kyberPreKey,
            oneTimePreKey = oneTimePreKey,
        )

    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    internal fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
