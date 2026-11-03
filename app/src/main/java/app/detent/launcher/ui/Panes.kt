package app.detent.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import app.detent.launcher.PANE_HALF
import app.detent.launcher.Palette
import app.detent.launcher.QuickMenu
import app.detent.launcher.Tone
import app.detent.launcher.W
import app.detent.launcher.mix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The quick panes of a held dial row: glass slices of rings round the dial centre, one dial step each,
 * beside the rim. The picked pane lights up in the accent; a deep press brings side panes out toward
 * the screen edge and over the dial. Colours come from the theme palette and the glass look, so every
 * theme and glass preset (off included) draws them.
 */
@Composable
fun QuickPanes(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    var last by remember { mutableStateOf<QuickMenu?>(null) }
    s.menu?.let { if (it.panes != null) last = it }
    val shown = s.menuShown && s.menu?.panes != null
    val open by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 320 else 180, easing = Ease), label = "panesOpen")
    val deep by animateFloatAsState(if (s.paneDeep) 1f else 0f, tween(240, easing = Ease), label = "panesDeep")
    if (last == null || (!shown && open <= .001f)) return
    val k by rememberUpdatedState(u.k)
    val fx = remember { GlassFx() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val halo = LocalHalo.current
    val styles = remember(u, halo, pal) {
        PaneStyles(
            label = textStyle(u, 13f, pal.fg, FontWeight.Normal, spacing = .01f, halo = halo),
            head = textStyle(u, 15f, pal.fg, FontWeight.Medium, spacing = .01f, halo = halo),
            sub = textStyle(u, 10f, pal.muted, FontWeight.Normal, spacing = .02f, halo = halo),
        )
    }
    val look = s.lookNow ?: Glass.Clear.look!!
    // Glass tint for this theme. A clear look still gets a body dark or light enough to read the names on.
    val body = mix(look.tintColor(pal).copy(alpha = 1f), pal.bg, .55f).copy(alpha = max(.38f, look.tintColor(pal).alpha + .3f))
    val hotBody = mix(pal.accent, pal.bg, .62f).copy(alpha = .62f)

    Canvas(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }
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
        // The veil quiets the dial under the panes, more so once the side panes cover it.
        drawRect(pal.veil, alpha = open * (.7f + .25f * deep))
        val kk = u.k
        val g = Geo(s.radius, s.cy, s.layout.ax)
        val left = s.left
        val cx = (if (left) W - g.cx else g.cx) * kk
        val cy = g.cy * kk
        val half = PANE_HALF * g.step
        val hot = s.paneHot
        for (slot in s.paneSlots(deep)) {
            // Main panes grow out of the rim one after another; side panes slide out with the deep press.
            val t = if (slot.col == Col.Main) ((open * 1.5f) - abs(slot.d) * .06f).coerceIn(0f, 1f)
            else (deep * 1.3f - slot.index * .12f).coerceIn(0f, 1f)
            if (t <= .01f) continue
            // With the side panes out, the other quick actions step back.
            val dimmed = deep > 0f && slot.col == Col.Main && slot.index != s.paneAnchor
            val rim = g.r
            val rIn = rim + (slot.rIn - rim) * t
            val rOut = rim + (slot.rOut - rim) * t
            val ang = slot.d * g.step
            // Screen angle of the pane centre, seen from the dial centre.
            val mid = if (left) ang else (PI.toFloat() - ang)
            val isHot = hot != null && hot.first == slot.col && hot.second == slot.index
            val tint = when {
                isHot -> hotBody
                slot.pane.tone == Tone.Head -> body.copy(alpha = body.alpha * .7f)
                else -> body
            }
            val a = t * (if (dimmed) .45f else 1f)
            with(fx) {
                slice(
                    cx, cy, rIn * kk, rOut * kk, mid, half, 12f * kk,
                    tint.copy(alpha = tint.alpha * a), pal.rim2.copy(alpha = pal.rim2.alpha * a), look, kk,
                )
            }
            // The name reads from the outer end of the pane toward the dial, turned like the dial names.
            val ra = rOut - 12f
            val x0 = g.cx - ra * cos(ang)
            val y0 = g.cy + ra * sin(ang)
            val maxW = ((rOut - rIn - 24f) * kk).toInt().coerceAtLeast(1)
            val p = slot.pane
            val color = when {
                isHot -> pal.accent
                p.tone == Tone.Setting -> pal.fg.copy(alpha = .85f)
                p.tone == Tone.Head -> pal.accent
                else -> pal.fg
            }
            val main = measurer.measure(
                p.label, (if (p.tone == Tone.Head) styles.head else styles.label).copy(color = color),
                overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW),
            )
            val sub = p.sub.takeIf { it.isNotEmpty() && rOut - rIn > 70f }?.let {
                measurer.measure(it, styles.sub, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
            }
            val tx = if (left) -1f else 0f
            withTransform({
                translate((if (left) W - x0 else x0) * kk, y0 * kk)
                rotate(Math.toDegrees((if (left) ang else -ang).toDouble()).toFloat(), Offset.Zero)
            }) {
                val lift = if (sub != null) -6.5f * kk else 0f
                drawText(main, topLeft = Offset(tx * main.size.width, -main.size.height / 2f + lift), alpha = a)
                if (sub != null) drawText(sub, topLeft = Offset(tx * sub.size.width, -sub.size.height / 2f + 8.5f * kk), alpha = a * .9f)
            }
        }
    }
}

private class PaneStyles(val label: TextStyle, val head: TextStyle, val sub: TextStyle)
