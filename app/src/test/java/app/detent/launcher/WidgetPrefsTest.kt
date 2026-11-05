package app.detent.launcher

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WidgetPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun widgetsKeepOrderAcrossPrefsRecreationAndRemoval() {
        context.getSharedPreferences("detent", Context.MODE_PRIVATE).edit().clear().commit()
        val first = Prefs(context)
        first.widgetIds = listOf(13, 7, 13, -1)
        assertEquals(listOf(13, 7), Prefs(context).widgetIds)
        val second = Prefs(context)
        second.widgetIds = second.widgetIds - 13
        assertEquals(listOf(7), Prefs(context).widgetIds)
    }

    @Test fun pendingWidgetPersistsUntilCompletedOrCancelled() {
        context.getSharedPreferences("detent", Context.MODE_PRIVATE).edit().clear().commit()
        val first = Prefs(context)
        assertNull(first.pendingWidgetId)
        first.pendingWidgetId = 42
        assertEquals(42, Prefs(context).pendingWidgetId)
        Prefs(context).pendingWidgetId = null
        assertNull(Prefs(context).pendingWidgetId)
    }
}
