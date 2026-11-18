package com.shilapi.xcertplay.media

internal data class PcmFormat(val codec: Int, val rate: Int, val channels: Int) {
    init { require(codec in PCM16..AAC_ADTS && rate in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000) && channels in 1..2) }
    val frameBytes get() = channels * 2
    fun validPcm(bytes: ByteArray) = bytes.isNotEmpty() && bytes.size % frameBytes == 0
    companion object { const val PCM16 = 1; const val AAC_ADTS = 2; const val MAX_PACKET = 64 * 1024 }
}
internal data class PlaybackRequest(val stream: Int, val format: PcmFormat)
internal data class MicrophoneRequest(val format: PcmFormat, val call: Boolean = false)

internal interface ProjectionPlaybackWorker : AutoCloseable {
    fun offer(bytes: ByteArray)
    fun awaitStopped()
}
internal interface ProjectionCaptureWorker : AutoCloseable {
    fun refreshRoute()
    fun awaitStopped()
}

/** Protocol readers may already be interrupted when their finally blocks stop media workers. */
internal fun awaitProjectionWorker(worker: Thread, timeoutMs: Long) {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var interrupted = false
    try {
        while (worker.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try { worker.join(maxOf(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))) }
            catch (_: InterruptedException) { interrupted = true }
        }
        check(!worker.isAlive) { "Projection media worker did not stop: ${worker.name}" }
    } finally { if (interrupted) Thread.currentThread().interrupt() }
}
