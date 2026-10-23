package com.shilapi.xcertplay.projection

/** Ordered framebuffer updates must all be applied before coalescing complete output snapshots. */
class ProjectionFramebuffer(val width: Int, val height: Int) {
    init { require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 8_388_608) }
    private val pixels = IntArray(width * height)
    private var revision = 0L
    data class Snapshot(val width: Int, val height: Int, val revision: Long, val argb: IntArray)

    @Synchronized fun update(x: Int, y: Int, w: Int, h: Int, argb: IntArray) {
        bounds(x, y, w, h)
        require(argb.size == w * h)
        repeat(h) { row -> argb.copyInto(pixels, (y + row) * width + x, row * w, (row + 1) * w) }
        revision++
    }
    @Synchronized fun copy(sourceX: Int, sourceY: Int, x: Int, y: Int, w: Int, h: Int) {
        bounds(sourceX, sourceY, w, h); bounds(x, y, w, h)
        val rows = if (y > sourceY) (h - 1 downTo 0) else (0 until h)
        for (row in rows) System.arraycopy(pixels, (sourceY + row) * width + sourceX,
            pixels, (y + row) * width + x, w)
        revision++
    }
    @Synchronized fun snapshot() = Snapshot(width, height, revision, pixels.copyOf())
    private fun bounds(x: Int, y: Int, w: Int, h: Int) {
        require(w > 0 && h > 0 && x >= 0 && y >= 0 && w <= width - x && h <= height - y)
    }
}
