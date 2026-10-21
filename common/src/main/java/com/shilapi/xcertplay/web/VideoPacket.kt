package com.shilapi.xcertplay.web

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Negotiated with media=2. Raw JPEG remains the default for existing browsers. */
internal object VideoPacket {
    const val MAGIC = 0x32565057 // WPV2, distinct from browser audio WPA1
    const val HEADER_BYTES = 36
    const val MAGIC_V3 = 0x33565057 // WPV3, appends a uint32 image format to the v2 header.
    const val HEADER_BYTES_V3 = 40

    fun encode(frame: WebSession.Frame, protocol: Int = 2): ByteArray {
        require(protocol == 3 || frame.format == ImageFormat.JPEG)
        return ByteBuffer.allocate((if (protocol == 3) HEADER_BYTES_V3 else HEADER_BYTES) + frame.jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(if (protocol == 3) MAGIC_V3 else MAGIC)
            // JS safely represents these IDs as integers; epochs/sequences are process counters.
            .putDouble(frame.sessionId.toDouble()).putDouble(frame.videoEpoch.toDouble())
            .putDouble(frame.sequence.toDouble()).putInt(frame.width).putInt(frame.height).apply {
                if (protocol == 3) putInt(frame.format.wireId)
            }
            .put(frame.jpeg).array()
    }
}
