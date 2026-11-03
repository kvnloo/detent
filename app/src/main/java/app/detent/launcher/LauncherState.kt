package app.detent.launcher

import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

enum class Spin { Idle, Drag, Fling, Settle, Wheel }
enum class Zone { None, Empty, Dial, Selected }
enum class Phase { From, In, Out }

/** A label transform: translate(x, y) rotate(rot) scale(s) translate(tx * width, -50%). */
data class Pose(val x: Float, val y: Float, val rot: Float, val s: Float, val tx: Float)

data class OpenApp(
    val id: Long,
    val label: String,
    val key: String?,
    val sub: String,
    val from: Pose,
    val fromDial: Boolean,
    val phase: Phase,
    /** True on the grid layouts. The grid opens the app with a dot ripple from ([rx], [ry]). */
    val ripple: Boolean = false,
    val rx: Float = 0f,
    val ry: Float = 0f,
)

/** Search shows this many recent apps. */
private const val RECENT = 5
/** Search keeps more keys than it shows, so an uninstalled app does not shorten the list. */
private const val RECENT_KEEP = 10

/** The voice mode: wait for the service, hear the user, think about the words, or report that it heard no app. */
enum class Hear { Wait, Talk, Think, Miss }

/** What the speech service reports while Detent listens. All calls arrive on the main thread. */
interface HearEvents {
    fun ready()
    fun level(db: Float)
    fun partial(text: String)
    fun end()
    fun results(said: List<String>)
    fun failed(error: Int)
}

class Press(val id: Long, val x: Float, val y: Float)
/** The menu of a held app. [slot] is the quick button that holds the app, or null for the dial. [from] is that button. */
class QuickMenu(val app: AppEntry, val shortcuts: List<ShortcutInfo>, val slot: Int? = null, val from: Pose? = null)

/** An installed app on quick button [slot]. */
class QuickApp(val slot: Int, val app: AppEntry, val icon: String?)
class Toast(val id: Long, val text: String, val undo: (() -> Unit)?)

/** A hint on the home screen: add [app] to the dial, or remove it. [count] gives the opens or the idle days. */
class DialHint(val app: AppEntry, val add: Boolean, val count: Int)

/** The calls that need the activity. */
interface Host {
    /** Apps that can act as an assistant (they take ACTION_ASSIST), as package to label. */
    fun assistants(): List<Pair<String, String>>

    /** Opens an assistant app's own voice entry. False when it can't be opened. */
    fun openAssistant(pkg: String): Boolean

    /** Blurs what is behind the window (a live wallpaper) as [look] says, or stops blurring when null. */
    fun setGlassBlur(look: GlassLook?)

    /** Hands a live wallpaper package to its wallpaper app, which asks to apply it. */
    fun openLiveWallpaper(theme: ThemeDef): Boolean

    fun launchApp(app: AppEntry, bounds: Rect?)

    /** True when an app on the phone can search the web. */
    fun canSearchWeb(): Boolean

    /** Hands [query] to the default browser, or else to the web search app that Android picks. */
    fun searchWeb(query: String, bounds: Rect?)
    fun launchShortcut(info: ShortcutInfo, bounds: Rect?)
    fun showAppInfo(app: AppEntry, bounds: Rect?)
    fun isDefaultHome(): Boolean
    fun requestHome()
    fun hasNotificationAccess(): Boolean
    fun openNotificationAccess()
    fun is24Hour(): Boolean

    /** Starts the speech input of the phone. Calls [done] with what it heard, best guess first. Returns false when the phone has no speech input. */
    fun listen(done: (List<String>) -> Unit): Boolean

    /** True when the phone has a speech service that Detent can use in its own voice mode. */
    fun canHear(): Boolean
    fun hasMic(): Boolean
    fun askMic(done: (Boolean) -> Unit)

    /** Starts the speech service. The service reports to [events] until it gives results or fails. */
    fun hear(events: HearEvents)

    /** Stops the microphone and keeps what the service heard so far. */
    fun finishHearing()

    /** Stops the speech service and drops what it heard. */
    fun stopHearing()

    /**
     * Sets the lock screen and system wallpaper off the main thread, then calls [done] on the main thread.
     * [grid] draws the dot grid in place of a wallpaper.
     */
    fun setWallpaper(theme: ThemeDef, pal: Palette, left: Boolean, custom: String?, grid: Boolean, done: (Boolean) -> Unit)

    /**
     * Opens the photo picker and saves the picked image. [done] gets the saved path, or null when the user
     * cancels. [failed] is true when the image cannot be read.
     */
    fun pickWallpaper(done: (path: String?, failed: Boolean) -> Unit)

    /** Save one JSON file with the Android document picker. Null means the user cancelled. */
    fun exportSettings(json: String, done: (Boolean?) -> Unit)

    /** Choose a JSON file with the Android document picker. Cancel is not an error. */
    fun importSettings(done: (String?, Boolean) -> Unit)

    /** Deletes saved wallpapers except [keep]. */
    fun cleanWallpapers(keep: String?)

    /** Pulls down the notification shade. Returns false when the phone does not allow it. */
    fun openNotifications(): Boolean
}

/** A drag from the top edge opens the notifications. A drag from the bottom edge opens search. */
private enum class Edge { None, Top, Bottom }

/** How far a sideways swipe on a row goes before it counts, in design units. */
const val SWIPE = 44f

/** What the search sheet gathers apps for: a folder, or the app that a swipe opens. */
enum class Gather { Folder, Swipe }

private class Drag(val x0: Float, val y0: Float, var y: Float, val t0: Long, val zone: Zone, val edge: Edge) {
    var moved = false

    /** True when this edge drag opened the notifications or search. */
    var pulled = false
    var scrub = false

    /** The dial row under the touch at the start, for a sideways swipe. */
    var row: Int? = null

    /** True when the drag goes sideways on a row: a swipe, not a turn. */
    var swipe = false

    /** True when the touch caught a turning dial. A tap then only stops the dial. */
    var caught = false
    val samples = ArrayDeque<Pair<Long, Float>>()
}

/** Onboarding steps. */
object Step {
    const val WELCOME = 0
    const val SHAPE = 1
    const val THEME = 2
    const val APPS = 3
    const val FEEL = 4
    const val READY = 5
}

class LauncherState(
    private val host: Host,
    val repo: AppsRepository,
    private val prefs: Prefs,
    private val fb: Feedback,
    private val scope: CoroutineScope,
) {
    // Screen size in design units. The UI sets these on each layout.
    var k = 1f
    var hd = H0
    var clockTop = 84f
    var buttonY = 796f
    val cy get() = layout.cy(hd)

    // Dial engine. The canvas reads these three every frame.
    var pos by mutableFloatStateOf(0f)
        private set
    var pawl by mutableFloatStateOf(0f)
        private set
    var radius by mutableFloatStateOf(330f)
        private set
    var spin by mutableStateOf(Spin.Idle)
        private set
    val index by derivedStateOf { pos.roundToInt() }

    /** A distant theme tap previews its destination, not every row the dial settles through. */
    var themeJump by mutableStateOf<Int?>(null)
        private set

    private fun themeTap(row: Int) {
        if (ob == Step.THEME) themeJump = row.takeIf { abs(it - index) > 1 }
    }

    /** How far the lens bulges, from 0.5 at rest to 1 under the finger. */
    var ls by mutableFloatStateOf(.5f)
        private set
    private var lsT = .5f

    /** The dot-matrix readout of the grid layouts. [readTick] changes on each frame that moves its dots. */
    val readout = Readout()
    var readTick by mutableIntStateOf(0)
        private set

    /** True while the readout shows an app name in place of the time. */
    var readName by mutableStateOf(false)
        private set

    private var vel = 0f
    private var target = 0f
    private var lastIdx = 0
    private var lastDetent = 0L
    private var lastClick = 0L
    private var rTarget = 330f
    private var loop: Job? = null
    private var drag: Drag? = null
    private var lpJob: Job? = null
    private var wheelJob: Job? = null
    private var toastJob: Job? = null
    private var hintJob: Job? = null
    private var warnJob: Job? = null
    private var clockJob: Job? = null
    private var lsJob: Job? = null
    private var scrubBase = 0
    private var appPos = 0
    private var seq = 0L

    var cfg by mutableStateOf(Cfg())
        private set
    var dialKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The order of the dial rows by row key, or empty for the order by name. */
    var order by mutableStateOf(prefs.order)
        private set

    /** The folders. A folder is on the dial when its key is in [dialKeys]. */
    var folders by mutableStateOf(prefs.folders)
        private set

    /** What a sideways swipe on a dial row opens: row key to app key. */
    var swipes by mutableStateOf(prefs.swipes)
        private set

    /** The key of the open folder, or null while the dial shows its own rows. */
    var folderKey by mutableStateOf<String?>(null)
        private set
    private var folderBase = 0

    /** The row that the dial carries to a new place, or null. The carried row stays in the notch as the dial turns. */
    var carry by mutableStateOf<String?>(null)
        private set
    private var carryBefore: List<String>? = null
    private var carryIn: String? = null

    /** The row of the sideways swipe in progress, how far it has come, and whether it has come far enough. */
    var swipeRow by mutableStateOf<Int?>(null)
        private set
    var swipeDx by mutableFloatStateOf(0f)
        private set
    var swipeArmed by mutableStateOf(false)
        private set

    var ob by mutableStateOf<Int?>(null)
        private set
    var obEdit by mutableStateOf(false)
        private set
    var obSeq by mutableIntStateOf(0)
        private set
    private var prevCfg: Cfg? = null
    private var prevKeys: Set<String>? = null

    var warn by mutableStateOf(false)
        private set
    var press by mutableStateOf<Press?>(null)
        private set
    var search by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** True while the search sheet takes words for a web search. */
    var web by mutableStateOf(false)
        private set

    /** True while the search sheet picks the app for a quick button. */
    var pick by mutableStateOf(false)
        private set

    /** The quick apps, at most [QUICK_MAX]. The first one sits next to the mic button. */
    var quick by mutableStateOf(prefs.quick)
        private set

    /** The installed quick apps in slot order. */
    val quickApps by derivedStateOf {
        val byKey = repo.apps.associateBy { it.key }
        quick.mapIndexedNotNull { i, q -> byKey[q.key]?.let { QuickApp(i, it, q.icon) } }
    }

    /** The quick button that the search sheet picks an app for. A slot past the end adds a button. */
    private var pickSlot = 0

    /** The app that the search sheet picks an icon for, or null while it lists the apps. */
    var pickFor by mutableStateOf<AppEntry?>(null)
        private set
    var open by mutableStateOf<OpenApp?>(null)
        private set
    var menu by mutableStateOf<QuickMenu?>(null)
        private set
    var menuShown by mutableStateOf(false)
        private set
    var scrub by mutableStateOf<List<String>?>(null)
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set
    var toastShown by mutableStateOf(false)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    var resumed by mutableStateOf(false)
        private set

    var sound by mutableStateOf(prefs.sound)
        private set
    var haptics by mutableStateOf(prefs.haptics)
        private set
    var dots by mutableStateOf(prefs.dots)
        private set
    var lockWall by mutableStateOf(prefs.lockWall)
        private set
    var voice by mutableStateOf(prefs.voice)
        private set
    var webSearch by mutableStateOf(prefs.webSearch)
        private set

    /** The grid layouts draw their dot field and shade at this strength, from 0 to 1. */
    var gridVis by mutableFloatStateOf(prefs.gridVis)
        private set

    /** Liquid glass over a see-through theme. */
    var glass by mutableStateOf(Glass.of(prefs.glass))
        private set

    /** The glass values; a preset fills them, the sliders change them. */
    var look by mutableStateOf(GlassLook.decode(prefs.glassLook) ?: Glass.of(prefs.glass).look ?: Glass.Clear.look!!)
        private set

    /** The look in use, or null when glass is off. */
    val lookNow: GlassLook? get() = if (glass.on) look else null

    /** True while the glass panel is open on the feel step. */
    var glassOpen by mutableStateOf(false)
        private set

    /** True when the theme lets the system wallpaper through, so glass has something to frost. */
    val seeThrough get() = Synced.isSystemWall(THEMES[cfg.theme]) && (customWall == null || layout.grid)

    /** True when Detent hides the Android status bar and shows battery, wifi, and signal itself. */
    var ownBar by mutableStateOf(prefs.ownBar)
        private set
    /** Path of the user's own wallpaper, or null for the theme wallpaper. */
    var customWall by mutableStateOf(prefs.customWall)
        private set
    private var prevCustom: String? = null
    var notifAccess by mutableStateOf(false)
        private set
    var isHome by mutableStateOf(false)
        private set
    private var unread by mutableStateOf<Map<String, Long>>(emptyMap())
    private var opened by mutableStateOf(prefs.opened())

    /** Bumped when the custom dial changes in place, so readers of [layout] redraw. */
    private var layoutRev by mutableIntStateOf(0)
    val layout: Layout get() = layoutRev.let { LAYOUTS[cfg.layout] }

    /** Off the dial, a tap opens the selected app. On the dial, a tap still opens the app under the finger. */
    var tapOpens by mutableStateOf(prefs.tapOpens)
        private set

    fun toggleTapOpens() {
        tapOpens = !tapOpens
        prefs.tapOpens = tapOpens
        toggled(tapOpens)
    }

    /**
     * The row whose name or mark is under ([x], [y]), or null. A row's target is its own angular slot
     * (half a row each side) between the far end of its name and just outside the rim.
     */
    internal fun dialHit(x: Float, y: Float): Int? {
        val g = Geo(radius, cy, layout.ax)
        val dx = g.cx - mirX(x)
        val dy = y - cy
        val dist = hypot(dx, dy)
        val kf = pos + atan2(dy, dx) / g.step
        val n = dialApps.size
        if (n == 0) return null
        val name = dialApps[mod(kf.roundToInt(), n)].display
        return dialSlot(dist, kf, pos, g, layout.markers, name.length)
    }

    /** The assistant the voice button opens, or null for Detent's own listening. */
    var micApp by mutableStateOf(prefs.micApp)
        private set

    /** Steps the voice button through Detent's own listening and each assistant app. */
    fun cycleMic() {
        tap()
        val all = listOf<String?>(null) + host.assistants().map { it.first }
        micApp = all[(all.indexOf(micApp) + 1).mod(all.size)]
        prefs.micApp = micApp
    }

    /** Every choice for the voice button: Detent's own listening (null), then each assistant app. */
    fun micChoices(): List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "detent") + host.assistants()

    fun setMic(pkg: String?) {
        micApp = pkg
        prefs.micApp = pkg
    }

    fun micLabel(): String = micApp?.let { p -> host.assistants().firstOrNull { it.first == p }?.second ?: p } ?: "detent"

    /** True while the customize panel is open on the shape step. */
    var customOpen by mutableStateOf(false)
        private set
    val kind get() = layout.kind
    val left get() = cfg.side == Side.Left
    /** The rows of the dial: apps and folders, in the saved order. */
    val rows by derivedStateOf { rowsFor(dialKeys) }

    /** The open folder row, or null. */
    val openFolder by derivedStateOf { folderKey?.let { k -> rows.firstOrNull { it.key == k } } }

    /** The rows that the dial shows: the apps of the open folder, or else the dial rows. */
    val dialApps by derivedStateOf { openFolder?.kids ?: rows }

    private fun rowsFor(keys: Set<String>): List<AppEntry> {
        val byKey = repo.apps.associateBy { it.key }
        val fs = folders.associateBy { it.key }
        val entries = keys.mapNotNull { k -> byKey[k] ?: fs[k]?.let { folderEntry(it, byKey) } }
            .sortedWith(compareBy({ it.label }, { it.key }))
        val at = entries.associateBy { it.key }
        return ordered(entries.map { it.key }, order).map { at.getValue(it) }
    }

    private fun folderEntry(f: Folder, byKey: Map<String, AppEntry>): AppEntry? {
        val kids = f.keys.mapNotNull { byKey[it] }
        val cover = kids.firstOrNull() ?: return null
        return AppEntry(f.key, f.name.lowercase(), cover.component, cover.user, f.key, kids)
    }

    /** The keys of all apps in folders on the dial. */
    private fun folderApps(): Set<String> =
        folders.filter { it.key in dialKeys }.flatMapTo(HashSet()) { it.keys }

    /** Where search shows an app: "on dial", "in <folder>", or null. */
    fun placeOf(key: String): String? {
        if (key in dialKeys) return "on dial"
        return folders.firstOrNull { it.key in dialKeys && key in it.keys }?.let { "in " + it.name.lowercase() }
    }

    init {
        decodeLayout(prefs.customLayout)?.let { LAYOUTS[CUSTOM] = it }
        fb.sound = sound
        fb.haptics = haptics
        val saved = prefs.load()
        if (saved != null) {
            cfg = saved.cfg
            dialKeys = saved.keys
            snapRadius()
            jumpTo(saved.pos.toFloat())
            syncWallpaper()
        } else {
            startOb()
        }
        repo.onLoaded = ::appsLoaded
        showClock(instant = true)
        scope.launch { Unread.state.collect { unread = it } }
        scope.launch {
            while (isActive) {
                now = System.currentTimeMillis()
                if (!readName) showClock()
                delay(60_000 - now % 60_000 + 20)
            }
        }
    }

    fun hasDot(app: AppEntry): Boolean =
        app.kids?.any { hasDot(it) } ?: (dots && notifAccess && (unread[app.pkgUser] ?: 0L) > (opened[app.pkgUser] ?: 0L))

    fun curApp(): AppEntry? {
        val a = dialApps
        return if (a.isEmpty()) null else a[selIndex(a.size)]
    }

    /** The selected row. The position counts full turns, so the row wraps. */
    private fun selIndex(n: Int) = mod(pos.roundToInt(), max(1, n))

    private fun appsLoaded() {
        val apps = repo.apps
        if (apps.isEmpty()) return
        if (ob != null && !obEdit && dialKeys.isEmpty()) {
            dialKeys = repo.defaults()
            return
        }
        val installed = apps.mapTo(HashSet()) { it.key }
        val live = folders.filter { f -> f.keys.any { it in installed } }.mapTo(HashSet()) { it.key }
        val kept = dialKeys.filterTo(HashSet()) { it in installed || it in live }
        if (kept.isEmpty()) {
            dialKeys = repo.defaults()
            persist()
        } else if (kept.size != dialKeys.size) {
            dialKeys = kept
            persist()
        }
        val q = quick.filter { it.key in installed }
        if (q.size != quick.size) saveQuick(q)
    }

    private fun persist() {
        if (ob != null) return
        prefs.save(cfg, dialKeys, pos.roundToInt())
        prefs.customWall = customWall
        prefs.syncDial(dialKeys, System.currentTimeMillis())
    }

    // Activity hooks.

    fun onResume() {
        resumed = true
        now = System.currentTimeMillis()
        notifAccess = host.hasNotificationAccess()
        isHome = host.isDefaultHome()
        if (!readName) showClock()
        hintJob?.cancel()
        hintJob = scope.launch {
            // The hint waits until the app name drops back into the dial.
            delay(900)
            if (!showHint()) return@launch
            delay(20_000)
            dropHint()
        }
        val o = open ?: return
        // Back from an app: the name drops back into the dial.
        if (o.phase != Phase.Out) {
            open = o.copy(phase = Phase.In)
            close(silent = true)
        }
    }

    fun onPause() {
        resumed = false
    }

    fun onStop() {
        cancelPress()
        hintJob?.cancel()
        dropHint()
        if (drag?.scrub == true) {
            drag = null
            endScrub()
        }
        drag = null
        clearSwipe()
        if (menu != null) closeMenu()
        dropCarry()
        // An app opened from a folder: home shows the dial again.
        closeFolder(quiet = true)
        // The microphone stays on only while Detent is in front.
        if (hear != null) closeVoice()
    }

    /** The home button while the launcher is already in front. */
    fun onHome() {
        dropCarry()
        closeFolder()
        if (menu != null) closeMenu()
        if (search) closeSearch()
        if (hear != null) closeVoice()
    }

    fun back() {
        when {
            themeImportLink != null -> closeThemeImport()
            open != null -> close()
            hear != null -> closeVoice()
            menu != null -> closeMenu()
            pickFor != null -> unpick()
            search -> closeSearch()
            carry != null -> dropCarry()
            folderKey != null -> closeFolder()
            ob in Step.SHAPE..Step.FEEL -> obBack()
        }
    }

    fun release() {
        loop?.cancel()
        fb.release()
    }

    // Engine.

    private fun jumpTo(p: Float) {
        pos = p
        target = p
        lastIdx = p.roundToInt()
        vel = 0f
        spin = Spin.Idle
    }

    private fun snapRadius() {
        radius = layout.radius
        rTarget = radius
    }

    private fun syncRadius() {
        val r = layout.radius
        if (r != rTarget) {
            rTarget = r
            run()
        }
    }

    private fun run() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var last = 0L
            while (true) {
                val t = withFrameMillis { it }
                val dt = if (last == 0L) 16f else min(40f, (t - last).toFloat())
                last = t
                if (!step(dt)) break
            }
        }
    }

    /** One frame. Returns false when the dial is at rest. */
    private fun step(dt: Float): Boolean {
        var p = pos
        when (spin) {
            Spin.Fling -> {
                p += vel * dt
                vel *= 0.9965f.pow(dt)
                if (abs(vel) < 0.004f) {
                    target = (p + sign(vel) * 0.3f).roundToInt().toFloat()
                    spin = Spin.Settle
                }
            }
            Spin.Settle -> {
                val d = target - p
                p += d * (1 - 0.78f.pow(dt / 16f))
                if (abs(d) < 0.002f) {
                    p = target
                    spin = Spin.Idle
                }
            }
            else -> {}
        }
        pos = p
        var morph = false
        if (abs(radius - rTarget) > 0.3f) {
            radius += (rTarget - radius) * (1 - 0.84f.pow(dt / 16f))
            morph = true
        } else {
            radius = rTarget
        }
        pawl *= 0.8f.pow(dt / 16f)
        val lsD = lsT - ls
        ls = if (abs(lsD) < .003f) lsT else ls + lsD * (1 - 0.82f.pow(dt / 16f))
        val idx = p.roundToInt()
        if (idx != lastIdx) {
            lastIdx = idx
            pawl = 1f
            if (carry != null) carryStep()
            val t = SystemClock.uptimeMillis()
            // The open sound covers the turn that a tap starts.
            if (open == null && t - lastClick > 26) {
                lastClick = t
                val letters = scrub != null
                fb.click(letters)
                // Rows per second since the last detent: a flick freewheels, a slow turn clicks firmly.
                val speed = 1000f / max(1L, t - lastDetent).toFloat()
                lastDetent = t
                if (letters) fb.letter() else fb.tick(speed)
            }
            followReadout()
        }
        val reading = layout.grid && readout.advance(SystemClock.uptimeMillis(), dt)
        if (reading) readTick++
        val done = spin == Spin.Idle && pawl < 0.01f && !morph && ls == lsT && !reading
        if (done) {
            pawl = 0f
            if (ob == null && scrub == null && folderKey == null) prefs.savePos(index)
            if (readName && drag == null && open == null && hear == null) scheduleClock()
        }
        return !done
    }

    private fun stepBy(n: Int) {
        themeJump = null
        val base = if (spin == Spin.Settle) target else pos.roundToInt().toFloat()
        if (kind == Kind.Lens) pulseLens()
        target = base + n
        spin = Spin.Settle
        run()
    }

    // Grid layouts.

    /** Top row of the readout. A tall status bar moves it down by whole grid rows. */
    val readY get() = 80f + G * ceil(max(0f, clockTop - 84f) / G)

    /** The lens column runs from below the date to above the bottom buttons. The lens sits at the notch. */
    val lensTop get() = readY + 112f
    val lensBottom get() = buttonY - 36f

    /** Rows on the dial: the themes on the theme step, the apps on all other screens. */
    private fun count() = if (ob == Step.THEME) THEMES.size else dialApps.size

    /** y of the selected app. */
    val anchorY get() = cy

    private fun pulseLens() {
        lsT = 1f
        lsJob?.cancel()
        lsJob = scope.launch {
            delay(700)
            lsT = .5f
            run()
        }
    }

    private fun onLens(x: Float, y: Float) = mirX(x) > 160 && y > lensTop - 12 && y < lensBottom + 12

    /** y of lens row [i] after the bulge. The geometry follows the lens drawing. */
    private fun lensRowY(i: Int): Float {
        val dy = (i - pos) * LENS_GAP
        val l = 70 + 60 * ls
        val ad = abs(dy)
        if (ad >= l) return cy + dy
        val t = 1 - ad / l
        return cy + dy * (1 + .9f * ls * t * t)
    }

    /** The lens row nearest to [y]. Rows count full turns, like the dial position. */
    private fun lensRowAt(y: Float): Int {
        val p = pos.roundToInt()
        var best = p
        var bestD = Float.MAX_VALUE
        for (i in p - 16..p + 16) {
            val d = abs(y - lensRowY(i))
            if (d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /** True when the name of lens row [i] is in view and covers ([x], [y]). */
    private fun onLensName(i: Int, x: Float, y: Float): Boolean {
        val dy = (i - pos) * LENS_GAP
        val l = 70 + 60 * ls
        val ad = abs(dy)
        if (ad >= l) return false
        val chars = when {
            scrub != null -> 1
            ob == Step.THEME -> THEME_NAMES[mod(i, THEMES.size)].length
            else -> dialApps.getOrNull(mod(i, max(1, dialApps.size)))?.display?.length ?: return false
        }
        val t = 1 - ad / l
        val s = 1 + .7f * t * t * ls + if (i == pos.roundToInt()) .15f else 0f
        val xr = LX - 18 - 8 * t * ls
        val mx = mirX(x)
        return mx > xr - chars * CH * s - 8 && mx < xr + 8 && abs(y - lensRowY(i)) < 13 * s
    }

    /** The readout layout shows the selected name, or the letter while you scrub. */
    private fun followReadout() {
        if (kind != Kind.Readout || ob != null || hear != null) return
        val letters = scrub
        if (letters != null) {
            clockJob?.cancel()
            readName = true
            readout.set(letters[mod(pos.roundToInt(), letters.size)], 2, 0, true, SystemClock.uptimeMillis(), false)
            run()
        } else {
            showName()
        }
    }

    private fun showName() {
        val app = curApp() ?: return
        clockJob?.cancel()
        readName = true
        readout.set(app.display, 1, 3, true, SystemClock.uptimeMillis(), false)
        run()
    }

    private fun showClock(instant: Boolean = false) {
        clockJob?.cancel()
        readName = false
        readout.set(clockText(), 2, 0, false, SystemClock.uptimeMillis(), instant)
        if (layout.grid) run()
    }

    private fun scheduleClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            delay(1600)
            if (drag == null && spin == Spin.Idle && open == null) showClock()
        }
    }

    private fun clockText(): String {
        val c = Calendar.getInstance()
        val h = if (host.is24Hour()) c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        else (c.get(Calendar.HOUR).takeIf { it != 0 } ?: 12).toString()
        return h + ":" + c.get(Calendar.MINUTE).toString().padStart(2, '0')
    }

    // Gestures. All coordinates are design units.

    /** How far a drag up from the bottom has come, from 0 to 1. At 1, search opens. */
    var lift by mutableFloatStateOf(0f)
        private set

    private fun blocked() =
        themeImportLink != null || open != null || search || hear != null || menu != null || ob == Step.WELCOME || ob == Step.APPS

    private fun mirX(x: Float) = if (left) W - x else x

    /** A drag that starts above this y pulls down the notifications. The zone ends above the clock and the readout. */
    private val pullY get() = (if (layout.grid) readY else clockTop) - 8f

    /** A drag that starts below this y opens search. The zone starts below the lens column. */
    private val pushY get() = buttonY - 24f

    private fun zoneAt(x: Float, y: Float): Zone {
        if (kind == Kind.Lens) {
            if (!onLens(x, y)) return Zone.Empty
            return if (lensRowAt(y) == pos.roundToInt()) Zone.Selected else Zone.Dial
        }
        if (y < 200 || y > hd - 102) return Zone.Empty
        val g = Geo(radius, cy, layout.ax)
        val dx = g.cx - mirX(x)
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist <= g.r - 200 || dist >= g.r + 40) return Zone.Empty
        val k = pos + atan2(dy, dx) / g.step
        if (abs(k - pos) > g.span) return Zone.Empty
        return if (k.roundToInt() == pos.roundToInt()) Zone.Selected else Zone.Dial
    }

    fun onDown(x: Float, y: Float) {
        if (blocked()) return
        themeJump = null
        val zone = if (ob == null) zoneAt(x, y) else Zone.None
        val edge = when {
            ob != null || carry != null -> Edge.None
            y < pullY -> Edge.Top
            y > pushY -> Edge.Bottom
            else -> Edge.None
        }
        val t = SystemClock.uptimeMillis()
        val d = Drag(x, y, y, t, zone, edge)
        d.caught = spin == Spin.Fling || spin == Spin.Wheel || (spin == Spin.Settle && abs(target - pos) > .3f)
        d.samples.addLast(t to pos)
        if (ob == null && edge == Edge.None && carry == null) d.row = swipeRowAt(x, y, zone)
        drag = d
        spin = Spin.Drag
        vel = 0f
        // The lens grows under the finger.
        if (kind == Kind.Lens && edge == Edge.None) {
            lsJob?.cancel()
            lsT = 1f
        }
        if (kind == Kind.Readout && ob == null && scrub == null && edge == Edge.None) showName()
        if (zone != Zone.None) {
            press = Press(++seq, x, y)
            lpJob?.cancel()
            lpJob = scope.launch {
                delay(500)
                longPress()
            }
        }
        run()
    }

    fun onMove(x: Float, y: Float) {
        val d = drag ?: return
        if (!d.moved && (abs(y - d.y0) > 6 || abs(x - d.x0) > 6)) {
            d.moved = true
            cancelPress()
            // Sideways on a row is a swipe. The dial stays where it is.
            if (d.row != null && !d.scrub && abs(x - d.x0) > abs(y - d.y0) * 1.3f) {
                d.swipe = true
                swipeRow = d.row
                pos = pos.roundToInt().toFloat()
            }
        }
        if (d.swipe) {
            swipeDx = x - d.x0
            val armed = abs(swipeDx) >= SWIPE
            if (armed != swipeArmed) {
                swipeArmed = armed
                if (armed) fb.tick() else fb.tap()
            }
            return
        }
        // A drag down from the top opens the notifications. A drag up from the bottom opens search.
        // These drags do not turn the dial.
        if (d.edge != Edge.None) {
            // The distance away from the edge.
            val dist = if (d.edge == Edge.Top) y - d.y0 else d.y0 - y
            if (d.edge == Edge.Bottom) lift = (dist / 24f).coerceIn(0f, 1f)
            if (!d.pulled && dist > 24 && dist > abs(x - d.x0)) {
                d.pulled = true
                if (d.edge == Edge.Bottom) {
                    openSearch()
                } else {
                    fb.tick()
                    if (!host.openNotifications()) showToast("can't open the notifications")
                }
            }
            return
        }
        pos -= (y - d.y) / if (kind == Kind.Lens) LENS_GAP else GAP
        d.y = y
        val t = SystemClock.uptimeMillis()
        d.samples.addLast(t to pos)
        while (d.samples.size > 2 && t - d.samples.first().first > 90) d.samples.removeFirst()
    }

    fun onUp(x: Float, y: Float) {
        cancelPress()
        lift = 0f
        val d = drag ?: return
        drag = null
        if (kind == Kind.Lens) lsT = .5f
        if (d.scrub) {
            endScrub()
            return
        }
        if (d.swipe) {
            val row = swipeRow
            val armed = swipeArmed
            clearSwipe()
            target = pos.roundToInt().toFloat()
            spin = Spin.Settle
            run()
            if (armed && row != null) swipeAt(row)
            return
        }
        val t = SystemClock.uptimeMillis()
        if (!d.moved && t - d.t0 < 450) {
            target = pos.roundToInt().toFloat()
            spin = Spin.Settle
            if (carry != null) dropCarry()
            else if (!d.caught) tapAt(x, y)
            run()
            return
        }
        val (t0, p0) = d.samples.first()
        vel = ((pos - p0) / max(16f, (t - t0).toFloat())).coerceIn(-0.06f, 0.06f)
        target = pos.roundToInt().toFloat()
        spin = if (abs(vel) > 0.006f) Spin.Fling else Spin.Settle
        run()
    }

    fun onCancel() {
        cancelPress()
        clearSwipe()
        lift = 0f
        val d = drag ?: return
        drag = null
        if (kind == Kind.Lens) lsT = .5f
        if (d.scrub) {
            endScrub()
            return
        }
        target = pos.roundToInt().toFloat()
        spin = Spin.Settle
        run()
    }

    fun onWheel(dy: Float) {
        if (blocked() || scrub != null) return
        themeJump = null
        pos += dy.coerceIn(-140f, 140f) / 90f
        if (kind == Kind.Lens) {
            lsJob?.cancel()
            lsT = 1f
        }
        if (kind == Kind.Readout && ob == null && !readName) showName()
        vel = 0f
        spin = Spin.Wheel
        wheelJob?.cancel()
        wheelJob = scope.launch {
            delay(110)
            if (kind == Kind.Lens) lsT = .5f
            target = pos.roundToInt().toFloat()
            spin = Spin.Settle
            run()
        }
        run()
    }

    private fun cancelPress() {
        lpJob?.cancel()
        press = null
    }

    private fun longPress() {
        val d = drag ?: return
        if (d.moved || carry != null) return
        press = null
        if (d.zone == Zone.Dial) {
            startScrub()
            return
        }
        drag = null
        if (kind == Kind.Lens) lsT = .5f
        target = pos.roundToInt().toFloat()
        spin = Spin.Settle
        fb.click(true)
        fb.heavy()
        if (d.zone == Zone.Selected) openMenu() else openSetup()
        run()
    }

    private fun tapAt(x: Float, y: Float) {
        if (kind == Kind.Lens) {
            if (!onLens(x, y)) return
            val k = lensRowAt(y)
            val named = onLensName(k, x, y)
            // Off the column, only a name is a target.
            if (!named && mirX(x) < LX - 16) return
            when {
                k == pos.roundToInt() -> if (ob == null) launchSelected()
                named && ob == null -> launchAt(k)
                else -> {
                    fb.tick()
                    themeTap(k)
                    target = k.toFloat()
                    spin = Spin.Settle
                }
            }
            return
        }
        // The readout layout opens the selected app from the readout too.
        if (kind == Kind.Readout && ob == null && y > readY - 10 && y < readY + 90) {
            launchSelected()
            return
        }
        if (y < 200 || y > hd - 102) return
        if (tapOpens && ob == null && kind == Kind.Radial) {
            // Quick launch on the dial; anywhere else opens what the notch points at.
            val hit = dialHit(x, y)
            if (hit == null || hit == pos.roundToInt()) launchSelected() else launchAt(hit)
            return
        }
        val g = Geo(radius, cy, layout.ax)
        val dx = g.cx - mirX(x)
        val dy = y - cy
        if (abs(hypot(dx, dy) - g.r) > 210) return
        val k = (pos + atan2(dy, dx) / g.step).roundToInt()
        val cur = pos.roundToInt()
        if (abs(k - pos) > g.span) return
        // The readout dial has no names to tap, so a tap on another row only turns the dial.
        if (ob == null && (k == cur || kind != Kind.Readout)) {
            if (k == cur) launchSelected() else launchAt(k)
        } else if (k != cur) {
            fb.tick()
            themeTap(k)
            target = k.toFloat()
            spin = Spin.Settle
        }
    }

    // Letter scrub: hold the dial, and the apps become their first letters.

    private fun startScrub() {
        val apps = dialApps
        val cur = curApp() ?: return
        val letters = apps.map { it.initial }.distinct()
        scrubBase = pos.roundToInt()
        val li = letters.indexOf(cur.initial).toFloat()
        pos = li
        target = li
        lastIdx = li.roundToInt()
        spin = Spin.Drag
        drag?.scrub = true
        fb.letter()
        fb.click(true)
        scrub = letters
        followReadout()
    }

    private fun endScrub() {
        val letters = scrub ?: return
        val letter = letters[mod(pos.roundToInt(), letters.size)]
        val apps = dialApps
        val n = apps.size
        scrub = null
        if (n == 0) return
        val idx = apps.indexOfFirst { it.initial == letter }
        val cur = mod(scrubBase, n)
        var d = idx - cur
        if (d > n / 2f) d -= n
        if (d < -n / 2f) d += n
        pos = scrubBase.toFloat()
        lastIdx = scrubBase
        target = (scrubBase + d).toFloat()
        spin = Spin.Settle
        followReadout()
        run()
    }

    var themeImportLink by mutableStateOf<String?>(null)
        private set

    fun openThemeImport(link: String = "") {
        if (ob == null || ob == Step.WELCOME || ob == Step.READY) openSetup()
        if (ob != Step.THEME) goTo(Step.THEME)
        themeImportLink = link
    }

    fun closeThemeImport() { themeImportLink = null }

    fun showImportedThemes() {
        themeImportLink = null
        if (THEMES.any { it.group == ImportedThemes.GROUP }) jumpGroup(ImportedThemes.GROUP)
    }

    // Onboarding and the setup editor.

    private fun tap() {
        fb.click(false)
        fb.tap()
    }

    private fun startOb() {
        jumpTo(0f)
        appPos = 0
        cfg = Cfg()
        snapRadius()
        dialKeys = if (repo.apps.isNotEmpty()) repo.defaults() else emptySet()
        prevCfg = null
        prevKeys = null
        obEdit = false
        ob = Step.WELCOME
        obSeq++
    }

    /** Hold the clock or an empty spot: shape, theme, apps and feel, with cancel and done. */
    private fun openSetup() {
        dropCarry()
        closeFolder(quiet = true)
        prevCfg = cfg
        prevKeys = dialKeys
        prevCustom = customWall
        obEdit = true
        search = false
        ob = Step.SHAPE
        obSeq++
    }

    private fun goTo(n: Int?) {
        val cur = ob
        if (cur == Step.THEME && n != Step.THEME) {
            cfg = cfg.copy(theme = mod(themeJump ?: pos.roundToInt(), THEMES.size))
            themeJump = null
            jumpTo(appPos.toFloat())
        }
        if (n == Step.THEME && cur != Step.THEME) {
            appPos = pos.roundToInt()
            themeJump = null
            jumpTo(cfg.theme.toFloat())
        }
        ob = n
        obSeq++
        if (n == null) {
            persist()
            host.cleanWallpapers(customWall)
            syncWallpaper()
            syncLive()
            syncGlass()
            showClock()
        }
        run()
    }

    /** A live theme asks its wallpaper app to switch, once per change of theme. */
    private fun syncLive() {
        val t = THEMES[cfg.theme]
        val key = t.importId ?: t.name
        if ((t.group != Synced.LIVE_GROUP && t.liveSource == null && t.mobilePackage == null) || prefs.appliedLive == key) return
        if (host.openLiveWallpaper(t) && (t.group == Synced.LIVE_GROUP || t.mobilePackage != null)) prefs.appliedLive = key
    }

    /** Copies the theme to the lock screen when the theme or the side changed since the last copy. */
    private fun syncWallpaper() {
        if (!lockWall) return
        val grid = layout.grid
        // The synced theme keeps whatever the system wallpaper is (for example a live wallpaper).
        if (Synced.isSystemWall(THEMES[cfg.theme]) && (grid || customWall == null)) return
        val custom = if (grid) null else customWall
        val key = "${THEMES[cfg.theme].name}|${cfg.side}|${if (grid) "grid" else custom ?: "theme"}"
        if (prefs.appliedWall == key) return
        host.setWallpaper(THEMES[cfg.theme], PALETTES[cfg.theme], left, custom, grid) { ok ->
            if (ok) prefs.appliedWall = key else showToast("can't set the lock screen")
        }
    }

    /** Opens the photo picker. The theme keeps the colours, and the photo replaces the theme wallpaper. */
    fun pickWallpaper() {
        tap()
        host.pickWallpaper { path, failed ->
            if (path != null) customWall = path
            else if (failed) showToast("can't open that image")
        }
    }

    fun useThemeWallpaper() {
        tap()
        customWall = null
    }

    fun toggleLockWall() {
        lockWall = !lockWall
        prefs.lockWall = lockWall
        if (!lockWall) prefs.appliedWall = null
        toggled(lockWall)
    }

    fun cycleGrid() {
        tap()
        val steps = floatArrayOf(1f, .8f, .6f, .4f, .2f, 0f)
        val i = steps.indexOfFirst { kotlin.math.abs(it - gridVis) < .01f }
        gridVis = steps[(i + 1) % steps.size]
        prefs.gridVis = gridVis
    }

    fun toggleGlassPanel() {
        tap()
        glassOpen = !glassOpen
    }

    /** Picks a starting look. The haptics come from the control that picked it. */
    fun pickGlass(g: Glass) {
        glass = g
        g.look?.let { look = it }
        prefs.glass = g.name
        prefs.glassLook = look.encode()
        syncGlass()
    }

    /** Moves the glass values. Live while a slider moves; [commit] saves them. */
    fun setLook(l: GlassLook, commit: Boolean) {
        look = l
        glass = Glass.PRESETS.firstOrNull { it.look == l } ?: Glass.Custom
        syncGlass()
        if (commit) {
            prefs.glass = glass.name
            prefs.glassLook = l.encode()
        }
    }

    fun setGrid(v: Float, commit: Boolean) {
        gridVis = v
        if (commit) prefs.gridVis = v
    }

    /** Shows layout [i] while a finger scrubs the layout row, without the tap feedback. */
    fun previewLayout(i: Int) {
        if (i !in LAYOUTS.indices || cfg.layout == i) return
        cfg = cfg.copy(layout = i)
        showClock(instant = true)
        syncRadius()
    }

    /** Blurs behind the window only when the theme shows the system wallpaper. */
    fun syncGlass() = host.setGlassBlur(if (seeThrough) lookNow else null)

    /** Haptics for a toggle: a crisp click when it turns on, a lighter fall when it turns off. */
    private fun toggled(now: Boolean) {
        fb.click(false)
        if (now) fb.on() else fb.off()
    }

    /** The haptics engine, for controls that run their own gestures. */
    val haptic: Feedback get() = fb

    fun setBar(own: Boolean) {
        tap()
        ownBar = own
        prefs.ownBar = own
    }

    fun obNext() {
        tap()
        when (val o = ob ?: return) {
            Step.FEEL -> goTo(if (obEdit) null else Step.READY)
            // Editing: done from any step.
            in Step.SHAPE..Step.APPS -> goTo(if (obEdit) null else o + 1)
            Step.READY -> goTo(null)
            else -> goTo(o + 1)
        }
    }

    fun obBack() {
        tap()
        val o = ob ?: return
        if (o == Step.SHAPE) {
            if (!obEdit) {
                goTo(Step.WELCOME)
            } else {
                cfg = prevCfg ?: cfg
                dialKeys = prevKeys ?: dialKeys
                customWall = prevCustom
                host.cleanWallpapers(prevCustom)
                ob = null
                showClock(instant = true)
                syncRadius()
            }
        } else if (o > Step.SHAPE) {
            goTo(o - 1)
        }
    }

    fun toggleCustomize() {
        tap()
        customOpen = !customOpen
    }

    /** Changes one part of the dial. The current dial becomes the starting point of the custom one. */
    fun customize(radius: Float? = null, marks: Markers? = null, perApp: Int? = null, notch: Markers? = null, quiet: Boolean = false) {
        if (!quiet) tap()
        val base = if (layout.grid) LAYOUTS[0] else layout
        val l = customLayout(radius ?: base.radius, marks ?: base.markers, perApp ?: base.ticksPerApp, notch ?: base.notch)
        LAYOUTS[CUSTOM] = l
        prefs.customLayout = encodeLayout(l)
        cfg = cfg.copy(layout = CUSTOM)
        layoutRev++
        showClock(instant = true)
        syncRadius()
    }

    /** In edit mode the step names are tabs. */
    fun jumpStep(n: Int) {
        if (!obEdit || ob == n || n !in Step.SHAPE..Step.FEEL) return
        tap()
        goTo(n)
    }

    fun setLayout(i: Int) {
        tap()
        cfg = cfg.copy(layout = i)
        showClock(instant = true)
        syncRadius()
    }

    fun setSide(side: Side) {
        tap()
        cfg = cfg.copy(side = side)
    }

    fun toggleApp(key: String) {
        val on = key in dialKeys
        if (on && dialApps.size <= 3) {
            fb.refuse()
            warn = true
            warnJob?.cancel()
            warnJob = scope.launch {
                delay(1400)
                warn = false
            }
            return
        }
        tap()
        dialKeys = if (on) dialKeys - key else dialKeys + key
    }

    fun jumpGroup(group: String) {
        val first = THEMES.indexOfFirst { it.group == group }
        val base = pos.roundToInt()
        val n = THEMES.size
        var d = first - mod(base, n)
        if (d > n / 2f) d -= n
        if (d < -n / 2f) d += n
        fb.tap()
        themeTap(base + d)
        target = (base + d).toFloat()
        spin = Spin.Settle
        run()
    }

    fun toggleSound() {
        sound = !sound
        fb.sound = sound
        prefs.sound = sound
        toggled(sound)
    }

    fun toggleHaptics() {
        haptics = !haptics
        fb.haptics = haptics
        prefs.haptics = haptics
        toggled(haptics)
    }

    fun toggleDots() {
        tap()
        if (!notifAccess) {
            dots = true
            prefs.dots = true
            host.openNotificationAccess()
        } else {
            dots = !dots
            prefs.dots = dots
        }
    }

    fun toggleVoice() {
        voice = !voice
        prefs.voice = voice
        toggled(voice)
    }

    fun toggleWebSearch() {
        webSearch = !webSearch
        prefs.webSearch = webSearch
        toggled(webSearch)
    }

    private fun currentSettings() = SettingsData(
        layout = layout.name,
        theme = THEMES[cfg.theme].name,
        side = if (left) "left" else "right",
        apps = dialKeys,
        quick = quick,
        recent = prefs.recent,
        sound = sound,
        haptics = haptics,
        dots = dots,
        lockWall = lockWall,
        ownBar = ownBar,
        voice = voice,
        webSearch = webSearch,
    )

    fun exportSettings() {
        tap()
        host.exportSettings(SettingsBackup.toJson(currentSettings())) { ok ->
            if (ok != null) showToast(if (ok) "settings exported" else "can't export settings")
        }
    }

    fun importSettings() {
        tap()
        if (!repo.loaded || repo.apps.isEmpty()) {
            showToast("apps are still loading")
            return
        }
        host.importSettings { text, failed ->
            if (text == null) {
                if (failed) showToast("can't read settings")
                return@importSettings
            }
            val installed = repo.apps.mapTo(HashSet()) { it.key }
            val data = runCatching {
                SettingsBackup.fromJson(
                    text, currentSettings(),
                    LAYOUTS.mapTo(HashSet()) { it.name },
                    THEMES.mapTo(HashSet()) { it.name },
                    installed,
                )
            }.getOrNull()
            if (data == null) {
                showToast("can't import settings")
                return@importSettings
            }

            // Keep at least three dial apps where possible, even when some saved apps are gone.
            val keys = if (data.apps.size >= minOf(3, installed.size)) data.apps
                else data.apps + repo.defaults().filter { it in installed }
            cfg = Cfg(
                layout = LAYOUTS.indexOfFirst { it.name == data.layout },
                theme = THEMES.indexOfFirst { it.name == data.theme },
                side = if (data.side == "left") Side.Left else Side.Right,
            )
            dialKeys = keys
            quick = data.quick
            recentKeys = data.recent
            sound = data.sound
            haptics = data.haptics
            dots = data.dots
            lockWall = data.lockWall
            ownBar = data.ownBar
            voice = data.voice
            webSearch = data.webSearch
            fb.sound = sound
            fb.haptics = haptics
            prefs.restore(data, cfg, keys)
            prefs.syncDial(keys, System.currentTimeMillis())
            appPos = 0
            jumpTo(0f)
            snapRadius()
            showClock(instant = true)
            // An import is committed even if the setup editor is later cancelled.
            prevCfg = cfg
            prevKeys = dialKeys
            prevCustom = customWall
            if (lockWall) syncWallpaper()
            showToast("settings imported")
        }
    }

    fun requestHome() {
        tap()
        host.requestHome()
    }

    fun refreshSystem() {
        notifAccess = host.hasNotificationAccess()
        isHome = host.isDefaultHome()
    }

    // Toast with undo.

    fun showToast(text: String, undo: (() -> Unit)? = null) {
        toastJob?.cancel()
        toast = Toast(++seq, text, undo)
        toastShown = true
        toastJob = scope.launch {
            delay(3200)
            toastShown = false
            delay(300)
            toast = null
        }
    }

    fun undo() {
        val t = toast
        if (t?.undo != null) {
            fb.letter()
            fb.click(false)
            t.undo.invoke()
        }
        toastJob?.cancel()
        toastShown = false
        toastJob = scope.launch {
            delay(300)
            toast = null
        }
    }

    /** Sets the dial apps and keeps the selected app under the notch. */
    private fun setAppsKeep(next: Set<String>) = setRows(next, null, curApp()?.key)

    /**
     * Sets the dial rows and, when [nextOrder] is not null, their order. The row [keep] stays under the notch.
     * Read [keep] before any change to the rows: a new order or folder moves what the notch points at.
     */
    private fun setRows(next: Set<String>, nextOrder: List<String>?, keep: String?) {
        if (nextOrder != null) {
            order = nextOrder
            prefs.order = nextOrder
        }
        if (keep != null && folderKey == null) {
            val ni = rowsFor(next).indexOfFirst { it.key == keep }
            if (ni >= 0) reindex(ni)
        }
        dialKeys = next
        persist()
    }

    /**
     * Moves the dial to row [idx] of a new app list. The position counts full turns, so a plain shift
     * lands on the wrong row when the list length changes.
     */
    private fun reindex(idx: Int) {
        val base = pos.roundToInt()
        pos = idx + (pos - base)
        target = idx + (target - base)
        lastIdx = idx + (lastIdx - base)
    }

    fun addToDial(app: AppEntry) {
        val before = dialKeys
        if (app.key in before) return
        fb.double()
        fb.click(false)
        setAppsKeep(before + app.key)
        showToast("added ${app.display} to the dial") { setAppsKeep(before) }
    }

    private fun removeFromDial(app: AppEntry) {
        val before = dialKeys
        if (dialApps.size <= 3) {
            fb.refuse()
            showToast("keep at least 3 on the dial")
            return
        }
        fb.double()
        fb.click(false)
        // The next app moves up into the notch.
        val size = dialApps.size
        reindex(mod(mod(pos.roundToInt(), size), size - 1))
        dialKeys = before - app.key
        persist()
        showToast("removed ${app.display}") { setAppsKeep(before) }
    }

    // Dial hints: add an app that you often open from search, or remove a dial app that you do not open.

    /** The hint on the home screen, or null. */
    var dialHint by mutableStateOf<DialHint?>(null)
        private set

    /** Shows a hint when one is due. Returns true when a hint shows. */
    private fun showHint(): Boolean {
        if (!resumed || ob != null || blocked() || dialHint != null) return false
        val now = System.currentTimeMillis()
        prefs.syncDial(dialKeys, now)
        if (now - prefs.hintAt < Suggest.GAP) return false
        // Folders get no hints, and an app in a folder counts as on the dial.
        val dial = rows.filter { it.kids == null }
        val byKey = repo.apps.associateBy { it.key }
        val sg = Suggest.pick(
            now, dial.mapTo(HashSet()) { it.key }, byKey.keys - folderApps(), prefs.searchOpens(),
            dial.associate { it.key to (opened[it.pkgUser] ?: 0L) }, prefs.dialSince(), prefs.quiet(),
        ) ?: return false
        val app = byKey[sg.key] ?: return false
        prefs.hintAt = now
        dialHint = DialHint(app, sg.add, sg.count)
        return true
    }

    /** Adds or removes the app of the hint. */
    fun takeHint() {
        val h = dialHint ?: return
        hintJob?.cancel()
        dialHint = null
        if (h.add) addToDial(h.app) else removeIdle(h.app)
    }

    /** "not now" or "keep": the app gets no hint for a long time. */
    fun skipHint() {
        val h = dialHint ?: return
        hintJob?.cancel()
        fb.tap()
        prefs.quiet(h.app.key, System.currentTimeMillis() + if (h.add) Suggest.NOT_NOW else Suggest.KEEP)
        dialHint = null
    }

    /** The hint goes away with no answer. It can come back after a few days. */
    private fun dropHint() {
        val h = dialHint ?: return
        prefs.quiet(h.app.key, System.currentTimeMillis() + Suggest.LATER)
        dialHint = null
    }

    /** Removes an app that is not in the notch. The app in the notch stays there. */
    private fun removeIdle(app: AppEntry) {
        val before = dialKeys
        if (dialApps.size <= Suggest.MIN_DIAL) return
        fb.double()
        fb.click(false)
        setAppsKeep(before - app.key)
        showToast("removed ${app.display}") { setAppsKeep(before) }
    }

    /** Counts an open from search or voice of an app that is not on the dial. */
    private fun markSearched(app: AppEntry) {
        if (placeOf(app.key) == null) prefs.markSearchOpen(app.key, System.currentTimeMillis())
    }

    // Quick actions: hold the selected app.

    private fun openMenu() {
        val app = curApp() ?: return
        menu = QuickMenu(app, repo.shortcuts(app))
        menuShown = true
    }

    fun closeMenu() {
        if (menu == null) return
        menuShown = false
        scope.launch {
            delay(200)
            if (!menuShown) menu = null
        }
    }

    private fun afterMenu(wait: Long, action: (AppEntry) -> Unit) {
        val app = menu?.app ?: return
        closeMenu()
        scope.launch {
            delay(wait)
            action(app)
        }
    }

    fun menuShortcut(info: ShortcutInfo) {
        // A shortcut of a quick app lifts off from its button.
        val from = menu?.from
        afterMenu(120) { app ->
            val label = (info.shortLabel ?: info.longLabel)?.toString()?.lowercase().orEmpty()
            openApp(app.display, app.key, app.pkgUser, from ?: selectedPose() ?: return@afterMenu, label, from == null) {
                host.launchShortcut(info, it)
            }
        }
    }

    fun menuInfo() = afterMenu(120) { app ->
        openApp(app.display, app.key, null, selectedPose() ?: return@afterMenu, "app info", true) {
            host.showAppInfo(app, it)
        }
    }

    fun menuRemove() = afterMenu(160) { removeFromDial(it) }

    // Folders, swipes and the order of the rows.

    /** The dial row under ([x], [y]) that a sideways swipe acts on, or null. */
    private fun swipeRowAt(x: Float, y: Float, zone: Zone): Int? {
        if (dialApps.isEmpty()) return null
        return when (kind) {
            Kind.Radial -> dialHit(x, y) ?: if (zone == Zone.Selected) pos.roundToInt() else null
            Kind.Lens -> if (onLens(x, y)) lensRowAt(y) else null
            else -> if (zone == Zone.Selected || zone == Zone.Dial) pos.roundToInt() else null
        }
    }

    private fun clearSwipe() {
        swipeRow = null
        swipeDx = 0f
        swipeArmed = false
    }

    /** The app that a swipe on [row] opens, or null. */
    fun swipeTarget(row: AppEntry): AppEntry? =
        swipes[row.key]?.let { k -> repo.apps.firstOrNull { it.key == k } }

    /** A swipe on row [k]: a folder opens, an app opens its swipe app. In a folder, a swipe goes back out. */
    private fun swipeAt(k: Int) {
        if (folderKey != null) {
            closeFolder()
            return
        }
        val apps = dialApps
        if (apps.isEmpty()) return
        val row = apps[mod(k, apps.size)]
        if (row.kids != null) {
            enterFolder(k, row)
            return
        }
        val t = swipeTarget(row)
        if (t == null) {
            fb.refuse()
            showToast("hold ${row.display} to give it a swipe")
            return
        }
        launchFrom(k, row, t)
    }

    private fun enterFolder(k: Int, row: AppEntry) {
        fb.double()
        fb.click(true)
        folderBase = k
        folderKey = row.key
        jumpTo(0f)
        followReadout()
        run()
    }

    fun closeFolder(quiet: Boolean = false) {
        if (folderKey == null) return
        if (!quiet) {
            fb.click(false)
            fb.tap()
        }
        folderKey = null
        jumpTo(folderBase.toFloat())
        followReadout()
        run()
    }

    /** Hold a row, then "move": the row rides in the notch while the dial turns. A tap drops it. */
    fun menuMove() = afterMenu(160) { app ->
        carryBefore = dialApps.map { it.key }
        carryIn = folderKey
        carry = app.key
        fb.double()
        showToast("turn the dial to move ${app.display}, tap to drop it")
    }

    private fun carryStep() {
        val key = carry ?: return
        val keys = dialApps.map { it.key }
        val from = keys.indexOf(key)
        if (from < 0) {
            carry = null
            return
        }
        applyOrder(moved(keys, from, selIndex(keys.size)), carryIn)
    }

    fun dropCarry() {
        val key = carry ?: return
        val before = carryBefore
        val inFolder = carryIn
        carry = null
        carryBefore = null
        fb.click(true)
        fb.double()
        val now = dialApps.map { it.key }
        val name = dialApps.firstOrNull { it.key == key }?.display ?: "it"
        if (before != null && before != now) showToast("moved $name") { applyOrder(before, inFolder) }
    }

    /** Saves the row order of the dial, or of the folder [inFolder]. */
    private fun applyOrder(keys: List<String>, inFolder: String?) {
        if (inFolder == null) {
            order = keys
            prefs.order = keys
        } else {
            saveFolders(folders.map { f -> if (f.key == inFolder) f.copy(keys = keys + f.keys.filter { it !in keys }) else f })
        }
    }

    private fun saveFolders(next: List<Folder>) {
        folders = next
        prefs.folders = next
    }

    private fun saveSwipes(next: Map<String, String>) {
        swipes = next
        prefs.swipes = next
    }

    /** Puts dial, order, folders and swipes back as they were. */
    private fun restore(keys: Set<String>, ord: List<String>, fs: List<Folder>, sw: Map<String, String>) {
        val cur = curApp()?.key
        saveFolders(fs)
        saveSwipes(sw)
        setRows(keys, ord, cur)
    }

    /** What the search sheet gathers apps for, or null. */
    var gather by mutableStateOf<Gather?>(null)
        private set

    /** The row the gather is for: an app, or a folder. */
    private var gatherRow by mutableStateOf<String?>(null)

    /** The swipe app of the row that the swipe sheet edits, or null. */
    val gatherSwipe: AppEntry? get() = gatherRow?.let { r -> swipes[r] }?.let { k -> repo.apps.firstOrNull { it.key == k } }

    /** The apps picked for the folder, cover first. */
    var gatherKeys by mutableStateOf<List<String>>(emptyList())
        private set
    var folderName by mutableStateOf("")

    /** The row that the gather sheet edits, as it shows on the dial. */
    var gatherLabel by mutableStateOf("")
        private set

    /** Hold an app, then "make a folder", or hold a folder, then "edit folder". */
    fun menuFolder() = afterMenu(160) { row ->
        openSearch()
        gather = Gather.Folder
        gatherRow = row.key
        gatherLabel = row.display
        gatherKeys = row.kids?.map { it.key } ?: listOf(row.key)
        folderName = if (row.kids != null) row.display else ""
    }

    /** Hold an app, then "swipe": pick the app that a sideways swipe on it opens. */
    fun menuSwipe() = afterMenu(160) { row ->
        openSearch()
        gather = Gather.Swipe
        gatherRow = row.key
        gatherLabel = row.display
    }

    /** A tap on an app in the search sheet. [y] is the row centre in design units. */
    fun resultTap(app: AppEntry, y: Float) {
        when (gather) {
            Gather.Folder -> {
                fb.tick()
                gatherKeys = if (app.key in gatherKeys) gatherKeys - app.key else gatherKeys + app.key
            }
            Gather.Swipe -> setSwipe(app)
            null -> if (pick) pickQuick(app) else openFromSearch(app, y)
        }
    }

    /** Sets the swipe app of the gather row, or clears it with null. */
    fun setSwipe(app: AppEntry?) {
        val row = gatherRow ?: return
        val before = swipes
        fb.double()
        fb.click(false)
        saveSwipes(if (app == null) swipes - row else swipes + (row to app.key))
        search = false
        gather = null
        val text = if (app == null) "$gatherLabel has no swipe" else "swipe $gatherLabel to open ${app.display}"
        showToast(text) { saveSwipes(before) }
    }

    /** "done" on the folder sheet: makes the folder, or saves the changes to it. */
    fun saveFolder() {
        val row = gatherRow ?: return
        val keys = gatherKeys
        val byKey = repo.apps.associateBy { it.key }
        val name = folderName.trim().lowercase().ifEmpty { guessFolderName(keys.mapNotNull { byKey[it]?.label }) }
        val beforeKeys = dialKeys
        val beforeOrder = order
        val beforeFolders = folders
        val beforeSwipes = swipes
        val rowKeys = rows.map { it.key }
        search = false
        gather = null
        if (isFolderKey(row)) {
            if (keys.isEmpty()) {
                // An empty folder leaves the dial.
                saveFolders(folders.filter { it.key != row })
                setAppsKeep(dialKeys - row)
            } else {
                saveFolders(folders.map { if (it.key == row) it.copy(name = name, keys = keys) else it })
            }
            fb.double()
            showToast("saved $name") { restore(beforeKeys, beforeOrder, beforeFolders, beforeSwipes) }
            return
        }
        if (keys.size < 2) {
            fb.refuse()
            showToast("a folder needs at least 2 apps")
            return
        }
        val f = Folder(System.currentTimeMillis().toString(36), name, keys)
        // The folder takes the place of the held app. Apps that move into it leave the dial,
        // unless the dial would drop below its smallest size.
        val moving = keys.filter { it in dialKeys && it != row }.toSet()
        val keep = rowKeys.size - 1 - moving.size + 1 < Suggest.MIN_DIAL
        val nextKeys = dialKeys - row - (if (keep) emptySet() else moving) + f.key
        val nextOrder = rowKeys.flatMap { k ->
            when {
                k == row -> listOf(f.key)
                !keep && k in moving -> emptyList()
                else -> listOf(k)
            }
        }
        saveFolders(folders + f)
        swipes[row]?.let { saveSwipes(swipes - row) }
        setRows(nextKeys, nextOrder, f.key)
        fb.double()
        fb.click(false)
        showToast("made folder $name, swipe it to open") { restore(beforeKeys, beforeOrder, beforeFolders, beforeSwipes) }
    }

    /** Hold a folder, then "ungroup": its apps take its place on the dial. */
    fun menuUngroup() = afterMenu(160) { row ->
        val kids = row.kids ?: return@afterMenu
        val beforeKeys = dialKeys
        val beforeOrder = order
        val beforeFolders = folders
        val add = kids.map { it.key }.filter { it !in dialKeys }
        val nextOrder = rows.map { it.key }.flatMap { if (it == row.key) add else listOf(it) }
        saveFolders(folders.filter { it.key != row.key })
        setRows(dialKeys - row.key + add, nextOrder, add.firstOrNull())
        fb.double()
        showToast("ungrouped ${row.display}") { restore(beforeKeys, beforeOrder, beforeFolders, swipes) }
    }

    /** In a folder: hold an app, then "take out". It goes on the dial next to the folder. */
    fun menuTakeOut() = afterMenu(160) { app ->
        val fk = folderKey ?: return@afterMenu
        val f = folders.firstOrNull { it.key == fk } ?: return@afterMenu
        if (f.keys.size <= 1) {
            fb.refuse()
            showToast("ungroup the folder instead")
            return@afterMenu
        }
        val beforeKeys = dialKeys
        val beforeOrder = order
        val beforeFolders = folders
        val nextOrder = rows.map { it.key }.flatMap { if (it == fk) listOf(fk, app.key) else listOf(it) }
        saveFolders(folders.map { if (it.key == fk) it.copy(keys = it.keys - app.key) else it })
        order = nextOrder
        prefs.order = nextOrder
        dialKeys = dialKeys + app.key
        persist()
        fb.double()
        showToast("took ${app.display} out of ${f.name.lowercase()}") { restore(beforeKeys, beforeOrder, beforeFolders, swipes) }
    }

    private fun afterQuickMenu(action: (slot: Int, app: AppEntry) -> Unit) {
        val m = menu ?: return
        val slot = m.slot ?: return
        afterMenu(160) { action(slot, m.app) }
    }

    /** Pick another icon for the held quick button. */
    fun menuQuickIcon() = afterQuickMenu { slot, app ->
        openPick(slot)
        pickFor = app
    }

    /** Pick another app for the held quick button. */
    fun menuQuickApp() = afterQuickMenu { slot, _ -> openPick(slot) }

    fun menuQuickAdd() = afterQuickMenu { _, _ -> openPick(quick.size) }

    fun menuQuickRemove() = afterQuickMenu { slot, app ->
        val before = quick
        fb.double()
        fb.click(false)
        saveQuick(quick.filterIndexed { i, _ -> i != slot })
        showToast("removed ${app.display} from the quick apps") { saveQuick(before) }
    }

    // Search.

    fun openSearch() {
        tap()
        query = ""
        pick = false
        pickFor = null
        web = false
        gather = null
        recentKeys = prefs.recent
        search = true
    }

    /** The web button: the search sheet takes words for the browser or the search app. */
    fun openWeb() {
        if (blocked()) return
        openSearch()
        web = true
    }

    /** Hands the words in the field to the browser or the search app. The words lift off from the field. */
    fun searchWeb() {
        val q = query.trim()
        if (q.isEmpty() || open != null) return
        if (!host.canSearchWeb()) {
            showToast("no app can search the web")
            return
        }
        val label = if (q.length > 24) q.take(23).trimEnd() + "…" else q
        openApp(label, null, null, Pose(28f, clockTop + 2f, 0f, 26f / 44f, 0f), "web", false) { host.searchWeb(q, it) }
    }

    fun closeSearch() {
        fb.tap()
        search = false
    }

    fun matches(q: String): List<AppEntry> {
        val s = q.trim().lowercase()
        val all = repo.apps
        if (s.isEmpty()) return all
        return all.filter { it.label.startsWith(s) } + all.filter { !it.label.startsWith(s) && s in it.label }
    }

    /**
     * Keys of the apps last opened from search, newest first. An open from search saves the new order
     * to [prefs] only. The list takes the new order when search opens again, so it does not reorder while the sheet closes.
     */
    private var recentKeys by mutableStateOf(prefs.recent)

    /** The apps last opened from search, newest first. Uninstalled apps drop out. */
    val recentApps by derivedStateOf {
        val byKey = repo.apps.associateBy { it.key }
        recentKeys.mapNotNull { byKey[it] }.take(RECENT)
    }

    fun removeRecent(app: AppEntry) {
        val before = recentKeys
        fb.tap()
        saveRecent(before - app.key)
        showToast("removed ${app.display} from recent") { saveRecent(before) }
    }

    fun clearRecent() {
        val before = recentKeys
        fb.tap()
        saveRecent(emptyList())
        showToast("cleared recent") { saveRecent(before) }
    }

    private fun saveRecent(keys: List<String>) {
        recentKeys = keys
        prefs.recent = keys
    }

    fun searchEnter() {
        if (web) return searchWeb()
        val app = matches(query).firstOrNull() ?: return
        resultTap(app, 200f)
    }

    /** Opens a search result. [y] is the row centre in design units. */
    fun openFromSearch(app: AppEntry, y: Float) {
        if (open != null) return
        markRecent(app)
        markSearched(app)
        openApp(app.display, app.key, app.pkgUser, Pose(28f, y, 0f, 16f / 44f, 0f), "", false) {
            host.launchApp(app, it)
        }
    }

    private fun markRecent(app: AppEntry) {
        prefs.recent = (listOf(app.key) + prefs.recent.filter { it != app.key }).take(RECENT_KEEP)
    }

    // Voice: say the name of an app to open it. The home screen listens in the style of its layout.
    // The dial marks swell with the voice, and the dial turns to the app that the words name.

    /** The voice mode, or null when Detent does not listen. */
    var hear by mutableStateOf<Hear?>(null)
        private set
    /** The words that the speech service heard so far. */
    var heardText by mutableStateOf("")
        private set
    /** The app that the words name so far. */
    var heardApp by mutableStateOf<AppEntry?>(null)
        private set
    /** The voice level from 0 to 1. */
    var voiceLevel by mutableFloatStateOf(0f)
        private set
    /** The start and the centre of the matched name on the radial layouts. An app that is not on the dial lifts off from here. */
    var heardPose: Pose? = null

    /** Where the name lifts off when an app opens from the system speech dialog. */
    private var voiceFrom = Pose(W / 2, 0f, 0f, 13f / 44f, 0f)

    /** A tap on the voice button. [x] and [y] give the centre of the button in design units. */
    fun voiceTap(x: Float, y: Float) {
        if (blocked()) return
        tap()
        voiceFrom = Pose(x - 6f, y, 0f, 13f / 44f, 0f)
        micApp?.let { if (host.openAssistant(it)) return }
        when {
            !host.canHear() -> systemVoice()
            host.hasMic() -> startHearing()
            // Without the microphone, the system speech dialog still works.
            else -> host.askMic { ok -> if (ok) startHearing() else systemVoice() }
        }
    }

    /** A tap on the screen while Detent listens: stop and use the words so far, or listen again after a miss. */
    fun hearTap() {
        when (hear) {
            Hear.Wait, Hear.Talk -> host.finishHearing()
            Hear.Miss -> {
                tap()
                startHearing()
            }
            else -> {}
        }
    }

    fun closeVoice() {
        if (hear == null) return
        fb.tap()
        endHearing()
        host.stopHearing()
    }

    private fun endHearing() {
        hear = null
        voiceLevel = 0f
        if (layout.grid && open == null) showClock()
    }

    private fun systemVoice() {
        if (!host.listen { heard(it, voiceFrom) }) showToast("this phone has no voice input")
    }

    private fun startHearing() {
        heardText = ""
        heardApp = null
        voiceLevel = 0f
        hear = Hear.Wait
        if (layout.grid) showClock()
        host.hear(hearEvents)
    }

    private val hearEvents = object : HearEvents {
        override fun ready() {
            if (hear == Hear.Wait) hear = Hear.Talk
        }

        override fun level(db: Float) {
            // The service reports about -2 dB in silence and about 10 dB for clear speech.
            if (hear == Hear.Talk) voiceLevel = ((db + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun partial(text: String) {
            if (hear == null || text.isBlank()) return
            heardText = text
            found(Voice.match(Voice.name(text), repo.apps) { it.label })
        }

        override fun end() {
            if (hear == Hear.Wait || hear == Hear.Talk) {
                hear = Hear.Think
                voiceLevel = 0f
            }
        }

        override fun results(said: List<String>) {
            if (hear == null) return
            if (said.none { Voice.name(it).isNotEmpty() }) return miss()
            heardText = said.first()
            hear = null
            voiceLevel = 0f
            heard(said, heardPose ?: voiceFrom)
        }

        override fun failed(error: Int) {
            if (hear == null) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> miss()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    endHearing()
                    systemVoice()
                }
                else -> {
                    endHearing()
                    showToast("voice input failed")
                }
            }
        }
    }

    /** The words name [app] so far. The dial turns to it, and the grid readout shows its name. */
    private fun found(app: AppEntry?) {
        if (app?.key == heardApp?.key) return
        heardApp = app
        if (app == null) {
            if (layout.grid) showClock()
            return
        }
        fb.tick()
        if (layout.grid) {
            clockJob?.cancel()
            readName = true
            readout.set(app.display, 1, 3, true, SystemClock.uptimeMillis(), false)
        }
        val row = dialRow(app)
        if (row != null) {
            target = row.toFloat()
            spin = Spin.Settle
        }
        run()
    }

    /** The row on the dial nearest to the notch that holds [app], or null when the app is not on the dial. */
    private fun dialRow(app: AppEntry): Int? {
        val apps = dialApps
        val i = apps.indexOfFirst { it.key == app.key }
        if (i < 0) return null
        val n = apps.size
        val base = (if (spin == Spin.Settle) target else pos).roundToInt()
        var d = mod(i - base, n)
        if (d > n / 2) d -= n
        return base + d
    }

    private fun miss() {
        hear = Hear.Miss
        voiceLevel = 0f
        found(null)
        fb.double()
    }

    /** Opens the app that [said] names. With no sure match, search shows the words. */
    private fun heard(said: List<String>, from: Pose) {
        val names = said.map(Voice::name).filter { it.isNotEmpty() }
        if (names.isEmpty() || blocked()) return
        val app = names.firstNotNullOfOrNull { n -> Voice.match(n, repo.apps) { it.label } }
        if (app == null) {
            if (layout.grid) showClock()
            openSearch()
            query = names.first()
            return
        }
        heardApp = app
        markRecent(app)
        // An app on the dial opens from the dial, like a tap on its name.
        val row = dialRow(app)
        if (row != null) {
            launchAt(row)
        } else {
            markSearched(app)
            openApp(app.display, app.key, app.pkgUser, if (layout.grid) readFrom() else from, "", false) { host.launchApp(app, it) }
        }
    }

    /** The name in the grid readout. An app that is not on the dial lifts off from here. */
    private fun readFrom() = Pose(RX, readY + 6.5f * RF, 0f, 20f / 44f, 0f)

    // Quick buttons: up to 3 apps in the bottom corner on the dial side.

    /** A tap on quick button [slot]. [x] and [y] give the start and the centre of its label in design units. */
    fun quickTap(slot: Int, x: Float, y: Float) {
        if (blocked()) return
        val app = quickApps.firstOrNull { it.slot == slot }?.app ?: return quickAdd()
        openApp(app.display, app.key, app.pkgUser, Pose(x, y, 0f, 13f / 44f, 0f), "", false) {
            host.launchApp(app, it)
        }
    }

    /** Hold quick button [slot]: a menu with the app shortcuts and the actions of the button. */
    fun quickHold(slot: Int, x: Float, y: Float) {
        if (blocked()) return
        val q = quickApps.firstOrNull { it.slot == slot } ?: return quickAdd()
        fb.click(true)
        fb.heavy()
        menu = QuickMenu(q.app, repo.shortcuts(q.app), slot, Pose(x, y, 0f, 13f / 44f, 0f))
        menuShown = true
    }

    /** Pick an app for a new quick button. */
    fun quickAdd() {
        if (blocked()) return
        openPick(quick.size)
    }

    private fun openPick(slot: Int) {
        openSearch()
        pick = true
        pickSlot = slot
    }

    /** A tap on an app while search picks a quick app. The sheet then shows the icons. */
    fun pickQuick(app: AppEntry) {
        fb.tick()
        fb.click(false)
        pickFor = app
    }

    /** Back from the icons to the list of apps. */
    fun unpick() {
        fb.tap()
        pickFor = null
    }

    /** The icon that the picker marks for [app]: the icon of its quick button, or else the icon that fits. Null marks the name. */
    fun iconFor(app: AppEntry): String? {
        val q = quick.firstOrNull { it.key == app.key }
        return if (q != null) q.icon else QuickIcons.suggest(app.label, app.component.packageName)
    }

    /** True when [app] is the only quick app after the pick. Only then does a button show the full name. */
    fun soloPick(app: AppEntry) = quick.put(pickSlot, app.key, null).size == 1

    /** Puts the picked app on the quick button with [icon], or with its name when [icon] is null. */
    fun setQuick(icon: String?) {
        val app = pickFor ?: return
        val before = quick
        val next = quick.put(pickSlot, app.key, icon)
        fb.double()
        fb.click(false)
        saveQuick(next)
        search = false
        if (next != before) {
            val text = if (before.any { it.key == app.key }) "changed the quick button" else "${app.display} is a quick app"
            showToast(text) { saveQuick(before) }
        }
    }

    private fun saveQuick(next: List<QuickSlot>) {
        quick = next
        prefs.quick = next
    }

    // Opening apps.

    private fun selectedPose(): Pose? {
        val app = curApp() ?: return null
        val n = app.display.length
        val s = selScale(n)
        val p = labelPose(0f, n, s, Geo(radius, cy, layout.ax), layout.markers, left)
        return Pose(p.x, p.y, p.rot, s * 15f / 44f, p.tx)
    }

    fun launchSelected() {
        val row = curApp() ?: return
        val from = selectedPose() ?: return
        // A folder opens its cover app; a swipe opens the folder.
        val app = row.target
        openApp(app.display, row.key, app.pkgUser, from, "", true) { host.launchApp(app, it) }
    }

    /** Opens the app at row [k]. The name lifts off where it is, and the dial turns the app into the notch. */
    private fun launchAt(k: Int) {
        val apps = dialApps
        if (apps.isEmpty()) return
        val row = apps[mod(k, apps.size)]
        launchFrom(k, row, row.target)
    }

    /** Opens [app] from dial row [k], which shows [row]. */
    private fun launchFrom(k: Int, row: AppEntry, app: AppEntry) {
        val d = k - pos
        val n = row.display.length
        val s = labelScale(abs(d), n)
        val p = labelPose(d, n, s, Geo(radius, cy, layout.ax), layout.markers, left)
        target = k.toFloat()
        spin = Spin.Settle
        openApp(app.display, row.key, app.pkgUser, Pose(p.x, p.y, p.rot, s * 15f / 44f, p.tx), "", true) {
            host.launchApp(app, it)
        }
    }

    private fun openApp(
        label: String,
        key: String?,
        pkgUser: String?,
        from: Pose,
        sub: String,
        fromDial: Boolean,
        origin: Pair<Float, Float>? = null,
        action: (Rect?) -> Unit,
    ) {
        if (open != null) return
        fb.click(true)
        fb.heavy()
        if (pkgUser != null) {
            val t = System.currentTimeMillis()
            opened = opened + (pkgUser to t)
            prefs.markOpened(pkgUser, t)
        }
        val grid = layout.grid
        // The grid ripple starts at the notch, or at the start of the row that opened the app.
        val (rx, ry) = origin ?: if (fromDial) notch() else (from.x + 6f) to from.y
        val o = OpenApp(++seq, label, key, sub, from, fromDial, Phase.From, grid, rx, ry)
        open = o
        search = false
        menu = null
        menuShown = false
        scope.launch {
            withFrameMillis { }
            withFrameMillis { }
            if (open?.id == o.id && open?.phase == Phase.From) open = o.copy(phase = Phase.In)
        }
        try {
            action(if (grid) dotBounds(rx, ry) else boundsOf(from, label))
        } catch (e: Exception) {
            open = null
            showToast("can't open $label")
        }
    }

    /** The notch in screen units. */
    private fun notch(): Pair<Float, Float> = mirX(layout.ax) to cy

    private fun dotBounds(x: Float, y: Float) =
        Rect(((x - 12) * k).roundToInt(), ((y - 12) * k).roundToInt(), ((x + 12) * k).roundToInt(), ((y + 12) * k).roundToInt())

    private fun boundsOf(p: Pose, label: String): Rect {
        val w = label.length * 24.64f * p.s
        val l = p.x + p.tx * w
        return Rect(
            (l * k).roundToInt(),
            ((p.y - 14) * k).roundToInt(),
            ((l + w) * k).roundToInt(),
            ((p.y + 14) * k).roundToInt(),
        )
    }

    fun close(silent: Boolean = false) {
        val o = open ?: return
        if (o.phase != Phase.In) return
        if (!silent) {
            fb.click(true)
            fb.tap()
        }
        // The name drops into the notch, also when it lifted off a different row.
        val back = if (o.fromDial && curApp()?.key == o.key) selectedPose() ?: o.from else o.from
        open = o.copy(phase = Phase.Out, from = back)
        scope.launch {
            // The grid ripple waits 140 ms, then shrinks for 540 ms.
            delay(if (o.ripple) 680 else 440)
            if (open?.id == o.id) open = null
            if (readName && drag == null) scheduleClock()
        }
    }

    // Hardware keys: j and k turn the dial, l or enter opens, slash searches.

    fun key(key: Key): Boolean {
        if (open != null) {
            if (key == Key.Escape || key == Key.Q || key == Key.H || key == Key.Backspace) {
                close()
                return true
            }
            return false
        }
        if (menu != null) {
            if (key == Key.Escape) closeMenu()
            return key == Key.Escape
        }
        if (search) {
            if (key == Key.Escape) {
                if (pickFor != null) unpick() else closeSearch()
            }
            return key == Key.Escape
        }
        val o = ob
        if (o == Step.WELCOME || o == Step.APPS || o == Step.READY) {
            if (key == Key.Enter) {
                obNext()
                return true
            }
            if (key == Key.Escape && o == Step.APPS) {
                obBack()
                return true
            }
            return false
        }
        when (key) {
            Key.DirectionDown, Key.J -> stepBy(1)
            Key.DirectionUp, Key.K -> stepBy(-1)
            Key.Enter, Key.L, Key.DirectionRight -> if (o != null) obNext() else launchSelected()
            Key.Escape -> if (o != null) obBack() else return false
            Key.Slash -> if (o == null) openSearch() else return false
            else -> return false
        }
        return true
    }
}
