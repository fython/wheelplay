package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.plugin.IProjectionPlugin
import moe.feng.wheelplay.plugin.PluginAudioWire
import moe.feng.wheelplay.plugin.PluginAudioWriter
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Owns both PCM/encoded downlink and PCM microphone uplink for exactly one plugin session. */
internal class RemotePluginAudio(
    private val context: Context,
    private val microphoneSupported: Boolean,
    private val active: () -> Boolean,
    private val captureAllowed: () -> Boolean,
    private val fail: (String) -> Unit,
    routeFactory: () -> com.shilapi.xcertplay.media.RemoteAudioRoute = WebSession.audio::createRoute,
) : AutoCloseable {
    private val playback = ParcelFileDescriptor.createReliablePipe()
    private val capture = ParcelFileDescriptor.createReliablePipe()
    private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(playback[0]))
    private val route = routeFactory()
    private val closed = AtomicBoolean()
    private val streams = mutableMapOf<Long, PluginAudioPlayback>()
    private val streamTypes = mutableMapOf<Long, Int>()
    @Volatile private var cleanupFailure: Throwable? = null
    private var lastPlaybackId = 0L
    private var lastMicrophoneId = 0L
    @Volatile private var microphone: PluginMicrophone? = null
    private val output = PluginAudioWriter(capture[1], fail) { message ->
        message is PluginAudioWire.MicrophoneData && microphone?.allows(message) == true
    }
    private val reader = Thread({ read() }, "plugin-audio-rx").apply { isDaemon = true; start() }
    init { route.onMicrophoneRouteChanged { output.discardMicrophone(); microphone?.refreshRoute() } }

    fun sendTo(peer: IProjectionPlugin, token: String) {
        try { peer.attachAudio(token, playback[1], capture[0]) }
        finally { playback[1].close(); capture[0].close() }
    }
    private fun read() {
        try {
            val wire = PluginAudioWire.Reader(input)
            while (!closed.get()) {
                when (val message = wire.read()) {
                    is PluginAudioWire.Start -> {
                        require(message.id > lastPlaybackId && streams.size < 16 && message.stream !in streamTypes.values)
                        lastPlaybackId = message.id
                        streams[message.id] = PluginAudioPlayback(context, message, route, { !closed.get() && active() }, fail)
                        streamTypes[message.id] = message.stream
                    }
                    is PluginAudioWire.Data -> checkNotNull(streams[message.id]) { "Audio data without a format" }.offer(message.bytes)
                    is PluginAudioWire.Stop -> {
                        val stream = checkNotNull(streams.remove(message.id)) { "Unknown audio stream" }
                        stream.close(); stream.awaitStopped(); streamTypes.remove(message.id)
                    }
                    is PluginAudioWire.Microphone -> {
                        require(microphoneSupported && message.id > lastMicrophoneId && microphone == null)
                        lastMicrophoneId = message.id
                        microphone = PluginMicrophone(context, message, route, { !closed.get() && captureAllowed() }, output::offer)
                    }
                    is PluginAudioWire.MicrophoneStop -> {
                        require(message.id == lastMicrophoneId && microphone != null)
                        microphone?.close(); microphone?.awaitStopped(); microphone = null
                    }
                    is PluginAudioWire.MicrophoneData -> error("Unexpected microphone data from plugin")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) fail("Plugin audio channel failed: ${error.message}")
        } finally {
            fun cleanup(action: () -> Unit) { runCatching(action).onFailure { cleanupFailure = it } }
            cleanup { microphone?.close() }; cleanup { microphone?.awaitStopped() }; microphone = null
            streams.values.forEach { cleanup { it.close() } }
            streams.values.forEach { cleanup { it.awaitStopped() } }
            streams.clear(); streamTypes.clear(); cleanup { route.close() }
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { microphone?.close() }.onFailure { cleanupFailure = it }
            runCatching { output.close() }.onFailure { cleanupFailure = it }
            runCatching { input.close() }
            runCatching { playback[1].close() }; runCatching { capture[0].close() }
            // Closing the pipe wakes a blocked read. Do not interrupt this thread:
            // it also joins playback/capture workers before acknowledging cleanup.
        }
    }
    fun awaitStopped() {
        reader.join(5000); check(!reader.isAlive) { "Plugin audio reader did not stop" }
        output.awaitStopped()
        cleanupFailure?.let { throw it }
    }
}
