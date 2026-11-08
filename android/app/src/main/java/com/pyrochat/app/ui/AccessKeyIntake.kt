package com.pyrochat.app.ui

import com.pyrochat.app.ui.qr.AccessKeyPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An access key handed to the app from outside the ACCESS KEY dialog (docs/FIRST-RUN.md §3). */
sealed interface IncomingKey {
    /** A well-formed `PYRO-XXXX-XXXX-XXXX-XXXX`, already normalised. Redacted in [toString]: the code is never logged. */
    data class Key(val code: String) : IncomingKey {
        override fun toString(): String = "Key(\u2026)"
    }

    /** One of our key prefixes with a code that fails the shape check — refused with `! not an access key`. */
    data object Refused : IncomingKey
}

/** What to do with an incoming key at the current [Stage] — the pure half of the pending-key machine. */
sealed interface KeyStep {
    /** Booting, or locked with an identity on the device: keep it in memory, apply right after unlock. */
    data object Hold : KeyStep

    /** Unlocked: open the ACCESS KEY dialog with it (or show the refusal there). */
    data class Apply(val key: IncomingKey) : KeyStep

    /** Forget it; show [text] where the user is. */
    data class Drop(val text: String) : KeyStep
}

/**
 * docs/FIRST-RUN.md §3: "if the app is locked or on the auth screen, keep the code in memory only
 * … and apply it right after unlock; if the user has no identity yet, drop it after showing
 * `! create your identity first, then scan the key again`". A refused code never waits for anything.
 */
object PendingKeyRules {
    const val CREATE_FIRST = "! create your identity first, then scan the key again"
    const val NOT_A_KEY = AccessKeyPayload.STATUS_NOT_A_KEY

    fun step(stage: Stage, key: IncomingKey): KeyStep = when (stage) {
        Stage.Boot -> KeyStep.Hold
        // docs/DECOY-ACCOUNT.md §5: locked, the app cannot tell whether an identity exists, so it
        // HOLDS. The key waits in RAM for the next unlock — or for the identity the user is about
        // to create, which is the same outcome the old "create one first" message asked for.
        Stage.Auth -> if (key is IncomingKey.Refused) KeyStep.Drop(NOT_A_KEY) else KeyStep.Hold
        Stage.Main -> KeyStep.Apply(key)
    }
}

/**
 * The in-memory holder for a key that arrived by deep link (`pyrochat://redeem/…`, MainActivity
 * `onCreate` / `onNewIntent`). RAM only — never on disk, never logged. One per process; the shell
 * ([PyroApp]) resolves it against the stage and opens the dialog once the app is unlocked.
 */
class AccessKeyIntake {
    private val _pending = MutableStateFlow<IncomingKey?>(null)
    /** The key waiting for [resolve]; null once applied or dropped. */
    val pending: StateFlow<IncomingKey?> = _pending.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** The refusal to show on the auth screen (`! not an access key` / `! create your identity first…`). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Either form (`pyro-key:v1:<CODE>` / `pyrochat://redeem/<CODE>`); anything else is [IncomingKey.Refused]. */
    fun offer(raw: String) {
        _notice.value = null
        _pending.value = when (val p = AccessKeyPayload.parse(raw)) {
            is AccessKeyPayload.Parsed.Key -> IncomingKey.Key(p.code)
            AccessKeyPayload.Parsed.NotAKey, AccessKeyPayload.Parsed.Foreign -> IncomingKey.Refused
        }
    }

    /** Run [PendingKeyRules] at [stage]: the key to open the dialog with (forgotten here), or null. */
    fun resolve(stage: Stage): IncomingKey? {
        val key = _pending.value ?: return null
        return when (val step = PendingKeyRules.step(stage, key)) {
            KeyStep.Hold -> null
            is KeyStep.Apply -> { _pending.value = null; step.key }
            is KeyStep.Drop -> { _pending.value = null; _notice.value = step.text; null }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
