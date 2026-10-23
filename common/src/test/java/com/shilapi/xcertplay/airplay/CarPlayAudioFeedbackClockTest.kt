package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayAudioFeedbackClockTest {
    @Test fun realAudioSetupAndEncryptedRtpReportTheNegotiatedClockThroughFeedbackAndMicrophone() {
        for ((bits, rate) in listOf(0x10000000L to 16000, 0x20000000L to 24000,
            0x40000000L to 48000, 0x4000L to 48000, 0x800000L to 48000)) {
            val loopback = InetAddress.getLoopbackAddress()
            ServerSocket(0, 1, loopback).use { server ->
                Socket(loopback, server.localPort).use { client ->
                    val peer = server.accept()
                    DatagramSocket(0, loopback).use { sender ->
                        val started = CountDownLatch(1)
                        val microphoneStarted = CountDownLatch(1)
                        var microphone: MicrophoneConfig? = null
                        var decodedFormat: AudioFormat? = null
                        val clock = AtomicLong(1000000000L)
                        val engine = CarPlayMediaEngine(object : MediaSink {
                            override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
                                decodedFormat = format; started.countDown()
                            }
                            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
                                microphone = config; microphoneStarted.countDown()
                            }
                        }, microphoneEnabled = true, nanoTime = clock::get)
                        val session = AirPlaySession(peer,
                            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
                                AirPlayDisplayConfig(800, 480)),
                            AirPlayIdentity.generate(), PairingStore(), null,
                            object : AirPlaySessionListener {}, engine)
                        val shared = ByteArray(32) { it.toByte() }
                        // This media-only fixture models an already authenticated control session.
                        val verification = ReflectionHelpers.getField<Any>(session, "pairVerify")
                        ReflectionHelpers.setField(verification, "sharedSecret", shared)
                        ReflectionHelpers.setField(verification, "verified", true)
                        try {
                            val audioType = if (bits == 0x800000L) "media" else "telephony"
                            val setup = engine.onAudio(session, 100, mapOf("streamConnectionID" to 1L,
                                "audioFormat" to bits, "audioType" to audioType,
                                "dataPort" to sender.localPort))!!
                            val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
                                "DataStream-Output-Encryption-Key".toByteArray(), 32)
                            val firstSample = -16
                            val packet = MicrophonePacketizer.sealPacket(key, 100,
                                MicrophoneCounters(timestamp = firstSample), byteArrayOf(1, 2, 3), 1)
                            sender.send(DatagramPacket(packet, packet.size, loopback, setup["dataPort"] as Int))
                            assertTrue("Encrypted audio must reach the media engine", started.await(3, TimeUnit.SECONDS))
                            assertEquals(48000, decodedFormat!!.sampleRate)
                            clock.addAndGet(20000000000L)
                            val feedback = (engine.onFeedback(session)!!["streams"] as List<*>).single() as Map<*, *>
                            assertEquals(rate, feedback["sampleRate"])
                            assertEquals((0xfffffff0L + 20L * rate) and 0xffffffffL, feedback["sampleTime"])
                            if (audioType == "telephony") {
                                assertTrue(microphoneStarted.await(3, TimeUnit.SECONDS))
                                assertEquals(rate, microphone!!.opusClockRate)
                                if (decodedFormat!!.codec == AudioCodecKind.OPUS) {
                                    assertEquals(1920, microphone!!.frameBytes)
                                    assertEquals(rate / 50, microphone!!.rtpSamplesPerPacket)
                                }
                            } else {
                                assertNull(microphone)
                            }
                        } finally {
                            engine.onSessionClosed(session); session.close()
                        }
                    }
                }
            }
        }
    }
}
