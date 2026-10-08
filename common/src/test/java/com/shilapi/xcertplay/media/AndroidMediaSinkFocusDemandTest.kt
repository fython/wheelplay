package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidMediaSinkFocusDemandTest {
    private class Route : RemoteAudioRoute {
        @Volatile var remote = false
        val packets = LinkedBlockingQueue<Int>()
        private var count = 0
        override fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int): Boolean {
            packets.add(++count)
            return remote
        }
        override fun audioStopped(type: Int) {}
        override fun microphone(type: Int, config: MicrophoneConfig): PcmInput? = null
        override fun microphoneStopped(type: Int) {}
        override fun onMicrophoneRouteChanged(listener: () -> Unit) {}
        override fun close() {}
    }
    private val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media")
    private fun pcm(sink: AndroidMediaSink, format: AudioFormat = this.format) {
        sink.onAudioRtp(1, format, ByteArray(28), 0)
    }
    private fun drained(route: Route, count: Int) {
        repeat(count) { assertNotNull("Renderer must process real PCM", route.packets.poll(2, TimeUnit.SECONDS)) }
    }

    @Test fun idleRendererMakesNoRequestsAndBlockedPcmCoalescesUntilPermissionReturns() {
        val ready = CountDownLatch(1)
        val requests = AtomicInteger()
        val requested = LinkedBlockingQueue<Int>()
        val route = Route()
        val sink = AndroidMediaSink(remoteAudio = route,
            onAudioDiagnostic = { if (it.startsWith("Audio: ready")) ready.countDown() })
        sink.setLocalMediaRequestedListener { requested.add(requests.incrementAndGet()) }
        try {
            sink.onAudioStarted(1, format, 0)
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertNull("Idle 10ms maintenance must not request focus", requested.poll(150, TimeUnit.MILLISECONDS))
            pcm(sink)
            assertEquals(1, requested.poll(2, TimeUnit.SECONDS))
            repeat(30) { pcm(sink) }
            drained(route, 31)
            assertNull("Denied/paused PCM must retain one outstanding demand", requested.poll(150, TimeUnit.MILLISECONDS))
            sink.setLocalMediaAllowed(true)
            sink.setLocalMediaAllowed(false) // A later focus loss must allow a fresh demand.
            pcm(sink)
            assertEquals(2, requested.poll(2, TimeUnit.SECONDS))
        } finally { sink.close() }
    }

    @Test fun browserPcmSkipsLocalFocusAndFallbackRequestsItOnce() {
        val route = Route().apply { remote = true }
        val requested = LinkedBlockingQueue<Unit>()
        val sink = AndroidMediaSink(remoteAudio = route)
        sink.setLocalMediaRequestedListener { requested.add(Unit) }
        try {
            sink.onAudioStarted(1, format, 0)
            repeat(10) { pcm(sink) }
            drained(route, 10)
            assertNull(requested.poll(150, TimeUnit.MILLISECONDS))
            route.remote = false
            repeat(10) { pcm(sink) }
            assertNotNull(requested.poll(2, TimeUnit.SECONDS))
            drained(route, 10)
            assertNull(requested.poll(150, TimeUnit.MILLISECONDS))
        } finally { sink.close() }
    }

    @Test fun navigationPcmDoesNotRequestMusicFocus() {
        val route = Route()
        val requested = LinkedBlockingQueue<Unit>()
        val sink = AndroidMediaSink(remoteAudio = route)
        val navigation = format.copy(audioType = "default")
        sink.setLocalMediaRequestedListener { requested.add(Unit) }
        try {
            sink.onAudioStarted(1, navigation, 0)
            repeat(10) { pcm(sink, navigation) }
            drained(route, 10)
            assertNull(requested.poll(150, TimeUnit.MILLISECONDS))
        } finally { sink.close() }
    }
}
