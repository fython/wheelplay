package com.shilapi.xcertplay.web

import android.os.SystemClock
import fi.iki.elonen.NanoWSD.WebSocketFrame
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One writer, one in-flight JPEG, and coalesced control replies. Never writes under [lock]. */
internal class WebStreamSender(
    private val latestFrame: () -> WebSession.Frame?,
    private val write: (WebSocketFrame) -> Unit,
    private val onFailure: () -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    enum class Control { STATUS, INPUT_UNAVAILABLE, BUSY, PONG, CLOSE, RTC }
    private data class Outgoing(val frame: WebSocketFrame, val afterSend: () -> Unit = {})
    private val lock = Any()
    private val controls = linkedMapOf<Control, Outgoing>()
    private var closed = false
    private var scheduled = false
    private var inFlight = false
    private var sentAt = 0L
    private var sequence = -1L

    fun frameAvailable() = synchronized(lock) { scheduleLocked() }

    fun acknowledge() = synchronized(lock) {
        inFlight = false
        scheduleLocked()
    }

    fun control(kind: Control, frame: WebSocketFrame, afterSend: () -> Unit = {}) = synchronized(lock) {
        if (!closed) {
            controls[kind] = Outgoing(frame, afterSend)
            scheduleLocked()
        }
    }

    fun frameStalled(at: Long): Boolean = synchronized(lock) { inFlight && at - sentAt > 6000 }

    private fun scheduleLocked() {
        if (closed || scheduled) return
        if (controls.isEmpty()) {
            if (inFlight) return
            val latest = latestFrame() ?: return
            if (latest.sequence == sequence) return
        }
        scheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        try {
            while (true) {
                val outgoing = synchronized(lock) {
                    if (closed) return
                    val control = controls.entries.firstOrNull()
                    if (control != null) {
                        controls.remove(control.key)
                        control.value
                    } else {
                        val frame = if (inFlight) null else latestFrame()?.takeIf { it.sequence != sequence }
                        if (frame == null) {
                            // Events use the same lock: none can be lost while the writer goes idle.
                            scheduled = false
                            return
                        }
                        inFlight = true
                        sentAt = now()
                        sequence = frame.sequence
                        Outgoing(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, frame.jpeg))
                    }
                }
                write(outgoing.frame)
                outgoing.afterSend()
            }
        } catch (_: Exception) {
            close()
            onFailure()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            controls.clear()
            // Shutdown under the lock prevents a racing event from scheduling after shutdown.
            executor.shutdownNow()
        }
    }
}
