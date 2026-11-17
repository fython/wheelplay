package moe.feng.wheelplay.plugin

import android.os.ParcelFileDescriptor
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded transport; PCM producers can opt into dropping stale samples under congestion. */
class PluginAudioWriter(descriptor: ParcelFileDescriptor, private val fail: (String) -> Unit,
    dropOldPcm: Boolean = false,
    private val allowed: (PluginAudioWire.Message) -> Boolean = { true }) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val queue = PluginAudioQueue(32, dropOldPcm)
    @Volatile private var writeStartedNs = 0L
    @Volatile private var lastWriteNs = System.nanoTime()
    @Volatile private var writes = 0L
    private val congested = AtomicBoolean()
    private var reportedDrops = 0L
    private var dropReportNs = 0L
    private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))
    private val worker = Thread({
        try {
            val wire = PluginAudioWire.Writer(output)
            while (!closed.get()) queue.poll(200, TimeUnit.MILLISECONDS)?.let { if (allowed(it)) {
                writeStartedNs = System.nanoTime()
                wire.write(it)
                val now = System.nanoTime()
                val elapsedMs = (now - writeStartedNs) / 1_000_000
                writes++
                lastWriteNs = now
                writeStartedNs = 0L
                if (elapsedMs > 100) moe.feng.wheelplay.plugin.PluginAudioDiagnostics.record("TX_SLOW ms=$elapsedMs queue=${queue.size} writes=$writes")
                if (queue.size < 8) congested.set(false)
            } }
        } catch (error: Exception) { failure("Audio transport interrupted: ${error.message}") }
    }, "plugin-audio-tx").apply { isDaemon = true; start() }
    @Synchronized fun offer(message: PluginAudioWire.Message) {
        if (closed.get() || failed.get()) return
        val accepted = queue.offer(message)
        if (queue.size >= 24 && congested.compareAndSet(false, true)) {
            val now = System.nanoTime()
            moe.feng.wheelplay.plugin.PluginAudioDiagnostics.record("TX_CONGESTION queue=${queue.size} writes=$writes writeMs=${if (writeStartedNs == 0L) 0 else (now - writeStartedNs) / 1_000_000} idleMs=${(now - lastWriteNs) / 1_000_000} incoming=${message.javaClass.simpleName}")
            PluginAudioDiagnostics.dump("tx-congestion")
        }
        val now = System.nanoTime()
        if (queue.dropped != reportedDrops && now - dropReportNs >= 1_000_000_000L) {
            PluginAudioDiagnostics.record("TX_PCM_DROPPED total=${queue.dropped} queue=${queue.size}")
            reportedDrops = queue.dropped
            dropReportNs = now
        }
        if (!accepted) failure("Audio transport queue overflow")
    }
    fun discardMicrophone() { queue.discardMicrophone() }
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
