package com.pyrochat.store.model

import com.pyrochat.store.codec.SwiftDateSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * `Note` (`ios/PyroChat/Core/Models.swift:119-148`). Four required keys; `pinned` is a genuine
 * tri-state on disk (absent / true / false all occur) and absent means not pinned (SPEC.md §4.5).
 */
@Serializable
data class Note(
    val id: String,
    val text: String,
    @Serializable(with = SwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = SwiftDateSerializer::class)
    val modifiedAt: Instant,
    val pinned: Boolean? = null,
) {
    val isPinned: Boolean get() = pinned ?: false
}
