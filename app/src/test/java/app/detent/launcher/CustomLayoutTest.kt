package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomLayoutTest {
    @Test
    fun roundTripKeepsEveryPart() {
        val l = customLayout(220f, Markers.Comet, 6, Markers.Spokes)
        val back = decodeLayout(encodeLayout(l))!!
        assertEquals(220f, back.radius)
        assertEquals(Markers.Comet, back.markers)
        assertEquals(6, back.ticksPerApp)
        assertEquals(Markers.Spokes, back.notch)
        assertEquals("custom · round · comet · spokes notch", back.name)
    }

    @Test
    fun sameNotchIsNotNamedTwice() {
        assertEquals("custom · soft · wave", customLayout(330f, Markers.Wave, 8, Markers.Wave).name)
    }

    @Test
    fun badValuesAreRejected() {
        assertNull(decodeLayout(null))
        assertNull(decodeLayout("330|Double|4"))
        assertNull(decodeLayout("9000|Double|4|Double"))
        assertNull(decodeLayout("330|Sparkles|4|Double"))
        assertNull(decodeLayout("330|Double|13|Double"))
    }

    @Test
    fun customIsLastSoSavedPresetNumbersStay() {
        assertEquals(LAYOUTS.lastIndex, CUSTOM)
        assertEquals("grid · readout", LAYOUTS[7].name)
    }
}
