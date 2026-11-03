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

/** Draws lit glass bands and beads. Null shader below Android 13; then it draws a plain tinted band. */
class GlassFx {
    private val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SRC) else null
    private val brush = shader?.let { ShaderBrush(it) }

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
