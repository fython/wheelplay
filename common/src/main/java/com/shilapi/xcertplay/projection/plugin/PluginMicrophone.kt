package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import com.shilapi.xcertplay.media.*
import moe.feng.wheelplay.plugin.PluginAudioWire

internal class PluginMicrophone(context: Context, private val request: PluginAudioWire.Microphone,
    route: RemoteAudioRoute, active: () -> Boolean, send: (PluginAudioWire.MicrophoneData) -> Unit) : AutoCloseable {
    private val capture = ProjectionMicrophone(context,
        MicrophoneRequest(PcmFormat(request.format.codec, request.format.rate, request.format.channels), request.call),
        route, active) { send(PluginAudioWire.MicrophoneData(request.id, it)) }
    fun refreshRoute() = capture.refreshRoute()
    fun allows(message: PluginAudioWire.MicrophoneData) = message.id == request.id && capture.allows()
    override fun close() = capture.close()
    fun awaitStopped() = capture.awaitStopped()
}
