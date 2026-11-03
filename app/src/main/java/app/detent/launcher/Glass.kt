package app.detent.launcher

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Liquid glass, after the hyprglass presets on the desktop. The window blur frosts whatever is behind
 * Detent (a live wallpaper), the tint is the theme background at the preset's alpha, and [GlassFx] lights
 * the glass shapes on top: fresnel rim, specular highlight, bevel shade, inner shadow, noise and a
 * chromatic fringe. Values are the desktop presets (hyprglass.conf, dark variants).
 */
enum class Glass(
    val label: String,
    val blur: Float,
    val tintA: Float,
    val fresnel: Float,
    val specular: Float,
    val bevel: Float,
    val noise: Float,
    val chroma: Float,
) {
    Off("off", 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    Clear("clear", .25f, .20f, .25f, .30f, .30f, 0f, 0f),
    Frost("frost", 1.6f, .46f, .30f, .35f, .22f, .12f, 0f),
    Smoke("smoke", 2.0f, .74f, .20f, .20f, .14f, .12f, 0f),
    Prism("prism", .70f, .30f, .40f, .45f, .36f, .01f, .30f);

    val on get() = this != Off

    /** Window blur in px. hyprglass blur_strength 1 is about 40 px on a 1440 px wide screen. */
    fun blurPx(widthPx: Int) = (blur * 40f * widthPx / 1440f).toInt()

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
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
    float shade = max(dot(nrm, -L), 0.0) * p2 * bevel * 0.6;
    // Inner shadow along the bottom rim.
    float bottom = clamp(n.y, 0.0, 1.0);
    float inner = bottom * bottom * p2 * 0.18;

    half3 col = tint.rgb;
    float a = tint.a;
    col += rim + half3(1.0, 0.99, 0.97) * spec;
    a += (rim.g + spec) * 0.85;
    col *= 1.0 - (shade + inner);
    a += (shade + inner) * 0.35;
    col += (hash(p) - 0.5) * noise * 0.18;
    a = clamp(a, 0.0, 1.0);
    return half4(clamp(col, 0.0, 1.0) * a, a) * aa;
}
"""

/** Draws lit glass bands and beads. Null shader below Android 13; then it draws a plain tinted band. */
class GlassFx {
    private val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SRC) else null
    private val brush = shader?.let { ShaderBrush(it) }

    /** A glass ring centred at [cx], [cy] (px) between radii [rIn] and [rOut] (px). */
    fun DrawScope.band(cx: Float, cy: Float, rIn: Float, rOut: Float, tint: Color, g: Glass, k: Float) {
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
    fun DrawScope.bead(cx: Float, cy: Float, r: Float, tint: Color, g: Glass, k: Float) =
        band(cx, cy, -100000f, r, tint, g, k)
}
