package com.pyrochat.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `DeadmanSwitch.shouldWipe` (`DeadmanSwitch.swift:19-22`): wipe iff a deadline exists and is past. */
class DeadmanSwitchTest {
    @Test fun noDeadlineNeverWipes() {
        assertFalse(DeadmanSwitch.shouldWipe(null, 1_000_000))
    }

    @Test fun deadlineInTheFutureKeeps() {
        assertFalse(DeadmanSwitch.shouldWipe(2_000_000, 1_999_999))
    }

    @Test fun deadlineReachedOrPassedWipes() {
        assertTrue(DeadmanSwitch.shouldWipe(2_000_000, 2_000_000))
        assertTrue(DeadmanSwitch.shouldWipe(2_000_000, 9_000_000))
    }

    // ---- docs/DECOY-ACCOUNT.md §4/§5 — a session that has no dead-man must not clear one --------

    @Test fun presenceInAnAccountWithNoDeadManPushesTheDeadlineForwardInsteadOfClearingIt() {
        val day = DeadmanSwitch.DAY_MS
        val now = 1_000_000_000L
        val armedByTheRealAccount = now + 30 * day
        // five days later the DECOY is opened: its own `wipeAfterDays` is null
        val later = now + 5 * day
        val next = DeadmanSwitch.presenceDeadline(armedByTheRealAccount, intervalDays = 30, now = later)
        assertEquals("unlocking a decoy must not switch off the real account's dead-man", later + 30 * day, next)
        assertTrue(next!! > armedByTheRealAccount)
    }

    @Test fun presenceWithNothingArmedChangesNothing() {
        assertNull(DeadmanSwitch.presenceDeadline(null, intervalDays = 30, now = 1_000))
    }

    @Test fun presenceLeavesADeadlineWhoseIntervalIsNotOnRecordExactlyWhereItIs() {
        // armed by a build that did not record the interval: extending it would be a guess, and
        // shortening or clearing it would be the bug — so nothing changes
        assertNull(DeadmanSwitch.presenceDeadline(2_000_000, intervalDays = null, now = 1_000))
        assertNull(DeadmanSwitch.presenceDeadline(2_000_000, intervalDays = 0, now = 1_000))
    }
}
