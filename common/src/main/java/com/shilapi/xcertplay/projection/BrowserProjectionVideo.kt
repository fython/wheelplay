package com.shilapi.xcertplay.projection

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.VideoCodec
import com.shilapi.xcertplay.web.ImageFrameEncoder
import com.shilapi.xcertplay.web.OffscreenVideoOutput
import com.shilapi.xcertplay.web.StreamTechnology
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.WebVideoSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android output endpoint shared by encoded, Surface and framebuffer sources. */
internal class BrowserProjectionVideo(
    context: Context, val width: Int, val height: Int,
    private val kind: VideoIngressKind,
) : ProjectionVideoOutput {
    private val app = context.applicationContext
    private val closed = AtomicBoolean()
    private var generation = -1L
    private val encoded = WebVideoSource(StreamTechnology.WEBRTC)
    private var offscreen: OffscreenVideoOutput? = null
    private var decoder: AndroidMediaSink? = null
    private val images = Executors.newSingleThreadScheduledExecutor { Thread(it, "projection-images").apply { isDaemon = true } }
    private val pending = AtomicReference<ProjectionFramebuffer.Snapshot?>()
    private var latest: ProjectionFramebuffer.Snapshot? = null
    private val imageEncoder = ImageFrameEncoder()
    private var hadViewer = false
    private var configured = false
    private var revision = 0L

    init {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 8_388_608)
        try {
            if (kind != VideoIngressKind.FRAMEBUFFER) offscreen = OffscreenVideoOutput(width, height,
                wanted = { !closed.get() && WebSession.hasViewer && (kind != VideoIngressKind.ENCODED || encoded.jpegNeeded) },
                encoding = { AirPlayPersistence.imageEncoding(app, WebSession.imageFormat) },
                publish = { bytes, format, quality ->
                    if (!closed.get()) {
                        WebSession.setVideoActive(generation, true)
                        WebSession.publish(generation, bytes, format, quality)
                    }
                })
            if (kind == VideoIngressKind.ENCODED) decoder = AndroidMediaSink(
                surface = offscreen!!.surface, videoWidth = width, videoHeight = height, encodedVideo = encoded)
            generation = WebSession.beginVideo(width, height)
            if (kind == VideoIngressKind.ENCODED) WebSession.attachVideoSource(generation, encoded)
            if (kind == VideoIngressKind.FRAMEBUFFER) images.scheduleWithFixedDelay({
                if (!closed.get()) {
                    val next = pending.getAndSet(null)
                    if (next != null) latest = next
                    val viewer = WebSession.hasViewer
                    if (viewer && (next != null || !hadViewer)) latest?.let { snapshot ->
                        val bitmap = Bitmap.createBitmap(snapshot.argb, width, height, Bitmap.Config.ARGB_8888)
                        try {
                            val options = AirPlayPersistence.imageEncoding(app, WebSession.imageFormat)
                            val (bytes, format) = imageEncoder.encode(options) { format, quality, output ->
                                bitmap.compress(format.compression, quality, output)
                            }
                            WebSession.publish(generation, bytes, format, options.quality)
                        } finally { bitmap.recycle() }
                    }
                    hadViewer = viewer
                }
            }, 0, 66, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) {
                cleanup.addSuppressed(failure)
                throw ProjectionCleanupException("Video startup cleanup failed", cleanup)
            }
            throw failure
        }
    }

    override val surface: Surface get() {
        check(kind == VideoIngressKind.SURFACE && !closed.get())
        return offscreen!!.surface
    }
    @Synchronized override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long {
        check(kind == VideoIngressKind.ENCODED && !closed.get())
        val target = decoder!!
        target.setVideoRecoveryHandler(0, requestKeyframe)
        target.onVideoCodec(0, codec)
        target.onVideoConfig(0, codecData.copyOf())
        configured = true
        return ++revision
    }
    @Synchronized override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long): Boolean {
        if (closed.get() || !configured || formatRevision != revision) return false
        decoder!!.onVideoFrame(0, frame, ptsUs, System.nanoTime(), 0)
        WebSession.setVideoActive(generation, true)
        return true
    }
    override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot): Boolean {
        if (closed.get() || kind != VideoIngressKind.FRAMEBUFFER) return false
        require(snapshot.width == width && snapshot.height == height && snapshot.argb.size == width * height)
        pending.set(snapshot.copy(argb = snapshot.argb.copyOf()))
        WebSession.setVideoActive(generation, true)
        return true
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        images.shutdownNow()
        check(images.awaitTermination(5, TimeUnit.SECONDS)) { "Image producer did not stop" }
        pending.set(null)
        decoder?.close(awaitWorkers = true)
        offscreen?.closeAndAwait()
        WebSession.endVideo(generation)
    }
}
