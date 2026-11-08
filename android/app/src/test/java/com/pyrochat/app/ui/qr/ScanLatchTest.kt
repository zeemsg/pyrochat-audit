package com.pyrochat.app.ui.qr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLatchTest {
    private val s = 1_000_000_000L

    @Test fun rejectedCodeIsReofferedOnlyAfterTheDebounce() {
        val latch = ScanLatch(debounceNanos = ScanLatch.DEBOUNCE_NANOS)
        assertTrue(latch.offer("lunch", 0))
        assertFalse("same frame", latch.offer("lunch", 10))
        assertFalse("within 1.5 s", latch.offer("lunch", s))
        assertTrue("after 1.5 s", latch.offer("lunch", 2 * s))
        assertFalse(latch.fired)
    }

    @Test fun aDifferentCodeIsOfferedImmediately() {
        val latch = ScanLatch()
        assertTrue(latch.offer("a", 0))
        assertTrue(latch.offer("b", 1))
        assertTrue("a is a new code again after b", latch.offer("a", 2))
    }

    @Test fun firedLatchOffersNothing() {
        val latch = ScanLatch()
        assertTrue(latch.offer("pyro-add:v1:bob:AAAA", 0))
        latch.fire()
        assertTrue(latch.fired)
        assertFalse(latch.offer("pyro-add:v1:bob:AAAA", 5 * s))
        assertFalse(latch.offer("other", 5 * s))
    }
}
