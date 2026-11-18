package moe.feng.wheelplay.projection.carlife

import android.content.Context
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.carlife.CarLifeSession
import moe.feng.wheelplay.carlife.CarLifeMicrophone

/** Direct in-process PCM delivery. Each stream owns a bounded, stale-PCM-dropping queue. */
internal class CarLifeAudio(private val context: Context, private val active: () -> Boolean,
    private val fail: (String) -> Unit,
    routeFactory: () -> RemoteAudioRoute = WebSession.audio::createRoute,
    private val playbackFactory: (PlaybackRequest, RemoteAudioRoute, () -> Boolean, (String) -> Unit) -> ProjectionPlaybackWorker =
        { request, route, active, fail -> ProjectionAudioPlayback(context, request, route, active, fail) },
    private val captureFactory: (RemoteAudioRoute, () -> Boolean, (ByteArray) -> Unit) -> ProjectionCaptureWorker =
        { route, active, send -> ProjectionMicrophone(context,
            MicrophoneRequest(PcmFormat(PcmFormat.PCM16, 16000, 1)), route, active, send) },
    private val captureAllowed: () -> Boolean = active,
) : CarLifeSession.AudioListener, CarLifeMicrophone.Listener, AutoCloseable {
    private val route = routeFactory()
    private data class Output(val format: PcmFormat, val worker: ProjectionPlaybackWorker)
    private val streams = mutableMapOf<Int, Output>()
    private val retiring = mutableSetOf<ProjectionPlaybackWorker>()
    @Volatile private var closed = false
    @Volatile private var capture: CarLifeMicrophone.Capture? = null
    @Volatile private var microphone: ProjectionCaptureWorker? = null
    init { route.onMicrophoneRouteChanged { microphone?.refreshRoute() } }
    @Synchronized override fun started(port: Int, sampleRate: Int, channels: Int) {
        if (closed) return
        stopped(port)
        val type = when (port) { 9240 -> 16; 9241 -> 6; else -> error("Unknown CarLife audio port") }
        val format = PcmFormat(PcmFormat.PCM16, sampleRate, channels)
        streams[port] = Output(format, playbackFactory(PlaybackRequest(type, format), route, { !closed && active() }, fail))
    }
    @Synchronized override fun pcm(port: Int, bytes: ByteArray) {
        if (closed) return
        val stream = checkNotNull(streams[port]) { "CarLife PCM without format" }
        require(bytes.isNotEmpty() && bytes.size <= 256 * 1024 && bytes.size % stream.format.frameBytes == 0)
        for (offset in bytes.indices step PcmFormat.MAX_PACKET)
            stream.worker.offer(bytes.copyOfRange(offset, minOf(offset + PcmFormat.MAX_PACKET, bytes.size)))
    }
    @Synchronized override fun stopped(port: Int) {
        streams.remove(port)?.worker?.let {
            retiring += it
            it.close(); it.awaitStopped()
            retiring -= it
        }
    }
    @Synchronized override fun started(next: CarLifeMicrophone.Capture) {
        if (closed || capture === next) return
        capture?.let { stopped(it) }
        capture = next
        microphone = captureFactory(route,
            { !closed && capture === next && active() && captureAllowed() }) { bytes ->
                if (!closed && capture === next) {
                    require(bytes.isNotEmpty() && bytes.size <= PcmFormat.MAX_PACKET && bytes.size % 2 == 0)
                    for (offset in bytes.indices step 640)
                        next.pcm(bytes.copyOfRange(offset, minOf(offset + 640, bytes.size)))
                }
            }
    }
    @Synchronized override fun stopped(old: CarLifeMicrophone.Capture) {
        if (capture !== old) return
        capture = null
        microphone?.let { it.close(); it.awaitStopped() }; microphone = null
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val errors = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) { runCatching(action).onFailure { errors += it } }
        capture = null
        microphone?.let { worker -> cleanup { worker.close(); worker.awaitStopped(); microphone = null } }
        streams.keys.toList().forEach { cleanup { stopped(it) } }
        retiring.toList().forEach { worker -> cleanup { worker.close(); worker.awaitStopped(); retiring -= worker } }
        cleanup { route.close() }
        errors.firstOrNull()?.let { first -> errors.drop(1).forEach(first::addSuppressed); throw first }
    }
}
