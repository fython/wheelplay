package com.shilapi.xcertplay.projection.plugin

import com.shilapi.xcertplay.media.ProjectionAdts
import com.shilapi.xcertplay.media.offerPlaybackAudio
import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class PluginAudioQueueTest {
    @Test fun pcmBurstRetainsRecentAudioWithinFixedCapacity() {
        val queue = ArrayBlockingQueue<ByteArray>(16)
        repeat(100) { assertTrue(offerPlaybackAudio(queue, byteArrayOf(it.toByte()), true)) }
        assertEquals(16, queue.size)
        assertEquals((84..99).toList(), generateSequence { queue.poll() }.map { it[0].toInt() }.toList())
    }
    @Test fun encodedOverflowIsReportedWithoutDiscardingDecoderInput() {
        val queue = ArrayBlockingQueue<ByteArray>(1)
        val first = byteArrayOf(1)
        assertTrue(offerPlaybackAudio(queue, first, false))
        assertFalse(offerPlaybackAudio(queue, byteArrayOf(2), false))
        assertSame(first, queue.poll())
    }
    @Test fun normalPcmCadencePreservesAllSamplesInOrder() {
        val queue = ArrayBlockingQueue<ByteArray>(2)
        repeat(100) {
            val bytes = byteArrayOf(it.toByte())
            assertTrue(offerPlaybackAudio(queue, bytes, true))
            assertSame(bytes, queue.poll())
        }
        assertTrue(queue.isEmpty())
    }
}
