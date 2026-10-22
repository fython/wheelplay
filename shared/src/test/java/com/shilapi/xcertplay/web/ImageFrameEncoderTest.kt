package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class ImageFrameEncoderTest {
    @Test fun selectedQualityReachesBothEncodersIncludingJpegFallback() {
        for (quality in listOf(1, 42, 75, 100)) {
            val encoder = ImageFrameEncoder()
            val calls = mutableListOf<Pair<ImageFormat, Int>>()
            val encoded = encoder.encode(ImageEncodingOptions(ImageFormat.WEBP, quality)) { format, value, output ->
                calls.add(format to value); output.write(value)
                format == ImageFormat.JPEG
            }
            assertEquals(listOf(ImageFormat.WEBP to quality, ImageFormat.JPEG to quality), calls)
            assertArrayEquals(byteArrayOf(quality.toByte()), encoded.first)
            encoder.encode(ImageEncodingOptions(ImageFormat.JPEG, 66)) { _, value, _ ->
                assertEquals(66, value); true
            }
        }
    }

    @Test fun outputsAreImmutableAndDoNotRetainPreviousStagingBytes() {
        val encoder = ImageFrameEncoder()
        val first = encoder.encode(ImageEncodingOptions(ImageFormat.WEBP)) { format, _, output ->
            assertEquals(ImageFormat.WEBP, format); output.write(byteArrayOf(1, 2)); true
        }
        val second = encoder.encode(ImageEncodingOptions(ImageFormat.JPEG)) { _, _, output -> output.write(3); true }
        assertArrayEquals(byteArrayOf(1, 2), first.first)
        assertArrayEquals(byteArrayOf(3), second.first)
        assertEquals(ImageFormat.JPEG, second.second)
    }

    @Test fun failedWebpFallsBackAtomicallyAndIsNotRepeatedOnLaterFrames() {
        for (throws in listOf(false, true)) {
            val encoder = ImageFrameEncoder()
            val formats = mutableListOf<ImageFormat>()
            repeat(2) {
                val result = encoder.encode(ImageEncodingOptions(ImageFormat.WEBP)) { format, _, output ->
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
        try { ImageFrameEncoder().encode(ImageEncodingOptions(ImageFormat.JPEG)) { _, _, _ -> false }; fail() }
        catch (_: IllegalStateException) { }
    }
}
