package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedTest {
    @Test fun hexColoursAcceptWithOrWithoutAHash() {
        assertEquals(0xFFC8BE72L, Synced.parse("c8be72"))
        assertEquals(0xFFC8BE72L, Synced.parse("#C8BE72"))
    }

    @Test fun hexColoursMustHaveSixDigits() {
        assertNull(Synced.parse("c8be7"))
        assertNull(Synced.parse("c8be720"))
        assertNull(Synced.parse("#C8BE7"))
        assertNull(Synced.parse("#C8BE720"))
    }

    @Test fun hexColoursRejectNonHexDigits() {
        assertNull(Synced.parse("c8be7g"))
        assertNull(Synced.parse("#C8BE7Z"))
        assertNull(Synced.parse(null))
    }

    @Test fun backgroundLuminanceSetsTheLightFlag() {
        val fg = 0xFFD1DEDAL
        val accent = 0xFFC8BE72L
        assertTrue(Synced.theme(0xFFFFFFFFL, fg, accent).light)
        assertFalse(Synced.theme(0xFF181F23L, fg, accent).light)
        // Mid-grey is below 0.5 luminance when sRGB gamma is accounted for.
        assertFalse(Synced.theme(0xFF808080L, fg, accent).light)
    }

    @Test fun theSyncedThemeKeepsTheSystemWallpaper() {
        val theme = Synced.theme(0xFF181F23L, 0xFFD1DEDAL, 0xFFC8BE72L)
        assertEquals("hyprland", theme.name)
        assertTrue(Synced.isSystemWall(theme))
    }
}
