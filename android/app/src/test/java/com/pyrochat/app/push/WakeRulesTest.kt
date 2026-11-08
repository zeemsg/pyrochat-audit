package com.pyrochat.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the zero-content notification. */
class WakeRulesTest {
    @Test fun onlyTheRelaysWakePayloadCounts() {
        assertTrue(WakePayload.isWake(mapOf("kind" to "wake")))
        assertTrue(WakePayload.isWake(mapOf("kind" to "wake", "extra" to "ignored")))
        assertFalse(WakePayload.isWake(emptyMap()))
        assertFalse(WakePayload.isWake(mapOf("kind" to "WAKE")))
        assertFalse(WakePayload.isWake(mapOf("kind" to "typing")))
        assertFalse(WakePayload.isWake(mapOf("title" to "hello", "body" to "leak")))
    }

    @Test fun liveAppIsTheOnlySuppressedCase() {
        assertFalse(WakePolicy.shouldNotify(foreground = true, online = true))
        assertTrue(WakePolicy.shouldNotify(foreground = true, online = false))   // link down: a message waits
        assertTrue(WakePolicy.shouldNotify(foreground = false, online = true))   // socket may be dying in the background
        assertTrue(WakePolicy.shouldNotify(foreground = false, online = false))  // the normal case
    }

    /**
     * docs/DECOY-ACCOUNT.md §6.6 — "a wake push for the real account, arriving while the decoy is
     * open, must not surface. Nothing may render for a slot that is not the open one."
     *
     * The payload cannot say which account it is for and must not learn how: the whole point of a
     * zero-content wake is that the push service and anyone watching it learn nothing. So the rule
     * is about what the OPEN session asked for, and it is asserted on the outcome — whether
     * anything renders — rather than on the notification.
     */
    @Test fun aWakeForASlotThatIsNotTheOpenOneDoesNotSurface() {
        // a decoy is open and never registered for wake push: this wake is not its
        assertFalse(WakeSlotPolicy.belongsToOpenSlot(sessionOpen = true, wakesThisSlot = false))
        // the account that did register: a wake may well be for it, and it renders as it always did
        assertTrue(WakeSlotPolicy.belongsToOpenSlot(sessionOpen = true, wakesThisSlot = true))
        // locked or cold, there is no open slot to be wrong about — and the banner is what it was,
        // which is also what keeps a phone holding one account and a phone holding two alike
        assertTrue(WakeSlotPolicy.belongsToOpenSlot(sessionOpen = false, wakesThisSlot = false))
        assertTrue(WakeSlotPolicy.belongsToOpenSlot(sessionOpen = false, wakesThisSlot = true))
    }

    @Test fun burstsAlertOnceThenReAlertAfterTheWindow() {
        val w = WakeCoalescing.QUIET_WINDOW_MS
        assertTrue(WakeCoalescing.shouldAlert(lastAlertAtMs = null, nowMs = 1_000))          // first wake / shade cleared
        assertFalse(WakeCoalescing.shouldAlert(lastAlertAtMs = 1_000, nowMs = 1_000))        // same instant
        assertFalse(WakeCoalescing.shouldAlert(lastAlertAtMs = 1_000, nowMs = 1_000 + w - 1)) // burst
        assertTrue(WakeCoalescing.shouldAlert(lastAlertAtMs = 1_000, nowMs = 1_000 + w))     // window over: re-alert
        assertTrue(WakeCoalescing.shouldAlert(lastAlertAtMs = 5_000, nowMs = 1_000))         // clock ran backwards: fail open
        assertFalse(WakeCoalescing.shouldAlert(lastAlertAtMs = 0, nowMs = 10, quietWindowMs = 100))
        assertTrue(WakeCoalescing.shouldAlert(lastAlertAtMs = 0, nowMs = 100, quietWindowMs = 100))
    }

    @Test fun badgeMapsUnreadHonestly() {
        assertEquals(0, Badge.number(null))   // locked: unknown → no number
        assertEquals(0, Badge.number(0))
        assertEquals(0, Badge.number(-3))
        assertEquals(7, Badge.number(7))
        assertEquals(1, Badge.forWake(null))  // a wake proves ≥ 1 waits
        assertEquals(1, Badge.forWake(0))
        assertEquals(4, Badge.forWake(4))
    }
}
