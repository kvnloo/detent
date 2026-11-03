package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialSlotTest {
    private val g = Geo(560f, CY0)
    private val m = Markers.Dots

    @Test
    fun onTheNameOfTheSelectedRow() {
        // Halfway along a 7-letter name, on row 0.
        val mid = labelRadius(g, m) - 7 * CH * 1.2f / 2
        assertEquals(0, dialSlot(mid, 0f, 0f, g, m, 7))
    }

    @Test
    fun onTheMarksOfAnotherRow() {
        assertEquals(2, dialSlot(g.r, 2.1f, 0f, g, m, 6))
    }

    @Test
    fun farLeftOfTheNamesIsOffTheDial() {
        assertNull(dialSlot(g.r - 300f, 0f, 0f, g, m, 6))
    }

    @Test
    fun betweenTwoRowsIsOffTheDial() {
        assertNull(dialSlot(g.r, 1.5f, 0f, g, m, 6))
    }

    @Test
    fun beyondTheRimIsOffTheDial() {
        assertNull(dialSlot(g.r + 40f, 0f, 0f, g, m, 6))
    }
}
