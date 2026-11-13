package com.shilapi.xcertplay.projection.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginAvcStreamTest {
    private fun nal(vararg payload: Int) = byteArrayOf(0, 0, 0, 1) + payload.map { it.toByte() }.toByteArray()
    private val config = nal(0x67, 0x64, 0, 0x32, 0x80) + nal(0x68, 0x80)
    private val idr = nal(0x65, 0xb8) // first_mb=0, slice_type=2
    private val p = nal(0x41, 0xe0) // first_mb=0, slice_type=0
    private fun frame(pts: Long, idr: Boolean = false, size: Int = 2) = PluginAvcStream.Unit(ByteArray(size), pts, idr)

    @Test fun completePicturePublishesImmediatelyWithoutWaitingForAnotherTimestamp() {
        val parser = PluginAvcStream()
        assertNull(parser.accept(config, 0))
        assertNotNull(parser.configuration)
        val first = parser.accept(idr, 100)!!
        assertTrue(first.keyframe); assertArrayEquals(idr, first.bytes)
        assertEquals(101L, parser.accept(p, 101)!!.ptsUs)
        assertNull(parser.accept(config, 101))
    }
    @Test fun rejectsGapsInFramingReorderedPicturesAndNonMonotonicTime() {
        assertThrows(IllegalArgumentException::class.java) { PluginAvcStream().accept(byteArrayOf(1, 2, 3), 0) }
        val parser = PluginAvcStream(); parser.accept(config + idr, 100)
        assertThrows(IllegalArgumentException::class.java) { parser.accept(p, 99) }
        assertThrows(IllegalArgumentException::class.java) { parser.accept(p, 100) }
        assertThrows(IllegalArgumentException::class.java) { parser.accept(p + p, 101) }
        assertThrows(IllegalArgumentException::class.java) { parser.accept(nal(0x41, 0xa0), 101) } // B slice
        assertThrows(IllegalStateException::class.java) { parser.accept(nal(0x67, 0x42, 0, 0x32), 101) }
    }
    @Test fun newDecoderGetsWholeGopExactlyOnceThenLiveFrames() {
        val queue = PluginAvcQueue(); val first = Any(); val second = Any()
        queue.offer(frame(1, true)); queue.offer(frame(2))
        assertEquals(1L, queue.poll(first)!!.ptsUs)
        queue.offer(frame(3))
        assertEquals(2L, queue.poll(first)!!.ptsUs)
        assertEquals(3L, queue.poll(first)!!.ptsUs)
        assertNull(queue.poll(first))
        assertEquals(1L, queue.poll(second)!!.ptsUs)
        assertEquals(2L, queue.poll(second)!!.ptsUs)
        assertEquals(3L, queue.poll(second)!!.ptsUs)
    }
    @Test fun reconnectingSameDecoderDoesNotRewindItsTimestamp() {
        val queue = PluginAvcQueue(); val consumer = Any()
        queue.offer(frame(1, true)); assertEquals(1L, queue.poll(consumer)!!.ptsUs)
        queue.poll(null)
        assertNull(queue.poll(consumer))
        queue.offer(frame(2)); assertEquals(2L, queue.poll(consumer)!!.ptsUs)
    }
    @Test fun expiredGopIsNotReplayedAndFreshIdrReenablesBootstrap() {
        val queue = PluginAvcQueue(maxFrames = 2, maxBytes = 4)
        queue.offer(frame(1, true)); queue.offer(frame(2)); queue.offer(frame(3))
        assertNull(queue.poll(Any()))
        queue.poll(null); queue.offer(frame(4, true))
        assertEquals(4L, queue.poll(Any())!!.ptsUs)
    }
    @Test fun liveOverflowFailsInsteadOfSilentlyLosingReferences() {
        val queue = PluginAvcQueue(maxFrames = 2, maxBytes = 4); queue.poll(Any())
        queue.offer(frame(1, true)); queue.offer(frame(2))
        assertThrows(IllegalStateException::class.java) { queue.offer(frame(3)) }
        queue.clear(); assertNull(queue.poll(Any()))
    }
}
