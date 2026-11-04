package app.detent.launcher

import android.accessibilityservice.AccessibilityService
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView

/** What the switcher needs from a screen. Real screens come from accessibility nodes; tests build their own. */
interface UiNode {
    val pkg: String?
    val id: String?
    val text: String?
    val desc: String?
    val clickable: Boolean
    val scrollable: Boolean
    val parent: UiNode?
    val children: List<UiNode>
}

/** One step of applying a scene inside Wallpaper Engine. */
sealed interface SwitchStep {
    /** Tap [node] and move to [next]. */
    data class Tap(val node: UiNode, val next: SwitchPhase) : SwitchStep
    data class Scroll(val node: UiNode) : SwitchStep
    data object Back : SwitchStep
    data object Wait : SwitchStep
    /** The scene isn't in Wallpaper Engine's library: import the package, which opens it ready to apply. */
    data object Import : SwitchStep
    data object Done : SwitchStep
}

enum class SwitchPhase { Find, Applied, Confirm }

/**
 * Applies a Wallpaper Engine scene the way a person would: find its tile by title, open it, tap the check mark.
 * When Wallpaper Engine is not the live wallpaper yet, Android shows its own preview, which needs "set
 * wallpaper" and then "home and lock screens". Pure, so it is tested without a phone.
 */
object SwitchPlan {
    const val WE = "io.wallpaperengine.weclient"
    private const val APPLY = "$WE:id/item_apply"
    private const val TILE = "$WE:id/ibWallpaper"
    private const val TITLE = "$WE:id/tvTitle"
    const val MAX_SCROLLS = 14

    private val SET = listOf("set wallpaper", "set as wallpaper", "apply")
    private val BOTH = listOf("home and lock screens", "home and lock screen", "home screen and lock screen", "both")

    fun step(root: UiNode, title: String, phase: SwitchPhase, scrolls: Int): SwitchStep {
        val all = flatten(root)
        if (root.pkg == WE) {
            if (phase != SwitchPhase.Find) return SwitchStep.Wait
            val apply = all.firstOrNull { it.id == APPLY }
            if (apply != null) {
                // The scene's preview: its title is in the toolbar.
                return if (all.any { it.text.equals(title, ignoreCase = true) }) SwitchStep.Tap(apply, SwitchPhase.Applied)
                else SwitchStep.Back
            }
            val tiles = all.filter { it.id == TITLE }
            if (tiles.isEmpty()) return SwitchStep.Wait
            tiles.firstOrNull { it.text.equals(title, ignoreCase = true) }?.let { label ->
                val tile = label.parent?.children?.firstOrNull { it.id == TILE } ?: clickableAncestor(label)
                if (tile != null) return SwitchStep.Tap(tile, SwitchPhase.Find)
            }
            val list = all.firstOrNull { it.scrollable }
            return if (list != null && scrolls < MAX_SCROLLS) SwitchStep.Scroll(list) else SwitchStep.Import
        }
        if (phase == SwitchPhase.Find) return SwitchStep.Wait
        // Android's live wallpaper preview, shown when Wallpaper Engine isn't the wallpaper yet.
        all.firstOrNull { n -> BOTH.any { n.label().equals(it, ignoreCase = true) } }?.let {
            return SwitchStep.Tap(clickableAncestor(it) ?: it, SwitchPhase.Confirm)
        }
        if (phase == SwitchPhase.Applied) {
            all.firstOrNull { n -> SET.any { n.label().equals(it, ignoreCase = true) } }?.let {
                return SwitchStep.Tap(clickableAncestor(it) ?: it, SwitchPhase.Applied)
            }
        }
        return SwitchStep.Wait
    }

    private fun UiNode.label() = (text ?: desc ?: "").trim()

    private fun clickableAncestor(n: UiNode): UiNode? {
        var c: UiNode? = n
        while (c != null && !c.clickable) c = c.parent
        return c
    }

    private fun flatten(root: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<UiNode>().apply { add(root) }
        while (stack.isNotEmpty() && out.size < 2000) {
            val n = stack.removeLast()
            out += n
            n.children.asReversed().forEach { stack.addLast(it) }
        }
        return out
    }
}

private class A11yNode(val info: AccessibilityNodeInfo, override val parent: UiNode?) : UiNode {
    override val pkg get() = info.packageName?.toString()
    override val id: String? get() = info.viewIdResourceName
    override val text get() = info.text?.toString()
    override val desc get() = info.contentDescription?.toString()
    override val clickable get() = info.isClickable
    override val scrollable get() = info.isScrollable
    override val children: List<UiNode> by lazy {
        (0 until info.childCount).mapNotNull { info.getChild(it)?.let { c -> A11yNode(c, this) } }
    }
}

/**
 * Detent's wallpaper switch. It acts only for a few seconds after Detent asks it to, and only on Wallpaper
 * Engine and Android's wallpaper preview. While it works, an overlay shows the scene's still, so the change
 * looks like the picker's own transition instead of another app flashing by.
 */
class WallpaperSwitch : AccessibilityService() {

    private class Request(val title: String, val preview: String?, val import: (() -> Boolean)?, val done: (Boolean) -> Unit) {
        val started = SystemClock.uptimeMillis()
        var phase = SwitchPhase.Find
        var scrolls = 0
        var imported = false
        var lastTap = 0L
        var appliedAt = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private var req: Request? = null
    private var overlay: ImageView? = null

    override fun onServiceConnected() {
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        finish(false)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (req != null) main.removeCallbacks(tick).also { main.postDelayed(tick, 120) }
    }

    private val tick: Runnable = Runnable { advance() }

    private fun begin(r: Request) {
        req?.let { finish(false) }
        req = r
        showOverlay(r.preview)
        main.postDelayed(tick, 400)
        main.postDelayed({ if (req === r) finish(false) }, TIMEOUT)
    }

    private fun advance() {
        val r = req ?: return
        val now = SystemClock.uptimeMillis()
        // Wallpaper Engine switches silently when it is already the wallpaper: it stays on its screen.
        if (r.phase == SwitchPhase.Applied && now - r.appliedAt > 1500 && liveIsWe()) return finish(true)
        if (r.phase == SwitchPhase.Confirm && now - r.appliedAt > 700) return finish(liveIsWe())
        val root = rootInActiveWindow ?: return again()
        if (now - r.lastTap < 450) return again()
        when (val s = SwitchPlan.step(A11yNode(root, null), r.title, r.phase, r.scrolls)) {
            is SwitchStep.Tap -> {
                if (s.node is A11yNode) s.node.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (s.next != SwitchPhase.Find && r.phase != s.next) r.appliedAt = now
                r.phase = s.next
                r.lastTap = now
            }
            is SwitchStep.Scroll -> {
                if (s.node is A11yNode) s.node.info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                r.scrolls++
                r.lastTap = now
            }
            SwitchStep.Back -> { performGlobalAction(GLOBAL_ACTION_BACK); r.lastTap = now }
            SwitchStep.Import -> {
                if (r.imported || r.import?.invoke() != true) return finish(false)
                r.imported = true
                r.scrolls = 0
                r.lastTap = now
            }
            SwitchStep.Done -> return finish(true)
            SwitchStep.Wait -> {}
        }
        again()
    }

    private fun again() {
        if (req != null) main.postDelayed(tick, 250)
    }

    private fun liveIsWe() = runCatching {
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == SwitchPlan.WE
    }.getOrDefault(false)

    private fun finish(ok: Boolean) {
        val r = req ?: return
        req = null
        main.removeCallbacks(tick)
        if (r.phase != SwitchPhase.Find || r.imported) performGlobalAction(GLOBAL_ACTION_HOME)
        hideOverlay()
        r.done(ok)
    }

    private fun showOverlay(path: String?) {
        hideOverlay()
        val bmp = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } ?: return
        val v = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { getSystemService(WindowManager::class.java).addView(v, lp) }.onSuccess {
            overlay = v
            v.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        // The home screen is back under the overlay by now; fade so the live scene takes over from its still.
        v.animate().alpha(0f).setStartDelay(250).setDuration(380).withEndAction {
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        }.start()
    }

    companion object {
        const val WE_PACKAGE = SwitchPlan.WE
        private const val TIMEOUT = 15_000L
        private var running: WallpaperSwitch? = null

        /** True when the user turned the switch on in accessibility settings. */
        fun enabled(ctx: Context): Boolean {
            if (running != null) return true
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") && it.endsWith("WallpaperSwitch") }
        }

        /**
         * Applies the scene titled [title]. Call right after opening Wallpaper Engine. [import] hands the
         * package over when the scene isn't in its library. Returns false when the switch isn't running.
         */
        fun request(title: String, preview: String?, import: (() -> Boolean)?, done: (Boolean) -> Unit): Boolean {
            val s = running ?: return false
            s.begin(Request(title, preview, import, done))
            return true
        }
    }
}
