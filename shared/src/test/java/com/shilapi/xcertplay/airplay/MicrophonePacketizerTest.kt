package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }

    @Test
    fun pcmPacketsFitOneLinkMtuWithTheirIpv6AndUdpHeaders() {
        // #1094: 20 ms of 48 kHz mono sealed to 1,956 bytes, which leaves as IP fragments; those recordings were silent.
        assertEquals(10, MicrophoneConfig.pcmFrameMillis(48_000, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(24_000, 1))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(16_000, 1))
        assertEquals(5, MicrophoneConfig.pcmFrameMillis(48_000, 2))

        for ((sampleRate, channels) in listOf(48_000 to 1, 48_000 to 2, 44_100 to 1, 24_000 to 1, 16_000 to 1, 8_000 to 1)) {
            val millis = MicrophoneConfig.pcmFrameMillis(sampleRate, channels)
            val pcm = config(AudioCodecKind.LPCM, sampleRate = sampleRate).copy(channels = channels, frameMillis = millis)
            val packet = MicrophonePacketizer.sealPacket(ByteArray(32), 100, MicrophoneCounters(), ByteArray(pcm.frameBytes),
                pcm.samplesPerPacket)
            assertTrue("$sampleRate Hz x$channels: ${packet.size} bytes", 40 + 8 + packet.size <= 1500)
        }
        // A 24 kHz Siri packet and a 48 kHz one now carry the same 480 samples.
        assertEquals(480, config(AudioCodecKind.LPCM, sampleRate = 48_000).copy(frameMillis = 10).samplesPerPacket)
    }

    private fun config(codec: AudioCodecKind, sampleRate: Int = 48_000, opusClockRate: Int = 48_000) = MicrophoneConfig(
        audioType = "speechrecognition",
        sampleRate = sampleRate,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = java.net.InetAddress.getLoopbackAddress(),
        port = 1,
        key = ByteArray(32),
        codec = codec,
        opusClockRate = opusClockRate,
    )
}
