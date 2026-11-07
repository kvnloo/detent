package app.detent.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Z0LiveTest {
    /** The exact companion launcher contract: action, package, and only FLAG_ACTIVITY_NEW_TASK. */
    @Test fun commandsAreTheCompanionContract() {
        for ((action, expected) in listOf(Z0Live.MUTE to "z0live.action.MUTE", Z0Live.END to "z0live.action.END")) {
            val i = Z0Live.command(action)
            assertEquals(expected, i.action)
            assertEquals("app.zer0.z0live", i.`package`)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, i.flags)
            assertNull(i.component)
            assertNull(i.data)
        }
    }
}
