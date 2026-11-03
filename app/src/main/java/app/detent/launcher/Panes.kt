package app.detent.launcher

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Quick panes: hold a dial row and its quick actions fan out beside the dial as slices of rings round the
 * dial centre, one slice per dial step. Slide up or down to pick one and let go to run it. Press harder
 * (or rest on a pane) and the picked pane opens side panes: [Col.Out] further from the dial, [Col.In]
 * over the dial's names. This file is the pure part: the slices, the hit test and the press detector.
 */

/** The three columns of panes. [Main] holds the quick actions; the side columns open on a deep press. */
enum class Col { Main, Out, In }

/** How a pane reads: an action, a notification, the app's own header, or a quieter app setting. */
enum class Tone { Action, Note, Head, Setting, Danger }

/** One pane. [act] runs it; null makes a pane that only shows. [out] and [inn] are its side panes. */
class Pane(
    val id: String,
    val label: String,
    val sub: String = "",
    val tone: Tone = Tone.Action,
    val out: List<Pane> = emptyList(),
    val inn: List<Pane> = emptyList(),
    val act: (() -> Unit)? = null,
)

/** A pane placed on the dial: column, rows from the selected row ([d]), and ring band in design units. */
class Slot(val col: Col, val index: Int, val d: Float, val rIn: Float, val rOut: Float, val pane: Pane)

/** Ring bands relative to the rim radius [r]. [deep] runs 0 (main only) to 1 (side columns out). */
class Bands(r: Float, private val deep: Float) {
    private fun lerp(a: Float, b: Float) = a + (b - a) * deep
    val mainIn = lerp(r + 16f, r + 10f)
    val mainOut = lerp(r + 206f, r + 118f)
    val outIn = r + 124f
    val outOut = r + 214f
    val inIn = r - 178f
    val inOut = r - 8f
}

/** Half the angular height of a pane, in rows. The rest of the step is the gap between panes. */
const val PANE_HALF = .43f

/** Most quick actions shown at once. */
const val PANE_MAX = 9

/**
 * The rows that [n] main panes take. They fill downward from the row under the held app, up to [below]
 * rows, and the rest go upward, up to [above]. Rows run 1, 2, 3 below and -1, -2 above. Extra panes drop.
 */
fun paneRows(n: Int, below: Int, above: Int): List<Int> {
    val down = min(n, max(0, below))
    val up = min(n - down, max(0, above))
    return (1..down).toList() + (1..up).map { -it }
}

/** How many whole rows fit between the selected row at [cy] and the limits [top] and [bottom], all in units. */
fun roomRows(span: Float, step: Float, r: Float, cy: Float, top: Float, bottom: Float): Pair<Int, Int> {
    fun fits(d: Int): Boolean {
        val y = cy + r * kotlin.math.sin(d * step)
        return y in top..bottom && abs(d) <= span - .5f
    }
    var below = 0
    while (below < 12 && fits(below + 1)) below++
    var above = 0
    while (above < 12 && fits(-(above + 1))) above++
    return below to above
}

/** Side panes stack on the row of the picked pane [d], spread evenly about it, kept inside [lo]..[hi]. */
fun sideRows(m: Int, d: Float, lo: Float, hi: Float): List<Float> {
    if (m <= 0) return emptyList()
    val first = (d - (m - 1) / 2f).coerceIn(lo, max(lo, hi - (m - 1)))
    return List(m) { first + it }
}

/** All slots for [main] panes on [rows], plus the side panes of [pickedMain] when [deep] > 0. */
fun layoutPanes(
    head: Pane,
    main: List<Pane>,
    rows: List<Int>,
    r: Float,
    deep: Float,
    picked: Int?,
    lo: Float,
    hi: Float,
): List<Slot> {
    val b = Bands(r, deep)
    val out = ArrayList<Slot>()
    out += Slot(Col.Main, -1, 0f, b.mainIn, b.mainOut, head)
    main.forEachIndexed { i, p -> if (i < rows.size) out += Slot(Col.Main, i, rows[i].toFloat(), b.mainIn, b.mainOut, p) }
    if (deep > 0f) {
        val pick = out.firstOrNull { it.col == Col.Main && it.index == (picked ?: -1) } ?: out.first()
        sideRows(pick.pane.out.size, pick.d, lo, hi).forEachIndexed { i, d ->
            out += Slot(Col.Out, i, d, b.outIn, b.outOut, pick.pane.out[i])
        }
        sideRows(pick.pane.inn.size, pick.d, lo, hi).forEachIndexed { i, d ->
            out += Slot(Col.In, i, d, b.inIn, b.inOut, pick.pane.inn[i])
        }
    }
    return out
}

/** Polar position of a touch: [dist] from the dial centre and [rows] round from the selected row. */
class Polar(val dist: Float, val rows: Float)

/** [x], [y] in design units on a dial whose centre is ([cx], [cy]) with [step] radians per row. [x] already mirrored. */
fun polar(x: Float, y: Float, cx: Float, cy: Float, step: Float) =
    Polar(hypot(cx - x, y - cy), atan2(y - cy, cx - x) / step)

/**
 * The slot that a touch picks. While only the main column shows, the distance does not matter: slide up
 * and down anywhere. With the side columns out, the distance picks the column, and the row picks the pane
 * nearest to the touch in it. A touch more than [reach] rows from every pane of its column picks nothing.
 * [strict] is for a fresh tap on open panes: it must land on a pane (within 8 units), not just on its row.
 */
fun pickSlot(slots: List<Slot>, p: Polar, deep: Boolean, reach: Float = .9f, strict: Boolean = false): Slot? {
    val col = if (!deep) Col.Main else {
        val main = slots.first { it.col == Col.Main }
        val hasOut = slots.any { it.col == Col.Out }
        val hasIn = slots.any { it.col == Col.In }
        when {
            hasOut && p.dist > main.rOut + 3f -> Col.Out
            hasIn && p.dist < main.rIn - 3f -> Col.In
            else -> Col.Main
        }
    }
    val hit = slots.filter { it.col == col }.minByOrNull { abs(it.d - p.rows) }?.takeIf { abs(it.d - p.rows) <= reach }
    if (strict && hit != null && (p.dist < hit.rIn - 8f || p.dist > hit.rOut + 8f || abs(hit.d - p.rows) > .55f)) return null
    return hit
}

/**
 * Tells a deep press from a plain hold. A finger pressing harder reads a higher pressure than the slow
 * average of the hold so far; phones that report no pressure (always 1, or always 0) never trip it.
 * [dwellMs] of resting on one pane counts as a deep press too, so every phone has one.
 */
class DeepPress(private val dwellMs: Long = 700L, private val rise: Float = .14f, private val ratio: Float = 1.3f) {
    private var base = -1f
    private var hits = 0
    private var restT = 0L
    private var restKey: Any? = null
    private var restX = 0f
    private var restY = 0f

    fun reset() {
        base = -1f
        hits = 0
        restKey = null
    }

    /** Feeds one sample. Returns true when this sample makes the press deep. */
    fun feed(t: Long, x: Float, y: Float, pressure: Float, on: Any?): Boolean {
        // Pressure: a slow average, and a jump well above it.
        if (base < 0f) base = pressure
        val hard = pressure > base * ratio && pressure - base > rise && pressure < 1.5f
        hits = if (hard) hits + 1 else 0
        if (!hard) base += (pressure - base) * .08f
        if (hits >= 2) return true
        // Dwell: the same pane, the finger within 8 units, long enough.
        if (on != restKey || abs(x - restX) > 8f || abs(y - restY) > 8f) {
            restKey = on
            restX = x
            restY = y
            restT = t
            return false
        }
        return on != null && t - restT >= dwellMs
    }
}
