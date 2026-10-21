package com.shilapi.xcertplay.projection

import android.graphics.Color
import android.graphics.Paint
import com.shilapi.xcertplay.media.HeapVideoFrame
import com.shilapi.xcertplay.media.VideoCodec
import java.io.DataInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic host diagnostic, with no network, authentication or microphone requirement. */
internal class SyntheticSource(
    private val kind: VideoIngressKind,
    private val output: (VideoIngressKind, Int, Int) -> ProjectionVideoOutput,
    private val encodedFixture: () -> InputStream,
) : ProjectionSourceProvider {
    override val descriptor = SourceDescriptor("demo-${kind.name.lowercase()}", "Demo · ${kind.name.lowercase()}")
    override val resources = setOf(ProjectionResource.DISPLAY)
    override fun probe() = SourceAvailability.Ready
    override fun open(): CompletableFuture<ProjectionSession> = CompletableFuture.supplyAsync {
        val clip = if (kind == VideoIngressKind.ENCODED) Clip.read(encodedFixture()) else null
        val video = output(kind, WIDTH, HEIGHT)
        try { Session(video, clip) } catch (failure: Throwable) {
            try { video.close() } catch (cleanup: Throwable) {
                cleanup.addSuppressed(failure)
                throw ProjectionCleanupException("Demo startup cleanup failed", cleanup)
            }
            throw failure
        }
    }

    private inner class Session(private val video: ProjectionVideoOutput, private val clip: Clip?) : ProjectionSession {
        override val descriptor = this@SyntheticSource.descriptor
        override val capabilities = ProjectionCapabilities(setOf(kind), if (clip == null) 10 else 0, nightMode = clip == null)
        private val stopped = AtomicBoolean()
        private val keyframe = AtomicBoolean()
        private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projection-demo").apply { isDaemon = true } }
        private val completion = CompletableFuture<Unit>()
        @Volatile private var contacts = emptyList<ProjectionContact>()
        @Volatile private var night = false
        @Volatile private var failureMessage: String? = null
        override val statusText get() = failureMessage
        override val inputReady get() = !stopped.get() && failureMessage == null && capabilities.maxTouchContacts > 0
        private val pixels = ProjectionFramebuffer(WIDTH, HEIGHT)
        private var frame = 0L
        private var clipPosition = 0
        private val revision = clip?.let { video.configure(VideoCodec.H264, it.config) { keyframe.set(true) } }

        init {
            worker.scheduleWithFixedDelay({
                if (!stopped.get() && failureMessage == null) try { draw() } catch (failure: Exception) { failureMessage = failure.message ?: failure.javaClass.simpleName }
            }, 0, 66, TimeUnit.MILLISECONDS)
        }
        private fun draw() {
            if (clip != null) {
                if (keyframe.getAndSet(false)) clipPosition = 0
                HeapVideoFrame(clip.frames[clipPosition]).use { video.frame(revision!!, it, frame * 66_667) }
                clipPosition = (clipPosition + 1) % clip.frames.size
            } else if (kind == VideoIngressKind.SURFACE) {
                val surface = video.surface
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(if (night) Color.DKGRAY else Color.rgb(20, 60, 90))
                    val paint = Paint().apply { color = Color.CYAN; textSize = 18f }
                    canvas.drawText("Surface · $frame", 12f, 28f, paint)
                    canvas.drawRect((frame % WIDTH).toFloat(), 48f, (frame % WIDTH + 18).toFloat(), 72f, paint)
                    contacts.filter { it.down }.forEach { canvas.drawCircle((it.x * (WIDTH - 1)).toFloat(), (it.y * (HEIGHT - 1)).toFloat(), 12f, paint) }
                } finally { surface.unlockCanvasAndPost(canvas) }
            } else {
                // Apply ordered increments, including overlapping CopyRect, before publishing a full snapshot.
                pixels.copy(1, 0, 0, 0, WIDTH - 1, HEIGHT)
                val color = if (night) Color.DKGRAY else Color.rgb((frame * 3 % 255).toInt(), 90, 150)
                pixels.update(WIDTH - 1, 0, 1, HEIGHT, IntArray(HEIGHT) { color })
                contacts.filter { it.down }.forEach {
                    val x = (it.x * (WIDTH - 9)).toInt(); val y = (it.y * (HEIGHT - 9)).toInt()
                    pixels.update(x, y, 9, 9, IntArray(81) { Color.WHITE })
                }
                video.framebuffer(pixels.snapshot())
            }
            frame++
        }
        override fun touch(contacts: List<ProjectionContact>): Boolean {
            if (!inputReady) return false
            this.contacts = contacts.toList()
            return true
        }
        override fun setNightMode(night: Boolean): ProjectionCommandResult {
            if (!capabilities.nightMode) return ProjectionCommandResult.UNSUPPORTED
            if (stopped.get()) return ProjectionCommandResult.UNAVAILABLE
            this.night = night
            return ProjectionCommandResult.SENT
        }
        override fun stop(): CompletableFuture<Unit> {
            if (stopped.compareAndSet(false, true)) {
                contacts = emptyList()
                worker.shutdownNow()
                CompletableFuture.runAsync {
                    try {
                        check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Demo producer did not stop" }
                        video.close()
                        completion.complete(Unit)
                    } catch (failure: Throwable) { completion.completeExceptionally(failure) }
                }
            }
            return completion
        }
    }
    private data class Clip(val config: ByteArray, val frames: List<ByteArray>) {
        companion object {
            fun read(stream: InputStream) = DataInputStream(stream).use { input ->
                fun bytes(): ByteArray = ByteArray(input.readInt().also { require(it in 1..1_048_576) }).also(input::readFully)
                val config = bytes()
                val count = input.readInt().also { require(it in 1..300) }
                Clip(config, List(count) { bytes() })
            }
        }
    }
    companion object { const val WIDTH = 320; const val HEIGHT = 180 }
}
