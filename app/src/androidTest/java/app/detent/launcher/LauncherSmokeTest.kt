package app.detent.launcher

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchAndThemeSetupRenderWithTheBuiltSourceIdentity() {
        compose.onRoot().assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = context.assets.open("detent-build.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(BuildConfig.SOURCE_SHA, identity.getString("source_sha"))
        assertEquals(BuildConfig.CHANNEL, identity.getString("channel"))
        assertTrue(BuildConfig.SOURCE_SHA.matches(Regex("[0-9a-f]{40}")))
        compose.onNodeWithText("begin").performClick()
        compose.onNodeWithText("next").performClick()
        compose.onNodeWithText("omarchy").assertIsDisplayed().performClick()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val out = File(context.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
        File(out, "theme-setup.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
