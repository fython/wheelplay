package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [MicrophoneRecoveryTest.Builder::class])
class MicrophoneRecoveryTest {
    @Before fun reset() {
        Builder.attempts.clear()
        Builder.rejected = emptySet()
        Builder.uninitialized = emptySet()
        Builder.created.clear()
        Builder.reads = listOf(AudioRecord.ERROR_DEAD_OBJECT)
    }

    @Test fun siriRetriesCommunicationWhenRecognitionCreationIsRefused() {
        Builder.rejected = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        withUplink { uplink -> assertTrue(uplink.start()) }
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION), Builder.attempts)
        assertTrue(Builder.created.single().released.await(2, TimeUnit.SECONDS))
    }

    @Test fun rejectedRecorderIsReleasedBeforeInitializationFallback() {
        Builder.uninitialized = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        withUplink { assertTrue(it.start()) }
        assertEquals(2, Builder.created.size)
        assertEquals(0L, Builder.created.first().released.count)
    }

    @Test fun transientReadRestartsCaptureAndDropsTheIncompleteFrame() {
        Builder.reads = listOf(100, AudioRecord.ERROR_INVALID_OPERATION, 640, AudioRecord.ERROR_DEAD_OBJECT)
        val counters = MicrophoneCounters()
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            MicrophoneUplink(config(receiver.localPort), counters = counters).use { uplink ->
                assertTrue(uplink.start())
                val packet = DatagramPacket(ByteArray(2_048), 2_048)
                receiver.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                val pcm = AirPlayCrypto.chachaOpen(ByteArray(32), AirPlayCrypto.nonce64(0),
                    bytes.copyOfRange(12, bytes.size - 8), bytes.copyOfRange(4, 12))
                assertArrayEquals(ByteArray(640) { 0x2a }, pcm)
                assertTrue(Builder.created.single().released.await(2, TimeUnit.SECONDS))
                assertEquals(2, Builder.created.single().starts)
                assertEquals(1, counters.sequence)
                assertEquals(320, counters.timestamp)
            }
        }
    }

    @Test fun persistentReadFailureHasABoundedRestartCountAndDeadObjectsAreNotRetried() {
        Builder.reads = List(10) { AudioRecord.ERROR_INVALID_OPERATION }
        withUplink { uplink ->
            assertTrue(uplink.start())
            val recorder = Builder.created.single()
            assertTrue(recorder.released.await(2, TimeUnit.SECONDS))
            assertEquals(4, recorder.starts)
        }
        Builder.created.clear()
        Builder.reads = listOf(AudioRecord.ERROR_DEAD_OBJECT)
        withUplink { uplink ->
            assertTrue(uplink.start())
            val recorder = Builder.created.single()
            assertTrue(recorder.released.await(2, TimeUnit.SECONDS))
            assertEquals(1, recorder.starts)
        }
    }

    @Test fun browserInputUsesEncoderPacketsAndKeepsTheNegotiatedRtpClock() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            var reads = 0
            val input = object : PcmInput {
                override fun read(buffer: ByteArray): Int =
                    if (reads++ == 0) 1_920.also { buffer.fill(0, 0, it) } else -1
                override fun close() = Unit
            }
            val counters = MicrophoneCounters()
            val encodedPacket = byteArrayOf(0x78, 0x01, 0x02)
            val encoder = object : MicrophoneOpusEncoder {
                override val implementation = "test"
                override val available = true
                override fun encode(pcm: ByteArray): List<ByteArray> {
                    assertEquals(1_920, pcm.size)
                    return listOf(encodedPacket)
                }
                override fun close() = Unit
            }
            MicrophoneUplink(config(receiver.localPort).copy(sampleRate = 48_000,
                codec = AudioCodecKind.OPUS, opusClockRate = 24_000), input, counters, { encoder }).use { uplink ->
                assertTrue(uplink.start())
                val packet = DatagramPacket(ByteArray(2_048), 2_048)
                receiver.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                val payload = AirPlayCrypto.chachaOpen(ByteArray(32), AirPlayCrypto.nonce64(0),
                    bytes.copyOfRange(12, bytes.size - 8), bytes.copyOfRange(4, 12))
                assertArrayEquals(encodedPacket, payload)
            }
            assertEquals(480, counters.timestamp)
            assertEquals(1, counters.sequence)
            assertTrue("browser microphone never opens a local recorder", Builder.attempts.isEmpty())
        }
    }

    @Test fun browserPcmInputSendsNegotiatedPcmWithoutAnOpusEncoder() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            var reads = 0
            val input = object : PcmInput {
                override fun read(buffer: ByteArray): Int {
                    if (reads++ > 0) return -1
                    repeat(320) { index ->
                        buffer[index * 2] = 0x12
                        buffer[index * 2 + 1] = 0x34
                    }
                    return 640
                }
                override fun close() = Unit
            }
            val counters = MicrophoneCounters()
            MicrophoneUplink(config(receiver.localPort), input, counters,
                { error("PCM must not initialize an Opus encoder") }).use { uplink ->
                assertTrue(uplink.start())
                val packet = DatagramPacket(ByteArray(2_048), 2_048)
                receiver.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                val pcm = AirPlayCrypto.chachaOpen(ByteArray(32), AirPlayCrypto.nonce64(0),
                    bytes.copyOfRange(12, bytes.size - 8), bytes.copyOfRange(4, 12))
                assertArrayEquals(ByteArray(640) { if (it % 2 == 0) 0x34 else 0x12 }, pcm)
            }
            assertEquals(320, counters.timestamp)
            assertEquals(1, counters.sequence)
            assertTrue(Builder.attempts.isEmpty())
        }
    }

    @Test fun unavailableEncoderNeverSendsPcmInANegotiatedOpusStream() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            val counters = MicrophoneCounters()
            MicrophoneUplink(config(receiver.localPort).copy(codec = AudioCodecKind.OPUS),
                counters = counters, createOpusEncoder = { null }).use { uplink ->
                assertFalse(uplink.start())
            }
            assertEquals(0, counters.sequence)
            assertTrue(Builder.attempts.isEmpty())
        }
    }

    private fun withUplink(counters: MicrophoneCounters = MicrophoneCounters(), block: (MicrophoneUplink) -> Unit) {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            MicrophoneUplink(config(receiver.localPort), counters = counters).use(block)
        }
    }

    private fun config(port: Int) = MicrophoneConfig("speechrecognition", 16_000, 1, 100,
        20, InetAddress.getLoopbackAddress(), port, ByteArray(32))

    @Implements(AudioRecord.Builder::class)
    class Builder {
        @RealObject private lateinit var builder: AudioRecord.Builder
        @Implementation fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            attempts += source
            if (source in rejected) throw UnsupportedOperationException("capture source refused")
            return Recorder(source !in uninitialized, reads.toMutableList()).also { created += it }
        }
        companion object {
            val attempts = mutableListOf<Int>()
            val created = mutableListOf<Recorder>()
            var rejected = emptySet<Int>()
            var uninitialized = emptySet<Int>()
            var reads = emptyList<Int>()
        }
    }

    class Recorder(private val initialized: Boolean, private val reads: MutableList<Int>) : AudioRecord(
        MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, 4096,
    ) {
        var starts = 0
        private var recording = false
        val released = CountDownLatch(1)
        override fun getState() = if (initialized) STATE_INITIALIZED else STATE_UNINITIALIZED
        override fun getRecordingState() = if (recording) RECORDSTATE_RECORDING else RECORDSTATE_STOPPED
        override fun startRecording() { starts++; recording = true }
        override fun stop() { recording = false }
        override fun release() { released.countDown() }
        override fun read(buffer: ByteArray, offset: Int, size: Int, mode: Int): Int {
            val count = if (reads.isEmpty()) ERROR_DEAD_OBJECT else reads.removeAt(0)
            if (count > 0) buffer.fill(if (count == 100) 0x11 else 0x2a, offset, offset + count)
            return count
        }
    }
}
