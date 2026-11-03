package app.detent.launcher

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class ImportedThemesTest {
    private lateinit var ctx: Context
    private lateinit var original: List<ThemeDef>
    private val favorite = ThemeCandidate("Ruins", "https://example.com/themes.json", workshop = "https://steamcommunity.com/sharedfiles/filedetails/?id=2133182232")

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        original = THEMES.toList()
        File(ctx.filesDir, "themes").deleteRecursively()
        File(ctx.filesDir, "live").deleteRecursively()
    }

    @After fun cleanup() {
        THEMES.clear(); THEMES.addAll(original)
        PALETTES.clear(); PALETTES.addAll(original.map(::Palette))
        THEME_NAMES.clear(); THEME_NAMES.addAll(original.map { it.name })
    }

    @Test fun linkFavoritesPersistAndReimportDoesNotDuplicateThem() {
        val first = ImportedThemes.install(ctx, listOf(favorite)) { }
        assertEquals(1, first.added)
        ImportedThemes.load(ctx)
        val theme = THEMES.last()
        assertEquals(Synced.SYSTEM_WALL, theme.wall)
        assertEquals(favorite.workshop, theme.liveSource)
        assertNull(theme.mobilePackage)
        assertNotNull(theme.importId)
        val size = THEMES.size
        val second = ImportedThemes.install(ctx, listOf(favorite)) { }
        ImportedThemes.load(ctx)
        assertEquals(0, second.added)
        assertEquals(1, second.existing)
        assertEquals(size, THEMES.size)
        // Simulate a process restart with just the original built-ins in memory.
        THEMES.clear(); THEMES.addAll(original)
        PALETTES.clear(); PALETTES.addAll(original.map(::Palette))
        THEME_NAMES.clear(); THEME_NAMES.addAll(original.map { it.name })
        ImportedThemes.load(ctx)
        assertEquals(theme.importId, THEMES.last().importId)
    }

    @Test fun unreadableLibraryIsNotOverwrittenByAnImport() {
        val file = File(ctx.filesDir, "themes/index.json").apply { parentFile!!.mkdirs(); writeText("broken index") }
        assertThrows(Exception::class.java) { ImportedThemes.install(ctx, listOf(favorite)) { } }
        assertEquals("broken index", file.readText())
    }

    @Test fun savedImportedIdentitySurvivesALiveThemeBeingInsertedBeforeIt() {
        ImportedThemes.install(ctx, listOf(favorite)) { }
        ImportedThemes.load(ctx)
        val prefs = Prefs(ctx)
        prefs.save(Cfg(theme = THEMES.lastIndex), emptySet(), 0)
        val id = THEMES.last().importId
        THEMES.add(0, Synced.theme(0xFF000000, 0xFFFFFFFF, 0xFFFF8800))
        assertEquals(id, THEMES[prefs.load()!!.cfg.theme].importId)
    }

    @Test fun liveReloadKeepsImportedFavoritesAndParallelListsAligned() {
        ImportedThemes.install(ctx, listOf(favorite)) { }
        ImportedThemes.load(ctx)
        val id = THEMES.last().importId
        File(ctx.filesDir, "live/themes.json").apply {
            parentFile!!.mkdirs()
            writeText("""[{"name":"legacy-live","bg":"181f23","fg":"d1deda","accent":"c8be72","file":"legacy.mpkg"}]""")
        }
        Synced.loadLive(ctx)
        assertEquals(id, THEMES.last().importId)
        assertEquals(1, THEMES.count { it.importId == id })
        assertEquals(1, THEMES.count { it.name == "legacy-live" })
        assertEquals(THEMES.size, PALETTES.size)
        assertEquals(THEMES.map { it.name }, THEME_NAMES)
    }
}
