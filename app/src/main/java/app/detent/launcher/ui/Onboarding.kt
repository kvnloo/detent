package app.detent.launcher.ui

import kotlin.math.abs
import app.detent.launcher.Glass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateMapOf
import app.detent.launcher.curveName
import app.detent.launcher.Markers
import app.detent.launcher.DENSITIES
import app.detent.launcher.CUSTOM
import app.detent.launcher.CURVES
import androidx.compose.foundation.horizontalScroll
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.detent.launcher.Ease
import app.detent.launcher.Kind
import app.detent.launcher.LAYOUTS
import app.detent.launcher.LauncherState
import app.detent.launcher.Palette
import app.detent.launcher.Side
import app.detent.launcher.Step
import app.detent.launcher.ThemeDef
import kotlinx.coroutines.launch

private class Copy(val counter: String, val title: String, val body: String)

private fun copy(step: Int, custom: Boolean, grid: Boolean) = when (step) {
    Step.WELCOME -> Copy("", "dial", "your apps sit on a wheel at the edge. turn it to choose, tap to open.")
    Step.SHAPE -> Copy("1 / 4", "shape", "pick a layout and a side, then turn the dial to try it.")
    Step.THEME -> if (grid) Copy("2 / 4", "theme", "turn the dial to pick the colours of the grid.")
    else if (custom) Copy("2 / 4", "theme", "turn the dial to pick the colours. your wallpaper stays.")
    else Copy("2 / 4", "theme", "turn the dial to try one. each brings its own wallpaper.")
    Step.APPS -> Copy("3 / 4", "apps", "pick what goes on the dial. the rest stay one search away.")
    Step.FEEL -> Copy("4 / 4", "feel", "a click and a tick for every detent. dots mark new notifications. pick the bar at the top and the buttons at the bottom.")
    else -> Copy("", "ready", "drag up from the bottom to search. hold the clock to change your setup. hold an app for shortcuts, or the dial to jump by letter.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Onboarding(s: LauncherState, pal: Palette, theme: ThemeDef) {
    val u = LocalUnits.current
    val step = s.ob ?: return
    val c = copy(step, s.customWall != null, s.layout.grid)

    // Each step fades in and rises 8 units.
    val fade = remember { Animatable(1f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(s.obSeq) {
        fade.snapTo(0f)
        rise.snapTo(0f)
        launch { fade.animateTo(1f, tween(350)) }
        rise.animateTo(1f, tween(400, easing = Ease))
    }
    val enter = Modifier.graphicsLayer {
        alpha = fade.value
        translationY = (1 - rise.value) * 8f * u.k
    }

    // The feel step has more rows of chips.
    val controlsY = u.navY - when {
        step == Step.FEEL && s.glassOpen -> 334f
        step == Step.FEEL -> if (s.layout.grid) 232f else 280f
        step == Step.SHAPE && s.customOpen -> 300f
        // Two rows of layout numbers.
        step == Step.SHAPE -> 216f
        else -> 170f
    }
    // Over a live wallpaper, secondary text is the text colour at lower opacity, with a halo, rather than a
    // fixed grey that disappears into the midtones of the picture.
    val see = s.seeThrough
    val dim = if (see) pal.fg.copy(alpha = .8f) else pal.muted2
    val faint = if (see) pal.fg.copy(alpha = .58f) else pal.muted
    Box(Modifier.fillMaxSize()) {
        if (step == Step.APPS) Box(Modifier.fillMaxSize().background(pal.veil))
        if (step == Step.SHAPE || step == Step.THEME || step == Step.FEEL) {
            val fadeH = 150f
            Box(
                Modifier.fillMaxWidth().place(u, 0f, controlsY - fadeH).height(u.dp(u.hd - controlsY + fadeH))
                    // A long eased fade into the panel (no shelf); solid from the first row down.
                    .background(
                        Brush.verticalGradient(
                            0f to pal.bg.copy(alpha = 0f),
                            (fadeH * .45f / (u.hd - controlsY + fadeH)) to pal.bg.copy(alpha = .35f),
                            (fadeH * .8f / (u.hd - controlsY + fadeH)) to pal.bg.copy(alpha = .82f),
                            (fadeH / (u.hd - controlsY + fadeH)) to pal.bg,
                            1f to pal.bg,
                        ),
                    ),
            )
        }

        SideBox(s.left, 84f, 28f, enter) {
            Column(Modifier.width(u.dp(170f)), verticalArrangement = Arrangement.spacedBy(u.dp(14f))) {
                val align = if (s.left) TextAlign.End else TextAlign.Start
                if (s.obEdit && step in Step.SHAPE..Step.FEEL) {
                    // Editing: the steps are tabs, so one change needs one screen.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u.dp(10f), if (s.left) Alignment.End else Alignment.Start)) {
                        listOf("shape" to Step.SHAPE, "theme" to Step.THEME, "apps" to Step.APPS, "feel" to Step.FEEL).forEach { (name, n) ->
                            Txt(
                                name, 11f, if (n == step) pal.accent else faint, Modifier.tap { s.jumpStep(n) }, spacing = .08f,
                                weight = if (n == step) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                } else {
                    Txt(c.counter, 11f, faint, Modifier.fillMaxWidth().heightIn(min = u.dp(14f)), spacing = .08f, align = align)
                }
                Txt(c.title, 52f, pal.fg, Modifier.fillMaxWidth(), weight = FontWeight.Light, spacing = -.05f, line = 1f, align = align)
                Txt(c.body, 13f, dim, Modifier.fillMaxWidth(), line = 1.6f, align = align)
            }
        }

        if (step == Step.APPS) AppChips(s, pal, enter, bottom = u.navY - 20f)

        Column(
            Modifier.fillMaxWidth().place(u, 0f, controlsY).padding(horizontal = u.dp(28f)),
            verticalArrangement = Arrangement.spacedBy(u.dp(12f)),
            horizontalAlignment = if (s.left) Alignment.End else Alignment.Start,
        ) {
            val gap = Arrangement.spacedBy(u.dp(8f))
            when (step) {
                Step.SHAPE -> {
                    if (s.customOpen) {
                        CustomRows(s, pal)
                    } else {
                        // Tap a number, or hold anywhere on the numbers and slide: the dial changes under the finger.
                        val bounds = remember { mutableStateMapOf<Int, Rect>() }
                        val names = LAYOUTS.map { it.name }
                        FlowRow(
                            Modifier.scrubRow(LAYOUTS.size, s.cfg.layout, bounds, { names[it] }, { s.previewLayout(it) }, { s.previewLayout(it) }),
                            horizontalArrangement = Arrangement.spacedBy(u.dp(6f)), verticalArrangement = Arrangement.spacedBy(u.dp(6f)),
                        ) {
                            LAYOUTS.indices.forEach { i ->
                                Box(Modifier.onGloballyPositioned { bounds[i] = it.boundsInRoot() }) {
                                    Chip(if (i == CUSTOM) "+" else "${i + 1}", s.cfg.layout == i, pal, { s.setLayout(i) }, square = true, size = 34f)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = gap) {
                        Chip("right", s.cfg.side == Side.Right, pal, { s.setSide(Side.Right) })
                        Chip("left", s.cfg.side == Side.Left, pal, { s.setSide(Side.Left) })
                        Chip(if (s.customOpen) "presets" else "customize", s.customOpen, pal, { s.toggleCustomize() })
                    }
                }
                Step.THEME -> {
                    Row(horizontalArrangement = gap) {
                        Chip("phone", theme.group == "phone", pal, { s.jumpGroup("phone") }, weight = FontWeight.Medium)
                        Chip("omarchy", theme.group == "omarchy", pal, { s.jumpGroup("omarchy") }, weight = FontWeight.Medium)
                    }
                    // The grid layouts have no wallpaper.
                    if (!s.layout.grid) Row(horizontalArrangement = gap) {
                        Chip("theme wallpaper", s.customWall == null, pal, { s.useThemeWallpaper() })
                        Chip("your wallpaper", s.customWall != null, pal, { s.pickWallpaper() })
                    }
                }
                Step.FEEL -> if (s.glassOpen) GlassRows(s, pal) else FeelRows(s, pal)
                Step.READY -> if (!s.isHome) Row(Modifier.padding(top = u.dp(42f))) {
                    Chip("set as home app", false, pal, { s.requestHome() })
                }
            }
            val pick = when (step) {
                Step.SHAPE -> LAYOUTS[s.cfg.layout].name
                // The readout dial has no names, so the theme name goes here.
                Step.THEME -> (if (s.kind == Kind.Readout) theme.name + " · " else "") +
                    theme.group + " · " + if (theme.light) "light" else "dark"
                Step.FEEL -> when {
                    s.glassOpen -> if (s.seeThrough) "hold a value and slide to set it" else "glass needs a see-through theme"
                    s.dots && !s.notifAccess -> "dots need notification access"
                    s.ownBar -> "drag down from the top for notifications"
                    s.lockWall -> "the lock screen uses the theme"
                    else -> ""
                }
                Step.READY -> if (s.isHome) "detent is your home app" else ""
                else -> ""
            }
            Txt(if (step == Step.FEEL && pick.isEmpty()) "hold any setting for its options" else pick, 12f, dim, Modifier.heightIn(min = u.dp(16f)), spacing = .02f)
        }

        Nav(s, pal, step)
    }
}

private val ONOFF = listOf("on", "off")

/** Feel settings. Tap toggles or steps; hold opens the options in a card, with a line on what the setting does. */
@Composable
private fun FeelRows(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    val gap = Arrangement.spacedBy(u.dp(8f))
    fun pickOn(now: Boolean, flip: () -> Unit): (Int) -> Unit = { i -> if ((i == 0) != now) flip() }
    Column(verticalArrangement = gap, horizontalAlignment = if (s.left) Alignment.End else Alignment.Start) {
        Row(horizontalArrangement = gap) {
            HoldChip("sound", s.sound, pal, ONOFF, if (s.sound) 0 else 1, { s.toggleSound() }, pickOn(s.sound) { s.toggleSound() },
                title = "sound", note = "a click for every app that passes the notch")
            HoldChip("haptics", s.haptics, pal, ONOFF, if (s.haptics) 0 else 1, { s.toggleHaptics() }, pickOn(s.haptics) { s.toggleHaptics() },
                title = "haptics", note = "ticks that follow the dial's speed, and a pop when a hold opens")
            HoldChip("dots", s.dots && s.notifAccess, pal, ONOFF, if (s.dots) 0 else 1, { s.toggleDots() }, pickOn(s.dots) { s.toggleDots() },
                title = "dots", note = "a dot beside apps with new notifications")
            HoldChip("lock screen", s.lockWall, pal, ONOFF, if (s.lockWall) 0 else 1, { s.toggleLockWall() }, pickOn(s.lockWall) { s.toggleLockWall() },
                title = "lock screen", note = "use the theme's colours on the lock screen")
        }
        Row(horizontalArrangement = gap) {
            HoldChip("bar · ${if (s.ownBar) "detent" else "system"}", true, pal, listOf("detent", "system"), if (s.ownBar) 0 else 1,
                { s.setBar(!s.ownBar) }, { s.setBar(it == 0) },
                title = "status bar", note = "detent's own bar, or the phone's")
            SlideChip("grid", s.gridVis, pal, listOf(0f, .2f, .4f, .6f, .8f, 1f), { s.cycleGrid() },
                { s.setGrid(it, false) }, { s.setGrid(it, true) })
            HoldChip("glass · ${s.glass.label}", s.glass.on, pal, Glass.PRESETS.map { it.label }, Glass.PRESETS.indexOf(s.glass),
                { s.toggleGlassPanel() }, { s.pickGlass(Glass.PRESETS[it]) },
                title = "glass", note = "tap to tune it")
        }
        if (!s.layout.grid) Row(horizontalArrangement = gap) {
            HoldChip("tap off the dial opens selected", s.tapOpens, pal, ONOFF, if (s.tapOpens) 0 else 1,
                { s.toggleTapOpens() }, pickOn(s.tapOpens) { s.toggleTapOpens() },
                title = "tap off the dial", note = "on: opens the selected app. off: picks the row level with the tap")
        }
        Row(horizontalArrangement = gap) {
            HoldChip("voice", s.voice, pal, ONOFF, if (s.voice) 0 else 1, { s.toggleVoice() }, pickOn(s.voice) { s.toggleVoice() },
                title = "voice", note = "a mic button at the bottom of home")
            if (s.voice) {
                val mics = s.micChoices()
                HoldChip("mic · ${s.micLabel()}", s.micApp != null, pal, mics.map { it.second }, mics.indexOfFirst { it.first == s.micApp },
                    { s.cycleMic() }, { s.setMic(mics[it].first) },
                    title = "the mic opens")
            }
            HoldChip("web search", s.webSearch, pal, ONOFF, if (s.webSearch) 0 else 1, { s.toggleWebSearch() }, pickOn(s.webSearch) { s.toggleWebSearch() },
                title = "web search", note = "search the web from the app search")
        }
    }
}

/**
 * The glass panel: a starting look, then four values. Each value is a slider chip: tap steps, hold and slide
 * to set it, sticky at its marks. The dial and the wallpaper blur change live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassRows(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    val gap = Arrangement.spacedBy(u.dp(8f))
    val l = s.look
    val quarters = listOf(0f, .25f, .5f, .75f, 1f)
    fun stepUp(v: Float) = quarters.firstOrNull { it > v + .01f } ?: 0f
    Column(verticalArrangement = gap, horizontalAlignment = if (s.left) Alignment.End else Alignment.Start) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(u.dp(6f), if (s.left) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(u.dp(6f)),
        ) {
            Glass.entries.filter { it != Glass.Custom || s.glass == Glass.Custom }.forEach { g ->
                Chip(g.label, s.glass == g, pal, { s.haptic.on(); s.pickGlass(g) }, padding = 13f)
            }
        }
        // Tint runs smoke (left) through clear (middle) to milk (right).
        val tintV = (l.tint + 1f) / 2f
        val tintName: (Float) -> String = { v ->
            val t = v * 2f - 1f
            when {
                abs(t) < .02f -> "clear"
                t < 0f -> "smoke ${(-t * 100).roundToInt()}"
                else -> "milk ${(t * 100).roundToInt()}"
            }
        }
        Row(horizontalArrangement = gap) {
            SlideChip("tint", tintV, pal, listOf(0f, .25f, .5f, .75f, 1f), { s.setLook(l.copy(tint = stepUp(tintV) * 2f - 1f), true) },
                { s.setLook(s.look.copy(tint = it * 2f - 1f), false) }, { s.setLook(s.look.copy(tint = it * 2f - 1f), true) },
                format = tintName, padding = 14f)
            SlideChip("frost", l.frost, pal, quarters, { s.setLook(l.copy(frost = stepUp(l.frost)), true) },
                { s.setLook(s.look.copy(frost = it), false) }, { s.setLook(s.look.copy(frost = it), true) }, padding = 14f)
        }
        Row(horizontalArrangement = gap) {
            SlideChip("light", l.light, pal, quarters, { s.setLook(l.copy(light = stepUp(l.light)), true) },
                { s.setLook(s.look.copy(light = it), false) }, { s.setLook(s.look.copy(light = it), true) }, padding = 14f)
            SlideChip("fringe", l.fringe, pal, quarters, { s.setLook(l.copy(fringe = stepUp(l.fringe)), true) },
                { s.setLook(s.look.copy(fringe = it), false) }, { s.setLook(s.look.copy(fringe = it), true) }, padding = 14f)
        }
        Row(horizontalArrangement = gap) {
            Chip("← feel", false, pal, { s.toggleGlassPanel() })
        }
    }
}

/** Mix and match: curve, marks, notch and density, each its own row. Any change makes the custom dial. */
@Composable
private fun CustomRows(s: LauncherState, pal: Palette) {
    val u = LocalUnits.current
    val l = s.layout
    val radial = !l.grid
    /** A row of options; hold and slide across it to try each on the dial, lift to keep one. */
    @Composable
    fun row(label: String, names: List<String>, current: Int, apply: (Int, Boolean) -> Unit) {
        val bounds = remember { mutableStateMapOf<Int, Rect>() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u.dp(6f))) {
            Txt(label, 11f, if (s.seeThrough) pal.fg.copy(alpha = .62f) else pal.muted, Modifier.width(u.dp(54f)), spacing = .06f)
            Row(
                Modifier.horizontalScroll(rememberScrollState())
                    .scrubRow(names.size, current, bounds, { names[it] }, { apply(it, true) }, { apply(it, true) }),
                horizontalArrangement = Arrangement.spacedBy(u.dp(6f)),
            ) {
                names.forEachIndexed { i, n ->
                    Box(Modifier.onGloballyPositioned { bounds[i] = it.boundsInRoot() }) {
                        Chip(n, radial && i == current, pal, { apply(i, false) }, padding = 12f, square = n.length <= 2, size = 34f)
                    }
                }
            }
        }
    }
    row("curve", CURVES.map { it.first }, CURVES.indexOfFirst { it.first == curveName(l.radius) }) { i, q -> s.customize(radius = CURVES[i].second, quiet = q) }
    val marks = Markers.entries
    row("marks", marks.map { it.name.lowercase() }, marks.indexOf(l.markers)) { i, q -> s.customize(marks = marks[i], quiet = q) }
    row("notch", marks.map { it.name.lowercase() }, marks.indexOf(l.notch)) { i, q -> s.customize(notch = marks[i], quiet = q) }
    row("density", DENSITIES.map { if (it == 0) "auto" else "$it" }, DENSITIES.indexOf(l.ticksPerApp)) { i, q -> s.customize(perApp = DENSITIES[i], quiet = q) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppChips(s: LauncherState, pal: Palette, enter: Modifier, bottom: Float) {
    val u = LocalUnits.current
    val top = 262f
    val n = s.dialApps.size
    val all = s.repo.apps
    Column(
        enter.fillMaxWidth().place(u, 0f, top).height(u.dp(bottom - top)).padding(horizontal = u.dp(24f)),
        verticalArrangement = Arrangement.spacedBy(u.dp(14f)),
    ) {
        FlowRow(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(u.dp(6f)),
            verticalArrangement = Arrangement.spacedBy(u.dp(6f)),
        ) {
            all.forEach { app ->
                Chip(app.display, app.key in s.dialKeys, pal, { s.toggleApp(app.key) }, padding = 13f, offBg = pal.chipBg, ms = 150)
            }
        }
        val warn = s.warn
        val color by animateColorAsState(if (warn) pal.accent else pal.muted2, tween(200), label = "countColor")
        Txt(
            if (warn) "keep at least 3 on the dial" else "$n on the dial · ${all.size - n} in search",
            12f, color, Modifier.padding(horizontal = u.dp(4f)),
        )
    }
}

@Composable
private fun Nav(s: LauncherState, pal: Palette, step: Int) {
    val u = LocalUnits.current
    val showBack = step != Step.WELCOME && step != Step.READY
    val back = if (step == Step.SHAPE && s.obEdit) "cancel" else "back"
    val next = when (step) {
        Step.WELCOME -> "begin"
        Step.FEEL -> if (s.obEdit) "done" else "next"
        Step.SHAPE, Step.THEME, Step.APPS -> if (s.obEdit) "done" else "next"
        Step.READY -> "go home"
        else -> "next"
    }
    Box(Modifier.fillMaxWidth().place(u, 16f, u.navY).padding(end = u.dp(44f)).height(u.dp(44f))) {
        if (showBack) {
            Box(
                Modifier.align(Alignment.CenterStart).height(u.dp(44f)).tap { s.obBack() }.padding(horizontal = u.dp(12f)),
                contentAlignment = Alignment.Center,
            ) { Txt(back, 13f, pal.muted2) }
        }
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val bg by animateColorAsState(pal.accent, tween(300), label = "nextBg")
        Row(
            Modifier.align(Alignment.CenterEnd).height(u.dp(44f))
                .graphicsLayer { val sc = if (pressed) .97f else 1f; scaleX = sc; scaleY = sc }
                .clip(RoundedCornerShape(50)).background(bg)
                .clickable(source, null) { s.obNext() }
                .padding(horizontal = u.dp(22f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u.dp(10f)),
        ) {
            Txt(next, 13f, pal.onAccent, weight = FontWeight.Medium)
            Txt("→", 13f, pal.onAccent, weight = FontWeight.Medium)
        }
    }
}
