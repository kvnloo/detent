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
    /** Re-reads files/live/themes.json: one theme per live wallpaper, appended after the synced theme. */
    const val RELOAD_LIVE = "app.detent.launcher.RELOAD_LIVE"
    const val LIVE_GROUP = "live"
    /** Theme name to the wallpaper package file (in files/live) that Wallpaper Engine opens. */
    private val liveFiles = mutableMapOf<String, String>()

    fun liveFile(ctx: Context, t: ThemeDef): java.io.File? =
        liveFiles[t.name]?.let { java.io.File(java.io.File(ctx.filesDir, "live"), it) }?.takeIf { it.isFile }
    private const val PREFS = "detent.synced"

    /** Listeners that redraw when the colours change. Called on the main thread. */
    val listeners = mutableListOf<() -> Unit>()

    fun theme(bg: Long, fg: Long, accent: Long): ThemeDef {
        val light = Color(bg).luminance() > 0.5f
        val hand = blend(fg, bg, 0.3f)
        return ThemeDef(NAME, GROUP, light, bg, fg, accent, SYSTEM_WALL, hand)
    }

    fun isSystemWall(t: ThemeDef) = t.wall == SYSTEM_WALL

    /** Reads the saved colours and the live themes into the theme list. Call before the first frame. */
    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains("bg")) put(sp.getLong("bg", 0), sp.getLong("fg", 0), sp.getLong("accent", 0))
        loadLive(ctx)
        ImportedThemes.load(ctx)
    }

    /**
     * files/live/themes.json is a list of {name, bg, fg, accent, file}. The live themes always sit at the
     * end of the list in file order, so saved theme indices stay valid while the file keeps its order.
     */
    fun loadLive(ctx: Context): Int {
        val f = java.io.File(java.io.File(ctx.filesDir, "live"), "themes.json")
        val list = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return 0
        val imported = THEMES.filter { it.group == ImportedThemes.GROUP }
        while (THEMES.lastOrNull()?.group == ImportedThemes.GROUP) {
            THEMES.removeAt(THEMES.lastIndex); PALETTES.removeAt(PALETTES.lastIndex); THEME_NAMES.removeAt(THEME_NAMES.lastIndex)
        }
        while (THEMES.isNotEmpty() && THEMES.last().group == LIVE_GROUP) {
            THEMES.removeAt(THEMES.lastIndex); PALETTES.removeAt(PALETTES.lastIndex); THEME_NAMES.removeAt(THEME_NAMES.lastIndex)
        }
        liveFiles.clear()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val bg = parse(o.optString("bg")) ?: continue
            val fg = parse(o.optString("fg")) ?: continue
            val acc = parse(o.optString("accent")) ?: continue
            val light = Color(bg).luminance() > 0.5f
            val t = ThemeDef(name, LIVE_GROUP, light, bg, fg, acc, SYSTEM_WALL, blend(fg, bg, 0.3f))
            THEMES += t; PALETTES += Palette(t); THEME_NAMES += name
            o.optString("file").takeIf { it.isNotBlank() }?.let { liveFiles[name] = it }
        }
        for (t in imported) { THEMES += t; PALETTES += Palette(t); THEME_NAMES += t.name }
        return liveFiles.size
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
        THEME_NAMES[i] = t.name
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
        if (intent.action == Synced.RELOAD_LIVE) {
            val n = Synced.loadLive(ctx.applicationContext)
            Synced.listeners.toList().forEach { it() }
            resultCode = 1
            resultData = "live themes: $n"
            return
        }
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
