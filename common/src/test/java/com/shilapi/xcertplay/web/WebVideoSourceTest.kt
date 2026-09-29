package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class WebVideoSourceTest {
    private val avcc = byteArrayOf(1, 0x64, 0, 0x20, -1, -31, 0, 4, 0x67, 0x64, 0, 0x20, 1, 0, 2, 0x68, 1)

    @Test fun h264ConfigurationCarriesSourceProfileAndParameterSets() {
        val source = WebVideoSource(StreamTechnology.WEBRTC)
        source.configure(110, VideoCodec.H264, avcc)
        assertEquals("640020", source.config!!.profile)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x20, 0, 0, 0, 1, 0x68, 1), source.config!!.parameters)
        assertTrue(source.jpegNeeded)
        assertFalse(source.frame(110, byteArrayOf(0, 0, 0, 1, 0x65), 0))
        val old = source.config
        source.configure(110, VideoCodec.H264, avcc)
        assertSame("Repeated keyframe configuration must keep the negotiated peer", old, source.config)
        source.configure(110, VideoCodec.H264, avcc.copyOf().apply { this[11] = 0x21 })
        assertNotSame(old, source.config)
        source.stopped(110); assertNull(source.config)
    }

    @Test fun invalidConfigAndExplicitJpegAlwaysUseTheExistingDecoder() {
        for (mode in StreamTechnology.entries) {
            val source = WebVideoSource(mode)
            source.configure(110, VideoCodec.H265, avcc); assertNull(source.config)
            source.configure(110, VideoCodec.H264, byteArrayOf(1)); assertNull(source.config)
            assertTrue(source.jpegNeeded)
        }
        val source = WebVideoSource(StreamTechnology.JPEG)
        source.configure(110, VideoCodec.H264, avcc); assertNull(source.config)
    }

    private fun hvcc() = ByteArray(23).apply {
        this[0] = 1; this[1] = 1; this[12] = 120; this[21] = 3; this[22] = 3
    } + byteArrayOf(32, 0, 1, 0, 2, 64, 1, 33, 0, 1, 0, 2, 66, 1, 34, 0, 1, 0, 2, 68, 1)

    @Test fun hevcConfigurationPreservesProfileTierLevelAndAllParameterSets() {
        val source = WebVideoSource(StreamTechnology.WEBRTC)
        val record = hvcc().apply { this[1] = 34; this[12] = -103 }
        source.configure(110, VideoCodec.H265, record)
        val config = source.config!!
        assertEquals(VideoCodec.H265, config.codec)
        assertEquals("profile-id=2;tier-flag=1;level-id=153;tx-mode=SRST", config.fmtp)
        assertArrayEquals(byteArrayOf(0,0,0,1,64,1,0,0,0,1,66,1,0,0,0,1,68,1), config.parameters)
        source.configure(110, VideoCodec.H265, record.copyOf())
        assertSame(config, source.config)
        source.configure(110, VideoCodec.H264, avcc)
        assertEquals(VideoCodec.H264, source.config!!.codec)
        assertNotSame(config, source.config)
    }

    @Test fun hevcRequiresCompleteValidParameterSetsAndRespectsJpegSelection() {
        val source = WebVideoSource(StreamTechnology.WEBRTC)
        for (record in listOf(hvcc().copyOf(30), hvcc().apply { this[22] = 2 },
            hvcc().apply { this[1] = 65 }, hvcc().apply { this[12] = 0 },
            hvcc().apply { this[28] = 66 })) {
            source.configure(110, VideoCodec.H265, record)
            assertNull(source.config)
            assertTrue(source.jpegNeeded)
        }
        val jpeg = WebVideoSource(StreamTechnology.JPEG)
        jpeg.configure(110, VideoCodec.H265, hvcc())
        assertNull(jpeg.config)
    }

    @Test fun recoveryIsRateLimitedAndUsesTheCurrentStream() {
        val source = WebVideoSource(StreamTechnology.WEBRTC)
        var requests = 0
        source.recovery(110) { requests++ }
        source.configure(110, VideoCodec.H264, avcc)
        source.requestKeyframe(); source.requestKeyframe()
        assertEquals(1, requests)
        source.requestKeyframe(force = true); assertEquals(2, requests)
        source.stopped(110); source.requestKeyframe(force = true); assertEquals(2, requests)
    }
}
