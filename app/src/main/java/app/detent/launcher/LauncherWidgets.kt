package app.detent.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

data class LauncherWidget(val id: Int, val name: String)

/** Android owns widget IDs and RemoteViews. Detent owns only their order and pending picker ID. */
class LauncherWidgets(
    private val activity: ComponentActivity,
    private val prefs: Prefs,
    private val changed: () -> Unit,
    private val failed: (String) -> Unit,
) {
    private val host = AppWidgetHost(activity, 0xD37E)
    private val manager = AppWidgetManager.getInstance(activity)

    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val allocated = prefs.pendingWidgetId ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            abandon(allocated)
            return@registerForActivityResult
        }
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocated) ?: allocated
        if (id <= 0) {
            abandon(allocated)
            failed("widget not selected")
            return@registerForActivityResult
        }
        if (id != allocated) {
            runCatching { host.deleteAppWidgetId(allocated) }
            prefs.pendingWidgetId = id
        }
        if (manager.getAppWidgetInfo(id) != null) {
            configureOrSave(id)
            return@registerForActivityResult
        }
        val provider = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra<ComponentName>(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER)
        }
        if (provider == null) {
            abandon(id)
            failed("widget provider unavailable")
            return@registerForActivityResult
        }
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(id, provider) }.getOrDefault(false)
        if (bound) {
            configureOrSave(id)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            try {
                bind.launch(intent)
            } catch (_: ActivityNotFoundException) {
                abandon(id)
                failed("widget binding unavailable")
            } catch (_: SecurityException) {
                abandon(id)
                failed("widget binding denied")
            }
        }
    }

    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = prefs.pendingWidgetId ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureOrSave(id) else abandon(id)
    }

    private val configure = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = prefs.pendingWidgetId ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) save(id) else abandon(id)
    }

    fun start() { runCatching { host.startListening() }.onFailure { failed("can't load widgets") } }
    fun stop() { host.stopListening() }

    /** Missing providers cannot be rendered. Release their IDs, but preserve all still-bound widgets. */
    fun entries(): List<LauncherWidget> {
        val saved = prefs.widgetIds
        val active = saved.mapNotNull { id ->
            manager.getAppWidgetInfo(id)?.let { LauncherWidget(id, it.label ?: it.provider.shortClassName) }
        }
        if (active.size != saved.size) {
            saved.filter { id -> active.none { it.id == id } }.forEach { runCatching { host.deleteAppWidgetId(it) } }
            prefs.widgetIds = active.map { it.id }
        }
        return active
    }

    fun view(id: Int): View {
        val info = manager.getAppWidgetInfo(id) ?: return FrameLayout(activity)
        return host.createView(activity, id, info).apply {
            // RemoteViews adapt to the space opposite Detent's dial, not the full screen.
            updateAppWidgetSize(null, 248, 158, 248, 158)
        }
    }

    fun add(): Boolean {
        prefs.pendingWidgetId?.let { abandon(it) }
        val id = runCatching { host.allocateAppWidgetId() }.getOrElse { return false }
        prefs.pendingWidgetId = id
        return try {
            picker.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            true
        } catch (_: ActivityNotFoundException) {
            abandon(id)
            false
        } catch (_: SecurityException) {
            abandon(id)
            false
        }
    }

    fun remove(id: Int) {
        if (id !in prefs.widgetIds) return
        prefs.widgetIds = prefs.widgetIds - id
        runCatching { host.deleteAppWidgetId(id) }
        changed()
    }

    private fun configureOrSave(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            abandon(id)
            failed("widget could not be bound")
            return
        }
        val component = info.configure
        if (component == null) {
            save(id)
            return
        }
        try {
            configure.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(component)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        } catch (_: ActivityNotFoundException) {
            abandon(id)
            failed("widget configuration unavailable")
        } catch (_: SecurityException) {
            abandon(id)
            failed("widget configuration denied")
        }
    }

    private fun save(id: Int) {
        prefs.widgetIds = prefs.widgetIds + id
        prefs.pendingWidgetId = null
        changed()
    }

    private fun abandon(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
        prefs.pendingWidgetId = null
    }
}
