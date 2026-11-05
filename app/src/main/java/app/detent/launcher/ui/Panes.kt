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
import app.detent.launcher.GlassFx
import app.detent.launcher.LauncherState
import app.detent.launcher.PANE_FLIGHT_MS
import app.detent.launcher.paneFlight
import app.detent.launcher.QuickIcons
import app.detent.launcher.PANE_DWELL
import app.detent.launcher.pillHalf
import app.detent.launcher.pillLength
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
 * The quick panes of a held dial row: glass pills laid along the dial's radii, one dial step each, beside
 * the rim, in Elemental UI's glass: smoky frosted pills with a bright rim over a blurred backdrop, each as
 * long as its label. The picked pill turns solid and bright with dark ink; resting on it draws a short rule
 * under its label, then its side options open over the dial and toward the screen edge. Colours come from
 * the theme palette, so every theme draws them.
 */
@Composable
fun QuickPanes(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    val halo = LocalHalo.current
    var last by remember { mutableStateOf<QuickMenu?>(null) }
    s.menu?.let { if (it.panes != null) last = it }
    val shown = s.menuShown && s.menu?.panes != null
    val open by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 300 else 170, easing = Ease), label = "panesOpen")
    val deep by animateFloatAsState(if (s.paneDeep) 1f else 0f, tween(240, easing = Ease), label = "panesDeep")
    // The press-harder rule: fills over the dwell on the pane under a resting finger.
    val dwellKey = s.paneDwell
    val dwell = remember { Animatable(0f) }
    LaunchedEffect(dwellKey) {
        dwell.snapTo(0f)
        if (dwellKey != null) dwell.animateTo(1f, tween(PANE_DWELL.toInt(), easing = LinearEasing))
    }
    val lift = s.paneLift
    val charge = remember(lift) { Animatable(0f) }
    LaunchedEffect(lift) {
        if (lift != null) charge.animateTo(1f, tween(500, easing = LinearEasing))
    }
    val flight = remember(lift) { Animatable(0f) }
    LaunchedEffect(shown, lift) {
        flight.animateTo(if (shown) 1f else 0f, tween(if (shown) PANE_FLIGHT_MS else 170, easing = LinearEasing))
    }
    if (lift == null && (last == null || (!shown && open <= .001f))) return
    val k by rememberUpdatedState(u.k)
    val fx = remember { GlassFx() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // Elemental UI glass: white text on a smoky pill on dark themes, the theme's ink on a milky one on light themes.
    val onGlass = if (pal.light) pal.fg else Color.White
    val shade = if (pal.light) null else Color.Black.copy(alpha = .35f)
    val ink = Color(0xFF1B1A18)
    val danger = if (pal.light) Color(0xFFB42318) else Color(0xFFFF7A7A)
    val styles = remember(u, pal) {
        PaneStyles(
            label = textStyle(u, 13f, onGlass, FontWeight.Medium, spacing = .01f, halo = shade),
            head = textStyle(u, 14.5f, onGlass, FontWeight.SemiBold, spacing = .01f, halo = shade),
            caption = textStyle(u, 8f, onGlass.copy(alpha = .66f), FontWeight.SemiBold, spacing = .16f, halo = shade),
        )
    }
    val smoke = if (pal.light) mix(pal.bg, Color.White, .4f).copy(alpha = .5f) else mix(pal.bg, Color.Black, .6f).copy(alpha = .36f)
    val rim = Color.White.copy(alpha = if (pal.light) .6f else .3f)
    // The picked pill inverts the glass: bright with dark ink on dark themes, the theme's ink with light text on light ones.
    val solid = if (pal.light) pal.fg.copy(alpha = .92f) else mix(Color.White, pal.accent, .92f).copy(alpha = .93f)
    val hotInk = if (pal.light) pal.bg else ink
    val dangerSolid = Color(0xFFD9443F).copy(alpha = .93f)

    Canvas(
        Modifier.fillMaxSize()
            .then(if (!shown) Modifier else Modifier.pointerInput(s) {
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
            }),
    ) {
        // A light scrim; the wallpaper and dial blur behind (panesVeil), so the pills read without a dark veil.
        drawRect(pal.bg, alpha = open * (if (pal.light) .1f else .22f) * (1f + .3f * deep))
        val kk = u.k
        val g = Geo(s.radius, s.cy, s.layout.ax)
        val left = s.left
        val cx = (if (left) W - g.cx else g.cx) * kk
        val cy = g.cy * kk
        val hw = pillHalf(g.step, s.radius + 14f)
        val hot = s.paneHot
        val travel = paneFlight(flight.value)
        for (slot in s.paneSlots(deep)) {
            // The held label owns the header throughout the transfer, never a fading replacement.
            if (lift != null && slot.col == Col.Main && slot.index == -1) continue
            // Main pills grow out of the rim one after another; side pills grow out of their anchor.
            val t = if (slot.col == Col.Main) (open * 1.6f - abs(slot.d) * .1f).coerceIn(0f, 1f) * travel
            else (deep * 1.5f - slot.index * .16f).coerceIn(0f, 1f)
            if (t <= .01f) continue
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            // With the side options out, the other quick actions step back.
            val dimmed = deep > 0f && slot.col == Col.Main && slot.index != s.paneAnchor
            val a = e * (if (dimmed) 1f - .6f * deep else 1f)
            val ang = slot.d * g.step
            val mid = if (left) ang else (PI.toFloat() - ang)
            val isHot = hot != null && hot.first == slot.col && hot.second == slot.index
            val p = slot.pane
            val head = p.tone == Tone.Head
            val bad = p.tone == Tone.Danger

            // Size the pill to its label (and caption), within its band.
            // Pills toward the screen edge may run past their band, up to 14 units from the edge, so a label fits.
            val c = cos(ang)
            val edge = if (c > .05f) (g.cx - 14f) / c else slot.rOut
            val band = if (slot.col == Col.Out) maxOf(slot.rOut, minOf(edge, slot.rOut + 90f)) - slot.rIn else slot.rOut - slot.rIn
            val maxText = ((band - hw * 2.2f) * kk).toInt().coerceAtLeast(1)
            val textColor = when {
                isHot -> if (bad) Color.White else hotInk
                bad -> danger
                else -> onGlass
            }
            val main = measurer.measure(
                p.label, (if (head) styles.head else styles.label).copy(color = textColor, shadow = if (isHot) null else styles.label.shadow),
                overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxText),
            )
            val cap = p.sub.takeIf { it.isNotEmpty() }?.let {
                measurer.measure(
                    it.uppercase(), styles.caption.copy(color = if (isHot) textColor.copy(alpha = .6f) else styles.caption.color,
                        shadow = if (isHot) null else styles.caption.shadow),
                    overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxText),
                )
            }
            val gap = 7f * kk
            val textW = (main.size.width + (cap?.let { gap + it.size.width } ?: 0f)) / kk
            val len = pillLength(textW, hw, band) * e
            val rIn: Float
            val rOut: Float
            if (slot.col == Col.In) {
                rOut = slot.rOut
                rIn = rOut - len
            } else {
                rIn = slot.rIn
                rOut = rIn + len
            }
            with(fx) {
                pill(
                    cx, cy, rIn * kk, rOut * kk, mid, hw * kk,
                    smoke.copy(alpha = smoke.alpha * a), (if (bad) dangerSolid else solid).let { it.copy(alpha = it.alpha * a) },
                    if (isHot) 1f else 0f, rim.copy(alpha = rim.alpha * a), kk,
                )
            }
            if (isHot && slot.col == Col.Main && deep < .5f && (p.out.isNotEmpty() || p.inn.isNotEmpty())) {
                chevrons(g, rIn - 6f, rOut + 6f, ang, left, onGlass.copy(alpha = .85f * a), kk)
            }
            if (e < .6f) continue
            // The label reads from the pill's end nearest the screen's left side, turned along the radius.
            val pad = hw * 1.05f
            val ra = if (left) rIn + pad else rOut - pad
            val x0 = g.cx - ra * cos(ang)
            val y0 = g.cy + ra * sin(ang)
            val ta = a * ((e - .6f) / .4f)
            val dwellOn = dwellKey != null && dwellKey.first == slot.col && dwellKey.second == slot.index && dwell.value > 0f
            withTransform({
                translate((if (left) W - x0 else x0) * kk, y0 * kk)
                rotate(Math.toDegrees((if (left) ang else -ang).toDouble()).toFloat(), Offset.Zero)
            }) {
                // The caption sits after the label on the same line, small and spaced, like Elemental's eyebrows.
                drawText(main, topLeft = Offset(0f, -main.size.height / 2f), alpha = ta)
                if (cap != null) drawText(cap, topLeft = Offset(main.size.width + gap, -cap.size.height / 2f + .5f * kk), alpha = ta * .9f)
                // Resting: a short rule under the label fills, as Elemental's rule under a title.
                if (dwellOn) {
                    val y = 8.5f * kk
                    val w = maxOf(main.size.width.toFloat(), 18f * kk) * dwell.value
                    drawLine(if (isHot) hotInk.copy(alpha = .7f) else onGlass.copy(alpha = .85f), Offset(0f, y), Offset(w, y),
                        strokeWidth = 1.4f * kk, cap = StrokeCap.Round, alpha = ta)
                }
            }
        }
        if (lift != null) {
            val source = lift.pose
            val iconOnly = lift.icon != null && QuickIcons.markOnly(lift.label)
            val sourceColor = if (lift.selected) pal.accent else pal.fg
            val sourceStyle = textStyle(u, 15f, sourceColor, if (lift.selected) FontWeight.Medium else FontWeight.Normal,
                spacing = .01f, halo = halo)
            val band = bandsForHeader(s.radius, deep)
            // The dial draws the full label. Keep that exact shape at capture, then gradually
            // constrain it to the header band during flight instead of popping into ellipsis.
            val fullLabel = measurer.measure(lift.label, sourceStyle, maxLines = 1)
            val headerWidth = ((band - hw * 2.2f) * kk).toInt().coerceAtLeast(1)
            val width = (fullLabel.size.width * (1f - travel) + headerWidth * travel).toInt().coerceAtLeast(1)
            val label = measurer.measure(lift.label, sourceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = width))
            val tw = if (iconOnly) 14f * kk else label.size.width.toFloat()
            val caption = s.menu?.panes?.head?.sub?.takeIf { it.isNotEmpty() }?.let {
                measurer.measure(it.uppercase(), styles.caption, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (80f * kk).toInt()))
            }
            val targetLen = pillLength((tw + (caption?.let { it.size.width + 7f * kk } ?: 0f)) / kk, hw, band)
            val sourceLen = pillLength(tw / kk, hw, maxOf(band, tw / kk + hw * 2.2f))
            val len = sourceLen + (targetLen - sourceLen) * travel
            val targetX = (if (left) W - (g.cx - (s.radius + 14f + hw)) else g.cx - (s.radius + 14f + len - hw)) * kk
            val x = source.x * kk + (targetX - source.x * kk) * travel
            val y = source.y * kk + (cy - source.y * kk) * travel
            val rot = Math.toDegrees(source.rot.toDouble()).toFloat() * (1f - travel)
            val scale = source.s + (1f - source.s) * travel
            val tx = source.tx * (1f - travel)
            val glass = (charge.value * .72f + flight.value * .28f).coerceIn(0f, 1f)
            val heldInk = if (s.paneHot == (Col.Main to -1)) hotInk else onGlass
            if (s.paneHot == (Col.Main to -1) && travel > .99f && deep < .5f) {
                chevrons(g, s.radius + 8f, s.radius + 20f + len, 0f, left, onGlass, kk)
            }
            withTransform({
                translate(x, y)
                rotate(rot, Offset.Zero)
                scale(scale, scale, Offset.Zero)
            }) {
                // Draw capsule around the original label before moving it along the flight path.
                val inset = hw * kk
                val start = tx * tw - inset
                with(fx) {
                    pill(start, 0f, 0f, len * kk, 0f, hw * kk,
                        smoke.copy(alpha = smoke.alpha * glass), solid.copy(alpha = solid.alpha * glass),
                        if (s.paneHot == (Col.Main to -1)) travel else 0f, rim.copy(alpha = rim.alpha * glass), kk)
                }
                if (s.paneHot == (Col.Main to -1) && deep < .5f && travel > .99f) {
                    drawLine(hotInk.copy(alpha = .65f), Offset(0f, 9f * kk),
                        Offset(maxOf(tw, 18f * kk) * dwell.value, 9f * kk), strokeWidth = 1.4f * kk, cap = StrokeCap.Round)
                }
                if (iconOnly) {
                    // The dial positions icon-only folders beyond the text origin, not at it.
                    val sourceIconX = if (source.tx < 0f) -20f * kk else 6f * kk
                    val iconX = sourceIconX + (tx * tw - sourceIconX) * travel
                    drawQuickIcon(lift.icon!!, androidx.compose.ui.graphics.lerp(sourceColor, heldInk, travel),
                        Offset(iconX, -7f * kk), 14f * kk, 1.3f * kk)
                } else {
                    drawText(label, color = androidx.compose.ui.graphics.lerp(sourceColor, if (s.paneHot == (Col.Main to -1)) hotInk else onGlass, travel),
                        topLeft = Offset(tx * tw, -label.size.height / 2f))
                    lift.icon?.let { icon ->
                        val ix = if (source.tx < 0f) -tw - 20f * kk else tw + 6f * kk
                        drawQuickIcon(icon, androidx.compose.ui.graphics.lerp(sourceColor, onGlass, travel).copy(alpha = 1f - travel),
                            Offset(ix, -7f * kk), 14f * kk, 1.3f * kk)
                    }
                }
                if (caption != null && travel > 0f) {
                    drawText(caption, color = heldInk.copy(alpha = .6f),
                        topLeft = Offset(tw + 7f * kk, -caption.size.height / 2f), alpha = travel)
                }
            }
        }
    }
}

private fun bandsForHeader(radius: Float, deep: Float) = app.detent.launcher.Bands(radius, deep).let { it.mainOut - it.mainIn }

/** Small chevrons at both ends of the picked pill: it has options to each side. Radii in design units. */
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

private class PaneStyles(val label: TextStyle, val head: TextStyle, val caption: TextStyle)
