package com.pyrochat.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** `Waveform.fromSamples` = `VoiceRecorder.swift:164-175`; `placeholder` = lines 183-185. */
class WaveformMathTest {
    @Test fun silenceDrawsTheFloor() {
        val bars = Waveform.fromSamples(FloatArray(24_000))
        assertEquals(32, bars.size)
        // peak = max(0, 0.001) = 0.001 → 0 / 0.001 = 0 → floor 0.12
        assertTrue(bars.all { it == 0.12f })
    }

    @Test fun placeholderIsPointThree() {
        assertTrue(Waveform.placeholder().all { it == 0.3f })
        assertEquals(32, Waveform.placeholder().size)
    }

    @Test fun constantSignalIsAllOnes() {
        val bars = Waveform.fromSamples(FloatArray(3200) { 0.5f })
        assertTrue(bars.all { it == 1f })
    }

    @Test fun oneLoudBarNormalisesTheRest() {
        val n = 3200                  // per = 100
        val s = FloatArray(n)
        for (i in 500 until 600) s[i] = 0.8f   // bar 5
        val bars = Waveform.fromSamples(s)
        assertEquals(1f, bars[5], 0f)
        for (b in bars.indices) if (b != 5) assertEquals(0.12f, bars[b], 0f)
    }

    @Test fun matchesTheSwiftArithmeticExactly() {
        // hand-port of the Swift loop with Float accumulation, on a ramp
        val n = 1000
        val s = FloatArray(n) { (it % 37) / 37f - 0.5f }
        val per = maxOf(1, n / 32)
        val expected = FloatArray(32)
        for (b in 0 until 32) {
            val lo = b * per
            val hi = minOf(lo + per, n)
            if (lo >= hi) { expected[b] = 0f; continue }
            var sum = 0f
            for (i in lo until hi) sum += s[i] * s[i]
            expected[b] = sqrt(sum / (hi - lo).toFloat())
        }
        val peak = maxOf(expected.max(), 0.001f)
        for (b in 0 until 32) expected[b] = maxOf(0.12f, expected[b] / peak)
        assertArrayEquals(expected, Waveform.fromSamples(s), 0f)
    }

    @Test fun fewerFramesThanBarsLeavesTrailingBarsAtTheFloor() {
        val bars = Waveform.fromSamples(FloatArray(10) { 0.4f })   // per = max(1, 10/32) = 1
        for (b in 0 until 10) assertEquals(1f, bars[b], 0f)
        for (b in 10 until 32) assertEquals(0.12f, bars[b], 0f)   // lo >= hi → 0 → floor
    }

    @Test fun deterministic() {
        val s = FloatArray(24_000) { kotlin.math.sin(it / 7.0).toFloat() }
        assertArrayEquals(Waveform.fromSamples(s), Waveform.fromSamples(s), 0f)
    }
}
