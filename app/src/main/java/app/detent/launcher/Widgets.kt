package app.detent.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Widget IDs belong to this installation, not to a portable settings backup. */
class Widgets(private val activity: ComponentActivity) {
    val host = AppWidgetHost(activity, 1024)
    private val manager = AppWidgetManager.getInstance(activity)
    private val prefs = activity.getSharedPreferences("widgets", 0)
    var ids by mutableStateOf(readIds())
        private set
    var shown by mutableStateOf(false)
    private var pending: Int
        get() = prefs.getInt("pending", AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) { prefs.edit().putInt("pending", value).apply() }

    private val configure = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish() else cancel()
    }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pending
        val returned = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) ?: id
        if (result.resultCode != Activity.RESULT_OK || returned != id) {
            cancel()
        } else {
            val info = manager.getAppWidgetInfo(id)
            when {
                info == null -> cancel()
                info.configure == null -> finish()
                else -> runCatching {
                    configure.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                }.onFailure { cancel() }
            }
        }
    }

    fun add() {
        if (pending != AppWidgetManager.INVALID_APPWIDGET_ID) return
        pending = host.allocateAppWidgetId()
        runCatching {
            picker.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending))
        }.onFailure { cancel() }
    }

    private fun finish() {
        val id = pending
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && manager.getAppWidgetInfo(id) != null) {
            ids = (ids + id).distinct()
            save()
        } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            host.deleteAppWidgetId(id)
        }
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        shown = true
    }

    private fun cancel() {
        val id = pending
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id)
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        shown = true
    }

    fun remove(id: Int) {
        ids = ids - id
        save()
        host.deleteAppWidgetId(id)
    }

    fun info(id: Int) = manager.getAppWidgetInfo(id)

    fun options(id: Int, width: Int, height: Int) {
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        })
    }

    private fun readIds() = prefs.getString("ids", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.distinct()
    private fun save() { prefs.edit().putString("ids", ids.joinToString(",")).apply() }
}
