package app.detent.launcher

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * A folder on the dial: one row that holds several apps. A tap opens the first app (the cover),
 * a swipe opens the folder, and the dial then turns through the apps inside.
 */
data class Folder(val id: String, val name: String, val keys: List<String>) {
    val key get() = FOLDER + id
}

const val FOLDER = "folder:"

fun isFolderKey(key: String) = key.startsWith(FOLDER)

/** One folder per line: id, tab, name, tab, the app keys joined by commas. */
fun encodeFolders(fs: List<Folder>) = fs.joinToString("\n") { f ->
    f.id + "\t" + f.name.replace('\t', ' ').replace('\n', ' ') + "\t" + f.keys.joinToString(",")
}

fun decodeFolders(s: String?): List<Folder> = s.orEmpty().split('\n').mapNotNull { line ->
    val p = line.split('\t')
    if (p.size != 3 || p[0].isEmpty()) return@mapNotNull null
    val keys = p[2].split(',').filter { it.isNotEmpty() }.distinct()
    if (keys.isEmpty()) null else Folder(p[0], p[1].ifBlank { "folder" }, keys)
}

/** One pair per line: the row key, tab, the key of the app that a swipe on the row opens. */
fun encodePairs(m: Map<String, String>) = m.entries.joinToString("\n") { it.key + "\t" + it.value }

fun decodePairs(s: String?): Map<String, String> = s.orEmpty().split('\n').mapNotNull { line ->
    val p = line.split('\t')
    if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
}.toMap()

/**
 * The dial rows in the saved [order]. Keys that the order does not know yet keep their place in [keys]
 * (which come sorted by name) and go after the known ones.
 */
fun ordered(keys: List<String>, order: List<String>): List<String> {
    val rank = order.withIndex().associate { it.value to it.index }
    return keys.withIndex().sortedWith(compareBy({ rank[it.value] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

/** [list] with the item at [from] moved to [to]. */
fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    val m = list.toMutableList()
    val x = m.removeAt(from)
    m.add(to, x)
    return m
}

/** A name for a new folder from the names of its apps. */
fun guessFolderName(labels: List<String>): String {
    val kinds = labels.map { l ->
        val n = l.lowercase()
        GUESS.firstOrNull { (_, words) -> words.any { it in n } }?.first
    }
    val best = kinds.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }
    // Name the folder by its kind when most of its apps share it.
    if (best != null && best.value * 2 >= labels.size) return best.key
    return labels.firstOrNull()?.let { "$it +" } ?: "folder"
}

private val GUESS = listOf(
    "ai" to listOf("chatgpt", "claude", "gemini", "perplexity", "hermes", "copilot", "grok"),
    "messaging" to listOf("messag", "whatsapp", "telegram", "signal", "discord", "slack", "beeper", "sms", "chat", "viber", "wechat", "teams", "element"),
    "social" to listOf("instagram", "facebook", "twitter", "threads", "reddit", "tiktok", "snapchat", "bluesky", "mastodon", "linkedin", "pinterest", "tumblr"),
    "music" to listOf("spotify", "music", "tidal", "soundcloud", "deezer", "podcast", "pocket casts", "audible"),
    "video" to listOf("youtube", "netflix", "prime video", "disney", "hulu", "twitch", "plex", "vlc"),
    "photos" to listOf("photo", "gallery", "camera", "lightroom", "snapseed"),
    "mail" to listOf("mail", "outlook", "proton", "spark", "thunderbird"),
    "maps" to listOf("maps", "waze", "uber", "lyft", "transit", "citymapper"),
    "money" to listOf("bank", "pay", "wallet", "venmo", "cash app", "robinhood", "chase", "paypal"),
    "shopping" to listOf("amazon", "ebay", "etsy", "shop", "target", "walmart"),
    "games" to listOf("game", "chess", "steam", "xbox", "playstation"),
)

/** A mark on the dial, [at] rows round from row 0. A major mark sits on a row. */
class Mark(val at: Float, val major: Boolean, val i: Int)

/**
 * The marks between rows [pos] - [span] and [pos] + [span]. With [perApp] > 0 every row gets that many
 * marks. With [perApp] = 0 (auto) each row gets one mark per app it holds: a plain app one, a folder of
 * four apps four, packed just after the row so that the count reads at a glance.
 */
fun marksFor(pos: Float, span: Float, perApp: Int, counts: List<Int>): List<Mark> {
    val out = ArrayList<Mark>()
    if (perApp > 0) {
        val first = ceil((pos - span) * perApp).toInt()
        val last = floor((pos + span) * perApp).toInt()
        for (i in first..last) out += Mark(i.toFloat() / perApp, mod(i, perApp) == 0, i)
        return out
    }
    val n = counts.size
    val r0 = floor(pos - span).toInt()
    val r1 = ceil(pos + span).toInt()
    for (r in r0..r1) {
        val c = if (n == 0) 1 else counts[mod(r, n)].coerceIn(1, 12)
        val gap = min(1f / (c + 1), .11f)
        for (j in 0 until c) {
            val at = r + j * gap
            if (at < pos - span || at > pos + span) continue
            out += Mark(at, j == 0, r * 16 + j)
        }
    }
    return out
}
