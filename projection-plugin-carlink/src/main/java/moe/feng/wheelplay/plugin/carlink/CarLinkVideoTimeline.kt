package moe.feng.wheelplay.plugin.carlink

/** SDK timestamps are signed and may start below zero over USB. Keep intervals intact. */
internal class CarLinkVideoTimeline {
    private var origin: Long? = null
    fun normalize(timestampUs: Long): Long {
        val start = origin ?: timestampUs.also { origin = it }
        val relative = try { Math.subtractExact(timestampUs, start) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Video timestamp overflow") }
        require(relative >= 0) { "Video timestamp precedes stream origin" }
        return relative
    }
}
