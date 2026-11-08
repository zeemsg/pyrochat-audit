package com.pyrochat.app.platform

/**
 * docs/BIOMETRIC-UNLOCK.md §2 — which of the three Android labels the `[cfg]` row and the unlock
 * button wear. The words are in `strings.xml`; this only picks between them.
 */
enum class BiometricKind {
    /** `unlock with fingerprint` */
    FINGERPRINT,

    /** `unlock with face` */
    FACE,

    /** `unlock with biometrics` — several sensors, or the device will not say which. */
    GENERIC;

    companion object {
        /** One sensor names itself; several (or iris, or none declared) get the generic word. */
        fun of(fingerprint: Boolean, face: Boolean, iris: Boolean): BiometricKind = when {
            fingerprint && !face && !iris -> FINGERPRINT
            face && !fingerprint && !iris -> FACE
            else -> GENERIC
        }
    }
}

/** docs/BIOMETRIC-UNLOCK.md §4 — the two answers the unlock screen can get. */
enum class BiometricDecision { ALLOW_BIOMETRY, REQUIRE_PASSWORD }

/**
 * docs/BIOMETRIC-UNLOCK.md §4 — when the typed password is required again. A pure function of the
 * four inputs, deliberately: everything it reads ([BiometricStore]) is outside the encrypted store,
 * so the unlock screen can ask before anything is decrypted.
 *
 * Times are raw seconds, the unit the `biometric-last-password` preference is written in.
 */
object BiometricPolicy {
    /** "Every 7 days." */
    const val PASSWORD_EVERY_SECONDS = 7L * 24 * 60 * 60

    /** "After three consecutive biometric failures (not cancellations)." */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /**
     * [lastPasswordUnlock] is the last successful PASSWORD unlock (null = none on record),
     * [consecutiveFailures] the count that resets on any successful unlock of either kind, and
     * [invalidated] whether the OS has dropped the stored key (enrolment changed).
     *
     * A clock that moved backwards fails closed — the same rule `AutoLock.shouldLock` uses.
     */
    fun decide(
        lastPasswordUnlock: Long?,
        consecutiveFailures: Int,
        invalidated: Boolean,
        now: Long,
    ): BiometricDecision {
        if (invalidated) return BiometricDecision.REQUIRE_PASSWORD
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return BiometricDecision.REQUIRE_PASSWORD
        if (lastPasswordUnlock == null) return BiometricDecision.REQUIRE_PASSWORD
        if (now < lastPasswordUnlock) return BiometricDecision.REQUIRE_PASSWORD
        if (now - lastPasswordUnlock >= PASSWORD_EVERY_SECONDS) return BiometricDecision.REQUIRE_PASSWORD
        return BiometricDecision.ALLOW_BIOMETRY
    }
}
