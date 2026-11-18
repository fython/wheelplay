package moe.feng.wheelplay.projection.carlife

import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.web.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The receiver feeds complete AVC access units directly; no Binder or pipe reader. */
internal class CarLifeVideo(width: Int, height: Int, private val fail: (String) -> Unit) : AutoCloseable {
    private val parser = ProjectionAvcStream()
    private val frames = ProjectionAvcQueue()
    private val source = WebVideoSource(StreamTechnology.WEBRTC)
    private val generation = WebSession.beginVideo(width, height)
    private var closed = false
    private var waitingConsumer: Any? = null
    private var waitingSince = 0L
    private val sender = Executors.newSingleThreadScheduledExecutor { Thread(it, "carlife-video-output").apply { isDaemon = true } }
    init {
        WebSession.attachVideoSource(generation, source)
        source.recovery(0) { }
        sender.scheduleWithFixedDelay({
            try { synchronized(this) {
                if (!closed) {
                    val consumer = source.connectedConsumer
                    val now = System.nanoTime()
                    if (consumer !== waitingConsumer || consumer == null || !source.jpegNeeded) {
                        waitingConsumer = consumer; waitingSince = now
                    }
                    if (consumer != null && source.jpegNeeded && now - waitingSince > 45_000_000_000L)
                        error("WebRTC 等待关键帧超时，请重新连接 CarLife")
                    frames.poll(consumer)?.let { source.frame(0, it.bytes, it.ptsUs) }
                }
            } } catch (error: Exception) { fail(error.message ?: "CarLife 视频发送失败") }
        }, 0, 2, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun frame(bytes: ByteArray, ptsUs: Long) {
        if (closed) return
        val frame = parser.accept(bytes, ptsUs)
        parser.configuration?.let { source.configure(0, VideoCodec.H264, it) }
        if (frame != null) { frames.offer(frame); WebSession.setVideoActive(generation, true) }
    }
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true; sender.shutdownNow(); frames.clear()
            source.stopped(0); WebSession.endVideo(generation)
        }
        check(sender.awaitTermination(5, TimeUnit.SECONDS)) { "CarLife video sender did not stop" }
    }
}
