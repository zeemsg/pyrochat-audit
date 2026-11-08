package com.pyrochat.app.ui

import com.pyrochat.app.ui.theme.TextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/TEXT-SIZE.md §4 — the stored word → multiplier function, and the §2 ceiling. */
class TextSizeTest {

    @Test fun fiveNamesInCfgOrderWithTheSpecMultipliers() {
        assertEquals(listOf("small", "normal", "large", "larger", "largest"), TextSize.names)
        assertEquals(0.85f, TextSize.multiplier("small"), 1e-6f)
        assertEquals(1.0f, TextSize.multiplier("normal"), 1e-6f)
        assertEquals(1.15f, TextSize.multiplier("large"), 1e-6f)
        assertEquals(1.3f, TextSize.multiplier("larger"), 1e-6f)
        assertEquals(1.5f, TextSize.multiplier("largest"), 1e-6f)
    }

    @Test fun unknownAndMissingBothRenderAsNormal() {
        assertEquals("normal", TextSize.name(null))
        assertEquals("normal", TextSize.name("gigantic"))
        assertEquals("normal", TextSize.name(""))
        assertEquals("normal", TextSize.name("Largest"))          // the store word is exact, like `theme`
        assertEquals(1.0f, TextSize.multiplier(null), 1e-6f)
        assertEquals(1.0f, TextSize.multiplier("gigantic"), 1e-6f)
        // every valid name renders as itself
        TextSize.names.forEach { assertEquals(it, TextSize.name(it)) }
    }

    @Test fun ourScaleMultipliesTheSystemOneUntilTheCeiling() {
        // a phone at 1× gets exactly the table
        TextSize.names.forEach { assertEquals(TextSize.multiplier(it), TextSize.scale(it, 1f), 1e-6f) }
        // a phone at 1.3× still gets all of `large` (1.3 × 1.15 = 1.495 ≤ 2)
        assertEquals(1.15f, TextSize.scale("large", 1.3f), 1e-6f)
        // §2: a large accessibility setting plus `largest` stays within twice the designed size
        listOf(1.3f, 1.5f, 1.8f, 2.0f, 2.5f, 3.2f).forEach { system ->
            TextSize.names.forEach { name ->
                val combined = system * TextSize.scale(name, system)
                assertTrue("$name at ${system}x combined to $combined", combined <= TextSize.MAX_COMBINED + 1e-5f)
            }
        }
        assertEquals(2.0f, 1.8f * TextSize.scale("largest", 1.8f), 1e-5f)   // clamped, not 2.7
        assertEquals(2f / 1.5f, TextSize.scale("largest", 1.5f), 1e-6f)     // held back: 1.5 × 1.5 would be 2.25
        // a shrunken system size is never inflated to reach the ceiling
        assertEquals(1.5f, TextSize.scale("largest", 0.85f), 1e-6f)
        // a nonsense density is treated as 1× rather than dividing by zero
        assertEquals(1.5f, TextSize.scale("largest", 0f), 1e-6f)
        assertEquals(1.5f, TextSize.scale("largest", Float.NaN), 1e-6f)
    }

    @Test fun fixedWidthArtStopsAtLarge() {
        assertEquals(TextSize.scale("small", 1f), TextSize.fixedWidthScale("small", 1f), 1e-6f)
        assertEquals(1.0f, TextSize.fixedWidthScale(null, 1f), 1e-6f)
        assertEquals(1.15f, TextSize.fixedWidthScale("large", 1f), 1e-6f)
        assertEquals(1.15f, TextSize.fixedWidthScale("larger", 1f), 1e-6f)
        assertEquals(1.15f, TextSize.fixedWidthScale("largest", 1f), 1e-6f)
        // the §2 ceiling still applies on top of the §3 cap
        assertEquals(1.0f, TextSize.fixedWidthScale("largest", 2f), 1e-6f)
        assertTrue(TextSize.fixedWidthScale("largest", 1.9f) * 1.9f <= TextSize.MAX_COMBINED + 1e-5f)
    }
}
