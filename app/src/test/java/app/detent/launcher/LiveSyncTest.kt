package app.detent.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class LiveSyncTest {

    // Wallpaper Engine packages.

    private fun pkgm(entries: List<Pair<String, ByteArray>>, magic: String = "PKGM0016"): ByteArray {
        val head = ByteArrayOutputStream()
        fun i32(v: Int) = head.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun str(s: String) { val b = s.toByteArray(); i32(b.size); head.write(b) }
        str(magic)
        i32(entries.size)
        var off = 0
        for ((name, data) in entries) { str(name); i32(off); i32(data.size); off += data.size }
        entries.forEach { head.write(it.second) }
        return head.toByteArray()
    }

    @Test fun readsTheTitleAndPreviewOfAPackage() {
        val project = """{"title":"Forgotten Ruins (Ambient Sounds) in 4K","type":"Scene","preview":"preview.gif"}"""
        val f = File.createTempFile("scene", ".mpkg")
        f.writeBytes(pkgm(listOf("project.json" to project.toByteArray(), "preview.gif" to byteArrayOf(1, 2, 3), "scene.pkg" to ByteArray(10))))
        val m = Mpkg.open(f)!!
        assertEquals("Forgotten Ruins (Ambient Sounds) in 4K", m.project()?.title)
        assertEquals("scene", m.project()?.type)
        val (name, bytes) = m.preview()!!
        assertEquals("preview.gif", name)
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3)))
        val out = Mpkg.ensurePreview(f)!!
        assertTrue(out.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        f.delete(); out.delete()
    }

    @Test fun rejectsFilesThatAreNotPackages() {
        assertNull(Mpkg.parseTable("PK\u0003\u0004 not a package".toByteArray()))
        assertNull(Mpkg.parseTable(pkgm(emptyList(), magic = "ZIP")))
        // A table that claims more entries than it holds.
        val b = pkgm(listOf("a" to ByteArray(1)))
        b[12] = 0x7F
        assertNull(Mpkg.parseTable(b.copyOf(40)))
    }

    // The switch inside Wallpaper Engine.

    private class N(
        override val pkg: String? = SwitchPlan.WE,
        override val id: String? = null,
        override val text: String? = null,
        override val desc: String? = null,
        override val clickable: Boolean = false,
        override val scrollable: Boolean = false,
        kids: List<N> = emptyList(),
    ) : UiNode {
        override var parent: UiNode? = null
        override val children: List<UiNode> = kids.onEach { it.parent = this }
    }

    private fun tile(title: String) = N(
        kids = listOf(
            N(id = "${SwitchPlan.WE}:id/ibWallpaper", clickable = true),
            N(id = "${SwitchPlan.WE}:id/tvTitle", text = title),
        ),
    )

    private fun grid(vararg titles: String) = N(kids = listOf(N(scrollable = true, kids = titles.map { tile(it) })))

    @Test fun opensTheScenesTileThenTapsTheCheckMark() {
        val s = SwitchPlan.step(grid("PIKACHU CHILL", "Forgotten Ruins (Ambient Sounds) in 4K"), "Forgotten Ruins (Ambient Sounds) in 4K", SwitchPhase.Find, 0)
        s as SwitchStep.Tap
        assertEquals("${SwitchPlan.WE}:id/ibWallpaper", s.node.id)
        assertEquals(SwitchPhase.Find, s.next)

        val preview = N(kids = listOf(N(text = "Forgotten Ruins (Ambient Sounds) in 4K"), N(id = "${SwitchPlan.WE}:id/item_apply", clickable = true)))
        val apply = SwitchPlan.step(preview, "forgotten ruins (ambient sounds) in 4k", SwitchPhase.Find, 0) as SwitchStep.Tap
        assertEquals("${SwitchPlan.WE}:id/item_apply", apply.node.id)
        assertEquals(SwitchPhase.Applied, apply.next)
    }

    @Test fun backsOutOfAnotherScenesPreview() {
        val other = N(kids = listOf(N(text = "PIKACHU CHILL"), N(id = "${SwitchPlan.WE}:id/item_apply", clickable = true)))
        assertEquals(SwitchStep.Back, SwitchPlan.step(other, "Abstract Landscape", SwitchPhase.Find, 0))
    }

    @Test fun scrollsTheLibraryThenImportsWhenTheSceneIsMissing() {
        val g = grid("PIKACHU CHILL")
        assertTrue(SwitchPlan.step(g, "Abstract Landscape", SwitchPhase.Find, 0) is SwitchStep.Scroll)
        assertEquals(SwitchStep.Import, SwitchPlan.step(g, "Abstract Landscape", SwitchPhase.Find, SwitchPlan.MAX_SCROLLS))
    }

    @Test fun confirmsAndroidsPreviewForHomeAndLock() {
        val picker = N(pkg = "com.android.wallpaper.livepicker", kids = listOf(N(text = "Set wallpaper", clickable = true)))
        val set = SwitchPlan.step(picker, "x", SwitchPhase.Applied, 0) as SwitchStep.Tap
        assertEquals("Set wallpaper", set.node.text)
        val where = N(pkg = "com.android.wallpaper.livepicker", kids = listOf(N(clickable = true, kids = listOf(N(text = "Home and lock screens")))))
        val both = SwitchPlan.step(where, "x", SwitchPhase.Applied, 0) as SwitchStep.Tap
        assertTrue(both.node.clickable)
        assertEquals(SwitchPhase.Confirm, both.next)
    }

    @Test fun neverTapsOutsideWallpaperEngineBeforeApplying() {
        val other = N(pkg = "com.openai.chatgpt", kids = listOf(N(text = "Set wallpaper", clickable = true)))
        assertEquals(SwitchStep.Wait, SwitchPlan.step(other, "x", SwitchPhase.Find, 0))
    }

    // Tailnet manifests.

    private val manifest = """
        {"version":1,"host":"groot","updated":5,
         "colors":{"bg":"#181f23","fg":"d1deda","accent":"c8be72","updated":200},
         "live":[{"name":"forgotten-ruins","title":"Forgotten Ruins (Ambient Sounds) in 4K","bg":"112122","fg":"dae5e7",
                  "accent":"90d8df","file":"2133182232.mpkg","sha256":"${"a".repeat(64)}","bytes":31937964},
                 {"name":"evil","bg":"112122","fg":"dae5e7","accent":"90d8df","file":"../../shared_prefs/detent.xml"}],
         "selection":{"theme":"black-hole","device":"Galaxy S22+","at":300},
         "peers":["mbp.tailnet.ts.net.","bad host"]}
    """.trimIndent()

    @Test fun readsAMachinesManifestAndDropsUnsafeFiles() {
        val m = TailnetSync.parseManifest(manifest)!!
        assertEquals("groot", m.host)
        assertEquals("181f23", m.colors?.bg)
        assertEquals(listOf("forgotten-ruins"), m.live?.map { it.name })
        assertEquals("a".repeat(64), m.live?.first()?.sha256)
        assertEquals("black-hole", m.selection?.theme)
        assertEquals(listOf("mbp.tailnet.ts.net"), m.peers)
        assertNull(TailnetSync.parseManifest("""{"version":2,"host":"x"}"""))
        assertNull(TailnetSync.parseManifest("not json"))
    }

    @Test fun theNewestChangeWinsAcrossMachines() {
        val a = TailnetSync.parseManifest(manifest)!!
        val b = a.copy(host = "mbp", colors = a.colors!!.copy(bg = "000000", updated = 100), selection = a.selection!!.copy(theme = "dusk", at = 900))
        assertEquals("181f23", TailnetSync.newestColors(listOf(a, b))?.bg)
        assertEquals("dusk", TailnetSync.newestSelection(listOf(a, b))?.theme)
    }

    @Test fun mergingKeepsLocalThemesAndDropsOnesAMachineWithdrew() {
        val m = TailnetSync.parseManifest(manifest)!!
        val current = listOf(
            JSONObject("""{"name":"forgotten-ruins","bg":"000000","fg":"ffffff","accent":"ffffff","file":"2133182232.mpkg"}"""),
            JSONObject("""{"name":"adb-only","bg":"000000","fg":"ffffff","accent":"ffffff","file":"1.mpkg"}"""),
            JSONObject("""{"name":"withdrawn","bg":"000000","fg":"ffffff","accent":"ffffff","file":"2.mpkg","host":"groot"}"""),
            JSONObject("""{"name":"from-offline","bg":"000000","fg":"ffffff","accent":"ffffff","file":"3.mpkg","host":"cos"}"""),
        )
        val out = TailnetSync.mergeLive(current, mapOf("groot" to m))
        assertEquals(listOf("forgotten-ruins", "adb-only", "from-offline"), out.map { it.getString("name") })
        assertEquals("112122", out[0].getString("bg"))
        assertEquals("groot", out[0].getString("host"))
    }

    @Test fun onlyTailnetHttpsAddressesAreUsed() {
        assertEquals("https://groot.tailnet.ts.net/detent", TailnetSync.normalizeBase("groot.tailnet.ts.net"))
        assertEquals("https://groot.tailnet.ts.net/detent", TailnetSync.normalizeBase("https://groot.tailnet.ts.net/detent/"))
        assertNull(TailnetSync.normalizeBase("http://groot.tailnet.ts.net/detent"))
        assertNull(TailnetSync.normalizeBase("https://evil.example.com/detent"))
        assertNull(TailnetSync.normalizeBase("https://user@groot.tailnet.ts.net/detent"))
        assertNotNull(TailnetSync.normalizeBase("mbp.tailnet.ts.net"))
    }
}
