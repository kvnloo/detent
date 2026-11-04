package app.detent.launcher

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * How the glass looks, each value 0 to 1 except [tint] (-1 smoke to +1 milk, 0 clear).
 * [frost] blurs the wallpaper behind the whole window (Android blurs per window, not per shape),
 * [light] is the rim, specular and bevel, and [fringe] the chromatic edge.
 */
data class GlassLook(val tint: Float, val frost: Float, val light: Float, val fringe: Float) {
    /** The colour laid over the glass shapes. Milk lifts toward the text colour; smoke sinks toward the background. */
    fun tintColor(p: Palette): Color = when {
        tint > 0f -> p.fg.copy(alpha = .05f + .2f * tint)
        tint < 0f -> p.bg.copy(alpha = .14f + .5f * -tint)
        else -> p.fg.copy(alpha = .035f)
    }

    /** The veil over the whole screen. Smoke darkens it; clear and milk keep the wallpaper bright. */
    val veil get() = if (tint < 0f) .08f + .3f * -tint else .04f

    val fresnel get() = .12f + .7f * light
    val specular get() = .15f + .95f * light
    val bevel get() = .06f + .3f * light
    val noise get() = .05f * frost
    val chroma get() = fringe

    /** Window blur in px. A full frost is about 56 px on a 1440 px wide screen. */
    fun blurPx(widthPx: Int) = (frost * 56f * widthPx / 1440f).toInt()

    fun encode() = "$tint|$frost|$light|$fringe"

    companion object {
        fun decode(s: String?): GlassLook? {
            val v = s?.split('|')?.map { it.toFloatOrNull() } ?: return null
            if (v.size != 4 || v.any { it == null }) return null
            return GlassLook(v[0]!!.coerceIn(-1f, 1f), v[1]!!.coerceIn(0f, 1f), v[2]!!.coerceIn(0f, 1f), v[3]!!.coerceIn(0f, 1f))
        }
    }
}

/**
 * Starting points, after the hyprglass presets on the desktop. Moving any value makes [Custom].
 * Milk is new: the frosted white of real glass over a dark wallpaper.
 */
enum class Glass(val label: String, val look: GlassLook?) {
    Off("off", null),
    Clear("clear", GlassLook(.1f, .12f, .6f, 0f)),
    Frost("frost", GlassLook(.3f, .6f, .5f, 0f)),
    Milk("milk", GlassLook(.65f, .85f, .45f, 0f)),
    Smoke("smoke", GlassLook(-.6f, .5f, .3f, 0f)),
    Prism("prism", GlassLook(.08f, .3f, .85f, .7f)),
    Custom("custom", null);

    val on get() = this != Off

    companion object {
        val PRESETS = listOf(Off, Clear, Frost, Milk, Smoke, Prism)
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Off
    }
}

private const val SRC = """
uniform float2 c;
uniform float rIn;
uniform float rOut;
uniform half4 tint;
uniform float fresnel;
uniform float specular;
uniform float bevel;
uniform float noise;
uniform float chroma;
uniform float bw;

float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

// Distance inside the band, positive inside.
float inside(float d, float grow) { return min(d - rIn + grow, rOut - d + grow); }

half4 main(float2 p) {
    float2 v = p - c;
    float d = length(v);
    float e = inside(d, 0.0);
    if (e < -1.0) return half4(0.0);
    float aa = clamp(e + 0.5, 0.0, 1.0);
    float2 n = v / max(d, 0.001);
    // Normal of the nearest edge: outward on the outer edge, inward on the inner edge.
    float2 nrm = (d > (rIn + rOut) * 0.5) ? n : -n;
    float2 L = float2(-0.7071, -0.7071);          // light from the top left, as specular_angle 315
    float prox = exp(-max(e, 0.0) / bw);
    float p2 = prox * prox;

    // Chromatic fringe: blue reads the rim a little further in than red.
    float s = chroma * 0.35 * bw;
    float fr = exp(-max(inside(d, -s), 0.0) / bw);
    float fb = exp(-max(inside(d, s), 0.0) / bw);
    half3 rim = half3(fr * fr, p2, fb * fb) * fresnel * 0.55;

    float lit = max(dot(nrm, L), 0.0);
    float spec = pow(lit, 6.0) * p2 * specular * 1.2;
    // A hairline at the very edge, the bright cut line real glass shows against the picture behind it.
    float line = exp(-max(e, 0.0) / max(bw * 0.12, 1.0)) * fresnel * 0.5;
    // Sheen: the side facing the light catches a little more of it across the whole body.
    float sheen = max(-n.y * 0.7 - n.x * 0.3, 0.0) * specular * 0.07;
    float shade = max(dot(nrm, -L), 0.0) * p2 * bevel * 0.45;
    float bottom = clamp(n.y, 0.0, 1.0);
    float inner = bottom * bottom * p2 * 0.1;

    // Premultiplied: the tint, then light added on top, then a light shadow laid over.
    half3 pm = tint.rgb * tint.a;
    float a = tint.a;
    half3 light = rim + half3(1.0, 0.99, 0.97) * (spec + line + sheen);
    pm += light;
    a += clamp((rim.g + spec + line + sheen) * 0.75, 0.0, 1.0);
    float sh = clamp(shade + inner, 0.0, 0.6) * 0.5;
    pm *= 1.0 - sh;
    a = a + sh * (1.0 - a);
    pm += (hash(p) - 0.5) * noise * 0.18 * a;
    a = clamp(a, 0.0, 1.0);
    return half4(clamp(pm, half3(0.0), half3(a)), a) * aa;
}
"""

/*
 * A glass lens: a spectacle-lens shape laid along the dial's ring, between rIn and rOut, centred on the
 * screen angle mid and span radians to each side at its fullest. The outline is a superellipse in the
 * ring's own polar frame (out along the radius, across along the arc), fuller toward the outer end
 * (lensHalf), so it bends with the dial. Light: a domed body, a chromatic fresnel rim, a specular crescent
 * and glint toward the light, a soft caustic in the accent on the far side, and a thicker, darker edge.
 */
private const val LENS_SRC = """
uniform float2 c;
uniform float rIn;
uniform float rOut;
uniform float mid;
uniform float span;
uniform float pw;
uniform half4 tint;
uniform half4 accent;
uniform float glow;
uniform float fresnel;
uniform float specular;
uniform float bevel;
uniform float noise;
uniform float chroma;
uniform float bw;

float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

float lensHalf(float t) { float u = clamp(t, -1.0, 1.0); return 0.74 + 0.2 * u + 0.06 * (1.0 - u * u); }

half4 main(float2 p) {
    float2 v = p - c;
    float d = length(v);
    float a = atan(v.y, v.x) - mid;
    a = a - 6.2831853 * floor((a + 3.1415927) / 6.2831853);
    float bx = (rOut - rIn) * 0.5;
    float qx = d - (rIn + rOut) * 0.5;
    float hy = max(d * span * lensHalf(qx / bx), 1.0);
    float qy = d * a;
    float X = abs(qx) / bx;
    float Y = abs(qy) / hy;
    float f = pow(X, pw) + pow(Y, pw) - 1.0;
    float gx = pw * pow(max(X, 1e-4), pw - 1.0) / bx;
    float gy = pw * pow(max(Y, 1e-4), pw - 1.0) / hy;
    float gl = length(float2(gx, gy)) + 1e-4;
    float e = min(-f / gl, 400.0);
    if (e < -1.0) return half4(0.0);
    float aa = clamp(e + 0.5, 0.0, 1.0);

    float2 n = v / max(d, 0.001);
    float2 t = float2(-n.y, n.x);
    float2 nl = normalize(float2(gx * sign(qx + 1e-4), gy * sign(qy + 1e-4)));
    float2 nrm = n * nl.x + t * nl.y;
    float2 L = float2(-0.7071, -0.7071);
    float h = sqrt(clamp(-f, 0.0, 1.0));
    float prox = exp(-max(e, 0.0) / bw);
    float p2 = prox * prox;

    float s = chroma * 0.35 * bw;
    float fr = exp(-max(e - s, 0.0) / bw);
    float fb = exp(-max(e + s, 0.0) / bw);
    half3 rim = half3(fr * fr, p2, fb * fb) * fresnel * 0.6;

    float lit = max(dot(nrm, L), 0.0);
    float spec = pow(lit, 5.0) * p2 * specular * 1.3;
    float line = exp(-max(e, 0.0) / max(bw * 0.12, 1.0)) * fresnel * 0.55;
    // Inside the lens, in its own unit frame turned to the screen.
    float2 sp = n * (qx / bx) + t * (qy / hy);
    float dg = length(sp - L * 0.48);
    float glint = exp(-dg * dg * 10.0) * specular * 0.22;
    float dc = length(sp + L * 0.5);
    float caustic = exp(-dc * dc * 6.0) * (0.1 + 0.25 * glow);
    float shade = max(dot(nrm, -L), 0.0) * p2 * bevel * 0.5;
    float thick = (1.0 - h) * 0.14;

    half3 pm = tint.rgb * tint.a;
    float al = tint.a;
    half3 light = rim + half3(1.0, 0.99, 0.97) * (spec + line + glint) + accent.rgb * caustic;
    pm += light;
    al += clamp((rim.g + spec + line + glint + caustic) * 0.75, 0.0, 1.0);
    float sh = clamp(shade + thick, 0.0, 0.6) * 0.5;
    pm *= 1.0 - sh;
    al = al + sh * (1.0 - al);
    pm += (hash(p) - 0.5) * noise * 0.18 * al;
    al = clamp(al, 0.0, 1.0);
    return half4(clamp(pm, half3(0.0), half3(al)), al) * aa;
}
"""

/** Draws lit glass bands and beads. Null shader below Android 13; then it draws a plain tinted band. */
class GlassFx {
    private val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SRC) else null
    private val brush = shader?.let { ShaderBrush(it) }
    private val lenser: LensShader? = if (Build.VERSION.SDK_INT >= 33) LensShader() else null

    /**
     * A glass lens along the ring round ([cx], [cy]) between radii [rIn] and [rOut], centred on the screen angle
     * [mid] and [half] radians to each side at its fullest. Lengths in px. [glow] 0..1 lights the caustic in
     * [accent]. Below Android 13 the same outline, tinted, with a rim.
     */
    fun DrawScope.lens(
        cx: Float, cy: Float, rIn: Float, rOut: Float, mid: Float, half: Float,
        tint: Color, accent: Color, glow: Float, rimColor: Color, g: GlassLook, k: Float,
    ) {
        if (rOut - rIn < 2f) return
        val outline = lensOutline(cx, cy, rIn, rOut, mid, half)
        val sh = lenser
        if (sh == null || Build.VERSION.SDK_INT < 33) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(outline[0].x, outline[0].y)
                for (i in 1 until outline.size) lineTo(outline[i].x, outline[i].y)
                close()
            }
            drawPath(path, tint)
            drawPath(path, rimColor, style = androidx.compose.ui.graphics.drawscope.Stroke(k))
            return
        }
        val br = sh.brush(cx, cy, rIn, rOut, mid, half, tint, accent, glow, g, maxOf(2f * k, minOf(rOut - rIn, 2f * rOut * half) * .2f))
        // Only the box round the lens runs the shader.
        val x0 = outline.minOf { it.x } - 4
        val y0 = outline.minOf { it.y } - 4
        drawRect(br, Offset(x0, y0), Size(outline.maxOf { it.x } + 4 - x0, outline.maxOf { it.y } + 4 - y0))
    }

    /** A glass ring centred at [cx], [cy] (px) between radii [rIn] and [rOut] (px). */
    fun DrawScope.band(cx: Float, cy: Float, rIn: Float, rOut: Float, tint: Color, g: GlassLook, k: Float) {
        val sh = shader
        if (sh == null || brush == null) {
            drawCircle(tint, (rIn + rOut) / 2, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(rOut - rIn))
            return
        }
        sh.setFloatUniform("c", cx, cy)
        sh.setFloatUniform("rIn", rIn)
        sh.setFloatUniform("rOut", rOut)
        sh.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
        sh.setFloatUniform("fresnel", g.fresnel)
        sh.setFloatUniform("specular", g.specular)
        sh.setFloatUniform("bevel", g.bevel)
        sh.setFloatUniform("noise", g.noise)
        sh.setFloatUniform("chroma", g.chroma)
        // Bezel width: hyprglass edge_thickness 0.10 of the shape, at least 2 units.
        sh.setFloatUniform("bw", maxOf(2f * k, (rOut - maxOf(rIn, 0f)) * .28f))
        drawRect(brush, Offset(cx - rOut - 2, cy - rOut - 2), Size(2 * rOut + 4, 2 * rOut + 4))
    }

    /** A glass bead: a disc of radius [r] (px). */
    fun DrawScope.bead(cx: Float, cy: Float, r: Float, tint: Color, g: GlassLook, k: Float) =
        band(cx, cy, -100000f, r, tint, g, k)
}

/** The lens shader and its brush. Android 13 and up only; [GlassFx.lens] draws the plain outline below that. */
@androidx.annotation.RequiresApi(33)
private class LensShader {
    private val shader = RuntimeShader(LENS_SRC)
    private val brush = ShaderBrush(shader)

    fun brush(
        cx: Float, cy: Float, rIn: Float, rOut: Float, mid: Float, half: Float,
        tint: Color, accent: Color, glow: Float, g: GlassLook, bw: Float,
    ): ShaderBrush {
        shader.setFloatUniform("c", cx, cy)
        shader.setFloatUniform("rIn", rIn)
        shader.setFloatUniform("rOut", rOut)
        shader.setFloatUniform("mid", mid)
        shader.setFloatUniform("span", half)
        shader.setFloatUniform("pw", LENS_POWER)
        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
        shader.setFloatUniform("accent", accent.red, accent.green, accent.blue, accent.alpha)
        shader.setFloatUniform("glow", glow)
        shader.setFloatUniform("fresnel", g.fresnel)
        shader.setFloatUniform("specular", g.specular)
        shader.setFloatUniform("bevel", g.bevel)
        shader.setFloatUniform("noise", g.noise)
        shader.setFloatUniform("chroma", g.chroma)
        shader.setFloatUniform("bw", bw)
        return brush
    }
}

/**
 * The outline of a lens (see [GlassFx.lens]) as [n] points, in px. The superellipse is traced in the ring's
 * polar frame: x out along the radius, y along the arc at that radius.
 */
fun lensOutline(cx: Float, cy: Float, rIn: Float, rOut: Float, mid: Float, half: Float, n: Int = 48): List<Offset> {
    val bx = (rOut - rIn) / 2f
    val rm = (rIn + rOut) / 2f
    val e = 2f / LENS_POWER
    fun sp(v: Float) = kotlin.math.sign(v) * Math.pow(kotlin.math.abs(v).toDouble(), e.toDouble()).toFloat()
    return List(n) { i ->
        val th = 2 * Math.PI.toFloat() * i / n
        val x = bx * sp(kotlin.math.cos(th))
        val r = rm + x
        val y = r * half * lensHalf(x / bx) * sp(kotlin.math.sin(th))
        val ang = mid + y / maxOf(r, 1f)
        Offset(cx + r * kotlin.math.cos(ang), cy + r * kotlin.math.sin(ang))
    }
}
