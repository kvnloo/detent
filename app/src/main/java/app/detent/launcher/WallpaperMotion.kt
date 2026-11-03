package app.detent.launcher

/** Direction of the reveal, using unwrapped dial rows so crossing the last theme never reverses it. */
internal fun wallpaperDirection(
    from: Int, to: Int, fromPath: String, toPath: String, preview: Boolean, jump: Boolean,
): Int {
    // A custom photo stays put, and a distant tap doesn't fly through unrelated wallpapers.
    if (!preview || jump || fromPath == toPath) return 0
    return to.compareTo(from)
}
