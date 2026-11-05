package app.detent.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock

/** Ask Android for its preferred clock handler; never hard-code a manufacturer's package. */
internal object ClockIntents {
    internal fun resolved(packageManager: PackageManager): List<Intent> {
        // SHOW_ALARMS opens the clock at the alarm list. Some clock apps expose only timers or SET_ALARM.
        val clockPackage = packageManager.resolveActivity(
            Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return listOfNotNull(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(AlarmClock.ACTION_SHOW_TIMERS),
            clockPackage?.let { packageManager.getLaunchIntentForPackage(it) },
        ).filter { it.resolveActivity(packageManager) != null }
    }

    fun open(activity: Activity): Boolean {
        for (intent in resolved(activity.packageManager)) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // The chosen app may have been removed between resolution and launch.
            } catch (_: SecurityException) {
                // An OEM clock can expose an unlaunchable component. Try the next handler.
            }
        }
        return false
    }
}
