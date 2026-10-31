package moe.feng.wheelplay

/** Base CarPlay canvas, before encoding resolution and UI scale are applied. */
internal data class CarPlayResolutionSlot(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    val aspectRatio: Float
        get() = width.toFloat() / height

    companion object {
        val DEFAULT = CarPlayResolutionSlot(1280, 720)

        fun forBrowser(viewport: Pair<Int, Int>?, adaptive: Boolean = true): CarPlayResolutionSlot {
            if (!adaptive || viewport == null || viewport.first <= 0 || viewport.second <= 0)
                return DEFAULT
            val scale = minOf(1.0, 3840.0 / viewport.first, 2160.0 / viewport.second)
            fun even(value: Double) = value.toInt().coerceAtLeast(2) / 2 * 2
            return CarPlayResolutionSlot(
                even(viewport.first * scale),
                even(viewport.second * scale),
            )
        }
    }
}
