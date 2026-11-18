package com.shilapi.xcertplay.media

import java.util.concurrent.ArrayBlockingQueue

internal object ProjectionAdts {
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

/** Live PCM packets are independently decodable: discard stale audio when playback falls behind.
 * Keep the queue bounded without blocking control/stop messages on the shared audio pipe.
 * Encoded packets retain the strict overflow behavior because dropping may break decoder state.
 * Called by the single audio-pipe reader; the playback worker only removes packets.
 */
internal fun offerPlaybackAudio(queue: ArrayBlockingQueue<ByteArray>, bytes: ByteArray, pcm: Boolean): Boolean {
    if (queue.offer(bytes)) return true
    if (!pcm) return false
    queue.poll()
    return queue.offer(bytes)
}
