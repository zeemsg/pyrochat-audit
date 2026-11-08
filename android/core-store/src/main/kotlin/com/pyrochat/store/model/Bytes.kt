package com.pyrochat.store.model

import com.pyrochat.store.codec.BytesSerializer
import kotlinx.serialization.Serializable

/**
 * Swift `Data` as iOS persists it: a JSON string of standard base64 (`+` `/` alphabet, `=` padding —
 * `android/fixtures/ios-store/SPEC.md` §4). Wraps a [ByteArray] with CONTENT equality so every data
 * class holding one (`Blob`, `ChatMessage.voiceData`, `AccessToken`, …) compares and hashes by value.
 *
 * The array is neither copied on construction nor on access — treat it as immutable.
 */
@Serializable(with = BytesSerializer::class)
class Bytes(val bytes: ByteArray) {
    val size: Int get() = bytes.size
    fun isEmpty(): Boolean = bytes.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Bytes && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "Bytes(${bytes.size} B)"

    companion object {
        val EMPTY = Bytes(ByteArray(0))
    }
}
