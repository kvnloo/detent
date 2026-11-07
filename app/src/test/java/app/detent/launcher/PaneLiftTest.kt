package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun thePressedLabelUsesDialSelectionNotItsTextScale() {
        assertTrue(paneSourceSelected(0f))
        assertTrue(paneSourceSelected(.49f))
        assertTrue(paneSourceSelected(-.49f))
        assertFalse(paneSourceSelected(.5f))
        assertFalse(paneSourceSelected(.51f))
    }

    @Test fun releasingDuringFlightCancelsForBothFastAndSlowHolds() {
        for (openedAt in listOf(500L, 5_000L, 50_000L)) {
            assertTrue(paneFlightInterrupted(openedAt, openedAt))
            assertTrue(paneFlightInterrupted(openedAt, openedAt + PANE_FLIGHT_MS - 1))
            assertFalse(paneFlightInterrupted(openedAt, openedAt + PANE_FLIGHT_MS))
            assertFalse(paneFlightInterrupted(openedAt, openedAt + PANE_FLIGHT_MS + 500))
        }
        assertFalse(paneFlightInterrupted(-1L, 100L))
    }
}
