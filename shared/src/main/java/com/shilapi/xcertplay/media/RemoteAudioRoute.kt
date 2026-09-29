package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.io.Closeable

/** Optional remote endpoint. All PCM is signed 16-bit little-endian. */
interface RemoteAudioRoute : Closeable {
    /** True transfers playback ownership to the endpoint; false keeps local playback. */
    fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int): Boolean
    fun audioStopped(type: Int)
    /** A source must provide PCM at the negotiated sample rate and channel count. */
    fun microphone(type: Int, config: MicrophoneConfig): PcmInput?
    fun microphoneStopped(type: Int)
    fun onMicrophoneRouteChanged(listener: () -> Unit)
}

interface PcmInput : Closeable {
    /** Blocks for a bounded interval; 0 means no samples, -1 means closed. */
    fun read(buffer: ByteArray): Int
}
