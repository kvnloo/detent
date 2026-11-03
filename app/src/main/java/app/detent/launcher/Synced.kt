package app.detent.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.content.edit

/**
 * The "hyprland" theme. Its colours come from another machine (a desktop theme, such as pywal on
 * Hyprland) and it shows the system wallpaper, so a live wallpaper stays visible behind the dial.
 *
 * Set the colours with:
 *   adb shell am broadcast -a app.detent.launcher.SET_COLORS -n <pkg>/app.detent.launcher.ColorsReceiver \
 *     --es bg 181f23 --es fg d1deda --es accent c8be72
 */
object Synced {
    const val NAME = "hyprland"
    const val GROUP = "synced"
    /** Wallpaper marker: draw no wallpaper and let the system wallpaper show through. */
    const val SYSTEM_WALL = "@system"
    const val ACTION = "app.detent.launcher.SET_COLORS"
    private const val PREFS = "detent.synced"

    /** Listeners that redraw when the colours change. Called on the main thread. */
    val listeners = mutableListOf<() -> Unit>()

    fun theme(bg: Long, fg: Long, accent: Long): ThemeDef {
        val light = Color(bg).luminance() > 0.5f
        val hand = blend(fg, bg, 0.3f)
        return ThemeDef(NAME, GROUP, light, bg, fg, accent, SYSTEM_WALL, hand)
    }

    fun isSystemWall(t: ThemeDef) = t.wall == SYSTEM_WALL

    /** Reads the saved colours into the theme list. Call before the first frame. */
    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains("bg")) return
        put(sp.getLong("bg", 0), sp.getLong("fg", 0), sp.getLong("accent", 0))
    }

    /** Saves new colours. Returns false when a colour does not parse. */
    fun save(ctx: Context, bg: String?, fg: String?, accent: String?): Boolean {
        val b = parse(bg) ?: return false
        val f = parse(fg) ?: return false
        val a = parse(accent) ?: return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong("bg", b)
            putLong("fg", f)
            putLong("accent", a)
        }
        put(b, f, a)
        listeners.toList().forEach { it() }
        return true
    }

    private fun put(bg: Long, fg: Long, accent: Long) {
        val i = THEMES.indexOfFirst { it.name == NAME }
        if (i < 0) return
        val t = theme(bg, fg, accent)
        THEMES[i] = t
        PALETTES[i] = Palette(t)
    }

    internal fun parse(s: String?): Long? {
        val hex = s?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return 0xFF000000L or hex.toLong(16)
    }

    private fun blend(a: Long, b: Long, t: Float): Long {
        fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toFloat()
        fun mixCh(s: Int) = (ch(a, s) * t + ch(b, s) * (1 - t)).toLong().coerceIn(0, 255) shl s
        return 0xFF000000L or mixCh(16) or mixCh(8) or mixCh(0)
    }
}

/** Takes colours from adb or another app on the device. */
class ColorsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Synced.ACTION) return
        val ok = Synced.save(
            ctx.applicationContext,
            intent.getStringExtra("bg"),
            intent.getStringExtra("fg"),
            intent.getStringExtra("accent"),
        )
        resultCode = if (ok) 1 else 0
        resultData = if (ok) "ok" else "bad colour"
    }
}
