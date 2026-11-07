package app.detent.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.format.DateFormat
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.app.NotificationManagerCompat
import app.detent.launcher.ui.DetentRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), Host {
    // AndroidUiDispatcher.Main carries the frame clock that the dial loop waits on.
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private lateinit var repo: AppsRepository
    private lateinit var state: LauncherState
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.refreshSystem()
    }
    private var pickDone: ((String?, Boolean) -> Unit)? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = pickDone ?: return@registerForActivityResult
        pickDone = null
        if (uri == null) {
            done(null, false)
            return@registerForActivityResult
        }
        val (w, h) = screenSize()
        val app = applicationContext
        thread(name = "detent-photo") {
            val path = runCatching { CustomWallpaper.save(app, uri, w, h).path }.getOrNull()
            runOnUiThread { done(path, path == null) }
        }
    }

    private var exportJson: String? = null
    private var exportDone: ((Boolean?) -> Unit)? = null
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exportJson
        val done = exportDone
        exportJson = null
        exportDone = null
        if (uri == null || json == null) {
            done?.invoke(null)
            return@registerForActivityResult
        }
        thread(name = "detent-export") {
            val ok = runCatching {
                val stream = contentResolver.openOutputStream(uri) ?: error("can't open output")
                stream.use { output -> java.io.OutputStreamWriter(output, Charsets.UTF_8).use { it.write(json) } }
            }.isSuccess
            runOnUiThread { done?.invoke(ok) }
        }
    }

    private var importDone: ((String?, Boolean) -> Unit)? = null
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = importDone ?: return@registerForActivityResult
        importDone = null
        if (uri == null) {
            done(null, false)
            return@registerForActivityResult
        }
        thread(name = "detent-import") {
            val result = runCatching {
                val stream = contentResolver.openInputStream(uri) ?: error("can't open input")
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            runOnUiThread { done(result.getOrNull(), result.isFailure) }
        }
    }

    private var heardDone: ((List<String>) -> Unit)? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val done = heardDone ?: return@registerForActivityResult
        heardDone = null
        val heard = if (r.resultCode == RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) else null
        done(heard.orEmpty())
    }

    private var micDone: ((Boolean) -> Unit)? = null
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micDone?.invoke(ok)
        micDone = null
    }
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        Synced.load(applicationContext)
        Synced.listeners += redraw
        // Lets a live wallpaper show behind the synced theme. Other themes paint over it.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        repo = AppsRepository(applicationContext)
        state = LauncherState(this, repo, Prefs(applicationContext), Feedback(applicationContext), scope)
        repo.start()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = state.back()
        })
        setContent { DetentRoot(state) }
        receiveThemeLink(intent)
    }

    override fun onResume() {
        super.onResume()
        state.onResume()
        state.syncGlass()
        TailnetSync.follow = { theme, device -> state.followTheme(theme, device) }
        TailnetSync.start(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        state.onPause()
    }

    override fun onStop() {
        super.onStop()
        state.onStop()
        TailnetSync.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val home = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        val alreadyHome = hasWindowFocus() && intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (home && alreadyHome) state.onHome()
        receiveThemeLink(intent)
    }

    private fun receiveThemeLink(incoming: Intent) {
        if (incoming.action == Intent.ACTION_VIEW && incoming.data?.scheme == "detent" && incoming.data?.host == "sync") {
            val n = TailnetSync.setHosts(applicationContext, incoming.data?.getQueryParameters("host").orEmpty())
            state.showToast(if (n > 0) "syncing with $n machine${if (n == 1) "" else "s"} on your tailnet" else "no tailnet address in that link")
            TailnetSync.stop()
            TailnetSync.start(applicationContext)
            setIntent(Intent(Intent.ACTION_MAIN))
            return
        }
        val text = when {
            incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain" -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            incoming.action == Intent.ACTION_VIEW && incoming.data?.scheme == "detent" && incoming.data?.host == "import" -> incoming.data?.getQueryParameter("url")
            else -> null
        } ?: return
        val link = sharedThemeUrl(text) ?: return
        state.openThemeImport(link)
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    override fun assistants(): List<Pair<String, String>> = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_ASSIST), 0)
        .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        .distinctBy { it.first }
        .filter { it.first != packageName }
        .sortedBy { it.second.lowercase() }

    override fun openAssistant(pkg: String): Boolean =
        open(Intent(Intent.ACTION_ASSIST).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun z0live(action: String): Boolean = open(Z0Live.command(action))

    override fun setGlassBlur(look: GlassLook?) {
        if (Build.VERSION.SDK_INT < 31) return
        val on = look != null && windowManager.isCrossWindowBlurEnabled
        window.setBackgroundBlurRadius(if (on) look!!.blurPx(screenSize().first) else 0)
    }

    override fun openLiveWallpaper(theme: ThemeDef): Boolean {
        val f = theme.mobilePackage?.let { java.io.File(it) }
            ?: if (theme.group == Synced.LIVE_GROUP) Synced.liveFile(applicationContext, theme) else null
        // With the wallpaper switch on, Detent applies the scene itself: no tap inside Wallpaper Engine.
        val title = if (theme.group == Synced.LIVE_GROUP) Synced.liveTitle(theme) else null
        if (title != null && WallpaperSwitch.enabled(this)) {
            val we = packageManager.getLaunchIntentForPackage(WallpaperSwitch.WE_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val importIt: (() -> Boolean)? = f?.let { file -> { openPackage(file) } }
            if (we != null && WallpaperSwitch.request(title, theme.preview, importIt) { ok ->
                    if (!ok) state.showToast("tap ✓ in wallpaper engine to finish")
                } && open(we)) return true
        }
        if (f == null) return theme.liveSource?.let { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false
        return openPackage(f)
    }

    private fun openPackage(f: java.io.File): Boolean {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.live", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(WallpaperSwitch.WE_PACKAGE)
        return open(view) || open(view.setPackage(null))
    }

    override fun liveWallpaperPackage(): String? =
        runCatching { android.app.WallpaperManager.getInstance(this).wallpaperInfo?.packageName }.getOrNull()

    override fun wallpaperSwitchOn() = WallpaperSwitch.enabled(this)

    override fun publishTheme(theme: String) = TailnetSync.publish(this, theme)

    override fun openWallpaperSwitchSettings() {
        val cn = ComponentName(this, WallpaperSwitch::class.java).flattenToString()
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            .putExtra(":settings:fragment_args_key", cn)
        if (!open(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private val redraw: () -> Unit = { runOnUiThread { recreate() } }

    override fun onDestroy() {
        Synced.listeners -= redraw
        stopHearing()
        repo.stop()
        state.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun reveal(b: Rect?): Bundle? = b?.let {
        ActivityOptions.makeClipRevealAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle()
    }

    override fun sendIntent(intent: android.app.PendingIntent, bounds: Rect?): Boolean = runCatching {
        // Detent is on screen, so the app it hands the tap to may open its activity.
        val o = bounds?.let { ActivityOptions.makeClipRevealAnimation(window.decorView, it.left, it.top, it.width(), it.height()) }
            ?: ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        intent.send(this, 0, null, null, null, null, o.toBundle())
    }.isSuccess

    override fun launchApp(app: AppEntry, bounds: Rect?) =
        launcherApps.startMainActivity(app.component, app.user, bounds, reveal(bounds))

    private fun webIntent(query: String) = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)

    /** The screens that take a web search from Detent. Chrome lists a web search screen that other apps cannot start. */
    private fun webScreens() = packageManager.queryIntentActivities(webIntent(""), PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo }.filter { it.exported }

    override fun canSearchWeb() = webScreens().isNotEmpty()

    override fun searchWeb(query: String, bounds: Rect?) {
        val web = webIntent(query)
        // The default browser searches with its own search engine. When it takes no web search, Android picks the app or asks.
        val browser = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        webScreens().firstOrNull { it.packageName == browser }?.let { web.setClassName(it.packageName, it.name) }
        startActivity(web, reveal(bounds))
    }

    override fun launchShortcut(info: ShortcutInfo, bounds: Rect?) =
        launcherApps.startShortcut(info, bounds, reveal(bounds))

    override fun showAppInfo(app: AppEntry, bounds: Rect?) =
        launcherApps.startAppDetailsActivity(app.component, app.user, bounds, reveal(bounds))

    override fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    override fun requestHome() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    override fun hasNotificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    override fun openNotificationAccess() {
        val cn = ComponentName(this, UnreadService::class.java).flattenToString()
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
        } else null
        if (detail == null || !open(detail)) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun is24Hour() = DateFormat.is24HourFormat(this)

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        @Suppress("DEPRECATION")
        DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }

    override fun setWallpaper(
        theme: ThemeDef,
        pal: Palette,
        left: Boolean,
        custom: String?,
        grid: Boolean,
        done: (Boolean) -> Unit,
    ) {
        val (w, h) = screenSize()
        val app = applicationContext
        thread(name = "detent-wallpaper") {
            val ok = runCatching { SystemWallpaper.apply(app, theme, pal, left, custom, grid, w, h) }.isSuccess
            runOnUiThread { done(ok) }
        }
    }

    override fun pickWallpaper(done: (path: String?, failed: Boolean) -> Unit) {
        pickDone = done
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            pickDone = null
            done(null, true)
        }
    }

    override fun exportSettings(json: String, done: (Boolean?) -> Unit) {
        exportJson = json
        exportDone = done
        try {
            exportDocument.launch("detent-settings.json")
        } catch (e: Exception) {
            exportJson = null
            exportDone = null
            done(false)
        }
    }

    override fun importSettings(done: (String?, Boolean) -> Unit) {
        importDone = done
        try {
            importDocument.launch(arrayOf("application/json", "text/plain"))
        } catch (e: Exception) {
            importDone = null
            done(null, true)
        }
    }

    override fun listen(done: (List<String>) -> Unit): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "say an app name")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        heardDone = done
        return try {
            speech.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            heardDone = null
            false
        }
    }

    override fun canHear() = SpeechRecognizer.isRecognitionAvailable(this)

    override fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun askMic(done: (Boolean) -> Unit) {
        micDone = done
        micRequest.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun hear(events: HearEvents) {
        stopHearing()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        // Events from a recognizer that Detent stopped do not count.
        fun live() = recognizer === r
        fun texts(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (live()) events.ready() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (live()) events.level(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (live()) events.end() }
            override fun onPartialResults(partialResults: Bundle?) {
                if (live()) texts(partialResults).firstOrNull()?.let(events::partial)
            }
            override fun onResults(results: Bundle?) {
                if (!live()) return
                stopHearing()
                events.results(texts(results))
            }
            override fun onError(error: Int) {
                if (!live()) return
                stopHearing()
                events.failed(error)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName),
        )
    }

    override fun finishHearing() {
        recognizer?.stopListening()
    }

    override fun stopHearing() {
        val r = recognizer ?: return
        recognizer = null
        r.cancel()
        r.destroy()
    }

    override fun cleanWallpapers(keep: String?) {
        val app = applicationContext
        thread(name = "detent-clean") { CustomWallpaper.clean(app, keep) }
    }

    // Android has no public call that opens the notification shade. Launchers use this hidden call. It needs EXPAND_STATUS_BAR.
    @SuppressLint("WrongConstant", "DiscouragedPrivateApi")
    override fun openNotifications(): Boolean = runCatching {
        val bar = getSystemService("statusbar") ?: return false
        bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
    }.isSuccess

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
