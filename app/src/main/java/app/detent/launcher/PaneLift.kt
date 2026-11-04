package app.detent.launcher

/** One label owns the press, glass capture and flight into the quick-pane header. */
data class PaneLift(val key: String, val label: String, val icon: String?, val pose: Pose)

const val PANE_FLIGHT_MS = 320

/** Keep glass formation at the source separate from the flight; endpoints are exact. */
fun paneFlight(progress: Float): Float {
    val t = ((progress - .18f) / .82f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
