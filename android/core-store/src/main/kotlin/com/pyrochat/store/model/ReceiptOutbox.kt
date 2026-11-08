package com.pyrochat.store.model

import kotlinx.serialization.Serializable

/**
 * `ReceiptOutbox` (`ios/PyroChat/Core/Models.swift:154-169`): receipts and burn-start syncs waiting
 * for the relay, `peerHash → [messageId]`. Each lane defaults to empty when absent; all three are
 * written whenever the outbox itself is present (SPEC.md §4.6).
 */
@Serializable
data class ReceiptOutbox(
    val delivered: Map<String, List<String>> = emptyMap(),
    val read: Map<String, List<String>> = emptyMap(),
    val burnStart: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = delivered.isEmpty() && read.isEmpty() && burnStart.isEmpty()
}
