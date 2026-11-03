package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionTest {
    @Test fun insertingAThemeKeepsTheSavedChoice() {
        val before = listOf("catppuccin", "gruvbox", "nord")
        val savedIndex = before.indexOf("nord")
        val savedName = before[savedIndex]
        val after = listOf("catppuccin", "everforest", "gruvbox", "nord")
        assertEquals("nord", after[savedThemeIndex(savedName, savedIndex, after)])
    }

    @Test fun nameWinsOverTheOldIndex() {
        val themes = listOf("dusk", "ember", "nord")
        assertEquals(2, savedThemeIndex("nord", 0, themes))
    }

    @Test fun anOldSaveStillUsesItsIndex() {
        val themes = listOf("dusk", "ember", "nord")
        assertEquals(1, savedThemeIndex(null, 1, themes))
        assertEquals(1, savedThemeIndex("removed", 1, themes))
    }

    @Test fun anInvalidIndexUsesTheFirstTheme() {
        val themes = listOf("dusk", "ember", "nord")
        assertEquals(0, savedThemeIndex(null, -1, themes))
        assertEquals(0, savedThemeIndex("removed", themes.size, themes))
    }
}
