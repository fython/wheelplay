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
    enum class Control { STATUS, INPUT_UNAVAILABLE, BUSY, PONG, CLOSE, RTC, MIC_CONFIG, MIC_STOP, AUDIO_STOP, AUDIO_RESET, AUDIO_ROUTE }
    private data class Outgoing(val frame: WebSocketFrame, val afterSend: () -> Unit = {})
    private val lock = Any()
    private val controls = linkedMapOf<Any, Outgoing>()
    private var closed = false
    private var scheduled = false
    private var inFlight = false
    private var sentAt = 0L
    private var sequence = -1L
    private data class Audio(val frame: WebSocketFrame, val sequence: Long, val at: Long)
    private val audio = java.util.ArrayDeque<Audio>()
    private val audioInFlight = linkedMapOf<Long, Long>()

    fun audio(packet: ByteArray, sequence: Long) = synchronized(lock) {
        if (!closed) {
            while (audio.size >= 16) audio.removeFirst()
            audio.addLast(Audio(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, packet), sequence, now()))
            scheduleLocked()
        }
    }
    fun acknowledgeAudio(sequence: Long) = synchronized(lock) {
        audioInFlight.keys.removeAll { it <= sequence }
        scheduleLocked()
    }
    fun clearAudio() = synchronized(lock) { audio.clear(); audioInFlight.clear() }
    fun clearAudioStream(stream: Int) = synchronized(lock) {
        audio.removeAll {
            val bytes = it.frame.binaryPayload
            bytes.size >= 8 && java.nio.ByteBuffer.wrap(bytes, 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int == stream
        }
    }

    fun frameAvailable() = synchronized(lock) { scheduleLocked() }

    fun acknowledge() = synchronized(lock) {
        inFlight = false
        scheduleLocked()
    }

    fun control(kind: Control, frame: WebSocketFrame, key: Any = kind, afterSend: () -> Unit = {}) = synchronized(lock) {
        if (!closed) {
            controls[key] = Outgoing(frame, afterSend)
            scheduleLocked()
        }
    }

    fun frameStalled(at: Long): Boolean = synchronized(lock) {
        inFlight && at - sentAt > 6000 || audioInFlight.values.firstOrNull()?.let { at - it > 2000 } == true
    }

    private fun scheduleLocked() {
        if (closed || scheduled) return
        if (controls.isEmpty() && (audio.isEmpty() || audioInFlight.size >= 4)) {
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
                    } else if (audio.isNotEmpty() && audioInFlight.size < 4) {
                        while (audio.isNotEmpty() && now() - audio.first.at > 100) audio.removeFirst()
                        if (audio.isEmpty()) { scheduled = false; scheduleLocked(); return }
                        val next = audio.removeFirst()
                        audioInFlight[next.sequence] = now()
                        Outgoing(next.frame)
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
            audio.clear(); audioInFlight.clear()
            // Shutdown under the lock prevents a racing event from scheduling after shutdown.
            executor.shutdownNow()
        }
    }
}
