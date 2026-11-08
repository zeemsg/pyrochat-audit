package com.pyrochat.app.platform

import com.pyrochat.app.platform.UnlockFailurePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/SCRUB-AND-WIPE.md §A4 first bullet — "Pure policy function from (failures, now) to
 * allow / warn(n) / wipe, covering 0, 6, 7, 8, 9, 10 and above", plus §A4 last bullet: "The
 * warning appears at seven and not at six."
 */
class UnlockFailurePolicyTest {
    private val now = 1_700_000_000_000L

    private fun at(n: Int) = UnlockFailurePolicy.decide(n, now)

    @Test fun nothingIsSaidBeforeTheSeventh() {
        for (n in 0..6) assertEquals("failures=$n", Decision.Allow, at(n))
    }

    @Test fun theWarningAppearsAtSevenAndNotAtSix() {
        assertEquals(Decision.Allow, at(6))
        assertEquals(Decision.Warn(3), at(7))
    }

    @Test fun itCountsDownThreeTwoOne() {
        assertEquals(Decision.Warn(3), at(7))
        assertEquals(Decision.Warn(2), at(8))
        assertEquals(Decision.Warn(1), at(9))
    }

    @Test fun theTenthWipes() {
        assertEquals(Decision.Wipe, at(10))
    }

    @Test fun anythingAboveTenWipesToo() {
        // a count that somehow ran past ten (a wipe interrupted mid-flight) must still wipe
        for (n in 11..40) assertEquals("failures=$n", Decision.Wipe, at(n))
        assertEquals(Decision.Wipe, at(Int.MAX_VALUE))
    }

    @Test fun aDamagedCountNeverDestroysAStoreNobodyGuessedAt() {
        assertEquals(Decision.Allow, at(-1))
        assertEquals(Decision.Allow, at(Int.MIN_VALUE))
    }

    @Test fun theClockChangesNothing() {
        // §A has no cool-down and no decay: ten guesses is ten guesses, whenever they happened
        for (n in listOf(0, 6, 7, 9, 10, 11)) {
            val early = UnlockFailurePolicy.decide(n, 0L)
            val late = UnlockFailurePolicy.decide(n, Long.MAX_VALUE)
            assertEquals("failures=$n", early, late)
            assertEquals("failures=$n", early, UnlockFailurePolicy.decide(n, -1L))
        }
    }

    @Test fun theWarnedRangeIsExactlyThreeStates() {
        val warned = (0..30).filter { at(it) is Decision.Warn }
        assertEquals(listOf(7, 8, 9), warned)
        assertTrue(warned.map { (at(it) as Decision.Warn).attemptsLeft } == listOf(3, 2, 1))
    }

    @Test fun theConstantsAreTheSpecNumbers() {
        assertEquals(10, UnlockFailurePolicy.MAX_FAILURES)
        assertEquals(7, UnlockFailurePolicy.WARN_FROM_FAILURES)
    }
}
