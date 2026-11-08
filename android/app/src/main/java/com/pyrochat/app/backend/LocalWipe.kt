package com.pyrochat.app.backend

/**
 * Everything this device destroys when it destroys itself, in one place and in one order.
 *
 * docs/BIOMETRIC-UNLOCK.md §5 is the reason it exists: the hardware-backed copy of the store key
 * must go "unconditionally and before anything else" on the duress PIN, the dead-man wipe,
 * `TOTAL WIPE` / obliterate — and a rolled-back registration, which leaves the same debris. Every
 * one of [Reason] routes through [wipe], so the ordering rule is written down once and the unit
 * suite can drive all of them.
 *
 * Auto-lock is deliberately NOT a reason: unlocking again with biometry after an auto-lock is the
 * whole point of the feature (§5 last line).
 */
class LocalWipe(
    /** §5: the wrapped store key + its Keystore key (`BiometricVault.shred`). */
    private val biometrics: () -> Unit,
    /** `SecureStore.obliterate` — the store file, every sidecar, every secret, the plaintext tmp. */
    private val obliterate: () -> Unit,
    /** The operator notices + remembered version kept in plain preferences. */
    private val notices: () -> Unit,
    /** `DeadmanSwitch.disarm` — the deadline is one datum for forensics. */
    private val deadman: () -> Unit,
) {
    /** Which path asked. Kept only so a wipe is legible in a log or a test. */
    enum class Reason {
        /** The duress PIN was typed at the unlock prompt. */
        DURESS,

        /** The dead-man deadline had passed at launch. */
        DEADMAN,

        /** `[cfg]` → `[ BURN EVERYTHING ]`. */
        TOTAL_WIPE,

        /**
         * docs/SCRUB-AND-WIPE.md §A3 — the tenth consecutive wrong typed password. "Exactly the
         * duress path, reused rather than reimplemented": it is a [Reason] and nothing else, so
         * the ordering and the post-conditions are the duress ones by construction.
         */
        TEN_WRONG_PASSWORDS,

        /** Registration failed after the store was created — roll it back. */
        REGISTRATION_ROLLBACK,
    }

    @Volatile
    var lastReason: Reason? = null
        private set

    /** The order is the contract: the hardware copy of the store key never outlives the store. */
    fun wipe(reason: Reason) {
        lastReason = reason
        biometrics()
        obliterate()
        notices()
        deadman()
    }
}
