package moe.feng.wheelplay.plugin

import android.os.ParcelFileDescriptor
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Never blocks a vendor/Binder/capture callback. Overflow fails the session instead of accumulating latency. */
class PluginAudioWriter(descriptor: ParcelFileDescriptor, private val fail: (String) -> Unit,
    private val allowed: (PluginAudioWire.Message) -> Boolean = { true }) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val queue = ArrayBlockingQueue<PluginAudioWire.Message>(32)
    private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))
    private val worker = Thread({
        try {
            val wire = PluginAudioWire.Writer(output)
            while (!closed.get()) queue.poll(200, TimeUnit.MILLISECONDS)?.let { if (allowed(it)) wire.write(it) }
        } catch (error: Exception) { failure("Audio transport interrupted: ${error.message}") }
    }, "plugin-audio-tx").apply { isDaemon = true; start() }
    fun offer(message: PluginAudioWire.Message) {
        if (closed.get() || failed.get()) return
        if (!queue.offer(message)) failure("Audio transport queue overflow")
    }
    fun discardMicrophone() { queue.removeIf { it is PluginAudioWire.MicrophoneData } }
    private fun failure(message: String) {
        if (!closed.get() && failed.compareAndSet(false, true)) fail(message)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { output.close() } finally { worker.interrupt(); queue.clear() }
        }
    }
    fun awaitStopped() {
        if (Thread.currentThread() !== worker) { worker.join(2000); check(!worker.isAlive) { "Audio writer did not stop" } }
    }
}
