package com.pyrochat.store.model

import com.pyrochat.store.codec.SwiftDateSerializer
import com.pyrochat.store.codec.SwiftDoubleSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A group channel (docs/GROUPS.md §3) — a shared piece of state inside every member's encrypted
 * store; the relay never sees it. Keys are EXACTLY the spec's (`Group { id, name, creator,
 * createdAt, epoch, policy, burnSeconds?, members, left? }`) plus the optional `former` list both
 * apps agreed on (removed/left members with their ORIGINAL `joinedAt`, so colour indices never
 * shift and a re-added member keeps the earliest join). Dates as Swift reference seconds, nils
 * omitted, unknown keys ignored — the same codec rules as every other persisted model.
 *
 * `name` is stored WITHOUT the leading `#` (shown as `#name`); `policy` is the wire word
 * (`alert` / `burn` / `burn_remove`, see [GroupPolicy]); `burnSeconds` has per-contact
 * semantics (absent = global default, `0` = explicitly off); `left` is set on the phone that
 * was removed or left (read-only thread), absent otherwise.
 */
@Serializable
data class Group(
    /** lowercase UUID string — also the thread key in `Blob.messages` */
    val id: String,
    val name: String,
    /** hash of the creator, fixed for the group's life (even after the creator leaves) */
    val creator: String,
    @Serializable(with = SwiftDateSerializer::class)
    val createdAt: Instant,
    /** bumped on every roster change; carried on every group payload */
    val epoch: Int,
    val policy: String,
    @Serializable(with = SwiftDoubleSerializer::class)
    val burnSeconds: Double? = null,
    val members: List<Member>,
    val left: Boolean? = null,
    /** members who left or were removed, keeping their original `joinedAt` (§5 colour history); absent when none */
    val former: List<Member>? = null,
) {
    val isLeft: Boolean get() = left == true

    /** Unknown policy words read as `alert` (the least destructive), as agreed with iOS. */
    val policyKind: GroupPolicy get() = GroupPolicy.fromWire(policy) ?: GroupPolicy.ALERT
    fun member(hash: String): Member? = members.firstOrNull { it.hash == hash }
    fun isMember(hash: String): Boolean = members.any { it.hash == hash }
    fun formerMember(hash: String): Member? = former?.firstOrNull { it.hash == hash }

    /**
     * §5 colour roster: current AND former members, one entry per hash keeping the EARLIEST join,
     * so a member's index never shifts when someone leaves and a re-added member keeps their slot.
     */
    val colourRoster: List<Member>
        get() = (members + (former ?: emptyList())).groupBy { it.hash }.map { (_, v) -> v.minBy { it.joinedAt } }
}

/**
 * `Member { hash, name, joinedAt }` — one roster entry; `name` is the adder's display name for
 * the member. `gone` (optional, agreed with iOS) records that the relay answered `unknown_user`
 * — the member deleted their account: skipped by every fan-out, kept in the roster for colours.
 */
@Serializable
data class Member(
    val hash: String,
    val name: String,
    @Serializable(with = SwiftDateSerializer::class)
    val joinedAt: Instant,
    val gone: Boolean? = null,
) {
    val isGone: Boolean get() = gone == true
}

/** The screenshot policy words (GROUPS.md §1/§6); [wire] is what the store and the payloads carry. */
enum class GroupPolicy(val wire: String, val label: String) {
    ALERT("alert", "alert"),
    BURN("burn", "burn for the screenshotter"),
    BURN_REMOVE("burn_remove", "burn and remove");

    companion object {
        /** Default for NEW groups (§1). */
        val DEFAULT = BURN_REMOVE
        fun fromWire(raw: String?): GroupPolicy? = entries.firstOrNull { it.wire == raw }
        fun fromLabel(label: String): GroupPolicy? = entries.firstOrNull { it.label == label }
    }
}
