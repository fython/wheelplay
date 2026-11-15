package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMicrophoneLifecycleTest {
    private class Rig : java.io.Closeable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val server = ServerSocket(0, 1, loopback)
        private val client = Socket(loopback, server.localPort)
        val starts = LinkedBlockingQueue<MicrophoneConfig>()
        val stops = LinkedBlockingQueue<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) { starts.add(config) }
            override fun onMicrophoneStopped(type: Int) { stops.add(type) }
        }, microphoneEnabled = true)
        val shared = ByteArray(32) { 4 }
        val session = AirPlaySession(
            server.accept(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine,
        ).also {
            // Synthetic verified state: tests exercise SETUP/media, never real authentication assets.
            it.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(it.pairVerify, shared)
            it.pairVerify.javaClass.getDeclaredField("verified").apply { isAccessible = true }.setBoolean(it.pairVerify, true)
        }

        fun setup(audioType: String = "default", vararg extra: Pair<String, Any?>): Map<String, Any?> {
            val stream = mapOf<String, Any?>("audioType" to audioType, "audioFormat" to 0x4000L,
                "streamConnectionID" to 42L, "dataPort" to 50000) + extra
            return requireNotNull(engine.onAudio(session, 100, stream))
        }

        fun downlink(response: Map<String, Any?>) {
            val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt42".asciiBytes(),
                "DataStream-Output-Encryption-Key".asciiBytes(), 32)
            val packet = MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(), byteArrayOf(0, 0), 1)
            DatagramSocket().use { it.send(DatagramPacket(packet, packet.size, loopback, (response["dataPort"] as Number).toInt())) }
        }

        override fun close() {
            session.close()
            client.close()
            server.close()
        }
    }

    @Test fun appVoiceNoteStartsWithoutDownlinkAndUsesMtuSizedPcm() = Rig().use { rig ->
        rig.setup()
        val config = rig.starts.poll(3, TimeUnit.SECONDS)
        assertNotNull("no capture without downlink", config)
        assertEquals("default", config!!.audioType)
        assertEquals(48000, config.sampleRate)
        assertEquals(10, config.frameMillis)
        assertEquals(480, config.rtpSamplesPerPacket)
    }

    @Test fun aDownlinkAndTheTimerCannotStartTwoMicrophones() = Rig().use { rig ->
        val response = rig.setup("speechrecognition")
        rig.downlink(response)
        assertNotNull(rig.starts.poll(3, TimeUnit.SECONDS))
        assertNull("timer started capture twice", rig.starts.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test fun callsStillWaitForTheirDownlink() = Rig().use { rig ->
        val response = rig.setup("telephony")
        assertNull(rig.starts.poll(800, TimeUnit.MILLISECONDS))
        rig.downlink(response)
        assertNotNull(rig.starts.poll(3, TimeUnit.SECONDS))
    }

    @Test fun teardownCancelsDelayedCaptureAndStopsItsMicrophone() = Rig().use { rig ->
        rig.setup()
        rig.engine.onTeardown(rig.session, 100)
        assertEquals(100, rig.stops.poll(1, TimeUnit.SECONDS))
        assertNull("capture restarted after TEARDOWN", rig.starts.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test fun replacementCancelsTheOldTimerAndDoesNotRecordNavigation() = Rig().use { rig ->
        rig.setup()
        rig.setup("default", "input" to false)
        assertEquals(100, rig.stops.poll(1, TimeUnit.SECONDS))
        assertNull("replaced recording started during navigation", rig.starts.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test fun sessionCloseCancelsDelayedCapture() = Rig().use { rig ->
        rig.setup()
        rig.session.close()
        assertEquals(100, rig.stops.poll(1, TimeUnit.SECONDS))
        assertNull("capture restarted after close", rig.starts.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test fun explicitPacketDurationAndOpusClockArePreserved() = Rig().use { rig ->
        rig.setup("speechrecognition", "framesPerPacket" to 960)
        assertEquals(20, rig.starts.poll(3, TimeUnit.SECONDS)!!.frameMillis)
        rig.setup("speechrecognition", "audioFormat" to 0x20000000L)
        val opus = rig.starts.poll(3, TimeUnit.SECONDS)!!
        assertEquals(AudioCodecKind.OPUS, opus.codec)
        assertEquals(960, opus.samplesPerPacket)
        assertEquals(480, opus.rtpSamplesPerPacket)
        assertEquals(20, opus.frameMillis)
    }
}
