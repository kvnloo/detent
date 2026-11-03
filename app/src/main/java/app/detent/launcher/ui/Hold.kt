package app.detent.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import app.detent.launcher.Feedback
import app.detent.launcher.Palette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** How long a press must last before a control opens its card. */
const val HOLD_MS = 300L

/**
 * One floating card at a time, shared by every holdable control on the setup screens. Geometry is in root px.
 *
 * The motion grammar, the same everywhere: a press sinks the control and fills its outline (anticipation, with a
 * rising haptic); at the threshold it pops and the card springs out of the control, above the finger so the finger
 * never hides it; the highlight follows the finger with a tick per option; lifting on an option seats it (firm
 * click, the card folds back into the control); lifting elsewhere lets go (soft fall, the card drops away).
 */
@Stable
class HoldHost(val fb: Feedback) {
    var card by mutableStateOf<Card?>(null)
        private set
    /** True when the last card closed with a choice; the exit motion differs. */
    var committed by mutableStateOf(false)
        private set
    var screenW = 0f

    fun open(c: Card) {
        committed = false
        card = c
    }

    fun close(choice: Boolean) {
        committed = choice
        card = null
    }
}

val LocalHold = staticCompositionLocalOf<HoldHost?> { null }

sealed class Card(val anchor: Rect)

/** A list of options stacked above the control. [hi] is the option under the finger, -1 for none. */
class PickCard(anchor: Rect, val title: String?, val note: String?, val options: List<String>, val current: Int, val box: Rect, val row: Float) :
    Card(anchor) {
    var hi by mutableIntStateOf(-1)

    fun optionAt(p: Offset): Int {
        val slack = 36f
        if (p.x < box.left - slack || p.x > box.right + slack) return -1
        if (p.y < box.top - slack || p.y > box.bottom) return -1
        val top = box.bottom - row * options.size
        return floor((p.y - top) / row).toInt().coerceIn(0, options.lastIndex)
    }
}

/** A magnified track above the control. Values run 0 to 1; [stops] are magnetic. */
class SlideCard(anchor: Rect, val label: String, start: Float, val stops: List<Float>, val track: Rect, val format: (Float) -> String) :
    Card(anchor) {
    var value by mutableFloatStateOf(start)
    var snapped by mutableStateOf<Float?>(null)
}

/** A name that floats over the option under the finger while a row is scrubbed. */
class TipCard(anchor: Rect) : Card(anchor) {
    var text by mutableStateOf("")
    var at by mutableStateOf(anchor)
}

/**
 * Press, hold and slide. A quick press is [onTap]. Holding [HOLD_MS] still calls [onHold], then [onMove] for every
 * move and [onUp] at release, with root px positions; those events are taken from children and scrollers. Moving
 * before the threshold gives the gesture back to whatever scrolls. [pressed] drives the sink-and-fill visual.
 */
fun Modifier.holdable(
    fb: Feedback?,
    onTap: (() -> Unit)?,
    onHold: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onUp: (Offset) -> Unit,
    pressed: (Boolean) -> Unit = {},
): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val slop = LocalViewConfiguration.current.touchSlop
    this.onGloballyPositioned { coords = it }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.changedToDown() } ?: continue
                fun root(p: Offset) = coords?.localToRoot(p) ?: p
                pressed(true)
                fb?.charge()
                var held = false
                var gone = false
                val t0 = down.uptimeMillis
                while (!held && !gone) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - t0
                    val ev = withTimeoutOrNull(maxOf(1L, HOLD_MS - elapsed)) { awaitPointerEvent(PointerEventPass.Initial) }
                    if (ev == null) {
                        held = true
                        break
                    }
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: continue
                    if (!c.pressed) {
                        gone = true
                        pressed(false)
                        if (onTap != null) {
                            c.consume()
                            onTap()
                        } else {
                            fb?.cancel()
                        }
                    } else if ((c.position - down.position).getDistance() > slop) {
                        gone = true
                        pressed(false)
                        fb?.cancel()
                    }
                }
                if (!held) continue
                onHold(root(down.position))
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                    ev.changes.forEach { it.consume() }
                    if (c.pressed) {
                        onMove(root(c.position))
                    } else {
                        pressed(false)
                        onUp(root(c.position))
                        break
                    }
                }
            }
        }
    }
}

/** Places a [PickCard] above [anchor], inside the screen. */
fun pickCard(host: HoldHost, u: Units, anchor: Rect, title: String?, note: String?, options: List<String>, current: Int): PickCard {
    val row = 38f * u.k
    val w = (maxOf(options.maxOf { it.length }, title?.length ?: 0, minOf(note?.length ?: 0, 26)) * 8.4f + 64f).coerceIn(150f, 300f) * u.k
    val head = (if (title != null) 30f else 8f) * u.k + (if (note != null) 40f * u.k else 0f)
    val h = head + row * options.size + 8f * u.k
    val margin = 28f * u.k
    val left = (anchor.center.x - w / 2).coerceIn(margin, host.screenW - margin - w)
    val bottom = anchor.top - 12f * u.k
    return PickCard(anchor, title, note, options, current, Rect(left, bottom - h, left + w, bottom), row)
}

fun slideCard(host: HoldHost, u: Units, anchor: Rect, label: String, start: Float, stops: List<Float>, format: (Float) -> String): SlideCard {
    val w = 300f * u.k
    val margin = 28f * u.k
    val left = (anchor.center.x - w / 2).coerceIn(margin, host.screenW - margin - w)
    val bottom = anchor.top - 14f * u.k
    return SlideCard(anchor, label, start, stops, Rect(left, bottom - 76f * u.k, left + w, bottom), format)
}

/**
 * A chip with options: tap does [onTap]; hold opens a card of [options] to pick by sliding. [note] explains the
 * setting in the card, so holding anything answers "what does this do".
 */
@Composable
fun HoldChip(
    text: String,
    on: Boolean,
    pal: Palette,
    options: List<String>,
    current: Int,
    onTap: () -> Unit,
    onPick: (Int) -> Unit,
    title: String? = null,
    note: String? = null,
    square: Boolean = false,
    size: Float = 44f,
    padding: Float = 16f,
    offBg: Color = Color.Transparent,
) {
    val host = LocalHold.current
    val u = LocalUnits.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var down by remember { mutableStateOf(false) }
    ChipFace(text, on, pal, down, square, size, padding, offBg,
        Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }.holdable(
            host?.fb, onTap,
            onHold = { host ?: return@holdable; host.fb.pop(); host.open(pickCard(host, u, anchor, title, note, options, current)) },
            onMove = { p ->
                val c = host?.card as? PickCard ?: return@holdable
                val i = c.optionAt(p)
                if (i != c.hi) {
                    c.hi = i
                    if (i >= 0) host.fb.hover()
                }
            },
            onUp = { p ->
                val c = host?.card as? PickCard ?: return@holdable
                val i = c.optionAt(p)
                if (i >= 0) {
                    host.fb.settle()
                    host.close(true)
                    onPick(i)
                } else {
                    host.fb.letGo()
                    host.close(false)
                }
            },
            pressed = { down = it },
        ),
    )
}

/**
 * A chip with a value from 0 to 1. Tap steps through [stops]; hold turns it into a magnified track that follows
 * the finger from where it is (no jump), sticks to each stop, ticks softly between them and stops hard at the ends.
 */
@Composable
fun SlideChip(
    label: String,
    value: Float,
    pal: Palette,
    stops: List<Float>,
    onTap: () -> Unit,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    padding: Float = 16f,
) {
    val host = LocalHold.current
    val u = LocalUnits.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var down by remember { mutableStateOf(false) }
    var x0 by remember { mutableFloatStateOf(0f) }
    var v0 by remember { mutableFloatStateOf(0f) }
    val shown = (host?.card as? SlideCard)?.takeIf { it.anchor == anchor }?.value ?: value
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        ChipFace("$label ${format(shown)}", shown > 0f, pal, down, false, 44f, padding, Color.Transparent,
            Modifier.holdable(
                host?.fb, onTap,
                onHold = { p ->
                    host ?: return@holdable
                    host.fb.pop()
                    x0 = p.x
                    v0 = value
                    host.open(slideCard(host, u, anchor, label, value, stops, format))
                },
                onMove = { p ->
                    val c = host?.card as? SlideCard ?: return@holdable
                    val raw = (v0 + (p.x - x0) / c.track.width).coerceIn(0f, 1f)
                    val stop = stops.minByOrNull { abs(it - raw) }?.takeIf { abs(it - raw) < .035f }
                    val v = stop ?: raw
                    val was = c.value
                    when {
                        (v == 0f || v == 1f) && was != v && stop == null -> host.fb.edge()
                        stop != null && c.snapped != stop -> host.fb.detent()
                        stop == null && abs(v - was) >= .01f -> host.fb.texture()
                    }
                    c.snapped = stop
                    if (stop != null || abs(v - was) >= .005f || v == 0f || v == 1f) {
                        c.value = v
                        onChange(v)
                    }
                },
                onUp = {
                    val c = host?.card as? SlideCard ?: return@holdable
                    host.fb.settle()
                    host.close(true)
                    onCommit(c.value)
                },
                pressed = { down = it },
            ),
            fill = shown,
        )
    }
}

/**
 * A row whose children are options: tap a child as usual, or hold anywhere on the row and slide across it;
 * [onHover] runs live (the dial previews under the finger) and [onPick] at release. Lifting off the row
 * goes back to [current].
 */
@Composable
fun Modifier.scrubRow(count: Int, current: Int, bounds: Map<Int, Rect>, label: (Int) -> String, onHover: (Int) -> Unit, onPick: (Int) -> Unit): Modifier {
    val host = LocalHold.current ?: return this
    var hi by remember { mutableIntStateOf(-1) }
    fun under(p: Offset): Int {
        for (i in 0 until count) {
            val b = bounds[i] ?: continue
            if (p.x in b.left - 4f..b.right + 4f && p.y in b.top - 80f..b.bottom + 80f) return i
        }
        return -1
    }
    return holdable(host.fb, null,
        onHold = { p ->
            host.fb.pop()
            hi = -1
            val i = under(p)
            val tip = TipCard(bounds[i] ?: Rect(p, Size(1f, 1f)))
            host.open(tip)
            if (i >= 0) {
                hi = i
                tip.text = label(i)
                tip.at = bounds[i]!!
                onHover(i)
            }
        },
        onMove = { p ->
            val tip = host.card as? TipCard ?: return@holdable
            val i = under(p)
            if (i != hi && i >= 0) {
                hi = i
                tip.text = label(i)
                tip.at = bounds[i]!!
                host.fb.hover()
                onHover(i)
            }
        },
        onUp = { p ->
            val i = under(p)
            if (i >= 0) {
                host.fb.settle()
                host.close(true)
                onPick(i)
            } else {
                host.fb.letGo()
                host.close(false)
                onHover(current)
            }
            hi = -1
        },
    )
}

/**
 * The chip look shared by every control: accent fill when on, hairline when off. [down] sinks it and fills
 * its outline over [HOLD_MS] so a hold announces itself before the card opens. [fill] (0 to 1) shows a value.
 */
@Composable
fun ChipFace(
    text: String,
    on: Boolean,
    pal: Palette,
    down: Boolean,
    square: Boolean,
    size: Float,
    padding: Float,
    offBg: Color,
    modifier: Modifier,
    fill: Float? = null,
) {
    val u = LocalUnits.current
    val sink by animateFloatAsState(if (down) .95f else 1f, spring(dampingRatio = .55f, stiffness = 900f), label = "sink")
    val charge = remember { Animatable(0f) }
    LaunchedEffect(down) {
        if (down) charge.animateTo(1f, tween(HOLD_MS.toInt())) else charge.animateTo(0f, tween(160))
    }
    val solid = on
    val border by animateColorAsState(if (on) pal.accent else pal.rim2, tween(200), label = "chipBorder")
    val bg by animateColorAsState(if (solid) pal.accent else offBg, tween(200), label = "chipBg")
    val fg by animateColorAsState(if (solid) pal.onAccent else pal.muted2, tween(200), label = "chipFg")
    val shape = if (square) RoundedCornerShape(u.dp(4f)) else RoundedCornerShape(50)
    val sized = if (square) Modifier.size(u.dp(size)) else Modifier.height(u.dp(40f))
    Box(
        modifier.then(sized).graphicsLayer { scaleX = sink; scaleY = sink }.clip(shape).background(bg)
            .then(
                if (fill != null && fill > 0f) {
                    // The level, as a bar along the bottom of the chip.
                    Modifier.drawBehind {
                        val box = this.size
                        val h = 3f * u.k
                        val inset = box.height * .5f
                        drawRoundRect(pal.onAccent.copy(alpha = .16f), Offset(inset, box.height - h - 5f * u.k), Size(box.width - 2 * inset, h), CornerRadius(h))
                        drawRoundRect(pal.onAccent.copy(alpha = .55f), Offset(inset, box.height - h - 5f * u.k), Size((box.width - 2 * inset) * fill, h), CornerRadius(h))
                    }
                } else {
                    Modifier
                },
            )
            .background(
                (if (solid) pal.onAccent.copy(alpha = .12f) else pal.fg.copy(alpha = .14f)).let { c ->
                    Brush.horizontalGradient(0f to c, charge.value to c, charge.value to Color.Transparent)
                },
            )
            .border(u.dp(1f + charge.value), border, shape)
            .then(if (square) Modifier else Modifier.padding(horizontal = u.dp(padding))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalHalo provides null) {
            Txt(text, if (square) 13f else 12f, fg, weight = if (square) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** Draws the open card, if any. Put it last in a full-screen Box at the root origin. */
@Composable
fun HoldCards(pal: Palette) {
    val host = LocalHold.current ?: return
    val u = LocalUnits.current
    var shown by remember { mutableStateOf<Card?>(null) }
    val a = remember { Animatable(0f) }
    val card = host.card
    LaunchedEffect(card) {
        if (card != null) {
            shown = card
            a.snapTo(0f)
            a.animateTo(1f, spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow))
        } else if (shown != null) {
            a.animateTo(0f, tween(if (host.committed) 150 else 190))
            shown = null
        }
    }
    val c = shown ?: return
    val v = a.value
    androidx.compose.runtime.CompositionLocalProvider(LocalHalo provides null) { CardsAt(c, pal, u, v, host.committed && card == null) }
}

@Composable
private fun CardsAt(c: Card, pal: Palette, u: Units, v: Float, committed: Boolean) {
    // The rest of the screen steps back, so the card is the only thing to look at.
    if (c !is TipCard) Box(Modifier.fillMaxSize().background(pal.bg.copy(alpha = .5f * v.coerceIn(0f, 1f))))
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = v.coerceIn(0f, 1f)
        },
    ) {
        when (c) {
            is PickCard -> PickBody(c, pal, u, v, committed)
            is SlideCard -> SlideBody(c, pal, u, v)
            is TipCard -> TipBody(c, pal, u, v)
        }
    }
}

private fun Modifier.at(r: Rect) = offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }

@Composable
private fun cardSurface(pal: Palette, u: Units) = Modifier
    .shadow(u.dp(18f), RoundedCornerShape(u.dp(18f)), ambientColor = Color.Black, spotColor = Color.Black)
    .clip(RoundedCornerShape(u.dp(18f)))
    .background(pal.bg)
    .background(pal.fg.copy(alpha = .05f))
    .background(Brush.verticalGradient(0f to pal.fg.copy(alpha = .07f), .4f to Color.Transparent))
    .border(u.dp(1f), Brush.verticalGradient(0f to pal.fg.copy(alpha = .28f), 1f to pal.fg.copy(alpha = .08f)), RoundedCornerShape(u.dp(18f)))

@Composable
private fun PickBody(c: PickCard, pal: Palette, u: Units, v: Float, committed: Boolean) {
    val b = c.box
    val hiY by animateFloatAsState(
        if (c.hi >= 0) c.hi.toFloat() else c.current.toFloat(),
        spring(dampingRatio = .8f, stiffness = 1400f), label = "hiY",
    )
    val hiA by animateFloatAsState(if (c.hi >= 0) 1f else 0f, tween(120), label = "hiA")
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.at(b).width(with(density) { b.width.toDp() }).height(with(density) { b.height.toDp() })
            .graphicsLayer {
                val s = if (committed) .92f + .08f * v else .86f + .14f * v
                scaleX = s
                scaleY = s
                translationY = if (committed) 0f else (1 - v) * 10f * u.k
                transformOrigin = TransformOrigin(((c.anchor.center.x - b.left) / b.width).coerceIn(0f, 1f), 1f)
            }
            .then(cardSurface(pal, u)),
    ) {
        var y = 10f
        if (c.title != null) {
            Txt(c.title, 11f, pal.muted2, Modifier.place(u, 16f, y), spacing = .08f)
            y += 22f
        }
        if (c.note != null) {
            Txt(c.note, 11f, pal.fg.copy(alpha = .74f), Modifier.place(u, 16f, y).padding(end = u.dp(16f)), line = 1.35f, maxLines = 2)
            y += 40f
        }
        val rowU = c.row / u.k
        val top = (b.height / u.k) - rowU * c.options.size - 4f
        Box(
            Modifier.place(u, 6f, top + hiY * rowU + 3f).width(with(density) { (b.width - 12f * u.k).toDp() })
                .height(u.dp(rowU - 6f)).graphicsLayer { alpha = hiA }
                .clip(RoundedCornerShape(u.dp(12f))).background(pal.accent),
        )
        c.options.forEachIndexed { i, name ->
            val hot = i == c.hi
            val scale by animateFloatAsState(if (hot) 1.06f else 1f, spring(dampingRatio = .6f, stiffness = 1200f), label = "opt")
            Box(Modifier.place(u, 0f, top + i * rowU).width(with(density) { b.width.toDp() }).height(u.dp(rowU)), contentAlignment = Alignment.CenterStart) {
                Txt(
                    name, 13f, if (hot) pal.onAccent else pal.fg,
                    Modifier.padding(start = u.dp(18f)).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) },
                    weight = if (hot || i == c.current) FontWeight.Medium else FontWeight.Normal,
                )
                if (i == c.current) {
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = u.dp(18f)).size(u.dp(6f)).clip(RoundedCornerShape(50)).background(if (hot) pal.onAccent else pal.accent))
                }
            }
        }
    }
}

@Composable
private fun SlideBody(c: SlideCard, pal: Palette, u: Units, v: Float) {
    val t = c.track
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shown by animateFloatAsState(c.value, spring(dampingRatio = .7f, stiffness = 2200f), label = "slide")
    Box(
        Modifier.at(t).width(with(density) { t.width.toDp() }).height(with(density) { t.height.toDp() })
            .graphicsLayer {
                val s = .9f + .1f * v
                scaleX = s
                scaleY = s
                translationY = (1 - v) * 8f * u.k
                transformOrigin = TransformOrigin(((c.anchor.center.x - t.left) / t.width).coerceIn(0f, 1f), 1f)
            }
            .then(cardSurface(pal, u)),
    ) {
        Txt(c.label, 11f, pal.muted2, Modifier.place(u, 16f, 10f), spacing = .08f)
        Txt(c.format(c.value), 15f, if (c.snapped != null) pal.accent else pal.fg, Modifier.align(Alignment.TopEnd).padding(top = u.dp(7f), end = u.dp(16f)), weight = FontWeight.Medium)
        Canvas(Modifier.fillMaxSize()) {
            val k = u.k
            val x0 = 18f * k
            val x1 = size.width - 18f * k
            val y = size.height - 24f * k
            val w = x1 - x0
            drawRoundRect(pal.fg.copy(alpha = .14f), Offset(x0, y - 3f * k), Size(w, 6f * k), CornerRadius(3f * k))
            drawRoundRect(pal.accent, Offset(x0, y - 3f * k), Size(w * shown, 6f * k), CornerRadius(3f * k))
            for (s in c.stops) {
                val x = x0 + w * s
                val hit = c.snapped == s
                drawCircle(if (shown >= s) pal.onAccent.copy(alpha = .7f) else pal.fg.copy(alpha = .45f), (if (hit) 2.6f else 1.8f) * k, Offset(x, y))
            }
            val kx = x0 + w * shown
            drawCircle(Color.Black.copy(alpha = .25f), 11f * k, Offset(kx, y + 1.5f * k))
            drawCircle(pal.fg, 10f * k, Offset(kx, y))
            drawCircle(pal.accent, (if (c.snapped != null) 5f else 3.5f) * k, Offset(kx, y))
        }
    }
}

@Composable
private fun TipBody(c: TipCard, pal: Palette, u: Units, v: Float) {
    if (c.text.isEmpty()) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = (c.text.length * 8.2f + 28f) * u.k
    val sw = LocalHold.current?.screenW ?: Float.MAX_VALUE
    val x by animateFloatAsState(
        (c.at.center.x - w / 2).coerceIn(16f * u.k, maxOf(16f * u.k, sw - w - 16f * u.k)),
        spring(dampingRatio = .8f, stiffness = 1600f), label = "tipX",
    )
    val y = c.at.top - 44f * u.k
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(with(density) { w.toDp() }).height(u.dp(32f))
            .graphicsLayer {
                scaleX = .9f + .1f * v
                scaleY = .9f + .1f * v
                transformOrigin = TransformOrigin(.5f, 1f)
            }
            .then(cardSurface(pal, u)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(c.text, 12f, pal.fg, weight = FontWeight.Medium, maxLines = 1)
    }
}
