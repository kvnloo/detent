package app.detent.launcher

/** One label owns the press, glass capture and flight into the quick-pane header. */
data class PaneLift(val key: String, val label: String, val icon: String?, val pose: Pose, val selected: Boolean)

const val PANE_FLIGHT_MS = 320

/** Match the dial's selected ink even for long labels whose scale is at or below 1. */
fun paneSourceSelected(distanceRows: Float): Boolean = kotlin.math.abs(distanceRows) < .5f

/** The original holding finger leaving before arrival reverses the flight; other menus are unaffected. */
fun paneFlightInterrupted(openedAtMs: Long, releasedAtMs: Long): Boolean =
    openedAtMs >= 0L && releasedAtMs - openedAtMs < PANE_FLIGHT_MS.toLong()

/** Keep glass formation at the source separate from the flight; endpoints are exact. */
fun paneFlight(progress: Float): Float {
    val t = ((progress - .18f) / .82f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
