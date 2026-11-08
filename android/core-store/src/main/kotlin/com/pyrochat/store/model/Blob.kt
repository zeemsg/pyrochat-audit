package com.pyrochat.store.model

import com.pyrochat.store.codec.FlatIntBytesMapSerializer
import com.pyrochat.store.codec.LegacyUrlMigratingSettingsSerializer
import com.pyrochat.store.codec.LenientDeliveryKeySerializer
import com.pyrochat.store.codec.LenientPeerKeysSerializer
import kotlinx.serialization.Serializable

/**
 * The persisted root — `SecureStore.Blob` (`ios/PyroChat/Core/SecureStore.swift:17-41`, coding keys
 * 252-257, decoder 258-280). Every key is optional on decode with exactly the iOS default
 * (SPEC.md §4.1); nothing outside this list may ever be persisted, because iOS drops unknown keys
 * on its next save (SPEC.md §5).
 *
 * Numeric widths: iOS stores `registrationId`/`nextPreKeyId` and the prekey ids as `UInt32`; they
 * are `Int` here because libsignal-java takes `int` ids and every real value is tiny
 * (registration ids are 1…16380, prekey ids < 2^24).
 *
 * Key material is kept as opaque [Bytes] keyed exactly as iOS keys it — `sessions` /
 * `knownIdentities` by peer hash (`ProtocolAddress.name`), the three prekey maps by id
 * (`SignalStore.swift:34-129`); `identityKeyPair` is `IdentityKeyPair.serialize()` (69 B),
 * `authPrivateKey` the 32-byte Ed25519 seed (SPEC.md §9).
 */
@Serializable
data class Blob(
    val usernameDisplay: String = "",
    /** hex sha256 of the ICU-lowercased username; `""` before registration. */
    val usernameHash: String = "",
    val identityKeyPair: Bytes = Bytes.EMPTY,
    val registrationId: Int = 0,
    val authPrivateKey: Bytes = Bytes.EMPTY,
    /** peerHash → `SessionRecord.serialize()` */
    val sessions: Map<String, Bytes> = emptyMap(),
    /** id → `PreKeyRecord.serialize()`; a FLAT `[id, b64, id, b64, …]` array on the wire (SPEC.md §4). */
    @Serializable(with = FlatIntBytesMapSerializer::class)
    val preKeys: Map<Int, Bytes> = emptyMap(),
    @Serializable(with = FlatIntBytesMapSerializer::class)
    val signedPreKeys: Map<Int, Bytes> = emptyMap(),
    @Serializable(with = FlatIntBytesMapSerializer::class)
    val kyberPreKeys: Map<Int, Bytes> = emptyMap(),
    /** peerHash → `IdentityKey.serialize()` (33 B, `0x05‖pub`) */
    val knownIdentities: Map<String, Bytes> = emptyMap(),
    val nextPreKeyId: Int = 1,
    val contacts: List<Contact> = emptyList(),
    /** threadHash → rows; an entry may be `[]` (a stranger's `burn_thread` with no contact). */
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val accessTokens: List<AccessToken> = emptyList(),
    /** Legacy relay/billing URLs are rewritten to the defaults while decoding (`Models.swift:257-262`). */
    @Serializable(with = LegacyUrlMigratingSettingsSerializer::class)
    val settings: AppSettings = AppSettings(),
    // --- v0.2 optionals: absent (not `null`) on the wire when nil ---
    val receiptOutbox: ReceiptOutbox? = null,
    val notes: List<Note>? = null,
    val blockedHashes: List<String>? = null,
    /** threadHash → unsent composer text; iOS drops the whole key once the map empties. */
    val drafts: Map<String, String>? = null,
    /** Group channels (docs/GROUPS.md §3) — absent on stores that predate groups; omitted again once empty. */
    val groups: List<Group>? = null,
    // --- sealed sender (docs/SEALED-SENDER.md §5.1): absent on stores that predate it ---
    /**
     * 32 random bytes, created the first time this build offers sealed sender. The relay holds only
     * its hash; the key goes to peers inside the encryption, and whoever holds it can write to this
     * account without the relay being told who they are. Anything that is not 32 bytes reads as absent.
     */
    @Serializable(with = LenientDeliveryKeySerializer::class)
    val deliveryKey: Bytes? = null,
    /**
     * peer hash → what is known about that peer's delivery key. A build from before sealed sender
     * drops both keys on its next save; the rules of §5.1 put them back without anyone doing
     * anything. Decoded leniently, like iOS: damage here must never make the store unopenable.
     */
    @Serializable(with = LenientPeerKeysSerializer::class)
    val peerKeys: Map<String, PeerKey>? = null,
    // --- docs/DECOY-ACCOUNT.md §1, "Allocation, and the one thing a decoy cannot do" ---
    /**
     * The container slot indices THIS account has allocated: its own, plus the slot of any decoy it
     * created. Safe here because anyone who can read it already holds this account, and it is what
     * makes allocation possible at all — a free slot cannot otherwise be told from an occupied one.
     *
     * Never anywhere else. Not the header, not the keystore, not a sidecar: a record of occupancy
     * outside the encrypted plaintext is a count an examiner can read without a password.
     */
    val allocatedSlots: List<Int>? = null,
    /**
     * The slot indices whose state this account KNOWS. The account that authored the container
     * knows every index, because it wrote the other slots' noise itself; an account created INTO an
     * existing container knows only its own, because the rest are bytes it cannot tell from noise.
     *
     * `knownSlots - allocatedSlots` is therefore the set a new account may safely be put in, and
     * §1's "the first account can create one decoy, a decoy cannot create another" is that
     * subtraction rather than a rule written anywhere. Nothing in the interface reads this: a decoy
     * asked to make a decoy simply finds no free slot and fails in the app's ordinary words.
     */
    val knownSlots: List<Int>? = null,
)
