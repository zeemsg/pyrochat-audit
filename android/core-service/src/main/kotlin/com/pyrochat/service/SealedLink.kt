package com.pyrochat.service

import com.pyrochat.crypto.Sealed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** What the status page says about sealed sender (docs/SEALED-SENDER.md §5.8). */
enum class SealedStatus {
    /** Messages go out sealed wherever the other person's app can take them. */
    ON,

    /** The relay did not offer sealed sender, or its certificate did not pass this phone's check. */
    NOT_OFFERED,

    /** It was offered, and the second connection it needs could not be opened. */
    NO_PRIVATE_CONNECTION,
}

/** My sender certificate for this login, and how long it may still be used. */
internal class SealedCertificate(
    val own: Sealed.Own,
    /**
     * Sealing stops here, a little before the certificate ends: the recipient judges it by the
     * relay's clock when the message arrives there. On the service's elapsed-time clock, which
     * the app points at one that keeps counting while the phone sleeps.
     */
    val untilMs: Long,
)

/**
 * One anonymous connection (docs/SEALED-SENDER.md §5.5): a socket that never logs in. Sealed
 * messages and prekey fetches go out on it, so the relay is not told they are mine. Confined to
 * the `ChatService` actor thread, like everything it is used from.
 */
internal class AnonLink(val client: RelayClient) {
    var events: Job? = null
    var ping: Job? = null

    /** deliver id → the message it carries, until the relay's verdict (or 15 s). */
    val attempts = HashMap<String, ChatService.Outbound>()

    /** deliver id → a caller that takes the verdict itself (null = sent, else the error code). */
    val verdictWaiters = HashMap<String, CompletableDeferred<String?>>()

    /** Peers whose prekey bundle was asked for here and has not come back. */
    val fetches = HashSet<String>()

    /** Attachments still writing their chunks here. */
    var uploads = 0

    /** Takes nothing new; closes once what is in flight has its verdict and what was asked has its answer (§5.2). */
    var retiring = false
    var ended = false
}
