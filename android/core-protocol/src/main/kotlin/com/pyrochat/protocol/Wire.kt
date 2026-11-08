package com.pyrochat.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import java.util.Base64

/**
 * Relay wire frames (docs/PROTOCOL.md) and the E2E envelope/payload shapes, typed one class per
 * frame. JSON field names are EXACTLY the wire names (`@SerialName` wherever the Kotlin name
 * differs); the discriminator is the `t` field, like iOS `Wire.Out.t` / `Wire.In.t`.
 *
 * Encoding rules (mirror Swift `JSONEncoder` with `encodeIfPresent` and the Node clients):
 *  - `nil`/null optionals are OMITTED (`explicitNulls = false`);
 *  - every non-null property is always written (`encodeDefaults = true` — with the default
 *    `encodeDefaults = false` a property equal to its default would be dropped, which is exactly
 *    the `Envelope.v` trap; nothing on this wire relies on "omitted means default");
 *  - unknown keys are ignored on decode (`ignoreUnknownKeys = true`, like Swift `Decodable`);
 *  - all base64 is standard alphabet WITH padding (`java.util.Base64` basic codec).
 */
object Wire {
    val json: Json = Json {
        classDiscriminator = "t"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            // A relay frame whose `t` we don't model decodes to In.Unknown instead of throwing,
            // so a future relay frame can never kill the receive loop (iOS drops such frames).
            // (a frame with no `t` at all stays a decode error — the relay never sends one)
            polymorphicDefaultDeserializer(In::class) { type -> type?.let { NamedUnknownInSerializer(it) } }
        }
    }

    /** Compact JSON text for one client→relay frame. */
    fun encode(frame: Out): String = json.encodeToString(Out.serializer(), frame)

    /** Decode one relay→client text frame; throws `SerializationException` on malformed JSON or a
     *  known frame missing required fields (callers drop such frames, like iOS `try?`). */
    fun decodeIn(text: String): In = json.decodeFromString(In.serializer(), text)

    /**
     * One platform inside a `versions` frame: `build` = iOS `CFBundleVersion` / Android
     * `versionCode` (integer ≥ 1), `name` = the marketing version (display only, ≤ 32),
     * `url` = where to get it (optional, `https://` only, ≤ 300). Defaults exist so a missing
     * field decodes and fails validation in the service instead of killing the receive loop.
     */
    @Serializable
    data class VersionEntry(val build: Int = 0, val name: String = "", val url: String? = null)

    /** `{id, pub, sig?}` — iOS `Wire.PreKeyJSON`, relay `UserRecord.PreKey`. `sig` only on the
     *  signed and Kyber prekeys; one-time prekeys carry no signature. */
    @Serializable
    data class PreKeyJson(val id: Int, val pub: String, val sig: String? = null)

    // ------------------------------------------------------------------ client -> relay

    @Serializable
    sealed class Out {
        /** §1 registration. All key material base64 (standard, padded). */
        @Serializable
        @SerialName("register")
        data class Register(
            /** hex sha256(lowercased username), 64 chars — see [UsernameHash]. */
            val user: String,
            /** raw 32-byte RFC 8032 Ed25519 public key (relay checks decoded length == 32). */
            @SerialName("auth_pub") val authPub: String,
            @SerialName("reg_id") val regId: Int,
            /** libsignal `IdentityKey.serialize()` — 33 bytes, `0x05` ‖ X25519 public. */
            @SerialName("identity_key") val identityKey: String,
            @SerialName("signed_prekey") val signedPreKey: PreKeyJson,
            @SerialName("kyber_prekey") val kyberPreKey: PreKeyJson,
            @SerialName("one_time_prekeys") val oneTimePreKeys: List<PreKeyJson>,
            /** Sealed sender (docs/SEALED-SENDER.md §4.1): base64 SHA-256 of my delivery key. Present =
             *  this build can receive sealed messages; the relay never sees the key itself. */
            val delivery: String? = null,
        ) : Out()

        @Serializable
        @SerialName("login")
        data class Login(val user: String) : Out()

        /** §2 challenge proof. `sig` = base64 of the 64-byte pure-Ed25519 signature over the RAW
         *  nonce bytes. `token`/`token_sig` = today's RFC 9474 access token (absent when none);
         *  `push_token`/`platform` optional wake-push registration (iOS always sends `push_token`,
         *  `""` when push is off; the Node clients never do — both are accepted). */
        @Serializable
        @SerialName("auth")
        data class Auth(
            val sig: String,
            val token: String? = null,
            @SerialName("token_sig") val tokenSig: String? = null,
            @SerialName("push_token") val pushToken: String? = null,
            val platform: String? = null,
            /** Sealed sender: sent at EVERY login by a sealed-capable build. A login without it tells
             *  the relay that an old build has taken the account over (§4.1). */
            val delivery: String? = null,
        ) : Out()

        @Serializable
        @SerialName("prekey_fetch")
        data class PreKeyFetch(val user: String) : Out()

        @Serializable
        @SerialName("prekey_upload")
        data class PreKeyUpload(@SerialName("one_time_prekeys") val oneTimePreKeys: List<PreKeyJson>) : Out()

        /** §4. `id` lowercase UUID string; `kind` ∈ msg|blob|control; `body` base64 envelope. */
        @Serializable
        @SerialName("send")
        data class Send(val id: String, val to: String, val ttl: Int, val kind: String, val body: String) : Out()

        @Serializable
        @SerialName("ack")
        data class Ack(val id: String) : Out()

        // sealed sender (docs/SEALED-SENDER.md §4)

        /** First frame of the anonymous connection: it never logs in, and stays anonymous for life. */
        @Serializable
        @SerialName("anon")
        data object Anon : Out()

        /**
         * A sealed message, on the anonymous connection only. `id` is a fresh UUID for every
         * attempt and is never a row id. `access` = the recipient's delivery key (base64, the key
         * lane); absent on the first-contact lane. The relay is not told who sends this.
         */
        @Serializable
        @SerialName("deliver")
        data class Deliver(
            val id: String,
            val to: String,
            val ttl: Int,
            val kind: String,
            val body: String,
            val access: String? = null,
        ) : Out()

        /** Ask for a new sender certificate (logged-in connection). */
        @Serializable
        @SerialName("cert")
        data object Cert : Out()

        @Serializable
        @SerialName("ping")
        data object Ping : Out()

        /** §11. `pushToken == ""` unregisters; `platform` absent ⇒ apns (Android sends "fcm"). */
        @Serializable
        @SerialName("push_token")
        data class PushToken(@SerialName("push_token") val pushToken: String, val platform: String? = null) : Out()

        @Serializable
        @SerialName("deregister")
        data object Deregister : Out()

        @Serializable
        @SerialName("report")
        data class Report(val user: String) : Out()

        // desktop-link pair channel (docs/MIRROR-PROTOCOL.md) — owned by the `mirror` module
        @Serializable
        @SerialName("pair_open")
        data object PairOpen : Out()

        @Serializable
        @SerialName("pair_join")
        data class PairJoin(val pair: String) : Out()

        @Serializable
        @SerialName("pair_msg")
        data class PairMsg(val pair: String, val body: String) : Out()

        @Serializable
        @SerialName("pair_close")
        data object PairClose : Out()
    }

    // ------------------------------------------------------------------ relay -> client

    @Serializable
    sealed class In {
        @Serializable
        @SerialName("registered")
        data object Registered : In()

        /** `nonce` = base64 of 32 random bytes; sign the DECODED bytes. */
        @Serializable
        @SerialName("challenge")
        data class Challenge(val nonce: String) : In()

        /** `sealed`, `cert`, `bind` and `cert_ttl` only when the relay offers sealed sender to this
         *  login (docs/SEALED-SENDER.md §4.1): my sender certificate, the relay's binding for my
         *  full hash, and how many seconds the two last. */
        @Serializable
        @SerialName("ready")
        data class Ready(
            val queued: Int = 0,
            val sealed: Boolean? = null,
            val cert: String? = null,
            val bind: String? = null,
            @SerialName("cert_ttl") val certTtl: Int? = null,
        ) : In()

        /** Reply to `cert`: a renewed certificate and binding. */
        @Serializable
        @SerialName("cert")
        data class Cert(
            val cert: String? = null,
            val bind: String? = null,
            @SerialName("cert_ttl") val certTtl: Int? = null,
        ) : In()

        /** The anonymous connection was accepted. */
        @Serializable
        @SerialName("anon_ok")
        data object AnonOk : In()

        @Serializable
        @SerialName("prekey_count")
        data class PreKeyCount(val n: Int) : In()

        /** `kyber_prekey` is omitted by the relay only for legacy records; iOS refuses such a
         *  bundle ("incomplete bundle"). `one_time_prekey` absent when the pool is empty. */
        @Serializable
        @SerialName("prekey_bundle")
        data class PreKeyBundle(
            val user: String,
            @SerialName("reg_id") val regId: Int,
            @SerialName("identity_key") val identityKey: String,
            @SerialName("signed_prekey") val signedPreKey: PreKeyJson,
            @SerialName("kyber_prekey") val kyberPreKey: PreKeyJson? = null,
            @SerialName("one_time_prekey") val oneTimePreKey: PreKeyJson? = null,
            /** Only on a reply to an anonymous fetch: that account can receive sealed messages. */
            val sealed: Boolean? = null,
        ) : In()

        /** `ts` = relay epoch milliseconds. A sealed message has no `from`: the sender is inside,
         *  and is believed only after the checks of docs/SEALED-SENDER.md §3. */
        @Serializable
        @SerialName("recv")
        data class Recv(val id: String, val from: String? = null, val kind: String, val body: String, val ts: Long? = null) : In()

        @Serializable
        @SerialName("sent")
        data class Sent(val id: String) : In()

        /** `ref` names the send id where applicable; `user` names the hash on `unknown_user`
         *  answers to `prekey_fetch`. */
        @Serializable
        @SerialName("error")
        data class Error(val code: String, val ref: String? = null, val user: String? = null) : In()

        @Serializable
        @SerialName("pong")
        data object Pong : In()

        @Serializable
        @SerialName("reported")
        data object Reported : In()

        @Serializable
        @SerialName("deregistered")
        data object Deregistered : In()

        @Serializable
        @SerialName("pair_opened")
        data class PairOpened(val pair: String, val ttl: Int? = null) : In()

        @Serializable
        @SerialName("pair_joined")
        data class PairJoined(val pair: String) : In()

        @Serializable
        @SerialName("pair_msg")
        data class PairMsg(val pair: String, val body: String) : In()

        @Serializable
        @SerialName("pair_closed")
        data class PairClosed(val pair: String, val reason: String? = null) : In()

        /**
         * Operator notice (PROTOCOL.md §"Operator notices") — the relay's own announcement, sent
         * after `ready` + `prekey_count` at every login and live while connected. `ts`/`expires`
         * are epoch SECONDS; `url` optional and always `https://`. Only `id` is structurally
         * required: the service validates the rest (blank text, non-https url, expired ⇒ dropped).
         */
        @Serializable
        @SerialName("notice")
        data class Notice(
            val id: String,
            val text: String = "",
            val url: String? = null,
            val ts: Long = 0,
            val expires: Long = 0,
        ) : In()

        /** The operator deleted a notice: clients drop it by `id`. */
        @Serializable
        @SerialName("notice_gone")
        data class NoticeGone(val id: String) : In()

        /**
         * Latest versions (PROTOCOL.md §"Latest versions") — the newest build of each app, sent
         * right after the notice replay at every login and live when the operator changes it.
         * Both platforms optional (`{"t":"versions"}` clears a stale banner); a third platform
         * later is an unknown key and ignored. Nothing is structurally required: the service
         * validates every entry ([VersionEntry]) and ignores a malformed frame as a whole.
         */
        @Serializable
        @SerialName("versions")
        data class Versions(val ios: VersionEntry? = null, val android: VersionEntry? = null) : In()

        /** Any frame with an unmodelled `t`. Never sent. */
        @Serializable(with = UnknownInSerializer::class)
        data class Unknown(val type: String) : In()
    }

    /** Error codes the relay emits (PROTOCOL.md §9 + inventory §1.1). */
    object ErrorCode {
        const val BAD_FRAME = "bad_frame"
        const val BAD_SIG = "bad_sig"
        const val BAD_TOKEN = "bad_token"
        const val TOKEN_SPENT = "token_spent"
        const val USERNAME_TAKEN = "username_taken"
        const val UNKNOWN_USER = "unknown_user"
        const val NOT_AUTHED = "not_authed"
        const val RATE_LIMITED = "rate_limited"
        const val TOO_LARGE = "too_large"
        const val QUEUE_FULL = "queue_full"
        const val UNKNOWN_PAIR = "unknown_pair"
        const val PAIR_LIMIT = "pair_limit"
        // sealed sender (docs/SEALED-SENDER.md §4.6)
        /** wrong delivery key */
        const val DENIED = "denied"
        /** the recipient cannot receive sealed messages */
        const val UNSUPPORTED = "unsupported"
        /** the first-contact lane refuses this kind or size */
        const val INTRO_LIMIT = "intro_limit"
        /** the recipient's first-contact allowance is used up */
        const val INTRO_FULL = "intro_full"
    }

    // ------------------------------------------------------------------ E2E envelope

    /**
     * What rides inside the relay `body` field: `base64(JSON {"v":1,"type":<int>,"data":"<base64 ciphertext>"})`.
     * Byte-identical to `bot/index.mjs` (`JSON.stringify({ v: 1, type, data })`): keys in this
     * order, no whitespace. `v` is REQUIRED on decode (Swift's synthesized decoder does not treat
     * the defaulted `v: Int = 1` as optional) — so it has no default here either and is always
     * written. `data` must be standard base64 with padding for iOS.
     */
    @Serializable
    data class Envelope(val v: Int, val type: Int, val data: String) {
        fun ciphertext(): ByteArray = Base64.getDecoder().decode(data)

        /** The relay `body` string. */
        fun toBody(): String =
            Base64.getEncoder().encodeToString(json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

        companion object {
            fun of(type: Int, ciphertext: ByteArray): Envelope =
                Envelope(Protocol.ENVELOPE_VERSION, type, Base64.getEncoder().encodeToString(ciphertext))

            fun fromBody(body: String): Envelope =
                json.decodeFromString(serializer(), String(Base64.getDecoder().decode(body), Charsets.UTF_8))
        }
    }

    /**
     * The plaintext inside the ciphertext (iOS `Wire.Payload`) — compact UTF-8 JSON. All optional
     * except `kind`; unknown `kind`/`control` values are ignored by receivers. M1 stub.
     * Note: Swift prints integral Doubles without a decimal (`"burnAfter":20`); kotlinx prints
     * `20.0` — semantically equal, both decoders accept either.
     */
    @Serializable
    data class Payload(
        val kind: String,                 // "text" | "blob_manifest" | "control"
        val text: String? = null,
        val senderName: String? = null,
        val burnAfter: Double? = null,
        val control: String? = null,
        val via: String? = null,
        val browser: String? = null,
        val linkUp: Boolean? = null,
        val refId: String? = null,
        val receipt: String? = null,
        val refIds: List<String>? = null,
        val subtype: String? = null,
        val chunks: Int? = null,
        val bytes: Int? = null,
        val duration: Double? = null,
        val filename: String? = null,
        val mime: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        // --- group channels (docs/GROUPS.md §2): `group` rides on EVERY group message and control ---
        val group: GroupRef? = null,
        /** `group_create` / `group_invite` / `group_rename`: the group name (stored without `#`). */
        val name: String? = null,
        /** `group_create` / `group_invite`: must equal the sender's hash on `group_create`. */
        val creator: String? = null,
        /** `group_create` / `group_invite`: the full roster. */
        val members: List<GroupMember>? = null,
        /** `group_create` / `group_invite` / `group_policy`: `alert` | `burn` | `burn_remove`. */
        val policy: String? = null,
        /** group default burn timer (seconds), like `Contact.burnSeconds`; distinct from the per-row `burnAfter`. */
        val burnSeconds: Double? = null,
        /** `group_create` / `group_invite`: Swift reference seconds (2001 epoch), like every date both apps write. */
        val createdAt: Double? = null,
        /** `group_add`: the new member; `group_remove`: only `member.hash` matters. */
        val member: GroupMember? = null,
        /**
         * `group_create` / `group_invite` (Android addition, ignored by a receiver that does not
         * know it): the group's former members with their original `joinedAt`, so a newcomer
         * deals colours from the same history as everyone else (GROUPS.md §5).
         */
        val former: List<GroupMember>? = null,
        // --- sealed sender (docs/SEALED-SENDER.md §4.4) ---
        /** My delivery key (base64, 32 bytes): on every payload to a peer until that peer echoes it. */
        @Serializable(with = LenientStringSerializer::class)
        val dk: String? = null,
        /** First 8 bytes of SHA-256 of the delivery key I hold for you (base64): on every payload while I hold one. */
        @Serializable(with = LenientStringSerializer::class)
        val dkh: String? = null,
    ) {
        fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

        companion object {
            fun decode(plaintext: ByteArray): Payload =
                json.decodeFromString(serializer(), String(plaintext, Charsets.UTF_8))
        }
    }
}

/** `group: {id, epoch}` — on every group message and control (GROUPS.md §2). */
@Serializable
data class GroupRef(val id: String, val epoch: Int)

/**
 * A roster entry on the wire (`members[]` of create/invite, `member` of add/remove). `joinedAt`
 * is a Swift `Date` as `JSONEncoder` writes it by default: seconds since 2001-01-01 — the same
 * number the stores persist. `name` and `joinedAt` are optional on decode so a bare
 * `member:{hash}` (`group_remove`) parses.
 */
@Serializable
data class GroupMember(
    val hash: String,
    val name: String? = null,
    val joinedAt: Double? = null,
    /** The member's delivery key, when the writer holds it (docs/SEALED-SENDER.md §4.4): members
     *  who never spoke directly can then reach each other without the relay being told who writes. */
    @Serializable(with = LenientStringSerializer::class)
    val dk: String? = null,
)

/**
 * An optional string that decodes to null when the peer sent anything else. A wrong shape drops
 * the field, never the payload: a payload that fails to decode is taken for ratchet damage and
 * costs the session. iOS decodes the same fields with `try?`.
 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** The `control` words of GROUPS.md §2 (the existing `control` field carries them). */
object GroupControl {
    const val CREATE = "group_create"
    const val INVITE = "group_invite"
    const val ADD = "group_add"
    const val REMOVE = "group_remove"
    const val LEAVE = "group_leave"
    const val RENAME = "group_rename"
    const val POLICY = "group_policy"
    const val BURN = "group_burn"
    /** Roster cap, creator included (§1). */
    const val MAX_MEMBERS = 20
}

/** Serializer behind [Wire.In.Unknown]: swallows the whole object, remembers only its `t`. */
open class UnknownInSerializerBase(private val type: String) : KSerializer<Wire.In.Unknown> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("pyrochat.wire.unknown")

    override fun deserialize(decoder: Decoder): Wire.In.Unknown {
        (decoder as JsonDecoder).decodeJsonElement()
        return Wire.In.Unknown(type)
    }

    override fun serialize(encoder: Encoder, value: Wire.In.Unknown) {
        encoder.encodeStructure(descriptor) {}
    }
}

/** Registered on the sealed hierarchy (never matches a real `t`). */
object UnknownInSerializer : UnknownInSerializerBase("")

/** Returned by the polymorphic default deserializer for an unmodelled `t`. */
class NamedUnknownInSerializer(type: String) : UnknownInSerializerBase(type)
