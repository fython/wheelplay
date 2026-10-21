package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class ImageFrameEncoderTest {
    @Test fun outputsAreImmutableAndDoNotRetainPreviousStagingBytes() {
        val encoder = ImageFrameEncoder()
        val first = encoder.encode(ImageFormat.WEBP) { format, output ->
            assertEquals(ImageFormat.WEBP, format); output.write(byteArrayOf(1, 2)); true
        }
        val second = encoder.encode(ImageFormat.JPEG) { _, output -> output.write(3); true }
        assertArrayEquals(byteArrayOf(1, 2), first.first)
        assertArrayEquals(byteArrayOf(3), second.first)
        assertEquals(ImageFormat.JPEG, second.second)
    }

    @Test fun failedWebpFallsBackAtomicallyAndIsNotRepeatedOnLaterFrames() {
        for (throws in listOf(false, true)) {
            val encoder = ImageFrameEncoder()
            val formats = mutableListOf<ImageFormat>()
            repeat(2) {
                val result = encoder.encode(ImageFormat.WEBP) { format, output ->
                    formats.add(format); output.write(format.wireId)
                    if (format == ImageFormat.WEBP && throws) error("unavailable")
                    format == ImageFormat.JPEG
                }
                assertEquals(ImageFormat.JPEG, result.second)
                assertArrayEquals(byteArrayOf(1), result.first)
            }
            assertEquals(listOf(ImageFormat.WEBP, ImageFormat.JPEG, ImageFormat.JPEG), formats)
        }
    }

    @Test fun failedJpegIsReportedInsteadOfPublishingInvalidBytes() {
        try { ImageFrameEncoder().encode(ImageFormat.JPEG) { _, _ -> false }; fail() }
        catch (_: IllegalStateException) { }
    }
}
