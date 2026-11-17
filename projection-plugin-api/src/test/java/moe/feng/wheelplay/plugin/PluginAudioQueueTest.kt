package moe.feng.wheelplay.plugin

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PluginAudioQueueTest {
    private val format = PluginAudioWire.Format(PluginAudioWire.PCM16, 44100, 2)
    private fun data(id: Long, value: Int) = PluginAudioWire.Data(id, byteArrayOf(value.toByte(), 0, 0, 0))
    private fun drain(q: PluginAudioQueue) = generateSequence { q.poll(0, TimeUnit.MILLISECONDS) }.toList()

    @Test fun burstKeepsNewestPcmAndBothEpochBoundaries() {
        val q = PluginAudioQueue(5, true)
        val first = PluginAudioWire.Start(1, 16, format)
        val stop = PluginAudioWire.Stop(1)
        val second = PluginAudioWire.Start(2, 16, format)
        listOf(first, data(1, 1), stop, second, data(2, 2)).forEach { assertTrue(q.offer(it)) }
        val fresh = data(2, 3)
        assertTrue(q.offer(fresh))
        val remaining = drain(q)
        assertEquals(listOf(first, stop, second), remaining.take(3))
        assertSame(fresh, remaining.last())
        assertEquals(1L, q.dropped)
    }
    @Test fun stopEvictsPcmButNeverControl() {
        val q = PluginAudioQueue(2, true)
        val start = PluginAudioWire.Start(1, 16, format)
        val stop = PluginAudioWire.Stop(1)
        q.offer(start); q.offer(data(1, 1))
        assertTrue(q.offer(stop))
        assertEquals(listOf(start, stop), drain(q))
    }
    @Test fun controlsOnlyDropIncomingPcmButReportControlOverflow() {
        val q = PluginAudioQueue(2, true)
        val start = PluginAudioWire.Start(1, 16, format)
        val stop = PluginAudioWire.Stop(1)
        q.offer(start); q.offer(stop)
        assertTrue(q.offer(data(2, 1)))
        assertFalse(q.offer(PluginAudioWire.Start(2, 16, format)))
        assertEquals(listOf(start, stop), drain(q))
        assertEquals(1L, q.dropped)
    }
    @Test fun strictModeDoesNotDropEncodedData() {
        val q = PluginAudioQueue(1, false)
        val original = data(1, 1)
        assertTrue(q.offer(original))
        assertFalse(q.offer(data(1, 2)))
        assertSame(original, drain(q).single())
        assertEquals(0L, q.dropped)
    }
    @Test fun sustainedBurstRemainsBoundedAndRetainsFinalSample() {
        val q = PluginAudioQueue(32, true)
        val start = PluginAudioWire.Start(1, 16, format)
        q.offer(start)
        repeat(1000) { assertTrue(q.offer(data(1, it))) }
        val stop = PluginAudioWire.Stop(1)
        assertTrue(q.offer(stop))
        val remaining = drain(q)
        assertEquals(32, remaining.size)
        assertSame(start, remaining.first())
        assertSame(stop, remaining.last())
        assertEquals(999.toByte(), (remaining[30] as PluginAudioWire.Data).bytes[0])
    }
}
