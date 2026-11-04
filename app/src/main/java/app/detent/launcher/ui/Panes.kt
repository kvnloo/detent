package app.detent.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import app.detent.launcher.Col
import app.detent.launcher.Ease
import app.detent.launcher.Geo
import app.detent.launcher.Glass
import app.detent.launcher.GlassFx
import app.detent.launcher.LauncherState
import app.detent.launcher.PANE_DWELL
import app.detent.launcher.PANE_HALF
import app.detent.launcher.Palette
import app.detent.launcher.QuickMenu
import app.detent.launcher.Tone
import app.detent.launcher.W
import app.detent.launcher.mix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** How far the dial behind open quick panes blurs, 0 to 1. Home blurs and dims the dial and clock with it. */
@Composable
fun panesVeil(s: LauncherState): Float {
    val shown = s.menuShown && s.menu?.panes != null
    val v by animateFloatAsState(if (shown) (if (s.paneDeep) 1f else .7f) else 0f, tween(220, easing = Ease), label = "panesVeil")
    return v
}

/**
 * The quick panes of a held dial row: glass lenses, shaped like spectacle lenses and bent along the dial's
 * ring, one dial step each, beside the rim. The picked lens takes the accent; resting on it fills a ring
 * along its outer edge, then its side options open over the dial and toward the screen edge. Colours
 * come from the theme palette and the glass look, so every theme and glass preset (off included) draws them.
 */
@Composable
fun QuickPanes(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    var last by remember { mutableStateOf<QuickMenu?>(null) }
    s.menu?.let { if (it.panes != null) last = it }
    val shown = s.menuShown && s.menu?.panes != null
    val open by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 320 else 180, easing = Ease), label = "panesOpen")
    val deep by animateFloatAsState(if (s.paneDeep) 1f else 0f, tween(260, easing = Ease), label = "panesDeep")
    // The press-harder ring: fills over the dwell on the pane under a resting finger.
    val dwellKey = s.paneDwell
    val dwell = remember { Animatable(0f) }
    LaunchedEffect(dwellKey) {
        dwell.snapTo(0f)
        if (dwellKey != null) dwell.animateTo(1f, tween(PANE_DWELL.toInt(), easing = LinearEasing))
    }
    if (last == null || (!shown && open <= .001f)) return
    val k by rememberUpdatedState(u.k)
    val fx = remember { GlassFx() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val halo = LocalHalo.current
    val styles = remember(u, halo, pal) {
        PaneStyles(
            label = textStyle(u, 12.5f, pal.fg, FontWeight.Normal, spacing = .01f, halo = halo),
            hot = textStyle(u, 12.5f, pal.fg, FontWeight.SemiBold, spacing = .01f, halo = halo),
            head = textStyle(u, 15f, pal.accent, FontWeight.SemiBold, spacing = .01f, halo = halo),
            sub = textStyle(u, 9.5f, pal.muted, FontWeight.Normal, spacing = .02f, halo = halo),
        )
    }
    val look = s.lookNow ?: Glass.Clear.look!!
    // Lens bodies: milky on light themes, a faint lift of the text colour on dark ones; the picked lens takes the accent.
    val body = if (pal.light) Color.White.copy(alpha = .46f) else mix(pal.fg, pal.bg, .78f).copy(alpha = .34f)
    val hotBody = mix(pal.accent, pal.bg, if (pal.light) .35f else .55f).copy(alpha = if (pal.light) .5f else .5f)
    val danger = Color(0xFFFF6B6B)

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(s) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    s.paneDown(down.position.x / k, down.position.y / k, down.pressure)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUp() || !ch.pressed) {
                            ch.consume()
                            s.paneRelease()
                            break
                        }
                        if (ch.positionChanged()) {
                            ch.consume()
                            s.paneDrag(ch.position.x / k, ch.position.y / k, ch.pressure)
                        }
                    }
                }
            },
    ) {
        // A light scrim; the dial itself blurs behind (panesVeil), so the lenses read without a dark veil.
        drawRect(pal.bg, alpha = open * (if (pal.light) .16f else .3f) * (1f + .3f * deep))
        val kk = u.k
        val g = Geo(s.radius, s.cy, s.layout.ax)
        val left = s.left
        val cx = (if (left) W - g.cx else g.cx) * kk
        val cy = g.cy * kk
        val half = PANE_HALF * g.step
        val hot = s.paneHot
        for (slot in s.paneSlots(deep)) {
            // Main lenses grow out of the rim one after another; side lenses open out of their anchor.
            val t = if (slot.col == Col.Main) (open * 1.6f - abs(slot.d) * .11f).coerceIn(0f, 1f)
            else (deep * 1.5f - slot.index * .18f).coerceIn(0f, 1f)
            if (t <= .01f) continue
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            val rim = g.r
            var rIn = slot.rIn
            var rOut = slot.rOut
            when (slot.col) {
                Col.Main -> rOut = rIn + (rOut - rIn) * e
                Col.In -> rIn = rOut - (rOut - rIn) * e
                Col.Out -> rOut = rIn + (rOut - rIn) * e
            }
            if (slot.col == Col.Main) rIn = rim + (rIn - rim) * e
            // With the side options out, the other quick actions step back.
            val dimmed = deep > 0f && slot.col == Col.Main && slot.index != s.paneAnchor
            val a = e * (if (dimmed) 1f - .6f * deep else 1f)
            val ang = slot.d * g.step
            // Screen angle of the lens centre, seen from the dial centre.
            val mid = if (left) ang else (PI.toFloat() - ang)
            val isHot = hot != null && hot.first == slot.col && hot.second == slot.index
            val p = slot.pane
            val tint = when {
                p.tone == Tone.Danger -> danger.copy(alpha = if (isHot) .42f else .16f)
                isHot -> hotBody
                else -> body
            }
            with(fx) {
                lens(
                    cx, cy, rIn * kk, rOut * kk, mid, half,
                    tint.copy(alpha = tint.alpha * a), pal.accent, if (isHot) 1f else 0f,
                    pal.rim2.copy(alpha = pal.rim2.alpha * a), look, kk,
                )
            }
            if (dwellKey != null && dwellKey.first == slot.col && dwellKey.second == slot.index && dwell.value > 0f) {
                dwellRing(cx, cy, (rOut - 4f) * kk, mid, half * .78f, dwell.value, pal.accent.copy(alpha = a), kk)
            }
            if (isHot && slot.col == Col.Main && deep < .5f && (p.out.isNotEmpty() || p.inn.isNotEmpty())) {
                chevrons(g, rIn - 5f, rOut + 5f, ang, left, pal.accent.copy(alpha = .9f * a), kk)
            }
            // Names read left to right from the lens end nearest the screen's left side.
            val w = rOut - rIn - 28f
            if (w < 12f) continue
            val maxW = (w * kk).toInt()
            val color = when {
                p.tone == Tone.Danger -> if (isHot) pal.fg else danger
                p.tone == Tone.Head -> pal.accent
                else -> pal.fg
            }
            val style = when {
                p.tone == Tone.Head -> styles.head
                isHot -> styles.hot
                else -> styles.label
            }
            val main = measurer.measure(p.label, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = maxW))
            val sub = p.sub.takeIf { it.isNotEmpty() && w > 44f }?.let {
                // On a lit lens the muted grey disappears into the accent; use the text colour, softened.
                val subStyle = if (isHot) styles.sub.copy(color = pal.fg.copy(alpha = .78f)) else styles.sub
                measurer.measure(it, subStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
            }
            val ra = if (left) rIn + 14f else rOut - 14f
            val x0 = g.cx - ra * cos(ang)
            val y0 = g.cy + ra * sin(ang)
            withTransform({
                translate((if (left) W - x0 else x0) * kk, y0 * kk)
                rotate(Math.toDegrees((if (left) ang else -ang).toDouble()).toFloat(), Offset.Zero)
            }) {
                val lift = if (sub != null) -6f * kk else 0f
                drawText(main, topLeft = Offset(0f, -main.size.height / 2f + lift), alpha = a)
                if (sub != null) drawText(sub, topLeft = Offset(0f, -sub.size.height / 2f + 8f * kk), alpha = a * .85f)
            }
        }
    }
}

/** The press-harder ring: an arc along the lens's outer edge, [t] of the way round. */
private fun DrawScope.dwellRing(cx: Float, cy: Float, r: Float, mid: Float, half: Float, t: Float, color: Color, k: Float) {
    val deg = (180.0 / PI).toFloat()
    drawArc(
        color, (mid - half) * deg, 2 * half * deg * t, false,
        topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
        style = Stroke(2.6f * k, cap = StrokeCap.Round),
    )
}

/** Small chevrons at both ends of the picked lens: it has options to each side. Radii in design units. */
private fun DrawScope.chevrons(g: Geo, rIn: Float, rOut: Float, ang: Float, left: Boolean, color: Color, k: Float) {
    for ((r, dir) in listOf(rIn to -1f, rOut to 1f)) {
        val x0 = g.cx - r * cos(ang)
        val y0 = g.cy + r * sin(ang)
        // Outward from the dial centre is +x in the turned frame on a left dial, -x on a right one.
        val o = if (left) dir else -dir
        withTransform({
            translate((if (left) W - x0 else x0) * k, y0 * k)
            rotate(Math.toDegrees((if (left) ang else -ang).toDouble()).toFloat(), Offset.Zero)
        }) {
            val path = Path().apply {
                moveTo(o * 1.5f * k, -3.5f * k)
                lineTo(o * 5f * k, 0f)
                lineTo(o * 1.5f * k, 3.5f * k)
            }
            drawPath(path, color, style = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private class PaneStyles(val label: TextStyle, val hot: TextStyle, val head: TextStyle, val sub: TextStyle)
