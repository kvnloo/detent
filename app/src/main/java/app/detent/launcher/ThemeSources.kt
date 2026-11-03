package app.detent.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** A proposed import, never downloaded or applied until the user chooses it. */
data class ThemeCandidate(
    val name: String,
    val source: String,
    val image: String? = null,
    val workshop: String? = null,
    val mobile: String? = null,
    val bg: Long? = null,
    val fg: Long? = null,
    val accent: Long? = null,
) {
    val id get() = workshop ?: mobile ?: image ?: source
    val live get() = workshop != null || mobile != null
}

data class ThemeCollection(val themes: List<ThemeCandidate>, val notes: List<String> = emptyList())

/** Public HTTPS links only. Network code also checks resolved addresses and each redirect. */
internal fun themeUrl(value: String): URI {
    val uri = runCatching { URI(value.trim()) }.getOrElse { error("That isn't a valid link.") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443)) { "Use a public HTTPS link without a login or custom port." }
    val host = uri.host.lowercase().trimEnd('.')
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Local network links aren't supported." }
    return uri
}

internal fun sharedThemeUrl(text: String): String? =
    Regex("https://[^\\s<>\" ]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }
        .firstOrNull { runCatching { themeUrl(it) }.isSuccess }

internal object ThemeSources {
    const val LIMIT = 200
    private val imageExt = Regex("\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
    private fun imagePath(path: String) = imageExt.containsMatchIn(path)
    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun raw(owner: String, repo: String, ref: String, path: String) =
        "https://raw.githubusercontent.com/${segment(owner)}/${segment(repo)}/${segment(ref)}/" + path.split('/').joinToString("/") { segment(it) }

    /** Accept GitHub blob links as manifest/image links rather than downloading GitHub's HTML. */
    fun direct(link: String): String {
        val uri = themeUrl(link)
        val p = uri.path.trim('/').split('/')
        return if (uri.host.equals("github.com", true) && p.size >= 5 && p[2] == "blob")
            raw(p[0], p[1], p[3], p.drop(4).joinToString("/")) else uri.toASCIIString()
    }

    fun discover(link: String, get: (String) -> String): ThemeCollection {
        val uri = themeUrl(link)
        val p = uri.path.trim('/').split('/')
        if (uri.host.equals("github.com", true) && p.size >= 2 && p.getOrNull(2) != "blob") {
            require(p.size == 2 || (p.size >= 4 && p[2] == "tree")) { "Use a repository, folder, image or collection JSON link." }
            val owner = p[0]; val repo = p[1].removeSuffix(".git")
            val meta = JSONObject(get("https://api.github.com/repos/${segment(owner)}/${segment(repo)}"))
            val ref = if (p.size >= 4) p[3] else meta.getString("default_branch")
            val folder = if (p.size > 4) p.drop(4).joinToString("/").trimEnd('/') + "/" else ""
            val tree = JSONObject(get("https://api.github.com/repos/${segment(owner)}/${segment(repo)}/git/trees/${segment(ref)}?recursive=1"))
            require(!tree.optBoolean("truncated")) { "This repository is too large. Link a collection JSON or a smaller repository." }
            val entries = tree.getJSONArray("tree")
            val paths = (0 until entries.length()).map { entries.getJSONObject(it) }
                .filter { it.optString("type") == "blob" }.map { it.getString("path") }.filter { it.startsWith(folder) }
            val images = paths.filter(::imagePath)
            val preferred = images.filter { Regex("(^|/)(backgrounds?|wallpapers?|walls)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val chosen = (preferred.ifEmpty { images.filterNot { Regex("(^|/)(icons?|node_modules|\\.git|screenshots?)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) } })
            val found = mutableListOf<ThemeCandidate>()
            val notes = mutableListOf<String>()
            // Parse curated collections first, so live favorites aren't lost in a large image repository.
            val manifests = paths.filter { it.substringAfterLast('/') in listOf("detent-themes.json", "themes.json", "collection.json", "live.json") }
                .take(12)
            for (path in manifests) {
                val url = raw(owner, repo, ref, path)
                try { found += manifest(get(url), url) }
                catch (e: Exception) { notes += "Couldn't read $path: ${e.message}" }
            }
            found += chosen.map { ThemeCandidate(it.substringAfterLast('/').substringBeforeLast('.'), raw(owner, repo, ref, it), raw(owner, repo, ref, it)) }
            if (preferred.isNotEmpty() && preferred.size < images.size) notes += "Showing wallpaper folders; icons and other images were left out."
            return bounded(found, notes)
        }
        val url = direct(link)
        if (imagePath(URI(url).path)) return ThemeCollection(listOf(ThemeCandidate(URI(url).path.substringAfterLast('/').substringBeforeLast('.'), url, url)))
        val text = get(url)
        if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) return bounded(manifest(text, url))
        val page = Jsoup.parse(text, url)
        val links = page.select("a[href], img[src]").mapNotNull { node ->
            val target = node.absUrl(if (node.tagName() == "img") "src" else "href")
            runCatching { direct(target) }.getOrNull()?.takeIf { imagePath(URI(it).path) }?.let {
                ThemeCandidate(node.attr("alt").ifBlank { URI(it).path.substringAfterLast('/').substringBeforeLast('.') }, it, it)
            }
        }
        return bounded(links)
    }

    fun manifest(text: String, base: String): List<ThemeCandidate> {
        fun resolve(value: String?): String? = value?.takeIf { it.isNotBlank() && !it.startsWith('/') && !it.contains('\\') }?.let {
            runCatching { direct(URI(base).resolve(it).toString()) }.getOrNull()
        }
        fun field(o: JSONObject, key: String) = o.optString(key).takeUnless { it.isBlank() || it == "null" }
        fun colour(o: JSONObject, key: String): Long? {
            val hex = field(o, key)?.removePrefix("#") ?: return null
            return if (hex.length == 6 && hex.all { it.digitToIntOrNull(16) != null }) 0xFF000000L or hex.toLong(16) else null
        }
        val array = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).let {
            it.optJSONArray("themes") ?: it.optJSONArray("wallpapers") ?: error("A collection needs a themes or wallpapers list.")
        }
        require(array.length() <= 2000) { "Collection is too large; split it into smaller lists." }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val workshop = resolve(field(o, "workshop_url") ?: field(o, "workshop"))?.takeIf {
                val u = URI(it)
                u.host.equals("steamcommunity.com", true) && u.path == "/sharedfiles/filedetails/" &&
                    u.rawQuery?.split('&')?.any { q -> q.matches(Regex("id=[0-9]+")) } == true
            }
            val mobileObj = o.optJSONObject("mobile")
            // mobile.file may be a path on the author's PC, not a published download.
            val mobile = resolve(mobileObj?.let { field(it, "url") } ?: field(o, "mpkg"))
            val image = resolve(field(o, "image") ?: field(o, "wallpaper") ?: field(o, "preview") ?: if (workshop == null && mobile == null) field(o, "url") else null)
            if (image == null && workshop == null && mobile == null) return@mapNotNull null
            ThemeCandidate(
                (field(o, "name") ?: field(o, "detent_name") ?: field(o, "title") ?: "theme ${i + 1}").take(100),
                base, image, workshop, mobile, colour(o, "bg"), colour(o, "fg"), colour(o, "accent"),
            )
        }
    }

    private fun bounded(themes: List<ThemeCandidate>, notes: List<String> = emptyList()): ThemeCollection {
        val unique = themes.distinctBy { it.id }
        require(unique.isNotEmpty()) { "No downloadable images or Wallpaper Engine links found. Try a wallpaper folder or collection JSON." }
        return ThemeCollection(unique.take(LIMIT), notes + if (unique.size > LIMIT) listOf("Showing the first $LIMIT items. Use a narrower folder link for the rest.") else emptyList())
    }
}
