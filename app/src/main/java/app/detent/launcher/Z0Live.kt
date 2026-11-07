package app.detent.launcher

import android.content.Intent

/**
 * The z0live companion's launcher contract: the same intents Lawnchair and adb send to its exported
 * Z0LiveCommandActivity. The action strings mirror z0live/core Z0LiveCommands in kvnloo/lawnchair.
 * MUTE toggles the microphone of a live call and END ends it; without a live call both do nothing.
 */
object Z0Live {
    const val PACKAGE = "app.zer0.z0live"
    const val MUTE = "z0live.action.MUTE"
    const val END = "z0live.action.END"

    fun command(action: String): Intent = Intent(action).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
