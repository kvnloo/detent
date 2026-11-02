package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val current = SettingsData(
        layout = "soft · radial · double",
        theme = "dusk",
        side = "right",
        apps = setOf("camera#0", "maps#0", "phone#0"),
        quick = listOf(QuickSlot("camera#0", "camera")),
        recent = listOf("maps#0"),
        sound = true,
        haptics = true,
        dots = true,
        lockWall = true,
        ownBar = false,
        voice = true,
        webSearch = true,
    )
    private val apps = setOf("camera#0", "maps#0", "phone#0", "mail#0")
    private val layouts = setOf("soft · radial · double", "grid · lens")
    private val themes = setOf("dusk", "paper")

    private fun read(text: String) = SettingsBackup.fromJson(text, current, layouts, themes, apps)

    @Test fun roundTripKeepsEverySetting() {
        val settings = current.copy(
            layout = "grid · lens",
            theme = "paper",
            side = "left",
            apps = setOf("phone#0", "camera#0", "maps#0"),
            quick = listOf(QuickSlot("mail#0", "letter"), QuickSlot("maps#0", null)),
            recent = listOf("mail#0", "phone#0"),
            sound = false,
            haptics = false,
            dots = false,
            lockWall = false,
            ownBar = true,
            voice = false,
            webSearch = false,
        )
        val json = SettingsBackup.toJson(settings)
        assertTrue(json.contains("\"version\": 1"))
        assertEquals(settings, read(json))
    }

    @Test fun unknownThemeNameKeepsCurrentTheme() {
        val parsed = read("""{"version":1,"theme":"not-on-this-phone","layout":"grid · lens"}""")
        assertEquals("dusk", parsed.theme)
        assertEquals("grid · lens", parsed.layout)
    }

    @Test fun unknownKeysAreIgnored() {
        val parsed = read("""{"version":1,"future":{"nested":[1,2]},"sound":false}""")
        assertEquals(current.copy(sound = false), parsed)
    }

    @Test fun unavailableAppsAreSkipped() {
        val parsed = read(
            """{"version":1,"apps":["camera#0","gone#0","maps#0"],""" +
                """"quick":[{"key":"gone#0"},{"key":"camera#0","icon":"camera"}],""" +
                """"recent":["gone#0","maps#0"]}"""
        )
        assertEquals(setOf("camera#0", "maps#0"), parsed.apps)
        assertEquals(listOf(QuickSlot("camera#0", "camera")), parsed.quick)
        assertEquals(listOf("maps#0"), parsed.recent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedVersionIsRejected() {
        read("""{"version":2}""")
    }

    @Test fun missingFieldsKeepCurrentValues() {
        val parsed = read("""{"version":1,"voice":false}""")
        assertFalse(parsed.voice)
        assertEquals(current.theme, parsed.theme)
        assertEquals(current.quick, parsed.quick)
    }
}
