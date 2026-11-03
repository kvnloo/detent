package app.detent.launcher.ui

import app.detent.launcher.GlassFx
import app.detent.launcher.Glass
import app.detent.launcher.GlassLook
import kotlin.math.exp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import app.detent.launcher.Geo
import app.detent.launcher.LauncherState
import app.detent.launcher.Layout
import app.detent.launcher.Markers
import app.detent.launcher.Palette
import app.detent.launcher.QuickIcons
import app.detent.launcher.Step
import app.detent.launcher.THEME_NAMES
import app.detent.launcher.W
import app.detent.launcher.labelScale
import app.detent.launcher.labelPose
import app.detent.launcher.marksFor
import app.detent.launcher.mod
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun DialCanvas(s: LauncherState, pal: Palette, wave: VoiceWave) {
    val u = LocalUnits.current
    val measurer = rememberTextMeasurer(cacheSize = 160)
    val halo = LocalHalo.current
    val regular = remember(u, halo) { textStyle(u, 15f, pal.fg, FontWeight.Normal, spacing = .01f, halo = halo) }
    val medium = remember(u, halo) { textStyle(u, 15f, pal.fg, FontWeight.Medium, spacing = .01f, halo = halo) }

    val scrub = s.scrub
    val themeStep = s.ob == Step.THEME
    val apps = s.dialApps
    val names = scrub ?: if (themeStep) THEME_NAMES else remember(apps) { apps.map { it.display } }
    val dots = if (scrub == null && !themeStep) apps.map { s.hasDot(it) } else null
    val icons = if (scrub == null && !themeStep) remember(apps, s.folderIcons) { apps.map { s.rowIcon(it) } } else null
    // Auto density gives each row one mark per app it holds.
    val counts = if (scrub == null && !themeStep) remember(apps) { apps.map { it.count } } else emptyList()
    val swipe = s.swipeRow?.let { r -> Swipe(mod(r, maxOf(1, names.size)), s.swipeDx, s.swipeArmed) }
    val o = s.open
    val hide = if (o != null && o.fromDial && dots != null) apps.indexOfFirst { it.key == o.key } else -1
    val layout = s.layout
    val left = s.left
    val alpha by animateFloatAsState(
        if (s.ob == Step.WELCOME || s.ob == Step.APPS) 0f else 1f, tween(500), label = "dialAlpha",
    )

    val fx = remember { GlassFx() }
    // The bezel dial is always glass; the others get a glass band when glass is on.
    val look = s.lookNow ?: if (layout.markers == Markers.Bezel) Glass.Clear.look else null

    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val g = Geo(s.radius, u.cy)
        if (look != null) drawGlassBand(fx, g, u.k, pal, layout.markers, left, look)
        drawMarks(s.pos, g, u.k, pal, layout, left, wave, counts)
        drawLabels(s.pos, g, u.k, pal, layout, left, names, dots, icons, hide, scrub != null, measurer, regular, medium, swipe)
        drawNotch(s.pawl, g, u.k, pal, layout.notch, left, fx, look ?: Glass.Clear.look!!)
    }
}

/** A sideways swipe on row [row], [dx] units so far. */
private class Swipe(val row: Int, val dx: Float, val armed: Boolean)

/** A band of lit glass under the marks. The bezel dial gets a wide band; the others a slim one. */
private fun DrawScope.drawGlassBand(fx: GlassFx, g: Geo, k: Float, pal: Palette, m: Markers, left: Boolean, glass: GlassLook) {
    val cx = (if (left) W - g.cx else g.cx) * k
    val (inner, outer) = if (m == Markers.Bezel) 26f to 4f else 14f to 8f
    val tint = glass.tintColor(pal)
    with(fx) { band(cx, g.cy * k, (g.r - inner) * k, (g.r + outer) * k, tint, glass, k) }
}

private fun DrawScope.drawMarks(pos: Float, g: Geo, k: Float, pal: Palette, layout: Layout, left: Boolean, wave: VoiceWave, counts: List<Int>) {
    val m = layout.markers
    fun x(v: Float) = (if (left) W - v else v) * k
    if (m == Markers.Ticks || m == Markers.Nodes) {
        drawCircle(pal.rim, g.r * k, Offset(x(g.cx), g.cy * k), style = Stroke(k))
    }
    if (m == Markers.Spokes) {
        drawCircle(pal.rim2, g.r * k, Offset(x(g.cx), g.cy * k), style = Stroke(.8f * k))
        // A second ring outside the rim, clear of the names.
        drawCircle(pal.rim, (g.r + 9) * k, Offset(x(g.cx), g.cy * k), style = Stroke(.6f * k))
    }
    for (mk in marksFor(pos, g.span, layout.ticksPerApp, counts)) {
        val d = mk.at - pos
        val a = abs(d)
        if (a > g.span) continue
        val ang = d * g.step
        val px = g.cx - g.r * cos(ang)
        val py = g.cy + g.r * sin(ang)
        val major = mk.major
        val hot = major && a < .5f
        val fade = (1 - a / g.span).pow(.8f)
        // While Detent listens, the voice swells the marks. A strong swell takes the accent colour.
        val v = wave.swell(d)
        val hue = (v * 1.4f).coerceIn(0f, 1f)
        when (m) {
            Markers.Ticks -> {
                val w = (if (major) 14f else 6f) + v * 18f
                val sg = if (left) -1f else 1f
                drawLine(
                    lerp(if (major) pal.tickMaj else pal.tickMin, pal.accent, hue),
                    Offset(x(px), py * k),
                    Offset(x(px) + sg * w * cos(ang) * k, (py - w * sin(ang)) * k),
                    strokeWidth = k * (1 + v),
                    alpha = max(fade, hue),
                )
            }
            Markers.Double -> {
                // Outer dots swell toward the notch. A fainter inner arc marks each app.
                val f = max(0f, 1 - a / 3.6f)
                val size = (if (hot) 9f else if (major) 3 + 5 * f else 2 + 2.6f * f) + v * (if (major) 8f else 4f)
                drawCircle(
                    if (hot) pal.accent else lerp(pal.dotMaj, pal.accent, hue), size / 2 * k, Offset(x(px), py * k),
                    alpha = if (hot) 1f else max((.3f + .7f * f) * fade, hue),
                )
                if (major) {
                    val ri = g.r - 16
                    val s2 = 2.5f + 3 * f + v * 4f
                    drawCircle(
                        pal.dotMaj, s2 / 2 * k, Offset(x(g.cx - ri * cos(ang)), (g.cy + ri * sin(ang)) * k),
                        alpha = max((.25f + .5f * f) * fade, hue * .6f),
                    )
                }
            }
            Markers.Bezel -> {
                // Etched into the glass: a dark cut with a light edge under it.
                val len = (if (hot) 18f else if (major) 12f else 5f) + v * 10f
                val r0 = g.r - 2f
                val r1 = r0 - len
                fun at(r: Float) = Offset(x(g.cx - r * cos(ang)), (g.cy + r * sin(ang)) * k)
                val cut = if (hot) pal.accent else lerp(pal.bg, pal.accent, hue)
                drawLine(pal.fg, at(r0 - .8f), at(r1 - .8f), strokeWidth = k * .8f, alpha = .25f * fade)
                drawLine(cut, at(r0), at(r1), strokeWidth = k * (if (major) 1.8f else 1f) + v, alpha = max(if (hot) 1f else .7f * fade, hue))
            }
            Markers.Comet -> {
                // The marks swell into a bright head at the notch and a tail on either side.
                val t = exp(-a * .8f)
                val size = (if (major) 3f + 10f * t else 1.6f + 4f * t) + v * 6f
                val c = if (hot) pal.accent else lerp(pal.dotMaj, pal.accent, max(t * t, hue))
                drawCircle(c, size / 2 * k, Offset(x(px), py * k), alpha = max((.18f + .82f * t) * fade, hue))
            }
            Markers.Spokes -> {
                // A watch bezel: ticks point inward, long at each app, longest at the notch.
                val len = (if (hot) 24f else if (major) 15f else 5f) + v * 12f
                drawLine(
                    if (hot) pal.accent else lerp(if (major) pal.tickMaj else pal.tickMin, pal.accent, hue),
                    Offset(x(px), py * k),
                    Offset(x(px + len * cos(ang)), (py - len * sin(ang)) * k),
                    strokeWidth = k * (if (hot) 2.2f else if (major) 1.5f else .9f) + v,
                    cap = StrokeCap.Round,
                    alpha = max(fade, hue),
                )
            }
            Markers.Wave -> {
                // The marks ride a wave that travels twice as fast as the dial, so turning makes it roll.
                val amp = 7f * (.35f + .65f * exp(-a * .5f)) + v * 10f
                val rr = g.r + amp * sin(mk.at * 1.9f + pos * 1.9f)
                val wx = g.cx - rr * cos(ang)
                val wy = g.cy + rr * sin(ang)
                val size = (if (hot) 8f else if (major) 4.5f else 2.2f) + v * 5f
                drawCircle(
                    if (hot) pal.accent else lerp(if (major) pal.dotMaj else pal.dotMin, pal.accent, hue),
                    size / 2 * k, Offset(x(wx), wy * k), alpha = max(fade, hue),
                )
            }
            else -> {
                val size = (if (m == Markers.Nodes) (if (major) 6f else 2f) else (if (major) 4f else 2f)) + v * 6f
                val c = when {
                    hot -> pal.accent
                    major -> pal.dotMaj
                    m == Markers.Dots -> pal.dotMin
                    else -> pal.nodeMin
                }
                drawCircle(if (hot) c else lerp(c, pal.accent, hue), size / 2 * k, Offset(x(px), py * k), alpha = max(fade, hue))
            }
        }
    }
}

private fun DrawScope.drawLabels(
    pos: Float,
    g: Geo,
    k: Float,
    pal: Palette,
    layout: Layout,
    left: Boolean,
    names: List<String>,
    dots: List<Boolean>?,
    icons: List<String?>?,
    hide: Int,
    scrubbing: Boolean,
    measurer: TextMeasurer,
    regular: TextStyle,
    medium: TextStyle,
    swipe: Swipe?,
) {
    val n = names.size
    if (n == 0) return
    for (i in 0 until n) {
        var d = mod(i - pos, n.toFloat())
        if (d > n / 2f) d -= n
        val a = abs(d)
        if (a >= g.span) continue
        val cl = max(0f, 1 - a)
        val sel = cl > .5f
        val name = names[i]
        val s = if (scrubbing) 1 + .9f * cl * cl else labelScale(a, name.length)
        val p = labelPose(d, name.length, s, g, layout.markers, left)
        // The open app's name is in the title, so its row stays empty while the dial turns.
        val op = if (i == hide) 0f else (1 - a / g.span).pow(1.35f)
        if (op < .004f) continue
        val text = measurer.measure(name, if (sel) medium else regular)
        val icon = icons?.getOrNull(i)
        // An emoji name gives way to its hairline icon, which matches the type and the theme.
        val iconOnly = icon != null && QuickIcons.markOnly(name)
        // The swiped name follows the finger a little, and lights up once the swipe counts.
        val sw = if (swipe != null && swipe.row == i) swipe else null
        val sx = sw?.let { (it.dx * .45f).coerceIn(-48f, 48f) } ?: 0f
        withTransform({
            translate((p.x + sx) * k, p.y * k)
            rotate(Math.toDegrees(p.rot.toDouble()).toFloat(), Offset.Zero)
            scale(s, s, Offset.Zero)
        }) {
            val color = if (sel || sw?.armed == true) pal.accent else pal.fg
            val tw = if (iconOnly) 0f else text.size.width.toFloat()
            if (!iconOnly) drawText(text, color = color, topLeft = Offset(p.tx * tw, -text.size.height / 2f), alpha = op)
            if (icon != null) {
                // The icon sits on the far side of the name from the rim, so the marks stay clear.
                val box = 14f * k
                val gap = 6f * k
                val ix = if (p.tx < 0f) -tw - gap - box else tw + gap
                drawQuickIcon(icon, color.copy(alpha = color.alpha * op), Offset(ix, -box / 2f), box, 1.3f * k)
            }
        }
        if (dots != null && dots[i]) drawCircle(pal.accent, 2.5f * k, Offset(p.bx * k, p.by * k), alpha = op)
    }
}

/** The fixed notch at the selected row. It kicks 4 units outward on every detent. */
private fun DrawScope.drawNotch(pawl: Float, g: Geo, k: Float, pal: Palette, m: Markers, left: Boolean, fx: GlassFx, glass: GlassLook) {
    val off = (if (left) 1f else -1f) * pawl * 4f
    fun x(v: Float) = ((if (left) W - v else v) + off) * k
    val cy = g.cy * k
    when (m) {
        Markers.Ticks -> drawRect(pal.accent, Offset(x(if (left) 242f else 204f), cy - k), Size(38 * k, 2 * k))
        Markers.Nodes -> drawCircle(pal.accent, 9.25f * k, Offset(x(226f), cy), style = Stroke(1.5f * k))
        Markers.Double -> {
            drawCircle(pal.bg, 8f * k, Offset(x(226f), cy))
            drawCircle(pal.accent, 5f * k, Offset(x(226f), cy))
        }
        Markers.Dots -> {
            val centres = floatArrayOf(191.5f, 200f, 209.5f)
            val sizes = floatArrayOf(3f, 4f, 5f)
            val alphas = floatArrayOf(.45f, .7f, 1f)
            for (i in 0..2) drawCircle(pal.accent, sizes[i] / 2 * k, Offset(x(centres[i]), cy), alpha = alphas[i])
        }
        Markers.Bezel -> {
            val c = Offset(x(226f), cy)
            with(fx) { bead(c.x, c.y, 10f * k, glass.tintColor(pal), glass, k) }
            drawCircle(pal.accent, 3.5f * k, c)
        }
        Markers.Comet -> {
            val c = Offset(x(226f), cy)
            drawCircle(pal.accent, 22f * k, c, alpha = .06f)
            drawCircle(pal.accent, 13f * k, c, alpha = .16f)
            drawCircle(pal.accent, 6f * k, c)
        }
        Markers.Spokes -> {
            // A pointer just outside the rim.
            val p = Path().apply {
                moveTo(x(222f), cy)
                lineTo(x(208f), cy - 6f * k)
                lineTo(x(208f), cy + 6f * k)
                close()
            }
            drawPath(p, pal.accent)
        }
        Markers.Wave -> {
            val c = Offset(x(226f), cy)
            drawCircle(pal.accent, 8f * k, c, style = Stroke(1.2f * k), alpha = .55f)
            drawCircle(pal.accent, 4f * k, c)
        }
    }
}
