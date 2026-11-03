package app.detent.launcher

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ThemeSourcesTest {
    @Test fun publicHttpsOnly() {
        listOf("http://example.com/a", "file:///etc/passwd", "https://localhost/a", "https://box.local/a", "https://user:pass@example.com/a", "https://example.com:8080/a")
            .forEach { assertThrows(IllegalArgumentException::class.java) { themeUrl(it) } }
        assertEquals("example.com", themeUrl("https://example.com/collection.json").host)
    }

    @Test fun sharedTextFindsTheLinkWithoutFetchingIt() {
        assertEquals("https://github.com/kvnloo/.files", sharedThemeUrl("try these: https://github.com/kvnloo/.files."))
        assertNull(sharedThemeUrl("no link here"))
    }

    @Test fun githubBlobLinksBecomeRawDownloads() {
        assertEquals("https://raw.githubusercontent.com/a/b/main/walls/sky%20blue.jpg", ThemeSources.direct("https://github.com/a/b/blob/main/walls/sky%20blue.jpg"))
    }

    @Test fun anImageLinkNeedsNoMetadataRequest() {
        val found = ThemeSources.discover("https://example.com/sky.webp") { error("should not fetch") }
        assertEquals("https://example.com/sky.webp", found.themes.single().image)
    }

    @Test fun manifestsResolveRelativeImagesAndOptionalColours() {
        val found = ThemeSources.manifest("""{"themes":[{"name":"sun","image":"walls/sun.webp","bg":"#ffffff","fg":"112233","accent":"abcdef"}]}""", "https://example.com/pack/themes.json").single()
        assertEquals("https://example.com/pack/walls/sun.webp", found.image)
        assertEquals(0xFFFFFFFFL, found.bg)
        assertFalse(found.live)
    }

    @Test fun dotfilesWorkshopFavoritesDoNotPretendLocalPackagesArePublished() {
        val found = ThemeSources.manifest("""{"workshop_dir":"/pc/Steam","mobile_store":"/data/mpkg","wallpapers":[{"title":"Forgotten Ruins","workshop_url":"https://steamcommunity.com/sharedfiles/filedetails/?id=2133182232","mobile":{"file":"2133182232.mpkg"}}]}""", "https://example.com/config/live.json").single()
        assertTrue(found.live)
        assertNull(found.mobile)
        assertEquals("Forgotten Ruins", found.name)
    }

    @Test fun publishedMobilePackagesRemainDistinctFromWorkshopLinks() {
        val found = ThemeSources.manifest("""[{"name":"ruins","preview":"ruins.jpg","mobile":{"url":"ruins.mpkg"}}]""", "https://example.com/collection.json").single()
        assertEquals("https://example.com/ruins.mpkg", found.mobile)
        assertEquals("https://example.com/ruins.jpg", found.image)
        assertTrue(found.live)
    }

    @Test fun maliciousManifestLinksAreNotImported() {
        assertTrue(ThemeSources.manifest("""[{"image":"file:///secret"},{"image":"http://local/a"},{"image":"https://localhost/a"}]""", "https://example.com/themes.json").isEmpty())
    }

    @Test fun repositoryUsesItsDefaultBranchAndIgnoresIcons() {
        val requests = mutableListOf<String>()
        val found = ThemeSources.discover("https://github.com/person/dots") { url ->
            requests += url
            when {
                url.endsWith("/person/dots") -> """{"default_branch":"master"}"""
                else -> """{"truncated":false,"tree":[{"type":"blob","path":"background/sky blue.jpg"},{"type":"blob","path":"icons/app.png"}]}"""
            }
        }
        assertEquals(1, found.themes.size)
        assertEquals("https://raw.githubusercontent.com/person/dots/master/background/sky%20blue.jpg", found.themes.single().image)
        assertTrue(requests.last().contains("/trees/master?recursive=1"))
    }

    @Test fun folderLinksStayInsideTheFolder() {
        val found = ThemeSources.discover("https://github.com/a/b/tree/main/walls/dark") { url ->
            if (url.contains("/trees/")) """{"tree":[{"type":"blob","path":"walls/dark/one.jpg"},{"type":"blob","path":"walls/light/two.jpg"}]}"""
            else """{"default_branch":"main"}"""
        }
        assertEquals("one", found.themes.single().name)
    }

    @Test fun truncatedTreesAreNotReportedAsCompleteCollections() {
        assertThrows(IllegalArgumentException::class.java) {
            ThemeSources.discover("https://github.com/a/b") { url ->
                if (url.contains("/trees/")) """{"truncated":true,"tree":[]}""" else """{"default_branch":"main"}"""
            }
        }
    }

    @Test fun galleryLinksResolveAndDeduplicate() {
        val found = ThemeSources.discover("https://example.com/gallery/") {
            """<html><a href="sky.jpg">sky</a><img src="sky.jpg" alt="Sky"><a href="javascript:bad()">no</a></html>"""
        }
        assertEquals(1, found.themes.size)
        assertEquals("https://example.com/gallery/sky.jpg", found.themes.single().image)
    }

    @Test fun largeCollectionsDeclareTheirLimit() {
        val json = (0..205).joinToString(",", "[", "]") { """{"image":"$it.jpg"}""" }
        val found = ThemeSources.discover("https://example.com/themes.json") { json }
        assertEquals(ThemeSources.LIMIT, found.themes.size)
        assertTrue(found.notes.any { it.contains("first 200") })
    }

    @Test fun emptyCollectionsHaveAnActionableError() {
        val error = assertThrows(IllegalArgumentException::class.java) { ThemeSources.discover("https://example.com/empty") { "<html></html>" } }
        assertTrue(error.message!!.contains("No downloadable images"))
    }

    @Test fun privateAddressesAreRejectedBeforeOpeningAConnection() {
        listOf("https://127.0.0.1/a", "https://192.168.1.1/a", "https://100.64.0.1/a", "https://240.0.0.1/a", "https://169.254.169.254/a", "https://[::1]/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { ThemeDownloads.bytes(it, 100) }
        }
    }

    /** Opt-in integration gate: this uses real public network responses, not the unit fixtures above. */
    @Test fun actualDotfilesRepoContainsImagesAndWorkshopFavorites() {
        assumeTrue(System.getenv("DETENT_IMPORT_SMOKE") == "1")
        val found = ThemeDownloads.discover("https://github.com/kvnloo/.files")
        assertTrue(found.themes.any { it.image?.contains("/background/") == true })
        assertTrue(found.themes.any { it.workshop?.contains("id=2133182232") == true })
        assertTrue(found.themes.filter { it.workshop != null }.all { it.mobile == null })
        val image = found.themes.first { it.image != null }.image!!
        assertTrue(ThemeDownloads.bytes(image, 16 * 1024 * 1024).isNotEmpty())
        println("REAL_DOTFILES_IMPORT found=${found.themes.size} images=${found.themes.count { it.image != null }} workshop=${found.themes.count { it.workshop != null }} downloaded=$image")
    }
}
