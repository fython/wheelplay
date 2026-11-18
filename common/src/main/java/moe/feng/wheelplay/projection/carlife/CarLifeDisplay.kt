package moe.feng.wheelplay.projection.carlife

import moe.feng.wheelplay.carlife.VideoSize
import kotlin.math.min
import kotlin.math.sqrt

/** Bound encoder work while retaining the WebUI aspect ratio; stable across pixel-sized jitter. */
internal object CarLifeDisplay {
    fun forViewport(size: Pair<Int, Int>?): VideoSize {
        if (size == null) return VideoSize.DEFAULT
        val (width, height) = size
        require(width > 0 && height > 0)
        val scale = min(1.0, min(4096.0 / maxOf(width, height),
            sqrt(1920.0 * 1080 / (width.toDouble() * height))))
        return VideoSize(((width * scale).toInt() / 8 * 8).coerceAtLeast(8),
            ((height * scale).toInt() / 8 * 8).coerceAtLeast(8))
    }
}
