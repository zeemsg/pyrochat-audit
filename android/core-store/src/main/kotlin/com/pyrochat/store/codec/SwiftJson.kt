@file:OptIn(ExperimentalSerializationApi::class)

package com.pyrochat.store.codec

import com.pyrochat.store.model.AppSettings
import com.pyrochat.store.model.Blob
import com.pyrochat.store.model.Bytes
import com.pyrochat.store.model.PeerKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import kotlin.math.abs
import kotlin.math.floor

/**
 * The Swift `JSONEncoder()` / `JSONDecoder()` dialect iOS uses for `pyro.store` and `.pyrobak`
 * (`android/fixtures/ios-store/SPEC.md` §4-5), reproduced with kotlinx-serialization:
 *
 * * `encodeDefaults = true` — Swift's synthesized `Encodable` writes every non-nil stored property,
 *   defaults included (`Contact.verified = false`, all 15 `AppSettings` keys, empty maps/arrays).
 *   The one deliberate exception is `ChatMessage.viaWeb`, which iOS writes as `true` or not at all.
 * * `explicitNulls = false` — nil optionals are omitted; there is no `null` anywhere in an iOS file,
 *   and an absent key decodes to the property default (`decodeIfPresent ?? default`).
 * * `ignoreUnknownKeys = true` — iOS decodes through explicit `CodingKeys` and drops the rest.
 * * `Date` ↔ [Instant] as seconds since 2001-01-01 ([SwiftDateSerializer]); `Data` ↔ [Bytes] as
 *   padded standard base64 ([BytesSerializer]); `[UInt32: Data]` ↔ `Map<Int, Bytes>` as a flat
 *   `[k, v, k, v]` array ([FlatIntBytesMapSerializer]); enums by their raw strings (`@SerialName`).
 * * Numbers are written the way Swift prints them — integral doubles without a fraction
 *   (`780000000`), fractional ones in plain shortest-round-trip decimal (`780000090.5`) — so an
 *   Android encode is JSON-semantically identical to the iOS one ([SwiftNumber]).
 *
 * Not reproduced (iOS accepts both forms): `/` is left unescaped where Swift writes `\/`, and key
 * order is arbitrary on both platforms — never compare encodings bytewise (SPEC.md §4).
 */
object SwiftJson {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** The exact bytes iOS would seal: compact UTF-8 JSON of [blob]. */
    fun encodeBlob(blob: Blob): ByteArray =
        json.encodeToString(Blob.serializer(), blob).toByteArray(Charsets.UTF_8)

    /**
     * Decodes a plaintext store. Throws [SerializationException] for anything iOS would refuse too:
     * a missing required key, an unknown enum raw value, `null` for a required key, an object where a
     * flat array is expected, unpadded or URL-safe base64 (SPEC.md §5).
     */
    fun decodeBlob(bytes: ByteArray): Blob =
        json.decodeFromString(Blob.serializer(), bytes.toString(Charsets.UTF_8))

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)
}

/** Swift `Date` ↔ seconds since 2001-01-01T00:00:00Z (`timeIntervalSinceReferenceDate`). */
object SwiftDate {
    /** Unix time of the Swift reference date. */
    const val REFERENCE_EPOCH_SECONDS = 978_307_200L

    /**
     * Splits the double into whole seconds + nanoseconds. Exact round trip through [toSeconds] for
     * every double of magnitude ≥ 2^23 s (any date after 2001-04-08): the nanosecond rounding error
     * (≤ 0.5 ns) is far below half an ulp of the double at that magnitude, so re-adding lands on
     * the original value. Verified on the fixture's sub-second timestamps in `SwiftJsonTest`.
     */
    fun toInstant(secondsSinceReference: Double): Instant {
        require(secondsSinceReference.isFinite()) { "non-finite date" }
        val whole = floor(secondsSinceReference)
        var secs = whole.toLong()
        var nanos = Math.round((secondsSinceReference - whole) * 1e9)
        if (nanos == 1_000_000_000L) {
            secs += 1
            nanos = 0
        }
        return Instant.ofEpochSecond(secs + REFERENCE_EPOCH_SECONDS, nanos)
    }

    fun toSeconds(instant: Instant): Double =
        (instant.epochSecond - REFERENCE_EPOCH_SECONDS).toDouble() + instant.nano / 1e9
}

/**
 * Swift-style number text: integral values print without a fraction, others as the shortest
 * decimal that round-trips, never in exponent notation. iOS decodes exponent notation as well,
 * so this only keeps Android output identical to what iOS writes.
 */
internal object SwiftNumber {
    fun format(value: Double): String {
        require(value.isFinite()) { "non-finite numbers cannot be persisted (Swift JSONEncoder throws too)" }
        if (value == Math.rint(value) && abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    fun encode(encoder: Encoder, value: Double) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) encoder.encodeDouble(value)
        else jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(format(value)))
    }
}

/** `Date` property serializer — see [SwiftDate]. */
object SwiftDateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.pyrochat.store.SwiftDate", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Instant) =
        SwiftNumber.encode(encoder, SwiftDate.toSeconds(value))

    override fun deserialize(decoder: Decoder): Instant = SwiftDate.toInstant(decoder.decodeDouble())
}

/** `TimeInterval` / `Double` property serializer — Swift number formatting on encode. */
object SwiftDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.pyrochat.store.SwiftDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) = SwiftNumber.encode(encoder, value)
    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
}

/**
 * Standard-alphabet, padded base64 — what `Data.base64EncodedString()` writes and the only form
 * `Data(base64Encoded:)` reads (SPEC.md §4; unpadded and URL-safe input is rejected on decode
 * to match).
 */
object SwiftBase64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        if (text.length % 4 != 0) {
            throw SerializationException("base64 must be padded to a multiple of 4 (iOS rejects unpadded Data)")
        }
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("invalid standard base64: ${e.message}", e)
        }
    }
}

/** `Data` ↔ [Bytes] as a base64 JSON string. */
object BytesSerializer : KSerializer<Bytes> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.pyrochat.store.SwiftData", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Bytes) = encoder.encodeString(SwiftBase64.encode(value.bytes))
    override fun deserialize(decoder: Decoder): Bytes = Bytes(SwiftBase64.decode(decoder.decodeString()))
}

/**
 * Swift `[UInt32: Data]` — encoded by `JSONEncoder` as a FLAT array `[id, "b64", id, "b64", …]`
 * because the key type is neither `String` nor `Int` (SPEC.md §4). An object form `{"1": "…"}`
 * fails to decode on iOS, so it fails here too. Keys are accepted as `1` or `1.0` (SPEC.md §5);
 * entries are written in ascending id order (iOS order is arbitrary).
 */
object FlatIntBytesMapSerializer : KSerializer<Map<Int, Bytes>> {
    private val delegate = ListSerializer(JsonElement.serializer())

    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.pyrochat.store.SwiftFlatMap", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Map<Int, Bytes>) {
        val flat = ArrayList<JsonElement>(value.size * 2)
        for ((id, bytes) in value.entries.sortedBy { it.key }) {
            flat += JsonPrimitive(id)
            flat += JsonPrimitive(SwiftBase64.encode(bytes.bytes))
        }
        encoder.encodeSerializableValue(delegate, flat)
    }

    override fun deserialize(decoder: Decoder): Map<Int, Bytes> {
        val flat = decoder.decodeSerializableValue(delegate)
        if (flat.size % 2 != 0) {
            throw SerializationException("flat dictionary must alternate key, value: got ${flat.size} elements")
        }
        val out = LinkedHashMap<Int, Bytes>(flat.size / 2)
        for (i in flat.indices step 2) {
            val value = flat[i + 1] as? JsonPrimitive
            if (value == null || !value.isString) {
                throw SerializationException("flat dictionary value must be a base64 string, got ${flat[i + 1]}")
            }
            out[parseKey(flat[i])] = Bytes(SwiftBase64.decode(value.content))
        }
        return out
    }

    private fun parseKey(element: JsonElement): Int {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            throw SerializationException("flat dictionary key must be a number, got $element")
        }
        val number = primitive.content.toDoubleOrNull()
            ?: throw SerializationException("flat dictionary key is not a number: ${primitive.content}")
        if (number != Math.rint(number) || number < 0 || number > Int.MAX_VALUE) {
            throw SerializationException("flat dictionary key $number is not a UInt32 id")
        }
        return number.toInt()
    }
}

/**
 * `AppSettings.init(from:)` (`Models.swift:257-262`): a stored relay/billing URL that is one of the
 * pre-domain addresses is replaced by the default while decoding; anything else the user typed
 * stays. Applied on the `Blob.settings` property, i.e. to every store and backup decode.
 */
object LegacyUrlMigratingSettingsSerializer :
    JsonTransformingSerializer<AppSettings>(AppSettings.serializer()) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val migrated = LinkedHashMap(element)
        var changed = false
        if (isLegacy(element["relayURL"])) {
            migrated["relayURL"] = JsonPrimitive(AppSettings.DEFAULT_RELAY_URL)
            changed = true
        }
        if (isLegacy(element["billingURL"])) {
            migrated["billingURL"] = JsonPrimitive(AppSettings.DEFAULT_BILLING_URL)
            changed = true
        }
        return if (changed) JsonObject(migrated) else element
    }

    private fun isLegacy(element: JsonElement?): Boolean {
        val primitive = element as? JsonPrimitive ?: return false
        return primitive.isString && AppSettings.isLegacyAddress(primitive.content)
    }
}

/**
 * A nullable value that decodes to null when what is on disk does not fit, instead of failing the
 * whole store. Used for the two sealed-sender keys (docs/SEALED-SENDER.md §5.1), exactly as iOS
 * decodes them with `try?`: a lost delivery key costs one message on the old path, a store that
 * will not open costs everything.
 */
open class LenientNullableSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.nullable.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(inner, element) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
    }
}

/** `Blob.deliveryKey`: 32 bytes or nothing. */
object LenientDeliveryKeySerializer : LenientNullableSerializer<Bytes>(BytesSerializer) {
    override fun deserialize(decoder: Decoder): Bytes? = super.deserialize(decoder)?.takeIf { it.size == 32 }
}

/** `Blob.peerKeys`. */
object LenientPeerKeysSerializer :
    LenientNullableSerializer<Map<String, PeerKey>>(MapSerializer(String.serializer(), PeerKey.serializer()))
