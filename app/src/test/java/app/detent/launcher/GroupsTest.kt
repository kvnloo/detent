package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
    @Test
    fun foldersRoundTrip() {
        val fs = listOf(Folder("a1", "messaging", listOf("x/.A#0", "y/.B#0")), Folder("b2", "social", listOf("z/.C#0")))
        assertEquals(fs, decodeFolders(encodeFolders(fs)))
    }

    @Test
    fun emptyAndBrokenFoldersDrop() {
        assertEquals(emptyList<Folder>(), decodeFolders(null))
        assertEquals(emptyList<Folder>(), decodeFolders("a\tname\t\nbroken line"))
    }

    @Test
    fun tabsInNamesDoNotBreakTheFile() {
        val f = Folder("a", "my\tapps", listOf("k"))
        assertEquals("my apps", decodeFolders(encodeFolders(listOf(f))).single().name)
    }

    @Test
    fun swipesRoundTrip() {
        val m = mapOf("row/.A#0" to "app/.B#0", FOLDER + "x" to "app/.C#0")
        assertEquals(m, decodePairs(encodePairs(m)))
    }

    @Test
    fun orderKeepsSavedRowsFirstAndNewOnesByName() {
        val byName = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), ordered(byName, listOf("c", "a")))
        assertEquals(byName, ordered(byName, emptyList()))
        // Rows that left the dial do not matter.
        assertEquals(listOf("b", "a", "c", "d"), ordered(byName, listOf("gone", "b")))
    }

    @Test
    fun movedShiftsOneItem() {
        assertEquals(listOf("b", "c", "a"), moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), moved(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), moved(listOf("a", "b"), 0, 5))
    }

    @Test
    fun folderNamesComeFromTheApps() {
        assertEquals("messaging", guessFolderName(listOf("whatsapp", "telegram", "messages")))
        assertEquals("social", guessFolderName(listOf("instagram", "x", "reddit")))
        assertEquals("ai", guessFolderName(listOf("chatgpt", "claude")))
        assertEquals("notes +", guessFolderName(listOf("notes", "calculator")))
    }

    @Test
    fun fixedDensityKeepsItsMarks() {
        val m = marksFor(0f, 1f, 4, emptyList())
        assertEquals(9, m.size)
        assertEquals(3, m.count { it.major })
    }

    @Test
    fun autoDensityShowsOneMarkPerApp() {
        // Rows: a plain app, a folder of 4, a folder of 2.
        val m = marksFor(1f, .9f, 0, listOf(1, 4, 2))
        val row1 = m.filter { it.at >= 1f && it.at < 1.5f }
        assertEquals(4, row1.size)
        assertEquals(1, row1.count { it.major })
        assertTrue(row1.all { it.at - 1f < .5f })
    }

    @Test
    fun autoDensityWrapsRound() {
        val m = marksFor(0f, 1.2f, 0, listOf(3, 1))
        // Row -1 is row 1 (one app); row 0 has three; row 1 has one.
        assertEquals(1, m.count { it.at >= -1f && it.at < -.5f })
        assertEquals(3, m.count { it.at >= 0f && it.at < .5f })
    }
}
