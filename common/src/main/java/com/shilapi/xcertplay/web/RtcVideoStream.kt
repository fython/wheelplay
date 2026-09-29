package com.shilapi.xcertplay.web

import android.util.Log
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.CompressedVideoFrame
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded compressed-frame queue; a lost reference chain resumes only at an IDR. */
internal class RtcVideoStream(
    private val source: WebVideoSource,
    private val config: WebVideoSource.Config,
    private val offer: (String) -> Unit,
    private val failure: () -> Unit,
) : Closeable {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val queue = RtcFrameQueue()
    private val performance = VideoPerformance()
    private val health = RtcReceiverHealth()
    @Volatile private var receiverReport: RtcReceiverHealth.Report? = null
    @Volatile private var congested = false
    @Volatile private var srtpStats: Map<String, Any?>? = null
    private var scheduled = false
    private var handle = 0L
    private var native: RtcNative? = null
    private var ptsOrigin: Long? = null
    private var blockedSince = 0L
    @Volatile var connected = false
        private set
    @Volatile var sentFrames = 0L
        private set
    @Volatile private var displayed = false

    fun start() {
        execute {
            native = RtcNative { kind, value -> execute {
                when (kind) {
                    "offer" -> offer(value)
                    "connected" -> {
                        Log.i("WheelPlayRtc", "${config.codec} direct stream connected profile=${config.profile}")
                        connected = true; source.requestKeyframe(force = true)
                    }
                    "keyframe" -> source.requestKeyframe()
                    "failed" -> fail()
                }
            } }
            handle = native!!.create(config.fmtp, config.codec == VideoCodec.H265)
            native!!.begin(handle)
        }
        try { worker.schedule({ if (!closed.get() && !displayed) fail() }, 10, TimeUnit.SECONDS) }
        catch (_: RejectedExecutionException) { }
    }

    fun answer(sdp: String) = execute { native?.answer(handle, sdp) }
    fun displayed() {
        if (connected && sentFrames > 0) { displayed = true; source.displayed(this) }
    }

    fun frame(frame: CompressedVideoFrame, pts: Long) {
        if (closed.get() || !connected) return
        val idr = frame.isRandomAccess(config.codec)
        synchronized(lock) {
            if (closed.get()) return
            if (!queue.offer(frame, pts, idr, System.nanoTime(), if (idr) config.parameters.size else 0)) {
                if (queue.recoveryExpired(System.nanoTime())) execute { fail() }
                else if (queue.needsKeyframe) source.requestKeyframe()
                return
            }
            if (!scheduled) { scheduled = true; execute(::drain) }
        }
    }

    private fun drain() {
        // Bound each batch so answer/close/failure tasks are never starved by a producer.
        repeat(6) {
            val next = synchronized(lock) {
                if (closed.get()) { scheduled = false; return }
                queue.poll(System.nanoTime()) ?: run {
                    scheduled = false
                    if (queue.needsKeyframe) source.requestKeyframe()
                    return
                }
            }
            performance.timing("queue", System.nanoTime() - next.queuedAtNs)
            if (ptsOrigin == null) ptsOrigin = next.pts
            val timestamp = (next.pts - ptsOrigin!!) * 90 / 1000
            val started = System.nanoTime()
            val accepted = try {
                val frame = next.data
                if (frame is NativeVideoFrame) native?.sendNative(handle, frame.handle, if (next.keyframe) config.parameters else null, timestamp) == true
                else {
                    val bytes = frame.toByteArray()
                    native?.send(handle, bytes, if (next.keyframe) config.parameters else null, timestamp) == true
                }
            } finally { next.data.close() }
            performance.timing("send", System.nanoTime() - started)
            if (accepted) { sentFrames++; performance.count("accepted"); blockedSince = 0L }
            else {
                performance.count("sendFailures")
                if (blockedSince == 0L) {
                    blockedSince = System.nanoTime()
                    worker.schedule({
                        if (!closed.get() && blockedSince != 0L && System.nanoTime() - blockedSince >= 2_000_000_000L) fail()
                    }, 2, TimeUnit.SECONDS)
                }
                synchronized(lock) { queue.invalidate(System.nanoTime()) }
                source.requestKeyframe()
            }
        }
        execute(::drain)
    }

    fun feedback(report: RtcReceiverHealth.Report) = execute {
        receiverReport = report
        when (health.update(report, sentFrames, System.nanoTime() / 1_000_000)) {
            RtcReceiverHealth.Action.KEYFRAME -> source.requestKeyframe()
            RtcReceiverHealth.Action.FALLBACK -> fail()
            RtcReceiverHealth.Action.NONE -> Unit
        }
        congested = health.congested
        // Feedback is sampled about once per second; JNI remains on the media worker.
        if (handle != 0L) native?.srtpStats(handle)?.let { s ->
            srtpStats = mapOf("backend" to "MbedTLS", "profile" to "AES128_CM_SHA1_80",
                "protectedPackets" to s[0], "retransmissions" to s[1],
                "protectNs" to s[2], "sendNs" to s[3], "plaintextBytes" to s[4], "failures" to s[5])
        }
    }

    fun snapshot(): Map<String, Any?> = synchronized(lock) {
        mapOf("sender" to performance.snapshot(), "srtp" to srtpStats, "queueFrames" to queue.size, "queueBytes" to queue.bytes,
            "expiredFrames" to queue.expired, "overflowFrames" to queue.overflow, "keyframeSkips" to queue.skipped,
            "congested" to congested, "receiver" to receiverReport?.let {
                mapOf("packets" to it.packets, "lost" to it.lost, "decoded" to it.decoded, "nack" to it.nack, "rttMs" to it.rttMs)
            })
    }

    private fun execute(task: () -> Unit) {
        if (closed.get()) return
        try { worker.execute {
            if (!closed.get()) try { task() }
            catch (error: Exception) { Log.w("WheelPlayRtc", "Direct stream failed; using JPEG", error); fail() }
            catch (error: LinkageError) { Log.w("WheelPlayRtc", "Native transport unavailable; using JPEG", error); fail() }
        } } catch (_: RejectedExecutionException) { }
    }

    private fun fail() { if (!closed.get()) failure() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        synchronized(lock) { queue.invalidate() }
        // Never destroy native state on its callback thread or while a send is in progress.
        worker.execute { if (handle != 0L) { native?.destroy(handle); handle = 0L }; native = null }
        worker.shutdown()
    }
}
