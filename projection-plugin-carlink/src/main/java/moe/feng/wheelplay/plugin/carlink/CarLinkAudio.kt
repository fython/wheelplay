package moe.feng.wheelplay.plugin.carlink

import android.content.Context
import android.os.Handler
import android.os.ParcelFileDescriptor
import com.ucar.vehiclesdk.ICarAudioRecorderListener
import com.ucar.vehiclesdk.UCarAdapter
import com.ucar.vehiclesdk.UCarCommon
import com.ucar.vehiclesdk.audio.UCarAudioManager
import com.ucar.vehiclesdk.player.AudioPlayerMgr
import moe.feng.wheelplay.plugin.PluginAudioWire
import moe.feng.wheelplay.plugin.PluginAudioWriter
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Adapter for sdk-lock.json only. Replaces the SDK player sink, not its audio connection. */
internal class CarLinkAudio(playback: ParcelFileDescriptor, capture: ParcelFileDescriptor,
    private val fail: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val writer = PluginAudioWriter(playback, ::failure)
    private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(capture))
    private val lock = Any()
    private val state = CarLinkAudioState(writer::offer)
    private var sdk: UCarAdapter? = null
    private var players: ForwardingPlayers? = null
    private val recorder = object : ICarAudioRecorderListener {
        override fun onStartRecorder(format: UCarCommon.AudioFormat, call: Boolean) = guarded {
            val next = format(format, microphone = true)
            require(next.codec == PluginAudioWire.PCM16) { "Microphone must be PCM16" }
            state.microphone(next, call)
        }
        override fun onStopRecorder() = guarded { state.stopMicrophone() }
    }
    private val reader = Thread({
        try {
            val wire = PluginAudioWire.Reader(input)
            while (!closed.get()) {
                val message = wire.read()
                require(message is PluginAudioWire.MicrophoneData) { "Unexpected host audio command" }
                val target = synchronized(lock) {
                    sdk?.takeIf { !closed.get() && state.acceptMicrophone(message) }
                }
                if (target != null && !closed.get()) {
                    val samples = ShortArray(message.bytes.size / 2)
                    ByteBuffer.wrap(message.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    // The vendor call can wait for its channel. Never hold the teardown lock here.
                    check(target.sendMicRecordData(samples.size, samples, 0L)) { "SDK microphone channel unavailable" }
                }
            }
        } catch (error: Exception) { failure("CarLink microphone transport failed: ${error.message}") }
    }, "carlink-microphone-rx").apply { isDaemon = true; start() }
    private fun failure(message: String) {
        if (!closed.get() && failed.compareAndSet(false, true)) fail(message)
    }
    private fun guarded(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        try { synchronized(lock) { if (!closed.get()) action() } }
        catch (error: Exception) { failure("CarLink audio failed: ${error.message}") }
    }
    fun install(context: Context, adapter: UCarAdapter) = synchronized(lock) {
        check(!closed.get() && sdk == null)
        val manager = field(adapter, "mUCarAudioManager")!!
        val original = field(manager, "mAudioPlayerMgr")!!
        // Installation precedes advertise; no player or microphone may already own resources.
        check((field(original, "mPlayers") as Map<*, *>).isEmpty()) { "SDK audio already active" }
        val listener = field(original, "mUCarAudioListener") as UCarAudioManager.IUCarAudioListener
        val replacement = ForwardingPlayers(context, listener)
        manager.javaClass.getDeclaredField("mAudioPlayerMgr").apply { isAccessible = true }.set(manager, replacement)
        (field(original, "mHandler") as Handler).looper.quitSafely()
        sdk = adapter; players = replacement
        adapter.registerAudioRecorderListener(recorder)
    }
    private inner class ForwardingPlayers(context: Context, listener: UCarAudioManager.IUCarAudioListener) : AudioPlayerMgr(context, listener) {
        override fun managePlayer(type: UCarCommon.AudioType, format: UCarCommon.AudioFormat?, action: UCarAudioManager.PlayerState, attributes: UCarCommon.AudioAttributes?) = guarded {
            when (action) {
                UCarAudioManager.PlayerState.START_PLAYER -> state.start(type.value, format(checkNotNull(format)))
                UCarAudioManager.PlayerState.STOP_PLAYER -> state.stop(type.value)
                UCarAudioManager.PlayerState.PAUSE_PLAYER -> state.pause(type.value)
                UCarAudioManager.PlayerState.RESUME_PLAYER -> state.resume(type.value)
            }
        }
        override fun write(type: UCarCommon.AudioType, data: ByteBuffer) = guarded {
            // The vendor may recycle the buffer immediately after this callback.
            state.data(type.value, data)
        }
        override fun stopAndClearAudioPlayers() = guarded { state.stopAll() }
    }
    private fun format(value: UCarCommon.AudioFormat, microphone: Boolean = false): PluginAudioWire.Format {
        val codec = when (value.mimeType) {
            "audio/pcm" -> { require(value.encodingFormat == android.media.AudioFormat.ENCODING_PCM_16BIT); PluginAudioWire.PCM16 }
            "audio/mp4a-latm" -> PluginAudioWire.AAC_ADTS
            else -> error("Unsupported SDK audio codec: ${value.mimeType}")
        }
        val channels = CarLinkAudioFormats.channels(value.channelConfig, microphone)
        return PluginAudioWire.Format(codec, value.sampleRate, channels)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) { runCatching(action).onFailure { failure = failure ?: it } }
        synchronized(lock) {
            state.clear()
            cleanup { sdk?.unregisterAudioRecorderListener(recorder) }
            cleanup { players?.let { (field(it, "mHandler") as Handler).looper.quitSafely() } }
        }
        cleanup { writer.close() }; cleanup { input.close() }; reader.interrupt()
        failure?.let { throw it }
    }
    fun awaitStopped() { reader.join(2000); check(!reader.isAlive) { "CarLink audio reader did not stop" }; writer.awaitStopped() }
    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Pinned SDK audio field missing: $name")
    }
}

/** Pure state machine: each resume has a fresh epoch; retired capture packets are discarded. */
internal class CarLinkAudioState(private val send: (PluginAudioWire.Message) -> Unit) {
    private data class Stream(val format: PluginAudioWire.Format, var id: Long?)
    private val streams = mutableMapOf<Int, Stream>()
    private var nextId = 0L
    private var mic: PluginAudioWire.Microphone? = null
    fun start(type: Int, format: PluginAudioWire.Format) {
        require(type in 0..31); stop(type); require(streams.size < 16)
        val id = ++nextId; streams[type] = Stream(format, id); send(PluginAudioWire.Start(id, type, format))
    }
    fun pause(type: Int) { streams[type]?.let { stream -> stream.id?.let { send(PluginAudioWire.Stop(it)) }; stream.id = null } }
    fun resume(type: Int) { streams[type]?.let { if (it.id == null) { it.id = ++nextId; send(PluginAudioWire.Start(it.id!!, type, it.format)) } } }
    fun stop(type: Int) { pause(type); streams.remove(type) }
    fun stopAll() { streams.keys.toList().forEach(::stop); stopMicrophone() }
    fun data(type: Int, data: ByteBuffer) {
        val stream = streams[type] ?: return
        val id = stream.id ?: return
        val copy = data.duplicate()
        require(copy.remaining() in 1..PluginAudioWire.MAX_PACKET)
        val bytes = ByteArray(copy.remaining()).also { copy.get(it) }
        if (stream.format.codec == PluginAudioWire.PCM16) require(stream.format.validPcm(bytes))
        send(PluginAudioWire.Data(id, bytes))
    }
    fun microphone(format: PluginAudioWire.Format, call: Boolean) {
        require(format.codec == PluginAudioWire.PCM16); stopMicrophone()
        mic = PluginAudioWire.Microphone(++nextId, format, call).also(send)
    }
    fun stopMicrophone() { mic?.let { send(PluginAudioWire.MicrophoneStop(it.id)) }; mic = null }
    fun acceptMicrophone(value: PluginAudioWire.MicrophoneData): Boolean {
        val current = mic ?: return false
        if (value.id != current.id) return false
        require(current.format.validPcm(value.bytes))
        require(value.bytes.size <= current.format.rate * current.format.frameBytes / 10)
        return true
    }
    fun clear() { streams.clear(); mic = null }
}

internal object CarLinkAudioFormats {
    fun channels(mask: Int, microphone: Boolean): Int = when (mask) {
        if (microphone) 16 else 4 -> 1 // CHANNEL_IN_MONO differs from CHANNEL_OUT_MONO.
        12 -> 2
        else -> error("Unsupported SDK channel mask: $mask")
    }
}
