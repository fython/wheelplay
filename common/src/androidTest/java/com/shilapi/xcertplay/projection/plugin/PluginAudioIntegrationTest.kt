package com.shilapi.xcertplay.projection.plugin

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.media.PcmInput
import com.shilapi.xcertplay.media.RemoteAudioRoute
import moe.feng.wheelplay.AirPlayPersistence
import moe.feng.wheelplay.CarPlayMicrophoneSource
import moe.feng.wheelplay.plugin.IProjectionPlugin
import moe.feng.wheelplay.plugin.PluginAudioWire
import moe.feng.wheelplay.plugin.PluginAudioWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PluginAudioIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private open class Route : RemoteAudioRoute {
        val packets = LinkedBlockingQueue<ByteArray>()
        val stopped = LinkedBlockingQueue<Int>()
        val captured = AtomicBoolean()
        val inputClosed = AtomicBoolean()
        override val playbackAvailable = true
        override fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int): Boolean {
            packets.add(pcm.copyOfRange(offset, offset + length)); return true
        }
        override fun audioStopped(type: Int) { stopped.add(type) }
        override fun microphone(type: Int, config: MicrophoneConfig): PcmInput = object : PcmInput {
            override fun read(buffer: ByteArray): Int {
                if (inputClosed.get()) return -1
                if (captured.compareAndSet(false, true)) { buffer.fill(7); return buffer.size }
                Thread.sleep(10); return 0
            }
            override fun close() { inputClosed.set(true) }
        }
        override fun microphoneStopped(type: Int) {}
        override fun onMicrophoneRouteChanged(listener: () -> Unit) {}
        override fun close() {}
    }
    private fun attach(audio: RemotePluginAudio, errors: LinkedBlockingQueue<String>): Pair<PluginAudioWriter, ParcelFileDescriptor> {
        lateinit var writer: PluginAudioWriter
        lateinit var capture: ParcelFileDescriptor
        // Local proxy duplicates received FDs just as Binder does across processes.
        val peer = Proxy.newProxyInstance(IProjectionPlugin::class.java.classLoader,
            arrayOf(IProjectionPlugin::class.java)) { _, method, args ->
            check(method.name == "attachAudio")
            writer = PluginAudioWriter(ParcelFileDescriptor.dup((args[1] as ParcelFileDescriptor).fileDescriptor), errors::add)
            capture = ParcelFileDescriptor.dup((args[2] as ParcelFileDescriptor).fileDescriptor)
            null
        } as IProjectionPlugin
        audio.sendTo(peer, "test")
        return writer to capture
    }

    @Test fun closingBlockedAudioReaderDoesNotRequireThreadInterruption() {
        val errors = LinkedBlockingQueue<String>()
        val routeClosed = CountDownLatch(1)
        val route = object : Route() { override fun close() { routeClosed.countDown() } }
        val audio = RemotePluginAudio(context, false, { true }, { false }, errors::add, { route })
        val (writer, capture) = attach(audio, errors)
        try {
            // Keep the peer's write end open: closing the host read FD must unblock read().
            audio.close(); audio.awaitStopped()
            assertEquals(0L, routeClosed.count)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { audio.close(); writer.close(); writer.awaitStopped(); capture.close() }
    }

    @Test fun stopWaitsForPlaybackCleanupWithoutTreatingItsWakeupAsFailure() {
        val errors = LinkedBlockingQueue<String>()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val routeClosed = CountDownLatch(1)
        val route = object : Route() {
            override fun output(type: Int, format: AudioFormat, pcm: ByteArray, offset: Int, length: Int): Boolean {
                entered.countDown()
                // Hold the worker through its shutdown interrupt, simulating pending resource cleanup.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (release.count != 0L && System.nanoTime() < deadline) {
                    try { release.await(20, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted.countDown() }
                }
                return true
            }
            override fun close() { routeClosed.countDown() }
        }
        val audio = RemotePluginAudio(context, false, { true }, { false }, errors::add, { route })
        val (writer, capture) = attach(audio, errors)
        try {
            writer.offer(PluginAudioWire.Start(1, 16, PluginAudioWire.Format(PluginAudioWire.PCM16, 16000, 1)))
            writer.offer(PluginAudioWire.Data(1, ByteArray(640)))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            audio.close()
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
            assertFalse("Route must remain open while the playback worker is still releasing resources",
                routeClosed.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            audio.awaitStopped()
            assertEquals(0L, routeClosed.count)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            release.countDown(); audio.close()
            writer.close(); writer.awaitStopped(); capture.close()
        }
    }

    @Test fun pcmPlaybackAndSyntheticBrowserMicrophoneReleaseTheirWorkers() {
        val route = Route()
        val errors = LinkedBlockingQueue<String>()
        val format = PluginAudioWire.Format(PluginAudioWire.PCM16, 16000, 1)
        val playback = PluginAudioPlayback(context, PluginAudioWire.Start(1, 16, format), route, { true }, errors::add)
        val oldSource = AirPlayPersistence.loadMicrophoneSource(context)
        var microphone: PluginMicrophone? = null
        try {
            val pcm = ByteArray(640) { (it % 127).toByte() }
            playback.offer(pcm)
            assertArrayEquals(pcm, route.packets.poll(3, TimeUnit.SECONDS))
            AirPlayPersistence.saveMicrophoneSource(context, CarPlayMicrophoneSource.BROWSER)
            val captured = LinkedBlockingQueue<PluginAudioWire.MicrophoneData>()
            microphone = PluginMicrophone(context, PluginAudioWire.Microphone(2, format, false), route, { true }, captured::add)
            val packet = captured.poll(3, TimeUnit.SECONDS)
            assertNotNull(packet); assertEquals(2L, packet!!.id)
            assertArrayEquals(ByteArray(640) { 7 }, packet.bytes)
        } finally {
            microphone?.close(); microphone?.awaitStopped()
            playback.close(); playback.awaitStopped()
            AirPlayPersistence.saveMicrophoneSource(context, oldSource)
        }
        assertTrue(route.inputClosed.get())
        assertEquals(16, route.stopped.poll(3, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
    }
    @Test fun aacAdtsIsDecodedByDeviceCodecIntoBrowserPcm() {
        val route = Route()
        val errors = LinkedBlockingQueue<String>()
        val format = PluginAudioWire.Format(PluginAudioWire.AAC_ADTS, 48000, 2)
        val playback = PluginAudioPlayback(context, PluginAudioWire.Start(1, 16, format), route, { true }, errors::add)
        try {
            // Synthetic 440 Hz, 100 ms stereo AAC-LC fixture; contains no recorded audio.
            val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("plugin-audio-tone.aac").use { it.readBytes() }
            playback.offer(fixture)
            val pcm = route.packets.poll(5, TimeUnit.SECONDS)
            assertNotNull(errors.toString(), pcm)
            assertTrue(pcm!!.size > 0 && pcm.size % 4 == 0)
        } finally { playback.close(); playback.awaitStopped() }
        assertTrue(errors.toString(), errors.isEmpty())
    }
    @Test fun pipeWriterRoundTripsAndStopsWhileReaderIsIdle() {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val errors = LinkedBlockingQueue<String>()
        val writer = PluginAudioWriter(pipe[1], errors::add)
        val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]))
        try {
            val expected = PluginAudioWire.Start(1, 16, PluginAudioWire.Format(1, 48000, 2))
            writer.offer(expected)
            assertEquals(expected, PluginAudioWire.Reader(input).read())
            writer.close(); writer.awaitStopped()
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { writer.close(); input.close() }
    }
}
