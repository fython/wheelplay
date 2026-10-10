package com.shilapi.xcertplay.web

/** A grace period for recoverable interruptions, not a video buffering budget. */
enum class RtcRecoveryPolicy(val graceMs: Long, val label: String) {
    QUICK(4_000, "4 秒 · 快速回退"),
    BALANCED(10_000, "10 秒 · 默认"),
    PATIENT(30_000, "30 秒 · 优先保持 WebRTC");

    val firstFrameMs get() = maxOf(10_000L, graceMs)
}
