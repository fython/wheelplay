package com.shilapi.xcertplay.web

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VideoPacketTest {
    @Test fun v3CarriesFormatAndV2CannotAccidentallySendWebp() {
        for (format in ImageFormat.entries) {
            val frame = WebSession.Frame(byteArrayOf(1, 2), 0, 99, 7, 42, 320, 180, format)
            val packet = VideoPacket.encode(frame, 3)
            val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(42, packet.size); assertEquals(VideoPacket.MAGIC_V3, header.int)
            assertEquals(7.0, header.double, 0.0); assertEquals(42.0, header.double, 0.0)
            assertEquals(99.0, header.double, 0.0); assertEquals(320, header.int); assertEquals(180, header.int)
            assertEquals(format.wireId, header.int); assertArrayEquals(frame.jpeg, packet.copyOfRange(40, packet.size))
            if (format == ImageFormat.WEBP) {
                try { VideoPacket.encode(frame); fail() } catch (_: IllegalArgumentException) { }
            }
        }
    }
    @Test fun headerPreservesSafeIntegerIdentityGeometryAndJpegBytes() {
        val jpeg = byteArrayOf(-1, -40, -1)
        val packet = VideoPacket.encode(WebSession.Frame(jpeg, 10, 99, 9007199254740991L, 42, 720, 1280))
        assertEquals(36 + jpeg.size, packet.size)
        val view = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x32565057, view.int)
        assertEquals(9007199254740991L, view.double.toLong())
        assertEquals(42L, view.double.toLong()); assertEquals(99L, view.double.toLong())
        assertEquals(720, view.int); assertEquals(1280, view.int)
        assertArrayEquals(jpeg, packet.copyOfRange(36, packet.size))
    }
}
