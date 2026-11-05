package app.detent.launcher

import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class WidgetsTest {
    private lateinit var activity: ComponentActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        activity.getSharedPreferences("widgets", 0).edit().clear().commit()
    }

    @Test fun widgetIdsSurviveHostRecreationWithoutDuplicates() {
        activity.getSharedPreferences("widgets", 0).edit().putString("ids", "12,23,12,broken").commit()
        assertEquals(listOf(12, 23), Widgets(activity).ids)
    }

    @Test fun removingAWidgetPersistsWithoutTouchingOtherWidgets() {
        activity.getSharedPreferences("widgets", 0).edit().putString("ids", "12,23").commit()
        val widgets = Widgets(activity)
        widgets.remove(12)
        assertEquals(listOf(23), widgets.ids)
        assertEquals(listOf(23), Widgets(activity).ids)
        widgets.remove(23)
        assertTrue(Widgets(activity).ids.isEmpty())
    }
}
