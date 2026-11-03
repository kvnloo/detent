package app.detent.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.detent.launcher.CY0
import app.detent.launcher.H0
import app.detent.launcher.Palette
import app.detent.launcher.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_light, FontWeight.Light),
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

/**
 * Converts design units to pixels. [hd] is the screen height in design units. [top] and [bottom] are the
 * system bar insets in design units.
 */
@Immutable
class Units(val k: Float, private val density: Float, val hd: Float, val top: Float, val bottom: Float) {
    fun dp(v: Float): Dp = (v * k / density).dp

    /** Font size in sp. The root sets the font scale to 1, so the dial geometry stays exact. */
    fun sp(v: Float): TextUnit = (v * k / density).sp

    val cy get() = CY0 + (hd - H0) / 2f

    /** Moves a y from the design's bottom area so that it keeps its distance to the bottom edge. */
    fun fromBottom(y: Float) = hd - (H0 - y)

    val clockTop get() = max(84f, top + 44f)
    val titleY get() = max(110f, top + 70f)
    val navY get() = min(fromBottom(800f), hd - bottom - 52f)
    val buttonY get() = min(fromBottom(796f), hd - bottom - 56f)
}

val LocalUnits = staticCompositionLocalOf { Units(1f, 1f, H0, 0f, 0f) }

/** A soft shadow behind text that sits on a live wallpaper, so it reads on any part of the picture. Null = none. */
val LocalHalo = staticCompositionLocalOf<Color?> { null }

/** Places a child at a design-unit offset inside a Box. */
fun Modifier.place(u: Units, x: Float, y: Float) =
    offset { IntOffset((x * u.k).roundToInt(), (y * u.k).roundToInt()) }

@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)

fun textStyle(u: Units, size: Float, color: Color, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, line: Float = 1.2f, halo: Color? = null) =
    TextStyle(
        shadow = halo?.let { Shadow(it, Offset.Zero, blurRadius = (4f + size * .5f) * u.k) },
        color = color,
        fontFamily = Mono,
        fontWeight = weight,
        fontSize = u.sp(size),
        letterSpacing = spacing.em,
        lineHeight = u.sp(size * line),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

@Composable
fun Txt(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    spacing: Float = 0f,
    line: Float = 1.2f,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val u = LocalUnits.current
    BasicText(
        text,
        modifier,
        style = textStyle(u, size, color, weight, spacing, line, LocalHalo.current).copy(textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A full-width row at [top] that holds its content on the side away from the dial. */
@Composable
fun SideBox(left: Boolean, top: Float, margin: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val u = LocalUnits.current
    Box(modifier.fillMaxWidth().place(u, 0f, top).padding(horizontal = u.dp(margin))) {
        Box(Modifier.align(if (left) Alignment.TopEnd else Alignment.TopStart), content = content)
    }
}

/** The design's selectable chip: accent fill when on, a hairline outline when off. */
@Composable
fun Chip(
    text: String,
    on: Boolean,
    pal: Palette,
    onClick: () -> Unit,
    square: Boolean = false,
    padding: Float = 16f,
    weight: FontWeight = FontWeight.Normal,
    offBg: Color = Color.Transparent,
    ms: Int = 200,
    size: Float = 44f,
) {
    val u = LocalUnits.current
    val border by animateColorAsState(if (on) pal.accent else pal.rim2, tween(ms), label = "chipBorder")
    val bg by animateColorAsState(if (on) pal.accent else offBg, tween(ms), label = "chipBg")
    val fg by animateColorAsState(if (on) pal.onAccent else pal.muted2, tween(ms), label = "chipFg")
    val shape = if (square) RoundedCornerShape(u.dp(4f)) else RoundedCornerShape(50)
    val sized = if (square) Modifier.size(u.dp(size)) else Modifier.height(u.dp(40f)).padding(horizontal = 0.dp)
    Box(
        sized.clip(shape).background(bg).border(u.dp(1f), border, shape).tap(onClick = onClick)
            .then(if (square) Modifier else Modifier.padding(horizontal = u.dp(padding))),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, if (square) 13f else 12f, fg, weight = if (square) FontWeight.Medium else weight, maxLines = 1)
    }
}
