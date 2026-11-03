package app.detent.launcher

/** The icons that the quick button can show in place of the app name. */
object QuickIcons {
    val names = listOf(
        "camera", "phone", "chat", "mail", "globe", "pin",
        "music", "headphones", "play", "photo", "calendar", "clock",
        "note", "book", "terminal", "code", "card", "bag",
        "gear", "sun", "heart", "star", "bolt", "home",
        "bell", "user", "game", "lock", "cloud",
    )

    // A word matches the start of a word in the app name or the package. A word with a space matches anywhere
    // in the app name. The first icon with a match wins, so the more exact words come first.
    private val words = listOf(
        "bag" to listOf("play store", "galaxy store", "store", "shop", "amazon", "market"),
        "lock" to listOf("password", "bitwarden", "authenticator", "vault", "keepass"),
        "music" to listOf("music", "spotify", "tidal", "deezer", "soundcloud"),
        "headphones" to listOf("podcast", "audible", "audiobook"),
        "camera" to listOf("camera"),
        "phone" to listOf("phone", "dialer"),
        "chat" to listOf("messag", "messenger", "sms", "signal", "whatsapp", "telegram", "chat", "discord", "slack"),
        "mail" to listOf("mail", "gmail", "outlook"),
        "globe" to listOf("chrome", "firefox", "browser", "internet", "brave", "opera", "vivaldi", "duckduckgo"),
        "pin" to listOf("maps", "waze", "navigation"),
        "play" to listOf("youtube", "video", "netflix", "player", "twitch", "plex"),
        "photo" to listOf("photo", "gallery"),
        "calendar" to listOf("calendar"),
        "clock" to listOf("clock", "alarm", "timer"),
        "note" to listOf("note", "keep", "obsidian", "memo"),
        "book" to listOf("book", "kindle", "reader"),
        "terminal" to listOf("termux", "terminal"),
        "code" to listOf("github", "gitlab", "code"),
        "card" to listOf("wallet", "pay", "bank", "vipps", "revolut"),
        "gear" to listOf("settings"),
        "sun" to listOf("weather"),
        "heart" to listOf("health", "fitness", "strava"),
        "bolt" to listOf("torch", "flashlight"),
        "home" to listOf("home"),
        "user" to listOf("contacts", "people"),
        "game" to listOf("game", "steam", "xbox"),
        "cloud" to listOf("drive", "cloud", "dropbox"),
    )

    /** The icon that fits an app with the name [label] and the package [pkg], or null when no icon fits. */
    fun suggest(label: String, pkg: String): String? {
        val l = label.lowercase()
        val names = l.split(Regex("[^\\p{L}\\p{N}]+"))
        val parts = pkg.lowercase().split('.', '_')
        fun hit(w: String, tokens: List<String>) = if (' ' in w) w in l else tokens.any { it.startsWith(w) }
        return words.firstOrNull { (_, w) -> w.any { hit(it, names) } }?.first
            ?: words.firstOrNull { (_, w) -> w.any { ' ' !in it && hit(it, parts) } }?.first
    }

    // Folder names name a kind of app, not an app. These words start a word of the folder name.
    private val folderWords = listOf(
        "lock" to listOf("lock", "key", "vault", "pass", "auth", "2fa", "secur", "privacy"),
        "code" to listOf("code", "dev", "git", "build"),
        "terminal" to listOf("term", "shell", "ssh"),
        "chat" to listOf("talk", "chat", "social", "messag", "friend", "people"),
        "globe" to listOf("brows", "web", "news", "explore", "surf"),
        "book" to listOf("read", "book", "learn", "study", "school"),
        "heart" to listOf("care", "health", "fit", "body", "sleep", "well"),
        "bolt" to listOf("ai", "agent", "bot", "llm"),
        "card" to listOf("money", "financ", "bank", "pay", "wallet"),
        "bag" to listOf("shop", "store", "buy"),
        "play" to listOf("watch", "video", "media", "stream", "tv"),
        "music" to listOf("music", "listen", "audio"),
        "mail" to listOf("mail", "email", "inbox"),
        "game" to listOf("game", "fun"),
        "pin" to listOf("travel", "map", "go", "trip", "ride"),
        "note" to listOf("note", "write", "work", "todo", "task", "plan"),
        "gear" to listOf("tool", "util", "setting", "system"),
        "photo" to listOf("photo", "pic", "image", "art"),
        "cloud" to listOf("cloud", "file", "drive"),
    )

    // Emoji that people use as folder names, and the hairline icon that stands for each.
    private val emoji = mapOf(
        "🔒" to "lock", "🔐" to "lock", "🔑" to "lock", "🗝" to "lock", "🛡" to "lock",
        "💬" to "chat", "🗨" to "chat", "📱" to "phone", "📞" to "phone", "✉" to "mail", "📧" to "mail",
        "🌐" to "globe", "🌍" to "globe", "🌎" to "globe", "🌏" to "globe", "📍" to "pin", "🗺" to "pin",
        "❤" to "heart", "💙" to "heart", "🩺" to "heart", "💪" to "heart",
        "🎵" to "music", "🎶" to "music", "🎧" to "headphones", "▶" to "play", "📺" to "play", "🎬" to "play",
        "📷" to "camera", "📸" to "camera", "🖼" to "photo", "📅" to "calendar", "🗓" to "calendar",
        "⏰" to "clock", "🕐" to "clock", "📝" to "note", "📚" to "book", "📖" to "book",
        "💻" to "code", "⌨" to "terminal", "💳" to "card", "💰" to "card", "🛍" to "bag", "🛒" to "bag",
        "⚙" to "gear", "🔧" to "gear", "☀" to "sun", "⭐" to "star", "⚡" to "bolt", "🤖" to "bolt",
        "🏠" to "home", "🔔" to "bell", "👤" to "user", "🎮" to "game", "☁" to "cloud",
    )

    /**
     * The icon for a folder named [name] that holds apps with the names and packages in [kids]. An emoji name
     * gives its icon, then a word of the name, then the icon that most of the apps share. Never null: a folder
     * of unknown apps gets the star.
     */
    fun forFolder(name: String, kids: List<Pair<String, String>>): String {
        emoji.entries.firstOrNull { name.contains(it.key) }?.let { return it.value }
        val tokens = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        folderWords.firstOrNull { (_, w) -> w.any { p -> tokens.any { t -> if (p.length <= 2) t == p else t.startsWith(p) } } }
            ?.let { return it.first }
        val votes = kids.mapNotNull { (l, p) -> suggest(l, p) }.groupingBy { it }.eachCount()
        return votes.maxByOrNull { it.value }?.key ?: "star"
    }

    /** [name] for a sentence: an emoji-only folder name becomes words, like "the lock folder". */
    fun spokenFolder(name: String) = if (markOnly(name)) "the ${forFolder(name, emptyList())} folder" else name

    /** True when [name] has no letter or digit, like an emoji. The dial then shows only the icon. */
    fun markOnly(name: String) = name.isNotBlank() && name.none { it.isLetterOrDigit() }
}

/** The saved folder icon that shows the name without an icon. */
const val NO_ICON = "-"
