package app.detent.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClockIntentsTest {
    private val manager get() = RuntimeEnvironment.getApplication().packageManager

    private fun handler(intent: Intent, packageName: String = "test.clock") {
        shadowOf(manager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.ClockActivity"
                exported = true
            }
        })
    }

    @Test fun alarmsTakePriorityOverOtherClockScreens() {
        handler(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        handler(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, ClockIntents.resolved(manager).first().action)
    }

    @Test fun timersWorkWhenAlarmsHaveNoHandler() {
        handler(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        assertEquals(AlarmClock.ACTION_SHOW_TIMERS, ClockIntents.resolved(manager).first().action)
    }

    @Test fun noClockHandlerGivesSafeEmptyResult() {
        assertTrue(ClockIntents.resolved(manager).isEmpty())
    }
}
