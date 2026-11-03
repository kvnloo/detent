package app.detent.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/**
 * Sets the lock screen and the system wallpaper to the launcher backdrop. Android then takes the lock screen
 * clock colours and the dynamic colours from it.
 */
object SystemWallpaper {
    /** Renders and sets the wallpaper. This blocks, so call it off the main thread. */
    fun apply(
        ctx: Context,
        theme: ThemeDef,
        pal: Palette,
        left: Boolean,
        custom: String?,
        grid: Boolean,
        width: Int,
        height: Int,
    ) {
        val wm = WallpaperManager.getInstance(ctx)
        check(wm.isWallpaperSupported && wm.isSetWallpaperAllowed) { "wallpaper changes are not allowed" }
        val bitmap = if (grid) renderGrid(pal, width, height) else render(ctx, theme, pal, left, custom, width, height)
        try {
            wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } finally {
            bitmap.recycle()
        }
    }

    /** The resting dot grid of the grid layouts, without the dial. */
    fun renderGrid(pal: Palette, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(pal.bg.toArgb())
        val k = w / W
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = pal.fg.copy(alpha = .16f).toArgb()
        var y = GO
        while (y < h / k + G) {
            var x = GO
            while (x < W) {
                c.drawCircle(x * k, y * k, 1.2f * k, paint)
                x += G
            }
            y += G
        }
        return out
    }

    /** The same layers the home screen draws behind the dial. [custom] is the path of the user's own wallpaper. */
    fun render(ctx: Context, theme: ThemeDef, pal: Palette, left: Boolean, custom: String?, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(pal.bg.toArgb())
        val hand = custom == null && theme.wall == null
        val src = if (custom != null) {
            BitmapFactory.decodeFile(custom) ?: error("can't read $custom")
        } else {
            val path = when {
                hand -> "hands.webp"
                left && theme.wallLeft != null -> theme.wallLeft
                else -> theme.wall
            }
            if (path!!.startsWith("/")) BitmapFactory.decodeFile(path) ?: error("can't read wallpaper")
            else ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (hand) paint.colorFilter = ColorMatrixColorFilter(handMatrix(pal))
        // Centre crop, like ContentScale.Crop.
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val dst = RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        c.save()
        if (hand && left) c.scale(-1f, 1f, w / 2f, h / 2f)
        c.drawBitmap(src, null, dst, paint)
        c.restore()
        src.recycle()
        if (!hand) {
            val bg = pal.bg
            fun a(alpha: Float) = bg.copy(alpha = alpha).toArgb()
            val scrim = Paint()
            scrim.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(a(.6f), a(.15f), a(.2f), a(.75f)), floatArrayOf(0f, .32f, .7f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
            val near = a(.86f)
            val far = a(.2f)
            scrim.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(if (left) near else far, a(.5f), if (left) far else near), floatArrayOf(0f, .5f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
        }
        return out
    }
}
