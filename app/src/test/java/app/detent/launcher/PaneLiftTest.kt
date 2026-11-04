package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneLiftTest {
    @Test fun glassFormsBeforeTheLabelTravels() {
        assertEquals(0f, paneFlight(0f), 0f)
        assertEquals(0f, paneFlight(.18f), 0f)
        assertTrue(paneFlight(.5f) in .01f.. .99f)
        assertEquals(1f, paneFlight(1f), 0f)
    }

    @Test fun flightNeverReversesOrOvershoots() {
        val frames = (-10..110).map { paneFlight(it / 100f) }
        assertTrue(frames.all { it in 0f..1f })
        assertTrue(frames.zipWithNext().all { (a, b) -> b >= a })
    }
}
