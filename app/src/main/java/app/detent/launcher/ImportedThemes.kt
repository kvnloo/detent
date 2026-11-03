package app.detent.launcher

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.util.AtomicFile
import androidx.compose.ui.graphics.luminance
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded public downloads. Redirects cannot escape HTTPS or enter the local network. */
internal object ThemeDownloads {
    fun bytes(link: String, limit: Int): ByteArray {
        var uri = themeUrl(link)
        repeat(4) {
            val addresses = InetAddress.getAllByName(uri.host)
            require(addresses.isNotEmpty() && addresses.all { address ->
                val bytes = address.address.map { it.toInt() and 255 }
                val sharedOrReserved = bytes.size == 4 && (bytes[0] == 0 || bytes[0] >= 240 ||
                    (bytes[0] == 100 && bytes[1] in 64..127) || (bytes[0] == 198 && bytes[1] in 18..19))
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress && !address.isMulticastAddress && !sharedOrReserved &&
                    !(bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc)
            }) { "Local network links aren't supported." }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Detent-theme-import")
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, image/*, text/html, */*")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    uri = themeUrl(uri.resolve(connection.getHeaderField("Location") ?: error("Redirect has no link.")).toString())
                } else {
                    if (status == 403 || status == 429) throw IOException("Source refused the request or is rate-limited. Try again later.")
                    if (status !in 200..299) throw IOException("Source returned HTTP $status.")
                    require(connection.contentLengthLong <= limit) { "File is too large (limit ${limit / 1024 / 1024} MB)." }
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= limit) { "Download exceeded its size limit." }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects.")
    }

    fun discover(link: String) = ThemeSources.discover(link) { bytes(it, 4 * 1024 * 1024).toString(Charsets.UTF_8) }
}

/** Imported records are append-only: saved theme indices retain their meaning across restarts and re-imports. */
internal object ImportedThemes {
    const val GROUP = "imported"
    const val BATCH = 20
    private fun dir(ctx: Context) = File(ctx.filesDir, "themes").apply { mkdirs() }
    private fun index(ctx: Context) = AtomicFile(File(dir(ctx), "index.json"))

    private fun records(ctx: Context): JSONArray {
        val file = index(ctx)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONArray()
        return JSONArray(file.openRead().bufferedReader().use { it.readText() })
    }

    /** Must run before prefs are read. Existing live themes precede imports. */
    fun load(ctx: Context) {
        val rows = runCatching { records(ctx) }.getOrElse { return }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            append(ctx, row)
        }
    }

    private fun append(ctx: Context, row: JSONObject) {
        val id = row.getString("id")
        if (THEMES.any { it.importId == id }) return
        val live = row.optBoolean("live")
        val preview = row.optString("image").takeIf { it.matches(Regex("[a-f0-9]{64}\\.jpg")) }
            ?.let { File(dir(ctx), it) }?.takeIf { it.isFile }?.path
        val mobile = row.optString("package").takeIf { it.matches(Regex("[a-f0-9]{64}\\.mpkg")) }
            ?.let { File(File(ctx.filesDir, "live"), it) }?.takeIf { it.isFile }?.path
        val theme = ThemeDef(
            row.getString("name"), GROUP, androidx.compose.ui.graphics.Color(row.getLong("bg")).luminance() > .5f,
            row.getLong("bg"), row.getLong("fg"), row.getLong("accent"),
            if (live) Synced.SYSTEM_WALL else preview,
            preview = if (live) preview else null,
            liveSource = row.optString("workshop").takeIf { it.isNotBlank() },
            mobilePackage = mobile, importId = id,
        )
        THEMES += theme; PALETTES += Palette(theme); THEME_NAMES += theme.name
    }

    data class Result(val added: Int, val existing: Int, val failed: List<String>)

    /** Downloads into private storage, persists atomically, then returns records for publication on main. */
    @Synchronized
    fun install(ctx: Context, chosen: List<ThemeCandidate>, progress: (String) -> Unit): Result {
        require(chosen.isNotEmpty() && chosen.size <= BATCH) { "Choose 1–$BATCH themes at a time." }
        var rows = records(ctx)
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toMutableSet()
        var added = 0; var existing = 0; var budget = 64 * 1024 * 1024
        val failed = mutableListOf<String>()
        for (candidate in chosen.distinctBy { it.id }) {
            val id = MessageDigest.getInstance("SHA-256").digest(candidate.id.toByteArray()).joinToString("") { "%02x".format(it) }
            if (id in ids) { existing++; continue }
            progress(candidate.name)
            val created = mutableListOf<File>()
            try {
                var colours = longArrayOf(0xFF181F23, 0xFFD1DEDA, 0xFFC8BE72)
                var image: File? = null
                if (candidate.image != null) {
                    val bytes = ThemeDownloads.bytes(candidate.image, minOf(16 * 1024 * 1024, budget))
                    budget -= bytes.size
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 50_000_000) { "Invalid or oversized image." }
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        val scale = minOf(1f, 2560f / max(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        colours = colours(WallpaperColors.fromBitmap(bitmap).primaryColor.toArgb())
                        image = File(dir(ctx), "$id.jpg").also { created += it }
                        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                    } finally { bitmap.recycle() }
                }
                var mobile: File? = null
                if (candidate.mobile != null) {
                    // Packages are data handed to Wallpaper Engine, never unpacked or executed by Detent.
                    val bytes = ThemeDownloads.bytes(candidate.mobile, minOf(48 * 1024 * 1024, budget))
                    budget -= bytes.size
                    require(bytes.isNotEmpty()) { "Empty phone package." }
                    mobile = File(File(ctx.filesDir, "live").apply { mkdirs() }, "$id.mpkg").also { created += it }
                    mobile.writeBytes(bytes)
                }
                val row = JSONObject().put("id", id).put("name", candidate.name).put("source", candidate.source)
                    .put("bg", candidate.bg ?: colours[0]).put("fg", candidate.fg ?: colours[1]).put("accent", candidate.accent ?: colours[2])
                    .put("live", candidate.live).put("image", image?.name ?: "").put("workshop", candidate.workshop ?: "")
                    .put("package", mobile?.name ?: "")
                // Commit per theme. A later failure cannot invalidate an earlier successful item.
                val next = JSONArray(rows.toString()).put(row)
                val atomic = index(ctx)
                val stream = atomic.startWrite()
                try {
                    stream.write(next.toString().toByteArray())
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
                rows = next
                ids += id
                added++
            } catch (e: Exception) {
                created.forEach { it.delete() }
                failed += "${candidate.name}: ${e.message ?: "couldn't import"}"
            }
        }
        return Result(added, existing, failed)
    }

    private fun colours(primary: Int): LongArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(primary, hsv)
        fun tone(saturation: Float, value: Float): Long = Color.HSVToColor(floatArrayOf(hsv[0], saturation, value)).toLong() and 0xffffffffL
        return longArrayOf(tone(minOf(hsv[1], .25f), .10f), tone(minOf(hsv[1], .12f), .91f), tone(maxOf(hsv[1], .45f), .9f))
    }
}
