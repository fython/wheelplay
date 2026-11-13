package com.shilapi.xcertplay.projection.plugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.media.PcmInput
import com.shilapi.xcertplay.media.RemoteAudioRoute
import moe.feng.wheelplay.AirPlayPersistence
import moe.feng.wheelplay.CarPlayMicrophoneSource
import moe.feng.wheelplay.plugin.PluginAudioWire
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Demand-driven capture only; sends PCM to the plugin, never CarPlay RTP or microphone logs. */
internal class PluginMicrophone(
    private val context: Context,
    private val request: PluginAudioWire.Microphone,
    private val route: RemoteAudioRoute,
    private val active: () -> Boolean,
    private val send: (PluginAudioWire.MicrophoneData) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val revision = AtomicLong()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var input: PcmInput? = null
    private val worker = Thread({ capture() }, "plugin-microphone").apply { isDaemon = true; start() }
    private fun source(): CarPlayMicrophoneSource = AirPlayPersistence.loadMicrophoneSource(context)
    private fun capture() {
        var previous: CarPlayMicrophoneSource? = null
        var browser = false
        var seenRevision = -1L
        var retryAt = 0L
        val bytes = ByteArray(request.format.rate / 50 * request.format.frameBytes)
        try {
            while (!closed.get()) {
                val requested = source()
                val currentRevision = revision.get()
                val wanted = active() && route.microphoneEnabled && requested != CarPlayMicrophoneSource.DISABLED
                if (!wanted) { release(); previous = null; Thread.sleep(100); continue }
                if (requested != previous || currentRevision != seenRevision || (browser && input == null)) {
                    release(); previous = requested; seenRevision = currentRevision; retryAt = 0L
                }
                if (input == null && recorder == null) {
                    if (System.nanoTime() < retryAt) { Thread.sleep(100); continue }
                    synchronized(this) {
                    if (closed.get()) break
                    browser = false
                    if (requested == CarPlayMicrophoneSource.BROWSER) {
                        input = route.microphone(MIC_STREAM, MicrophoneConfig(
                            if (request.call) "telephony" else "speechrecognition", request.format.rate,
                            request.format.channels, 0, 20, InetAddress.getLoopbackAddress(), 0, ByteArray(0)))
                        browser = input != null
                    }
                    if (input == null) recorder = createRecorder()
                    }
                    if (input == null && recorder == null) { retryAt = System.nanoTime() + 1_000_000_000L; continue }
                }
                val count = runCatching { input?.read(bytes) ?: recorder?.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING) ?: -1 }.getOrDefault(-1)
                if (count < 0) { release(); retryAt = System.nanoTime() + 200_000_000L; continue }
                if (count == 0) continue
                require(count % request.format.frameBytes == 0)
                // Permission/policy may change during a blocking read; never forward the stale capture.
                if (!closed.get() && active() && route.microphoneEnabled && source() == requested && revision.get() == currentRevision &&
                    (browser || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) {
                    send(PluginAudioWire.MicrophoneData(request.id, bytes.copyOf(count)))
                }
                // Browser readiness changes are handled by refreshRoute(), including local fallback.
            }
        } catch (_: InterruptedException) {
            // Session/capture teardown.
        } catch (_: Exception) {
            // Permission revocation or unavailable recording must not terminate video/playback.
        } finally { release() }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val mask = if (request.format.channels == 1) android.media.AudioFormat.CHANNEL_IN_MONO else android.media.AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(request.format.rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        var next: AudioRecord? = null
        return try {
            next = AudioRecord.Builder().setAudioSource(if (request.call) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(request.format.rate).setChannelMask(mask)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, request.format.rate * request.format.frameBytes / 5)).build()
            check(next.state == AudioRecord.STATE_INITIALIZED); next.startRecording()
            check(next.recordingState == AudioRecord.RECORDSTATE_RECORDING); next
        } catch (_: Exception) { next?.release(); null }
    }
    /** Interrupt the current source so its worker re-evaluates readiness and user policy. */
    fun refreshRoute() { revision.incrementAndGet(); release() }
    fun allows(message: PluginAudioWire.MicrophoneData): Boolean = message.id == request.id &&
        !closed.get() && active() && route.microphoneEnabled && source() != CarPlayMicrophoneSource.DISABLED &&
        (input != null || recorder != null)
    @Synchronized private fun release() {
        input?.close(); input = null
        val current = recorder; recorder = null
        runCatching { current?.stop() }; runCatching { current?.release() }
        route.microphoneStopped(MIC_STREAM)
    }
    override fun close() { closed.set(true); release(); worker.interrupt() }
    fun awaitStopped() { worker.join(2000); check(!worker.isAlive) { "Plugin microphone did not stop" } }
    private companion object { const val MIC_STREAM = 30 }
}
