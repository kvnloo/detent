package app.detent.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickIconsTest {
    @Test fun nameFindsTheIcon() {
        assertEquals("camera", QuickIcons.suggest("camera", "com.sec.android.app.camera"))
        assertEquals("chat", QuickIcons.suggest("whatsapp", "com.whatsapp"))
        assertEquals("mail", QuickIcons.suggest("gmail", "com.google.android.gm"))
        assertEquals("gear", QuickIcons.suggest("settings", "com.android.settings"))
    }

    @Test fun moreExactWordsWin() {
        assertEquals("bag", QuickIcons.suggest("play store", "com.android.vending"))
        assertEquals("music", QuickIcons.suggest("youtube music", "com.google.android.apps.youtube.music"))
        assertEquals("lock", QuickIcons.suggest("keepassdx", "com.kunzisoft.keepass.free"))
    }

    @Test fun wordsMatchOnlyAtTheStart() {
        // "pay" must not match inside "display".
        assertNull(QuickIcons.suggest("display", "com.example.display"))
    }

    @Test fun packageFindsTheIconWhenTheNameDoesNot() {
        assertEquals("clock", QuickIcons.suggest("uhr", "com.example.clock"))
        assertEquals("phone", QuickIcons.suggest("telefon", "com.samsung.android.dialer"))
    }

    @Test fun everySuggestionIsAnIcon() {
        val labels = listOf("camera", "spotify", "maps", "chrome", "photos", "calendar", "termux", "weather", "steam", "drive")
        for (l in labels) assertTrue(l, QuickIcons.suggest(l, "") in QuickIcons.names)
    }

    @Test fun folderEmojiGivesItsIcon() {
        assertEquals("lock", QuickIcons.forFolder("🔒", emptyList()))
        assertEquals("chat", QuickIcons.forFolder("💬 friends", emptyList()))
    }

    @Test fun folderNamesFindTheirIcon() {
        assertEquals("code", QuickIcons.forFolder("code", emptyList()))
        assertEquals("chat", QuickIcons.forFolder("talk", emptyList()))
        assertEquals("globe", QuickIcons.forFolder("browse", emptyList()))
        assertEquals("heart", QuickIcons.forFolder("care", emptyList()))
        assertEquals("bolt", QuickIcons.forFolder("ai", emptyList()))
        // A two-letter word must be the whole word: "ai" does not match "air".
        assertEquals("star", QuickIcons.forFolder("air", emptyList()))
    }

    @Test fun folderOfUnknownNameTakesTheIconMostAppsShare() {
        val kids = listOf("gmail" to "com.google.android.gm", "outlook" to "com.microsoft.office.outlook", "maps" to "x.maps")
        assertEquals("mail", QuickIcons.forFolder("stuff", kids))
        assertEquals("star", QuickIcons.forFolder("stuff", listOf("zzz" to "x.y")))
    }

    @Test fun onlyNamesWithoutLettersAreMarks() {
        assertTrue(QuickIcons.markOnly("🔒"))
        assertTrue(!QuickIcons.markOnly("🔒 keys"))
        assertTrue(!QuickIcons.markOnly(""))
    }

    @Test fun emojiFolderNamesAreSpokenAsWords() {
        assertEquals("the lock folder", QuickIcons.spokenFolder("🔒"))
        assertEquals("talk", QuickIcons.spokenFolder("talk"))
    }

    @Test fun everyFolderIconIsDrawn() {
        val all = listOf("🔒", "code", "term", "talk", "browse", "read", "care", "ai", "money", "shop", "watch", "music", "mail",
            "games", "travel", "notes", "tools", "photos", "cloud", "stuff")
        all.forEach { assertTrue(it, QuickIcons.forFolder(it, emptyList()) in QuickIcons.names + "star") }
    }
}
