package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class VideoPerformanceTest {
    @Test fun boundedTimingWindowAndIdleRateAreIndependentOfCumulativeCounts() {
        var now = 1L
        val metrics = VideoPerformance { now }
        repeat(300) { metrics.count("input"); metrics.timing("queue", it * 1_000_000L) }
        val snapshot = metrics.snapshot()
        assertEquals(300L, (snapshot["counts"] as Map<*, *>)["input"])
        val queue = (snapshot["timings"] as Map<*, *>)["queue"] as Map<*, *>
        assertEquals(179.5, queue["meanMs"])
        assertEquals(287.0, queue["p95Ms"])
        now += 3_000_000_000L
        assertEquals(0.0, (metrics.snapshot()["fps"] as Map<*, *>)["input"])
        assertEquals(300L, (metrics.snapshot()["counts"] as Map<*, *>)["input"])
    }
}
