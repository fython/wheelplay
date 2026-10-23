package com.shilapi.xcertplay.airplay

import java.net.SocketTimeoutException

/** Absolute deadline for an incomplete wire message, including partial encrypted frames.
 * Idle established sessions have no read timeout; receiving another byte cannot extend a request.
 */
internal class RtspReadDeadline(
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Int = 30_000,
) {
    private var started: Long? = null

    fun socketTimeoutMillis(): Int {
        val start = started ?: return 0
        val remaining = timeoutMillis * 1_000_000L - (nowNanos() - start)
        if (remaining <= 0) throw SocketTimeoutException("RTSP: incomplete message deadline exceeded")
        return ((remaining + 999_999L) / 1_000_000L).toInt()
    }

    fun receivedBytes() {
        socketTimeoutMillis()
        if (started == null) started = nowNanos()
    }

    fun messageCompleted() {
        started = null
    }

    fun retainPartial(pending: Boolean) {
        if (!pending) started = null
        else if (started == null) started = nowNanos()
    }
}
