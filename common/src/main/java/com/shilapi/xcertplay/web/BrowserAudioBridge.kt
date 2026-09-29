package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.media.PcmInput
import com.shilapi.xcertplay.media.RemoteAudioRoute
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The authenticated display owns both routes. Disconnect restores Android automatically. */
internal class BrowserAudioBridge {
    private val lock = Any()
    private var owner: Any? = null
    private var send: ((ByteArray, Long) -> Unit)? = null
    private var control: ((String) -> Unit)? = null
    private var secureOwner = false
    private var requestedPlayback = false
    private var requestedCapture = false
    private var playback = false
    private var capture = false
    private var sequence = 0L
    private var microphoneId = 0
    private var route: Route? = null
    val microphoneRequested get() = synchronized(lock) { requestedCapture && owner != null && secureOwner }

    fun attach(owner: Any, secure: Boolean, send: (ByteArray, Long) -> Unit, control: (String) -> Unit) = synchronized(lock) {
        check(this.owner == null)
        this.owner = owner; secureOwner = secure; this.send = send; this.control = control
        sendRoute()
    }

    fun configure(playback: Boolean, capture: Boolean) {
        val change = synchronized(lock) {
            requestedPlayback = playback; requestedCapture = capture
            sendRoute()
            this.playback = this.playback && playback
            val nextCapture = this.capture && capture
            val changed = this.capture != nextCapture
            this.capture = nextCapture
            if (changed) route?.closeInputs()
            if (changed) route?.changed else null
        }
        change?.invoke()
    }

    /** Browser readiness is only an acknowledgement; App preferences remain authoritative. */
    fun ready(owner: Any, playback: Boolean, capture: Boolean) {
        val change = synchronized(lock) {
            if (this.owner !== owner) return
            this.playback = requestedPlayback && playback
            val nextCapture = requestedCapture && secureOwner && capture
            val changed = this.capture != nextCapture
            this.capture = nextCapture
            if (changed) route?.closeInputs()
            if (changed) route?.changed else null
        }
        change?.invoke()
    }

    private fun sendRoute() {
        if (owner != null) control?.invoke(JSONObject().put("type", "audio-route")
            .put("playback", requestedPlayback).put("microphone", requestedCapture && secureOwner).toString())
    }

    fun detach(owner: Any) {
        val change = synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null; secureOwner = false; send = null; control = null; playback = false
            val changed = capture; capture = false; route?.closeInputs()
            if (changed) route?.changed else null
        }
        change?.invoke()
    }

    fun input(owner: Any, packet: ByteArray): Boolean = synchronized(lock) {
        if (this.owner !== owner || !capture) return false
        require(packet.size in 14..20_000 && packet.size % 2 == 0)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == MIC_MAGIC)
        val id = header.int
        val seq = header.int.toLong() and 0xffff_ffffL
        val input = route?.inputs?.values?.firstOrNull { it.id == id } ?: return false
        val bytes = packet.size - 12
        require(bytes % (input.config.channels * 2) == 0)
        require(bytes <= input.config.sampleRate * input.config.channels * 2 / 10)
        if (seq <= input.lastSequence) return false
        input.lastSequence = seq
        input.offer(packet.copyOfRange(12, packet.size))
        true
    }

    fun createRoute(): RemoteAudioRoute = synchronized(lock) {
        route?.close()
        Route().also { route = it }
    }

    private inner class Route : RemoteAudioRoute {
        val inputs = mutableMapOf<Int, Input>()
        var changed: (() -> Unit)? = null
        private var closed = false
        override fun onMicrophoneRouteChanged(listener: () -> Unit) = synchronized(lock) { changed = listener }
        override fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int): Boolean {
            val outgoing = synchronized(lock) {
                if (closed || route !== this || !playback || send == null) return false
                if (format.channels !in 1..2 || format.sampleRate !in 8_000..96_000 ||
                    length <= 0 || length % (format.channels * 2) != 0) return false
                val seq = ++sequence
                val packet = ByteBuffer.allocate(20 + length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(AUDIO_MAGIC).putInt(type).putInt(format.sampleRate)
                    .putShort(format.channels.toShort()).putShort(0).putInt(seq.toInt())
                    .put(pcm, offset, length).array()
                Triple(send!!, packet, seq)
            }
            outgoing.first(outgoing.second, outgoing.third)
            return true
        }
        override fun audioStopped(type: Int) = synchronized(lock) {
            if (!closed && route === this) control?.invoke(JSONObject().put("type", "audio-stop").put("stream", type).toString())
            Unit
        }
        override fun microphone(type: Int, config: MicrophoneConfig): PcmInput? = synchronized(lock) {
            if (closed || route !== this || !capture) return null
            inputs.remove(type)?.close()
            Input(++microphoneId, config).also {
                inputs[type] = it
                control?.invoke(JSONObject().put("type", "mic-config").put("id", it.id)
                    .put("rate", config.sampleRate).put("channels", config.channels).toString())
            }
        }
        override fun microphoneStopped(type: Int) = synchronized(lock) {
            inputs.remove(type)?.let { it.close(); control?.invoke(JSONObject().put("type", "mic-stop").put("id", it.id).toString()) }
            Unit
        }
        fun closeInputs() { inputs.values.forEach { it.close() }; inputs.clear() }
        override fun close() = synchronized(lock) {
            if (closed) return
            closed = true; closeInputs(); changed = null
            if (route === this) {
                route = null
                control?.invoke("""{"type":"audio-reset"}""")
            }
        }
    }

    private class Input(val id: Int, val config: MicrophoneConfig) : PcmInput {
        private val queue = ArrayBlockingQueue<ByteArray>(5)
        @Volatile private var closed = false
        var lastSequence = -1L
        private var current: ByteArray? = null
        private var offset = 0
        fun offer(pcm: ByteArray) { if (!closed && !queue.offer(pcm)) { queue.poll(); queue.offer(pcm) } }
        override fun read(buffer: ByteArray): Int {
            if (closed) return -1
            if (current == null) { current = queue.poll(100, TimeUnit.MILLISECONDS) ?: return 0; offset = 0 }
            val pcm = current!!
            val count = minOf(buffer.size, pcm.size - offset)
            pcm.copyInto(buffer, 0, offset, offset + count); offset += count
            if (offset == pcm.size) current = null
            return count
        }
        override fun close() { closed = true; queue.clear() }
    }

    companion object {
        const val AUDIO_MAGIC = 0x31415057 // WPA1
        const val MIC_MAGIC = 0x314d5057 // WPM1
    }
}
