package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class OpusEncoderSupportTest {
    @Test fun platformEncoderAllowsOpusAdvertisement() {
        assertTrue(OpusEncoderSupport.isAvailable(platformAvailable = { true }))
    }

    @Test fun missingOrUnreadablePlatformCapabilityFallsBackToPcmAdvertisement() {
        assertFalse(OpusEncoderSupport.isAvailable(platformAvailable = { false }))
        assertFalse(OpusEncoderSupport.isAvailable(
            platformAvailable = { throw IllegalStateException("codec list unavailable") },
        ))
    }

    @Test fun negotiatedOpusUsesThePlatformEncoderAndReleasesUnavailableEncoders() {
        val platform = FakeEncoder(available = true)
        assertSame(platform, MicrophoneOpusEncoders.create(48_000, platform = { bitrate ->
            assertEquals(48_000, bitrate)
            platform
        }))
        assertFalse(platform.closed)

        val missing = FakeEncoder(available = false)
        assertNull(MicrophoneOpusEncoders.create(48_000, platform = { missing }))
        assertTrue(missing.closed)
    }

    private class FakeEncoder(override val available: Boolean) : MicrophoneOpusEncoder {
        override val implementation = "fake"
        var closed = false
        override fun encode(pcm: ByteArray): List<ByteArray> = emptyList()
        override fun close() { closed = true }
    }
}
