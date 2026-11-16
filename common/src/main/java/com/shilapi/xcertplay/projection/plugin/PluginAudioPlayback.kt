package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.RemoteAudioRoute
import moe.feng.wheelplay.plugin.PluginAudioWire
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One decoder/track owner per stream. The SDK never also plays a negotiated stream. */
internal class PluginAudioPlayback(
    context: Context,
    private val start: PluginAudioWire.Start,
    private val route: RemoteAudioRoute,
    private val active: () -> Boolean,
    private val fail: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val queue = ArrayBlockingQueue<ByteArray>(16)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val music = start.stream == 16
    private val attributes = AudioAttributes.Builder()
        .setUsage(when (start.stream) {
            1, 2 -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            3 -> AudioAttributes.USAGE_ASSISTANT
            4 -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            5 -> AudioAttributes.USAGE_NOTIFICATION
            6 -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            else -> AudioAttributes.USAGE_MEDIA
        }).setContentType(if (music) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build()
    @Volatile private var focus = false
    @Volatile private var volume = 1f
    private var focusRequested = false
    private val focusRequest = AudioFocusRequest.Builder(if (music) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            focus = change == AudioManager.AUDIOFOCUS_GAIN || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            volume = if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) .2f else 1f
        }, Handler(Looper.getMainLooper())).build()
    private var track: AudioTrack? = null
    private var decoder: MediaCodec? = null
    private var outputRate = start.format.rate
    private var outputChannels = start.format.channels
    private var samples = 0L
    private val worker = Thread({ run() }, "plugin-audio-${start.stream}").apply { isDaemon = true; start() }

    fun offer(bytes: ByteArray) {
        if (closed.get()) return
        if (start.format.codec == PluginAudioWire.PCM16) require(start.format.validPcm(bytes))
        check(bytes.size in 1..PluginAudioWire.MAX_PACKET)
        if (!queue.offer(bytes)) fail("Plugin audio playback queue overflow")
    }
    private fun run() {
        try {
            if (start.format.codec == PluginAudioWire.AAC_ADTS) {
                val rates = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
                val index = rates.indexOf(start.format.rate); require(index >= 0)
                val config = (2 shl 11) or (index shl 7) or (start.format.channels shl 3)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, start.format.rate, start.format.channels).apply {
                    setInteger(MediaFormat.KEY_IS_ADTS, 1)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((config shr 8).toByte(), config.toByte())))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PluginAudioWire.MAX_PACKET)
                }
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(format, null, null, 0); it.start() }
            }
            while (!closed.get()) {
                val bytes = queue.poll(20, TimeUnit.MILLISECONDS)
                if (!active()) { pauseLocal(); continue }
                val codec = decoder
                if (codec == null) bytes?.let(::pcm)
                else {
                    if (bytes != null) {
                        // A callback may contain several ADTS access units; feed them individually.
                        for (frame in PluginAdts.frames(bytes, start.format.rate, start.format.channels)) {
                            var input = codec.dequeueInputBuffer(0)
                            val deadline = System.nanoTime() + 200_000_000L
                            while (input < 0 && !closed.get() && System.nanoTime() < deadline) {
                                drain(codec); input = codec.dequeueInputBuffer(5_000)
                            }
                            if (closed.get()) break
                            check(input >= 0) { "AAC decoder input stalled" }
                            val buffer = checkNotNull(codec.getInputBuffer(input)); buffer.clear()
                            require(buffer.remaining() >= frame.size); buffer.put(frame)
                            codec.queueInputBuffer(input, 0, frame.size, samples * 1_000_000 / start.format.rate, 0)
                            samples += 1024
                            drain(codec)
                        }
                    }
                    drain(codec)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) fail("Plugin audio playback failed: ${error.message}")
        } finally {
            pauseLocal(); runCatching { track?.release() }; track = null
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
            route.audioStopped(start.stream)
        }
    }
    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                PluginAudioWire.Format(PluginAudioWire.PCM16, rate, channels)
                require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_16BIT)
                if (rate != outputRate || channels != outputChannels) { pauseLocal(); track?.release(); track = null }
                outputRate = rate; outputChannels = channels
            } else if (index >= 0) {
                try {
                    if (info.size > 0) {
                        require(info.size <= PluginAudioWire.MAX_PACKET)
                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        pcm(ByteArray(info.size).also { buffer.get(it) })
                    }
                } finally { codec.releaseOutputBuffer(index, false) }
            } else return
        }
    }
    private fun pcm(bytes: ByteArray) {
        require(bytes.size % (outputChannels * 2) == 0)
        if (!active() || closed.get()) return
        val format = AudioFormat(AudioCodecKind.LPCM, outputRate, outputChannels, start.stream,
            if (music) "media" else if (start.stream in 1..2) "telephony" else "speechrecognition")
        if (route.output(start.stream, format, bytes, 0, bytes.size)) { pauseLocal(); return }
        if (!focusRequested) {
            focusRequested = true
            focus = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!focus) { track?.let { it.pause(); it.flush() }; return }
        val local = track ?: run {
            val mask = if (outputChannels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(outputRate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min * 2, outputRate * outputChannels * 2 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { check(it.state == AudioTrack.STATE_INITIALIZED); track = it }
        }
        local.setVolume(volume)
        if (local.playState != AudioTrack.PLAYSTATE_PLAYING) local.play()
        var offset = 0
        val deadline = System.nanoTime() + 300_000_000L
        while (offset < bytes.size && !closed.get() && active()) {
            // Switching to browser playback must not continue draining old local samples.
            if (route.playbackAvailable) { pauseLocal(); return }
            val written = local.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0) { "AudioTrack write failed ($written)" }
            if (written == 0) { if (System.nanoTime() >= deadline) { local.pause(); local.flush(); return }; Thread.sleep(2) }
            else offset += written
        }
    }
    private fun pauseLocal() {
        runCatching { track?.pause(); track?.flush() }
        if (focusRequested) { manager.abandonAudioFocusRequest(focusRequest); focusRequested = false; focus = false }
    }
    override fun close() { closed.set(true); worker.interrupt(); queue.clear() }
    fun awaitStopped() { worker.join(2000); check(!worker.isAlive) { "Audio playback worker did not stop" } }
}

internal object PluginAdts {
    fun frames(bytes: ByteArray, rate: Int, channels: Int): List<ByteArray> {
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        val result = mutableListOf<ByteArray>(); var offset = 0
        fun b(index: Int) = bytes[offset + index].toInt() and 255
        while (offset < bytes.size) {
            require(bytes.size - offset >= 7 && b(0) == 255 && b(1) and 0xf6 == 0xf0) { "Invalid AAC ADTS header" }
            val index = (b(2) shr 2) and 15
            require((b(2) shr 6) == 1 && rates.getOrNull(index) == rate && (((b(2) and 1) shl 2) or (b(3) shr 6)) == channels)
            require(b(6) and 3 == 0) { "Multiple AAC raw blocks are unsupported" }
            val length = ((b(3) and 3) shl 11) or (b(4) shl 3) or (b(5) shr 5)
            val header = if (b(1) and 1 == 1) 7 else 9
            require(length > header && offset + length <= bytes.size) { "Incomplete AAC access unit" }
            result += bytes.copyOfRange(offset, offset + length); offset += length
        }
        return result
    }
}
