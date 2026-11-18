package moe.feng.wheelplay.projection.carlife

import moe.feng.wheelplay.carlife.CarLifeMicrophone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLifeMicrophoneStateTest {
    @Test fun sourceRestartRejectsStaleCaptureCallbacksAndLateStop() {
        val h = AudioHarness()
        val first = mutableListOf<ByteArray>(); val second = mutableListOf<ByteArray>()
        val a = CarLifeMicrophone.Capture { first.add(it) }
        val b = CarLifeMicrophone.Capture { second.add(it) }
        h.audio.started(a); h.audio.started(a)
        assertEquals(1, h.captures.size)
        h.captures[0].send(ByteArray(1280) { 3 })
        assertEquals(listOf(640, 640), first.map { it.size })
        h.audio.stopped(a); h.audio.started(b); h.audio.stopped(a)
        assertTrue(h.captures[0].joined); assertFalse(h.captures[0].active())
        h.captures[0].send(byteArrayOf(1, 0)); assertTrue(second.isEmpty())
        assertTrue(h.captures[1].active())
        h.captures[1].send(byteArrayOf(2, 0)); assertArrayEquals(byteArrayOf(2, 0), second.single())
        h.audio.close(); h.audio.close(); h.audio.started(a)
        h.captures[1].send(byteArrayOf(4, 0))
        assertEquals(1, second.size); assertEquals(2, h.captures.size)
        assertTrue(h.captures.all { it.joined }); assertTrue(h.routeClosed)
    }
    @Test fun malformedPcmCannotReachSource() {
        val h = AudioHarness()
        h.audio.started(CarLifeMicrophone.Capture { error("must not forward") })
        assertThrows(IllegalArgumentException::class.java) { h.captures.single().send(byteArrayOf(1)) }
        h.audio.close()
    }
}
