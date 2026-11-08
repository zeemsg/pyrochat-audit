package com.pyrochat.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/BIOMETRIC-UNLOCK.md §7 first bullet: the policy is a pure function — given the
 * last-password-unlock time, the consecutive failure count, the invalidation flag and "now", it
 * returns allow-biometry or require-password. Times are raw seconds.
 */
class BiometricPolicyTest {
    private val week = BiometricPolicy.PASSWORD_EVERY_SECONDS
    private val t0 = 1_700_000_000L

    private fun decide(last: Long? = t0, fails: Int = 0, invalid: Boolean = false, now: Long = t0) =
        BiometricPolicy.decide(last, fails, invalid, now)

    @Test fun freshPasswordUnlockAllowsBiometry() {
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide())
    }

    // ---- the 7 day boundary, both sides ------------------------------------------------------

    @Test fun oneSecondUnderSevenDaysStillAllows() {
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(now = t0 + week - 1))
    }

    @Test fun exactlySevenDaysRequiresThePassword() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(now = t0 + week))
    }

    @Test fun pastSevenDaysRequiresThePassword() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(now = t0 + week + 1))
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(now = t0 + 90 * 24 * 3600))
    }

    @Test fun sevenDaysIsSevenDays() {
        assertEquals(7L * 24 * 60 * 60, BiometricPolicy.PASSWORD_EVERY_SECONDS)
    }

    @Test fun noPasswordUnlockOnRecordRequiresOne() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(last = null))
    }

    @Test fun clockMovedBackFailsClosed() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(now = t0 - 1))
    }

    // ---- the third failure --------------------------------------------------------------------

    @Test fun oneAndTwoFailuresStillAllow() {
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(fails = 0))
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(fails = 1))
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(fails = 2))
    }

    @Test fun theThirdFailureRequiresThePassword() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(fails = 3))
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(fails = 4))
        assertEquals(3, BiometricPolicy.MAX_CONSECUTIVE_FAILURES)
    }

    @Test fun resetOnSuccessAllowsAgain() {
        // the counter is what the store holds; a success writes 0 back and the answer flips
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(fails = 3))
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(fails = 0))
    }

    // ---- invalidation -------------------------------------------------------------------------

    @Test fun invalidationOverridesEverything() {
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(invalid = true))
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(invalid = true, fails = 0, now = t0))
    }

    @Test fun invalidationWinsEvenOverAPerfectlyFreshUnlock() {
        assertEquals(BiometricDecision.ALLOW_BIOMETRY, decide(now = t0 + 60))
        assertEquals(BiometricDecision.REQUIRE_PASSWORD, decide(invalid = true, now = t0 + 60))
    }

    // ---- §2: which of the three Android labels ------------------------------------------------

    @Test fun oneSensorNamesItself() {
        assertEquals(BiometricKind.FINGERPRINT, BiometricKind.of(fingerprint = true, face = false, iris = false))
        assertEquals(BiometricKind.FACE, BiometricKind.of(fingerprint = false, face = true, iris = false))
    }

    @Test fun severalOrUnknownGetTheGenericWord() {
        assertEquals(BiometricKind.GENERIC, BiometricKind.of(fingerprint = true, face = true, iris = false))
        assertEquals(BiometricKind.GENERIC, BiometricKind.of(fingerprint = true, face = false, iris = true))
        assertEquals(BiometricKind.GENERIC, BiometricKind.of(fingerprint = false, face = false, iris = true))
        assertEquals(BiometricKind.GENERIC, BiometricKind.of(fingerprint = false, face = false, iris = false))
    }
}
