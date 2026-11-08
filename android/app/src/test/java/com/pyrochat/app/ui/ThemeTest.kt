package com.pyrochat.app.ui

import com.pyrochat.app.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test fun tenNamedThemesDefaultingToEmber() {
        assertEquals(10, Palette.themes.size)
        assertEquals(10, Palette.themeNames.toSet().size)
        assertEquals("ember", Palette.themeNames.first())
        assertTrue(Palette.themeNames.contains("amber"))
        assertEquals(Palette.ember, Palette.forName("hologram"))     // unknown → ember, never a crash
        assertNotEquals(Palette.ember, Palette.forName("paper"))
        // the exact hex values iOS uses (Theme.themes)
        assertEquals(0xFF7EE8FA.toInt(), Palette.forName("ice").value.shr(32).toInt())
    }
}
