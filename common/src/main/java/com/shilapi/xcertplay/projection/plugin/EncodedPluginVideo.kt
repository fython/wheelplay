package com.shilapi.xcertplay.projection.plugin

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.shilapi.xcertplay.media.VideoCodec
import com.shilapi.xcertplay.web.StreamTechnology
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.WebVideoSource
import moe.feng.wheelplay.plugin.EncodedVideoWire
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No Surface, decoder, image fallback, screen capture files or encoder in this output. */
internal class EncodedPluginVideo(private val failure: (String) -> Unit) : AutoCloseable {
    private val pipe = ParcelFileDescriptor.createReliablePipe()
    private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]))
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val parser = PluginAvcStream()
    private val frames = PluginAvcQueue()
    private val source = WebVideoSource(StreamTechnology.WEBRTC)
    private val sender = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "plugin-webrtc").apply { isDaemon = true } }
    private val lifecycle = Any()
    private var generation = -1L
    private val keyframeWait = PluginKeyframeWait()
    private val reader = Thread({
        try {
            val (width, height) = EncodedVideoWire.readFormat(input)
            synchronized(lifecycle) {
                if (closed.get()) return@Thread
                generation = WebSession.beginVideo(width, height)
                WebSession.attachVideoSource(generation, source)
            }
            var sequence = 0L
            while (!closed.get()) {
                val record = EncodedVideoWire.readFrame(input)
                require(record.sequence == sequence++) { "Encoded video sequence gap" }
                val unit = parser.accept(record.bytes, record.ptsUs)
                synchronized(lifecycle) {
                    if (closed.get()) return@Thread
                    parser.configuration?.let { source.configure(0, VideoCodec.H264, it) }
                    if (unit != null) {
                        frames.offer(unit)
                        WebSession.setVideoActive(generation, true)
                    }
                }
            }
        } catch (error: Exception) { report(error.message ?: "压缩视频通道已关闭") }
    }, "plugin-encoded-reader").apply { isDaemon = true }

    init {
        // Extension v1 does not promise a keyframe control API. A new consumer gets bounded
        // bootstrap replay; loss on an existing consumer waits for the sender's next IDR.
        source.recovery(0) { }
        reader.start()
        sender.scheduleWithFixedDelay({
            if (!closed.get()) try {
                val consumer = source.connectedConsumer
                val now = SystemClock.elapsedRealtime()
                if (keyframeWait.expired(consumer, source.jpegNeeded, now) &&
                    consumer === source.connectedConsumer && source.jpegNeeded) {
                    report("WebRTC 等待关键帧超时，请重新连接或在插件设置中选择 Surface。")
                }
                frames.poll(consumer)?.let { source.frame(0, it.bytes, it.ptsUs) }
            } catch (error: Exception) { report(error.message ?: "WebRTC 视频发送失败") }
        }, 0, 2, TimeUnit.MILLISECONDS)
    }

    /** Local handle must be closed only after Binder has marshalled its duplicate. */
    fun sendTo(remote: moe.feng.wheelplay.plugin.IProjectionPlugin, token: String) {
        try { remote.startEncoded(token, pipe[1]) } finally { pipe[1].close() }
    }
    private fun report(message: String) { if (!closed.get() && failed.compareAndSet(false, true)) failure(message) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { pipe[1].close() }; runCatching { input.close() }
        sender.shutdownNow()
        synchronized(lifecycle) {
            source.stopped(0)
            if (generation >= 0) WebSession.endVideo(generation)
        }
        frames.clear()
    }
}

/** Sender-thread state: offline time must never count against a decoder's recovery budget. */
internal class PluginKeyframeWait {
    private var consumer: Any? = null
    private var waitingSince: Long? = null

    fun expired(currentConsumer: Any?, awaitingFrame: Boolean, nowMs: Long): Boolean {
        if (consumer !== currentConsumer || currentConsumer == null || !awaitingFrame) {
            consumer = currentConsumer
            waitingSince = null
        }
        if (currentConsumer == null || !awaitingFrame) return false
        val start = waitingSince ?: nowMs.also { waitingSince = it }
        return nowMs - start > 45_000
    }
}
