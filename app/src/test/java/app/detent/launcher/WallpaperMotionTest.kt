package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperMotionTest {
    @Test fun upwardSwipeRevealsFromBelow() {
        assertEquals(1, direction(4, 5))
    }

    @Test fun downwardSwipeRevealsFromAbove() {
        assertEquals(-1, direction(5, 4))
    }

    @Test fun wraparoundKeepsTheGestureDirection() {
        assertEquals(1, direction(27, 28))
        assertEquals(-1, direction(0, -1))
    }

    @Test fun fastSwipeKeepsItsDirectionEvenIfDecodingSkipsRows() {
        assertEquals(1, direction(4, 8))
        assertEquals(-1, direction(8, 4))
    }

    @Test fun distantTapCrossfadesInsteadOfScrolling() {
        assertEquals(0, direction(4, 20, jump = true))
    }

    @Test fun customPhotoAndSharedHandArtStayInPlace() {
        assertEquals(0, wallpaperDirection(4, 5, "/photo.webp", "/photo.webp", true, false))
        assertEquals(0, wallpaperDirection(0, 1, "hands.webp", "hands.webp", true, false))
    }

    @Test fun leavingThemePreviewDoesNotScrollBackThroughAppRows() {
        assertEquals(0, direction(20, 3, preview = false))
    }

    @Test fun changingThePhotoWithoutMovingTheDialCrossfades() {
        assertEquals(0, direction(4, 4))
    }

    private fun direction(from: Int, to: Int, preview: Boolean = true, jump: Boolean = false) =
        wallpaperDirection(from, to, "old.webp", "new.webp", preview, jump)
}
