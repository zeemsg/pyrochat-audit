package com.pyrochat.store.model

import com.pyrochat.store.codec.SwiftDateSerializer
import com.pyrochat.store.codec.SwiftDoubleSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * `Contact` (`ios/PyroChat/Core/Models.swift:10-30`). `hash`, `name`, `addedAt` are required on
 * decode; `verified` defaults to false and is always written; `burnSeconds` is absent for
 * "use the global default", `0` for explicitly off (SPEC.md §4.2).
 */
@Serializable
data class Contact(
    /** sha256(username) — the routing address */
    val hash: String,
    /** display name; auto-created placeholders are `"anon-" + hash.take(8)` (`ChatService.swift:545`) */
    val name: String,
    @Serializable(with = SwiftDateSerializer::class)
    val addedAt: Instant,
    val verified: Boolean = false,
    /** per-conversation self-destruct default in seconds (Swift `TimeInterval?`) */
    @Serializable(with = SwiftDoubleSerializer::class)
    val burnSeconds: Double? = null,
) {
    val id: String get() = hash
}
