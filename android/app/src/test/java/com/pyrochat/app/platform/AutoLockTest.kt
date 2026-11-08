package com.pyrochat.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AutoLock.shouldLock` (`PyroChatApp.swift:73-79`) + `autoLockAfterMinutes` (`Models.swift:227-233`). */
class AutoLockTest {
    @Test fun settingDecode() {
        assertEquals(5, AutoLock.timeoutMinutes(null))     // secure default
        assertNull(AutoLock.timeoutMinutes(0))             // explicitly off
        assertEquals(1, AutoLock.timeoutMinutes(1))
        assertEquals(15, AutoLock.timeoutMinutes(15))
    }

    @Test fun noStampOrNoTimeoutNeverLocks() {
        assertFalse(AutoLock.shouldLock(null, 5, 1_000_000))
        assertFalse(AutoLock.shouldLock(1_000, null, 1_000_000))
    }

    @Test fun elapsedThreshold() {
        val bg = 1_000_000L
        assertFalse(AutoLock.shouldLock(bg, 5, bg + 5 * 60_000 - 1))
        assertTrue(AutoLock.shouldLock(bg, 5, bg + 5 * 60_000))
        assertTrue(AutoLock.shouldLock(bg, 1, bg + 61_000))
    }

    @Test fun clockMovedBackFailsClosed() {
        assertTrue(AutoLock.shouldLock(1_000_000, 5, 999_999))
    }

    @Test fun timerStampsOnceAndClearsOnForeground() {
        var now = 10_000L
        val timer = AutoLockTimer { now }
        timer.noteBackground()
        now += 1_000
        timer.noteBackground()                 // second .background must not move the stamp
        assertEquals(10_000L, timer.backgroundedAt)
        now = 10_000 + 5 * 60_000
        assertTrue(timer.noteForeground(5))
        assertNull(timer.backgroundedAt)
        assertFalse(timer.noteForeground(5))   // nothing armed any more
    }

    @Test fun timerRespectsOff() {
        var now = 0L
        val timer = AutoLockTimer { now }
        timer.noteBackground()
        now = 24L * 3_600_000
        assertFalse(timer.noteForeground(AutoLock.timeoutMinutes(0)))
    }
}
