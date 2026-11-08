package com.pyrochat.app.platform

/**
 * docs/SCRUB-AND-WIPE.md §A — what the unlock screen must do after N consecutive wrong TYPED
 * passwords. A pure function of (failures, now), deliberately: the counter it reads lives OUTSIDE
 * the encrypted store (§A1), so the screen can decide before anything has been decrypted, and the
 * whole rule is one testable expression on both platforms.
 *
 * `now` is part of the signature because the spec names it. Nothing in §A is time-dependent —
 * ten guesses is ten guesses, with no cool-down and no decay — so it is accepted and ignored; a
 * future rule that does look at the clock has its seat already taken.
 */
object UnlockFailurePolicy {
    /** §A: "Ten consecutive wrong passwords destroy the store." */
    const val MAX_FAILURES = 10

    /** §A2: "From the seventh failure" — so the first warned-about state has 3 attempts left. */
    const val WARN_FROM_FAILURES = 7

    /** What the unlock screen does with the count it just read. */
    sealed interface Decision {
        /** Nothing is shown; the password field is exactly as it always was. */
        data object Allow : Decision

        /** §A2: `N attempts left before this device wipes`, above the field, in the alert colour. */
        data class Warn(val attemptsLeft: Int) : Decision

        /** §A3: the duress path, reused. No message, no explanation, no trace. */
        data object Wipe : Decision
    }

    /**
     * [failures] is the number of consecutive wrong typed passwords ALREADY recorded — so the
     * screen calls this after incrementing, and the boot path calls it with the stored count.
     *
     * A negative count (a corrupted preference) is read as zero rather than as a wipe: a damaged
     * file must never be able to destroy a store that nobody guessed at.
     */
    fun decide(failures: Int, now: Long): Decision {
        val n = failures.coerceAtLeast(0)
        if (n >= MAX_FAILURES) return Decision.Wipe
        if (n >= WARN_FROM_FAILURES) return Decision.Warn(MAX_FAILURES - n)
        return Decision.Allow
    }
}
