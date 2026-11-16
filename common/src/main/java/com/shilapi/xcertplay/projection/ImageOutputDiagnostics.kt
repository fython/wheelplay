package com.shilapi.xcertplay.projection

import android.os.SystemClock

/** Counts published images, not browser presentation or decoded source frames. */
internal object ImageOutputDiagnostics {
    private val times = java.util.ArrayDeque<Long>()
    private var description = "尚无输出"
    @Synchronized fun record(width: Int, height: Int, format: String, quality: Int) {
        val now = SystemClock.elapsedRealtime()
        times.addLast(now)
        while (times.isNotEmpty() && times.first < now - 1000) times.removeFirst()
        description = "$width × $height · $format · 质量 $quality"
    }
    @Synchronized fun describe(): String {
        val now = SystemClock.elapsedRealtime()
        while (times.isNotEmpty() && times.first < now - 1000) times.removeFirst()
        return "$description\n最近 1 秒发布：${times.size} 张（非浏览器显示帧率）"
    }
}
