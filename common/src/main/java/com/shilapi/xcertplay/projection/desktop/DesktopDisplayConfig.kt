package com.shilapi.xcertplay.projection.desktop

/** Immutable for the lifetime of a display, its video output and input mapping. */
internal data class DesktopDisplayConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val dpi: Int = 160,
) {
    init {
        require(width in MIN_SIZE..MAX_SIZE && height in MIN_SIZE..MAX_SIZE &&
            width.toLong() * height <= MAX_PIXELS && dpi in MIN_DPI..MAX_DPI) {
            "Unsupported virtual display size or density"
        }
    }

    companion object {
        const val MIN_SIZE = 320
        const val MAX_SIZE = 3840
        const val MAX_PIXELS = 8_294_400L
        const val MIN_DPI = 72
        const val MAX_DPI = 640
    }
}
