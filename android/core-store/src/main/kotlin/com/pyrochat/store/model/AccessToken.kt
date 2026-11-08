package com.pyrochat.store.model

import kotlinx.serialization.Serializable

/**
 * `AccessToken` (`ios/PyroChat/Core/Models.swift:110-114`) — all three keys required (SPEC.md §4.4).
 * `token` = epochDay (8 B big-endian) ‖ 32 B nonce; `signature` = 256 B unblinded RSA-PSS.
 */
@Serializable
data class AccessToken(
    /** UTC days since 1970 */
    val epochDay: Long,
    val token: Bytes,
    val signature: Bytes,
)
