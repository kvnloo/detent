package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanesTest {
    private fun pane(id: String, out: Int = 0, inn: Int = 0) =
        Pane(id, id, out = List(out) { Pane("$id-o$it", "o$it") }, inn = List(inn) { Pane("$id-i$it", "i$it") })

    @Test
    fun rowsFillDownThenUp() {
        assertEquals(listOf(1, 2, 3), paneRows(3, below = 4, above = 3))
        assertEquals(listOf(1, 2, -1, -2), paneRows(4, below = 2, above = 3))
        // More panes than room: the rest drop.
        assertEquals(listOf(1, -1), paneRows(5, below = 1, above = 1))
        assertEquals(emptyList<Int>(), paneRows(0, 4, 4))
    }

    @Test
    fun roomStopsAtTheScreenLimits() {
        val g = Geo(560f, 470f)
        val (below, above) = roomRows(g.span, g.step, 560f, 470f, 210f, 756f)
        assertTrue(below in 4..6)
        assertTrue(above in 4..6)
        // Near the top there is no room above.
        assertEquals(0, roomRows(g.span, g.step, 560f, 220f, 210f, 756f).second)
    }

    @Test
    fun sidePanesCentreOnThePickedRowAndStayOnScreen() {
        assertEquals(listOf(1.5f, 2.5f), sideRows(2, 2f, -3f, 4f))
        assertEquals(listOf(1f, 2f, 3f), sideRows(3, 2f, -3f, 4f))
        // At the bottom they shift up.
        assertEquals(listOf(2f, 3f, 4f), sideRows(3, 4f, -3f, 4f))
        assertEquals(emptyList<Float>(), sideRows(0, 1f, -3f, 4f))
    }

    @Test
    fun slideUpAndDownPicksAnywhereAcrossWhileShallow() {
        val main = listOf(pane("a"), pane("b"), pane("c"))
        val slots = layoutPanes(pane("head"), main, listOf(1, 2, 3), 400f, 0f, null, -3f, 4f)
        assertEquals(4, slots.size)
        // Far from the panes horizontally, the row alone picks.
        assertEquals("b", pickSlot(slots, Polar(dist = 100f, rows = 2.2f), deep = false)!!.pane.id)
        assertEquals("head", pickSlot(slots, Polar(dist = 900f, rows = .1f), deep = false)!!.pane.id)
        assertNull(pickSlot(slots, Polar(500f, 5f), deep = false))
    }

    @Test
    fun deepPickChoosesTheColumnByDistance() {
        val main = listOf(pane("a", out = 2, inn = 3), pane("b"))
        val slots = layoutPanes(pane("head"), main, listOf(1, 2), 400f, 1f, picked = 0, lo = -3f, hi = 4f)
        val b = Bands(400f, 1f)
        assertEquals(2, slots.count { it.col == Col.Out })
        assertEquals(3, slots.count { it.col == Col.In })
        assertEquals("a-o1", pickSlot(slots, Polar(b.outOut - 10f, 1.4f), deep = true)!!.pane.id)
        assertEquals("a-i0", pickSlot(slots, Polar(b.inIn + 20f, 0f), deep = true)!!.pane.id)
        assertEquals("a", pickSlot(slots, Polar((b.mainIn + b.mainOut) / 2, 1f), deep = true)!!.pane.id)
    }

    @Test
    fun polarMatchesTheDialRows() {
        val g = Geo(300f, 470f)
        val a = 2 * g.step
        val x = g.cx - g.r * kotlin.math.cos(a)
        val y = g.cy + g.r * kotlin.math.sin(a)
        val p = polar(x, y, g.cx, g.cy, g.step)
        assertEquals(300f, p.dist, .01f)
        assertEquals(2f, p.rows, .001f)
    }

    @Test
    fun harderPressTripsDeep() {
        val d = DeepPress()
        var t = 0L
        repeat(10) { assertFalse(d.feed(t, 0f, it * 20f, .3f, "a")); t += 16 }
        assertFalse(d.feed(t, 0f, 200f, .6f, "a"))
        assertTrue(d.feed(t + 16, 0f, 200f, .62f, "a"))
    }

    @Test
    fun flatPressureNeverTripsButRestingDoes() {
        val d = DeepPress(dwellMs = 700L)
        var t = 0L
        // Moving on: no dwell, and a pressure stuck at 1 never counts.
        repeat(20) { assertFalse(d.feed(t, 0f, it * 20f, 1f, "a$it")); t += 50 }
        // Resting on one pane long enough does.
        assertFalse(d.feed(t, 0f, 0f, 1f, "b"))
        assertFalse(d.feed(t + 400, 2f, 1f, 1f, "b"))
        assertTrue(d.feed(t + 720, 3f, 2f, 1f, "b"))
        // Resting on nothing does not.
        d.reset()
        assertFalse(d.feed(0, 0f, 0f, 1f, null))
        assertFalse(d.feed(2000, 0f, 0f, 1f, null))
    }

    @Test
    fun pillsFitTheirLabelWithinTheBand() {
        val hw = 12f
        // A short label still makes a capsule, not a disc.
        assertEquals(hw * 3.4f, pillLength(4f, hw, 180f), 0f)
        // A label grows the pill by itself plus the round ends.
        assertEquals(80f + hw * 2.2f, pillLength(80f, hw, 180f), 1e-3f)
        // A long label stops at the band.
        assertEquals(180f, pillLength(400f, hw, 180f), 0f)
        assertEquals(20f, pillLength(4f, hw, 20f), 0f)
    }

    @Test
    fun pillThicknessFollowsTheDialStepWithinLimits() {
        assertEquals(.36f * .1f * 300f, pillHalf(.1f, 300f), 1e-4f)
        assertEquals(15f, pillHalf(.5f, 300f), 0f)
        assertEquals(9f, pillHalf(.01f, 300f), 0f)
    }

    @Test
    fun pillOutlineStaysInsideItsBandAndWidth() {
        val pts = pillOutline(0f, 0f, 300f, 400f, 0f, 12f)
        for (p in pts) {
            val r = kotlin.math.hypot(p.x, p.y)
            assertTrue("r=$r", r in 299.5f..400.5f)
            // Constant thickness: the arc distance from the centre line stays within the half-thickness.
            assertTrue(kotlin.math.abs(r * kotlin.math.atan2(p.y, p.x)) <= 12.01f)
        }
        assertEquals(300f, pts.minOf { kotlin.math.hypot(it.x, it.y) }, .5f)
        assertEquals(400f, pts.maxOf { kotlin.math.hypot(it.x, it.y) }, .5f)
    }

    @Test
    fun paneLabelsDropBlanksRepeatsAndTheAppName() {
        val got = paneLabels(listOf("New  Chat", "", "Discord", "new chat", null, "Mom"), "Discord") { it }
        assertEquals(listOf("new chat", "mom"), got.map { it.second })
    }
}
