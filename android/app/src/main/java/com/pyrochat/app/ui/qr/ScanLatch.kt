package com.pyrochat.app.ui.qr

/**
 * The one-shot latch of iOS `QRScanner.ScannerView` (`LinkDesktopView.swift:179-227`): every
 * decoded code is offered to the host's `accept`; the first ACCEPTED one fires the latch and the
 * camera stops. Rejected codes keep the camera live.
 *
 * Android's analyzer delivers the same rejected code on every frame (up to 30×/s), so a rejected
 * value is re-offered only after [debounceNanos] — the host's status text and haptics fire once
 * per code, not per frame. A different code is offered immediately.
 */
class ScanLatch(private val debounceNanos: Long = DEBOUNCE_NANOS) {
    @Volatile
    var fired: Boolean = false
        private set

    private var lastValue: String? = null
    private var lastNanos: Long = Long.MIN_VALUE

    /** True when the host should see [value] now. */
    @Synchronized
    fun offer(value: String, nowNanos: Long): Boolean {
        if (fired) return false
        if (value == lastValue && nowNanos - lastNanos < debounceNanos) return false
        lastValue = value
        lastNanos = nowNanos
        return true
    }

    /** The host accepted a code: nothing is offered again until a fresh scanner is built. */
    @Synchronized
    fun fire() {
        fired = true
    }

    companion object {
        const val DEBOUNCE_NANOS: Long = 1_500_000_000L
    }
}
