package app.detent.launcher

import android.content.Context
import androidx.core.content.edit

class Saved(val cfg: Cfg, val keys: Set<String>, val pos: Int)

// A name survives insertions and reordering; the old index supports earlier versions.
internal fun savedThemeIndex(name: String?, legacyIndex: Int, names: List<String>): Int {
    val byName = name?.let { names.indexOf(it) } ?: -1
    return if (byName >= 0) byName else legacyIndex.takeIf { it in names.indices } ?: 0
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("detent", Context.MODE_PRIVATE)
    private val openedSp = ctx.getSharedPreferences("detent.opened", Context.MODE_PRIVATE)

    // The data for the dial hints: "s:" opens from search, "a:" the time an app joined the dial,
    // "z:" the time until which an app gets no hint.
    private val usageSp = ctx.getSharedPreferences("detent.usage", Context.MODE_PRIVATE)

    /** The saved setup, or null when onboarding has not finished. */
    fun load(): Saved? {
        if (!sp.getBoolean("done", false)) return null
        val cfg = Cfg(
            layout = sp.getInt("layout", 0).coerceIn(LAYOUTS.indices),
            theme = sp.getString("themeImportId", null)?.let { id -> THEMES.indexOfFirst { it.importId == id }.takeIf { it >= 0 } }
                ?: savedThemeIndex(sp.getString("themeName", null), sp.getInt("theme", 0), THEME_NAMES),
            side = if (sp.getString("side", "right") == "left") Side.Left else Side.Right,
        )
        return Saved(cfg, sp.getStringSet("apps", null).orEmpty().toSet(), sp.getInt("pos", 0))
    }

    fun save(cfg: Cfg, keys: Set<String>, pos: Int) = sp.edit {
        putBoolean("done", true)
        putInt("layout", cfg.layout)
        putInt("theme", cfg.theme)
        putString("themeImportId", THEMES[cfg.theme].importId)
        putString("themeName", THEME_NAMES.getOrNull(cfg.theme))
        putString("side", if (cfg.side == Side.Left) "left" else "right")
        putStringSet("apps", HashSet(keys))
        putInt("pos", pos)
    }

    fun savePos(pos: Int) = sp.edit { putInt("pos", pos) }

    /** Apply an imported setup as one preferences transaction. Local wallpaper files are left alone. */
    fun restore(data: SettingsData, cfg: Cfg, keys: Set<String>) = sp.edit {
        putBoolean("done", true)
        putInt("layout", cfg.layout)
        putInt("theme", cfg.theme)
        putString("themeName", THEME_NAMES.getOrNull(cfg.theme))
        putString("themeImportId", THEMES[cfg.theme].importId)
        putString("side", if (cfg.side == Side.Left) "left" else "right")
        putStringSet("apps", HashSet(keys))
        putInt("pos", 0)
        putString("quicks", data.quick.joinToString("\n") { it.key + "\t" + (it.icon ?: "") })
        remove("quick")
        remove("quickIcon")
        putString("recent", data.recent.joinToString("\n"))
        putBoolean("sound", data.sound)
        putBoolean("haptics", data.haptics)
        putBoolean("dots", data.dots)
        putBoolean("lockWall", data.lockWall)
        putBoolean("ownBar", data.ownBar)
        putBoolean("voice", data.voice)
        putBoolean("webSearch", data.webSearch)
        remove("appliedWall")
    }

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit { putBoolean("sound", v) }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit { putBoolean("haptics", v) }

    var dots: Boolean
        get() = sp.getBoolean("dots", true)
        set(v) = sp.edit { putBoolean("dots", v) }

    /** Shows the mic button in the bottom corner. */
    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit { putBoolean("voice", v) }

    /** Shows the web button in the bottom corner. */
    var webSearch: Boolean
        get() = sp.getBoolean("webSearch", true)
        set(v) = sp.edit { putBoolean("webSearch", v) }

    /** Sets the lock screen and the system wallpaper to the theme. */
    var lockWall: Boolean
        get() = sp.getBoolean("lockWall", true)
        set(v) = sp.edit { putBoolean("lockWall", v) }

    /** Path of the user's own wallpaper, or null to use the theme wallpaper. */
    var customWall: String?
        get() = sp.getString("customWall", null)?.takeIf { java.io.File(it).exists() }
        set(v) = sp.edit { putString("customWall", v) }

    /** IDs allocated by Android's AppWidgetHost. Keep order and never persist invalid IDs. */
    var widgetIds: List<Int>
        get() = sp.getString("widgetIds", null)?.split(',')
            ?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }?.distinct().orEmpty()
        set(v) = sp.edit { putString("widgetIds", v.filter { it > 0 }.distinct().joinToString(",")) }

    /** An in-flight picker/bind/configuration survives activity recreation. */
    var pendingWidgetId: Int?
        get() = sp.getInt("pendingWidgetId", -1).takeIf { it > 0 }
        set(v) = sp.edit {
            if (v == null) remove("pendingWidgetId") else putInt("pendingWidgetId", v)
        }

    /** The quick apps. Each line holds the app key, a tab, and the icon name. */
    var quick: List<QuickSlot>
        get() {
            val lines = sp.getString("quicks", null)
            if (lines != null) {
                return lines.split('\n').filter { it.isNotEmpty() }.map {
                    QuickSlot(it.substringBefore('\t'), it.substringAfter('\t', "").ifEmpty { null })
                }
            }
            // Detent 0.10 and older keep one quick app.
            val key = sp.getString("quick", null) ?: return emptyList()
            return listOf(QuickSlot(key, sp.getString("quickIcon", null)))
        }
        set(v) = sp.edit {
            putString("quicks", v.joinToString("\n") { it.key + "\t" + (it.icon ?: "") })
            remove("quick")
            remove("quickIcon")
        }

    /** True when Detent hides the Android status bar and shows battery, wifi, and signal itself. */
    var ownBar: Boolean
        get() = sp.getBoolean("ownBar", false)
        set(v) = sp.edit { putBoolean("ownBar", v) }

    /** Keys of the apps last opened from search, newest first. */
    var recent: List<String>
        get() = sp.getString("recent", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        set(v) = sp.edit { putString("recent", v.joinToString("\n")) }

    /** How strongly the grid layouts draw their dot field and shade, from 0 to 1. */
    var gridVis: Float
        get() = sp.getFloat("gridVis", 1f)
        set(v) = sp.edit { putFloat("gridVis", v) }

    /** A tap off the dial opens the selected app, instead of the row level with the tap. */
    var tapOpens: Boolean
        get() = sp.getBoolean("tapOpens", false)
        set(v) = sp.edit { putBoolean("tapOpens", v) }

    /** Package of the assistant that the voice button opens, or null for Detent's own listening. */
    var micApp: String?
        get() = sp.getString("micApp", null)
        set(v) = sp.edit { putString("micApp", v) }

    /** The dial rows in their order, by row key. Empty: by name. */
    var order: List<String>
        get() = sp.getString("order", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        set(v) = sp.edit { putString("order", v.joinToString("\n")) }

    /** The folders, see [encodeFolders]. */
    var folders: List<Folder>
        get() = decodeFolders(sp.getString("folders", null))
        set(v) = sp.edit { putString("folders", encodeFolders(v)) }

    /** The icon of each folder by folder id, see [encodePairs]. A missing folder picks its own icon. */
    var folderIcons: Map<String, String>
        get() = decodePairs(sp.getString("folderIcons", null))
        set(v) = sp.edit { putString("folderIcons", encodePairs(v)) }

    /** What a swipe on a row opens, see [encodePairs]. */
    var swipes: Map<String, String>
        get() = decodePairs(sp.getString("swipes", null))
        set(v) = sp.edit { putString("swipes", encodePairs(v)) }

    /** The custom dial, see [encodeLayout]. */
    var customLayout: String?
        get() = sp.getString("customLayout", null)
        set(v) = sp.edit { putString("customLayout", v) }

    /** The glass values, see [GlassLook.encode]. */
    var glassLook: String?
        get() = sp.getString("glassLook", null)
        set(v) = sp.edit { putString("glassLook", v) }

    /** The liquid glass preset, by [Glass] name. */
    var glass: String
        get() = sp.getString("glass", "Off") ?: "Off"
        set(v) = sp.edit { putString("glass", v) }

    /** The live theme whose wallpaper was last handed to the wallpaper app. */
    var appliedLive: String?
        get() = sp.getString("appliedLive", null)
        set(v) = sp.edit { putString("appliedLive", v) }

    /** The theme and side that the system wallpaper shows now. */
    var appliedWall: String?
        get() = sp.getString("appliedWall", null)
        set(v) = sp.edit { putString("appliedWall", v) }

    fun opened(): Map<String, Long> = openedSp.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun markOpened(pkgUser: String, time: Long) = openedSp.edit { putLong(pkgUser, time) }

    private fun <T> usage(prefix: String, read: (Any?) -> T?): Map<String, T> =
        usageSp.all.mapNotNull { (k, v) -> if (k.startsWith(prefix)) read(v)?.let { k.removePrefix(prefix) to it } else null }.toMap()

    private fun times(v: Any?) = (v as? String)?.split(',')?.mapNotNull { it.toLongOrNull() }

    /** Times of the opens from search in the last week, by app key. */
    fun searchOpens(): Map<String, List<Long>> = usage("s:", ::times)

    fun markSearchOpen(key: String, time: Long) {
        val old = times(usageSp.getString("s:$key", null)).orEmpty()
        val keep = (old.filter { time - it < Suggest.WEEK } + time).takeLast(8)
        usageSp.edit { putString("s:$key", keep.joinToString(",")) }
    }

    /** The time that each dial app joined the dial, by app key. */
    fun dialSince(): Map<String, Long> = usage("a:") { it as? Long }

    /** Records the time that apps join the dial. An app that joins the dial forgets its opens from search. */
    fun syncDial(keys: Set<String>, now: Long) {
        val since = dialSince().keys
        if (since == keys) return
        usageSp.edit {
            for (k in keys - since) {
                putLong("a:$k", now)
                remove("s:$k")
            }
            for (k in since - keys) remove("a:$k")
        }
    }

    /** The time until which each app gets no hint, by app key. */
    fun quiet(): Map<String, Long> = usage("z:") { it as? Long }

    fun quiet(key: String, until: Long) = usageSp.edit { putLong("z:$key", until) }

    /** The time of the last dial hint. */
    var hintAt: Long
        get() = usageSp.getLong("hintAt", 0L)
        set(v) = usageSp.edit { putLong("hintAt", v) }
}
