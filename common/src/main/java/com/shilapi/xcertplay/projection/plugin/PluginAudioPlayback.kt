package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import com.shilapi.xcertplay.media.*
import moe.feng.wheelplay.plugin.PluginAudioWire

internal class PluginAudioPlayback(context: Context, start: PluginAudioWire.Start,
    route: RemoteAudioRoute, active: () -> Boolean, fail: (String) -> Unit) : AutoCloseable {
    private val playback = ProjectionAudioPlayback(context,
        PlaybackRequest(start.stream, PcmFormat(start.format.codec, start.format.rate, start.format.channels)), route, active, fail)
    fun offer(bytes: ByteArray) = playback.offer(bytes)
    override fun close() = playback.close()
    fun awaitStopped() = playback.awaitStopped()
}
