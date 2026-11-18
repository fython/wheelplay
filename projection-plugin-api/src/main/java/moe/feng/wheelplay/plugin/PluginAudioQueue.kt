package moe.feng.wheelplay.plugin

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in for producers whose Data records are independent PCM packets (never encoded audio).
 * Control records keep their order, including Start/Stop for older epochs still in the queue.
 */
internal class PluginAudioQueue(capacity: Int, private val dropOldPcm: Boolean) {
    private val queue = ArrayBlockingQueue<PluginAudioWire.Message>(capacity)
    @Volatile var dropped = 0L
        private set
    val size get() = queue.size

    @Synchronized fun offer(message: PluginAudioWire.Message): Boolean {
        if (queue.offer(message)) return true
        if (!dropOldPcm) return false
        // Producers are serialized; the consumer can only free space while we search.
        val stale = queue.firstOrNull { it is PluginAudioWire.Data }
        if (stale != null && queue.remove(stale)) dropped++
        if (queue.offer(message)) return true
        // A queue entirely occupied by control records must not lose a boundary.
        if (message is PluginAudioWire.Data) { dropped++; return true }
        return false
    }
    fun poll(timeout: Long, unit: TimeUnit) = queue.poll(timeout, unit)
    fun discardMicrophone() { queue.removeIf { it is PluginAudioWire.MicrophoneData } }
    fun clear() = queue.clear()
}
