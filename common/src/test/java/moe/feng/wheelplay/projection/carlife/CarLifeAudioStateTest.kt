package moe.feng.wheelplay.projection.carlife

import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class AudioHarness {
    class Playback(val request: PlaybackRequest) : ProjectionPlaybackWorker {
        val packets = mutableListOf<ByteArray>()
        var closed = false
        var joined = false
        override fun offer(bytes: ByteArray) { check(!closed); packets += bytes }
        override fun close() { closed = true }
        override fun awaitStopped() { check(closed); joined = true }
    }
    class Capture(val active: () -> Boolean, val send: (ByteArray) -> Unit) : ProjectionCaptureWorker {
        var closed = false
        var joined = false
        override fun close() { closed = true }
        override fun awaitStopped() { check(closed); joined = true }
        override fun refreshRoute() = Unit
    }
    val playback = mutableListOf<Playback>()
    val captures = mutableListOf<Capture>()
    var routeClosed = false
    val route = object : RemoteAudioRoute {
        override fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int) = true
        override fun audioStopped(type: Int) = Unit
        override fun microphone(type: Int, config: MicrophoneConfig): PcmInput? = null
        override fun microphoneStopped(type: Int) = Unit
        override fun onMicrophoneRouteChanged(listener: () -> Unit) = Unit
        override fun close() { routeClosed = true }
    }
    val audio = CarLifeAudio(RuntimeEnvironment.getApplication(), { true }, { error(it) }, { route },
        { request, _, _, _ -> Playback(request).also { playback += it } },
        { _, active, send -> Capture(active, send).also { captures += it } })
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLifeAudioStateTest {
    @Test fun mediaAndNavigationHaveIndependentWorkersAndRestartClosesOldWorker() {
        val h = AudioHarness()
        h.audio.started(9240, 48000, 2); h.audio.started(9241, 16000, 1)
        h.audio.stopped(9240); h.audio.pcm(9241, byteArrayOf(1, 2)); h.audio.started(9240, 44100, 2)
        assertEquals(listOf(16, 6, 16), h.playback.map { it.request.stream })
        assertTrue(h.playback[0].joined)
        assertFalse(h.playback[1].closed)
        assertArrayEquals(byteArrayOf(1, 2), h.playback[1].packets.single())
        h.audio.close()
        assertTrue(h.playback.all { it.closed && it.joined }); assertTrue(h.routeClosed)
    }
    @Test fun largePcmSplitsAtSampleBoundariesAndOwnsItsBuffers() {
        val h = AudioHarness()
        val pcm = ByteArray(192000) { (it % 127).toByte() }; val original = pcm.copyOf()
        h.audio.started(9240, 48000, 2); h.audio.pcm(9240, pcm); pcm.fill(0)
        val packets = h.playback.single().packets
        assertTrue(packets.all { it.size <= PcmFormat.MAX_PACKET && it.size % 4 == 0 })
        assertArrayEquals(original, packets.flatMap { it.asIterable() }.toByteArray())
        h.audio.close()
    }
    @Test fun concurrentPortsDoNotLeakWorkersAndCloseRejectsLateCallbacks() {
        val h = AudioHarness(); val threads = Executors.newFixedThreadPool(2)
        try {
            listOf(9240, 9241).map { port -> threads.submit {
                repeat(100) { h.audio.started(port, 48000, 2); h.audio.pcm(port, byteArrayOf(1, 2, 3, 4)); h.audio.stopped(port) }
            } }.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(200, h.playback.size); assertTrue(h.playback.all { it.joined })
            h.audio.close()
            h.audio.started(9240, 48000, 2); h.audio.pcm(9240, byteArrayOf(1, 2, 3, 4)); h.audio.stopped(9240)
            assertEquals(200, h.playback.size)
        } finally { h.audio.close(); threads.shutdownNow() }
    }
    @Test fun malformedPcmIsRejectedBeforeAnySamplesAreEmitted() {
        val h = AudioHarness()
        assertThrows(IllegalStateException::class.java) { h.audio.pcm(9240, byteArrayOf(1, 2)) }
        h.audio.started(9240, 48000, 2)
        assertThrows(IllegalArgumentException::class.java) { h.audio.pcm(9240, ByteArray(65538)) }
        assertTrue(h.playback.single().packets.isEmpty())
        h.audio.close()
    }
}
