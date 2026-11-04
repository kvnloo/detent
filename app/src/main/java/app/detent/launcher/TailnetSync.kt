package app.detent.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Keeps Detent in step with the owner's other machines over Tailscale. Each machine runs `detent-sync-serve`
 * behind `tailscale serve`, so it answers only inside the tailnet, over https with the tailnet's certificate:
 *
 *   GET  <base>/v1/manifest      the desktop colours, the live wallpaper list, the last theme choice, peers
 *   GET  <base>/v1/files/<file>  a live wallpaper package, checked against the manifest's SHA-256
 *   POST <base>/v1/selection     {"theme", "device", "at"}: this phone chose a theme
 *
 * The desktop colours feed the synced theme, the live list becomes the live themes (packages are downloaded
 * once and checked), and a theme chosen on any device is followed here unless this phone chose later. A
 * machine that can't be reached changes nothing, so Detent keeps working offline with what it has.
 */
object TailnetSync {
    private const val PREFS = "detent.sync"
    private const val POLL_MS = 30_000L
    private const val PEER_PROBE_MS = 10 * 60_000L
    private const val MAX_MANIFEST = 256 * 1024
    private const val MAX_PACKAGE = 200L * 1024 * 1024

    /** Called on the main thread when another device chose [theme] after this one last did. */
    var follow: ((theme: String, device: String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    private val etags = HashMap<String, String>()
    private val manifests = HashMap<String, Manifest>()
    private var peersProbedAt = 0L

    data class Colors(val bg: String, val fg: String, val accent: String, val updated: Long)
    data class LiveEntry(
        val name: String, val title: String?, val bg: String, val fg: String, val accent: String,
        val file: String, val sha256: String?, val bytes: Long,
    )
    data class Selection(val theme: String, val device: String, val at: Long)
    data class Manifest(
        val host: String, val colors: Colors?, val live: List<LiveEntry>?, val selection: Selection?, val peers: List<String>,
    )

    // Parsing and merging are pure, so they are tested on the JVM.

    private val HEX = Regex("^#?([0-9a-fA-F]{6})$")
    private val FILE = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val PEER = Regex("^[A-Za-z0-9.-]{1,253}$")

    private fun hex(v: String?): String? = v?.trim()?.let { HEX.matchEntire(it)?.groupValues?.get(1)?.lowercase() }

    fun parseManifest(body: String): Manifest? = runCatching {
        val o = JSONObject(body)
        if (o.optInt("version", 0) != 1) return null
        val host = o.optString("host").trim().take(64).ifEmpty { return null }
        val colors = o.optJSONObject("colors")?.let { c ->
            val bg = hex(c.optString("bg")) ?: return@let null
            val fg = hex(c.optString("fg")) ?: return@let null
            val acc = hex(c.optString("accent")) ?: return@let null
            Colors(bg, fg, acc, c.optLong("updated", 0))
        }
        val live = o.optJSONArray("live")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = e.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: return@mapNotNull null
                val file = e.optString("file").takeIf { FILE.matches(it) && it.endsWith(".mpkg") } ?: return@mapNotNull null
                LiveEntry(
                    name, e.optString("title").trim().takeIf { it.isNotEmpty() }?.take(200),
                    hex(e.optString("bg")) ?: return@mapNotNull null,
                    hex(e.optString("fg")) ?: return@mapNotNull null,
                    hex(e.optString("accent")) ?: return@mapNotNull null,
                    file, e.optString("sha256").lowercase().takeIf { SHA.matches(it) }, e.optLong("bytes", 0),
                )
            }
        }
        val sel = o.optJSONObject("selection")?.let { s ->
            val theme = s.optString("theme").trim().takeIf { it.isNotEmpty() } ?: return@let null
            Selection(theme, s.optString("device").trim().take(64), s.optLong("at", 0))
        }
        val peers = o.optJSONArray("peers")?.let { a -> (0 until a.length()).map { a.optString(it).trim().trimEnd('.') } }
            .orEmpty().filter { PEER.matches(it) }.distinct().take(32)
        Manifest(host, colors, live, sel, peers)
    }.getOrNull()

    /** The colours with the latest change across machines. */
    fun newestColors(ms: Collection<Manifest>): Colors? = ms.mapNotNull { it.colors }.maxByOrNull { it.updated }

    /** The latest theme choice across machines. */
    fun newestSelection(ms: Collection<Manifest>): Selection? = ms.mapNotNull { it.selection }.maxByOrNull { it.at }

    /**
     * Merges the live lists: entries in the phone's current list stay unless a machine that answered used
     * to offer them and no longer does; a machine's entry replaces the phone's entry of the same name.
     * Returns entries as themes.json objects, in a stable order (current order first, then new ones).
     */
    fun mergeLive(current: List<JSONObject>, answered: Map<String, Manifest>): List<JSONObject> {
        val offered = LinkedHashMap<String, Pair<String, LiveEntry>>()
        for ((host, m) in answered.toSortedMap()) m.live?.forEach { e -> offered.putIfAbsent(e.name, host to e) }
        val listing = answered.filterValues { it.live != null }.keys
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (o in current) {
            val name = o.optString("name")
            val from = o.optString("host").takeIf { it.isNotEmpty() }
            val repl = offered[name]
            when {
                repl != null -> out += entryJson(repl.first, repl.second)
                from != null && from in listing -> continue
                else -> out += o
            }
            seen += name
        }
        for ((name, v) in offered) if (name !in seen) out += entryJson(v.first, v.second)
        return out
    }

    private fun entryJson(host: String, e: LiveEntry) = JSONObject().apply {
        put("name", e.name); put("bg", e.bg); put("fg", e.fg); put("accent", e.accent); put("file", e.file)
        e.title?.let { put("title", it) }
        e.sha256?.let { put("sha256", it) }
        put("host", host)
    }

    /**
     * A base like https://host.tailnet.ts.net/detent. Only https on a tailnet name (*.ts.net): tailscale serve
     * gives every machine a certificate there, and nothing outside the tailnet can answer for that name.
     */
    fun normalizeBase(s: String): String? {
        val t = s.trim().trimEnd('/')
        val withScheme = if ("://" in t) t else "https://$t/detent"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: return null
        if (u.protocol != "https" || u.userInfo != null || u.query != null || u.ref != null) return null
        if (!u.host.orEmpty().lowercase().endsWith(".ts.net")) return null
        return withScheme
    }

    // State on the phone.

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hosts(ctx: Context): List<String> = sp(ctx).getString("hosts", "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** Sets the machines to sync with. Returns how many addresses were usable. */
    fun setHosts(ctx: Context, list: List<String>): Int {
        val ok = list.mapNotNull { normalizeBase(it) }.distinct()
        sp(ctx).edit { putString("hosts", ok.joinToString("\n")) }
        synchronized(manifests) { manifests.clear(); etags.clear() }
        return ok.size
    }

    fun deviceName(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Starts polling while Detent is in front. Safe to call on every resume. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (hosts(app).isEmpty() || running) return
        running = true
        worker = thread(name = "detent-tailnet-sync", isDaemon = true) {
            while (running) {
                runCatching { pollOnce(app) }
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /** Tells every machine this phone chose [theme], so the others follow. Runs in the background. */
    fun publish(ctx: Context, theme: String) {
        val app = ctx.applicationContext
        val at = System.currentTimeMillis()
        sp(app).edit { putLong("localAt", at) }
        val hosts = hosts(app)
        if (hosts.isEmpty()) return
        val body = JSONObject().put("theme", theme).put("device", deviceName(app)).put("at", at).toString()
        thread(name = "detent-tailnet-publish", isDaemon = true) {
            for (h in hosts) runCatching { post(URL("$h/v1/selection"), body) }
        }
    }

    /** One round: read every machine, then apply what changed. Blocks; call off the main thread. */
    fun pollOnce(ctx: Context) {
        val hosts = hosts(ctx)
        val answered = HashMap<String, Manifest>()
        for (h in hosts) fetchManifest(h)?.let { answered[h] = it }
        discoverPeers(ctx, hosts, answered)
        if (answered.isEmpty()) return
        val all = synchronized(manifests) { manifests.putAll(answered); manifests.toMap() }

        newestColors(all.values)?.let { c ->
            val key = "${c.bg}${c.fg}${c.accent}"
            if (sp(ctx).getString("colors", null) != key) main.post {
                if (Synced.save(ctx, c.bg, c.fg, c.accent)) sp(ctx).edit { putString("colors", key) }
            }
        }

        val byHost = answered.mapKeys { (_, m) -> m.host }
        if (byHost.values.any { it.live != null }) syncLive(ctx, byHost, all)

        newestSelection(all.values)?.let { s ->
            val seen = sp(ctx).getLong("seenAt", 0)
            val local = sp(ctx).getLong("localAt", 0)
            if (s.at > seen && s.at > local && s.device != deviceName(ctx)) {
                sp(ctx).edit { putLong("seenAt", s.at) }
                main.post { follow?.invoke(s.theme, s.device) }
            }
        }
    }

    private fun syncLive(ctx: Context, byHost: Map<String, Manifest>, all: Map<String, Manifest>) {
        val dir = File(ctx.filesDir, "live").apply { mkdirs() }
        val index = File(dir, "themes.json")
        val current = runCatching { JSONArray(index.readText()) }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val merged = mergeLive(current, byHost)
        // Packages first: a theme is listed only once its package is on the phone and checked.
        val baseOf = all.entries.associate { (base, m) -> m.host to base }
        val ready = merged.filter { o ->
            val file = File(dir, o.optString("file"))
            val sha = o.optString("sha256").takeIf { it.isNotEmpty() }
            val host = o.optString("host").takeIf { it.isNotEmpty() }
            when {
                sha == null -> file.isFile
                hasSha(file, sha) -> true
                host != null && baseOf[host] != null -> download(URL("${baseOf[host]}/v1/files/${file.name}"), file, sha)
                else -> file.isFile
            }
        }
        val text = JSONArray(ready).toString(1)
        if (runCatching { index.readText() }.getOrNull() == text) return
        val tmp = File(dir, "themes.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(index)) { tmp.delete(); return }
        main.post {
            Synced.loadLive(ctx)
            Synced.listeners.toList().forEach { it() }
        }
    }

    private fun discoverPeers(ctx: Context, hosts: List<String>, answered: Map<String, Manifest>) {
        val now = System.currentTimeMillis()
        if (now - peersProbedAt < PEER_PROBE_MS) return
        peersProbedAt = now
        val known = hosts.mapNotNull { runCatching { URL(it).host }.getOrNull() }.toSet()
        val fresh = answered.values.flatMap { it.peers }.filter { it !in known }.distinct()
        val added = fresh.mapNotNull { p -> normalizeBase(p)?.takeIf { fetchManifest(it) != null } }
        if (added.isNotEmpty()) sp(ctx).edit { putString("hosts", (hosts + added).distinct().joinToString("\n")) }
    }

    private fun hasSha(f: File, sha: String): Boolean {
        if (!f.isFile) return false
        val side = File(f.parentFile, f.name + ".sha256")
        if (side.isFile && side.lastModified() >= f.lastModified() && side.readText().trim() == sha) return true
        val got = sha256(f)
        if (got == sha) side.writeText(sha)
        return got == sha
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun connect(url: URL, readMs: Int): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000
        readTimeout = readMs
        instanceFollowRedirects = false
    }

    private fun fetchManifest(base: String): Manifest? {
        val url = URL("$base/v1/manifest")
        val c = runCatching { connect(url, 5000) }.getOrNull() ?: return null
        return try {
            c.setRequestProperty("Accept", "application/json")
            synchronized(manifests) { etags[base] }?.let { c.setRequestProperty("If-None-Match", it) }
            when (c.responseCode) {
                304 -> synchronized(manifests) { manifests[base] }
                200 -> {
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { i ->
                        val b = ByteArray(8192)
                        while (true) { val n = i.read(b); if (n < 0) break; out.write(b, 0, n); check(out.size() <= MAX_MANIFEST) }
                    }
                    parseManifest(String(out.toByteArray(), Charsets.UTF_8))?.also { m ->
                        synchronized(manifests) { c.getHeaderField("ETag")?.let { etags[base] = it }; manifests[base] = m }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: URL, dest: File, sha: String): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val c = runCatching { connect(url, 30_000) }.getOrNull() ?: return false
        return try {
            if (c.responseCode != 200) return false
            val d = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().use { out ->
                c.inputStream.use { i ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(b); if (n < 0) break
                        size += n; check(size <= MAX_PACKAGE)
                        d.update(b, 0, n); out.write(b, 0, n)
                    }
                }
            }
            val got = d.digest().joinToString("") { "%02x".format(it) }
            (got == sha && tmp.renameTo(dest)).also { ok ->
                if (ok) {
                    File(dest.parentFile, dest.name + ".sha256").writeText(sha)
                    File(dest.parentFile, dest.name + ".preview").delete()
                }
            }
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
            c.disconnect()
        }
    }

    private fun post(url: URL, body: String) {
        val c = connect(url, 5000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }
}
