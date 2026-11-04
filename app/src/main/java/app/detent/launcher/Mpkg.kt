package app.detent.launcher

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a Wallpaper Engine phone package (.mpkg). The file starts with a "PKGM00xx" header and a table of
 * entries (name, offset, size, little-endian), then the entry bytes. Detent needs only two entries: the
 * title in project.json, which is what Wallpaper Engine shows on its tile, and the preview image.
 */
class Mpkg private constructor(private val file: File, private val base: Long, val entries: Map<String, Pair<Long, Int>>) {

    fun read(name: String): ByteArray? {
        val (off, size) = entries[name] ?: return null
        if (size < 0 || size > MAX_ENTRY) return null
        return RandomAccessFile(file, "r").use { f ->
            if (base + off + size > f.length()) return null
            ByteArray(size).also { f.seek(base + off); f.readFully(it) }
        }
    }

    /** The project.json fields Detent uses, or null when the package has none. */
    fun project(): Project? = read("project.json")?.let { parseProject(String(it, Charsets.UTF_8)) }

    /** The preview image bytes and its file name, from project.json or the usual names. */
    fun preview(): Pair<String, ByteArray>? {
        val named = project()?.preview
        val name = listOfNotNull(named, "preview.jpg", "preview.png", "preview.gif").firstOrNull { it in entries } ?: return null
        return read(name)?.let { name to it }
    }

    data class Project(val title: String?, val type: String?, val preview: String?)

    companion object {
        private const val MAX_ENTRY = 24 * 1024 * 1024
        private const val MAX_ENTRIES = 4096

        fun open(file: File): Mpkg? = runCatching {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(minOf(f.length(), 256 * 1024L).toInt())
                f.readFully(head)
                parseTable(head)?.let { (base, entries) -> Mpkg(file, base, entries) }
            }
        }.getOrNull()

        /** Entry table from the first bytes of a package: the data base offset and name to (offset, size). */
        internal fun parseTable(head: ByteArray): Pair<Long, Map<String, Pair<Long, Int>>>? {
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            fun str(): String? {
                if (b.remaining() < 4) return null
                val n = b.int
                if (n < 0 || n > 1024 || b.remaining() < n) return null
                return String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
            }
            val magic = str() ?: return null
            if (!magic.startsWith("PKGM")) return null
            if (b.remaining() < 4) return null
            val count = b.int
            if (count < 0 || count > MAX_ENTRIES) return null
            val entries = LinkedHashMap<String, Pair<Long, Int>>()
            repeat(count) {
                val name = str() ?: return null
                if (b.remaining() < 8) return null
                val off = b.int.toLong() and 0xFFFFFFFFL
                entries[name] = off to b.int
            }
            return b.position().toLong() to entries
        }

        internal fun parseProject(json: String): Project? = runCatching {
            val o = JSONObject(json)
            Project(
                o.optString("title").trim().takeIf { it.isNotEmpty() },
                o.optString("type").trim().lowercase().takeIf { it.isNotEmpty() },
                o.optString("preview").trim().takeIf { it.isNotEmpty() },
            )
        }.getOrNull()

        /**
         * Writes the package's preview next to it as `<file>.preview` once, and returns its path.
         * BitmapFactory decodes jpg, png and the first frame of a gif.
         */
        fun ensurePreview(pkg: File): File? {
            val out = File(pkg.parentFile, pkg.name + ".preview")
            if (out.isFile && out.length() > 0 && out.lastModified() >= pkg.lastModified()) return out
            val (_, bytes) = open(pkg)?.preview() ?: return null
            val tmp = File(pkg.parentFile, out.name + ".tmp")
            return runCatching { tmp.writeBytes(bytes); check(tmp.renameTo(out)); out }.getOrElse { tmp.delete(); null }
        }
    }
}
