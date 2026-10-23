package com.shilapi.xcertplay.projection

import android.view.Surface
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.MediaCodecSupport
import com.shilapi.xcertplay.media.VideoCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The adapters run against an injected host port; no browser or protocol controller is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SyntheticSourceTest {
    private class Output : ProjectionVideoOutput {
        override val surface: Surface get() = error("No Surface in this test")
        val firstFrame = CountDownLatch(1)
        val touchFeedback = CountDownLatch(1)
        @Volatile var configured = false
        @Volatile var closed = false
        @Volatile var randomAccess = false
        @Volatile var updates = 0
        @Volatile var keyframe: (() -> Unit)? = null
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long {
            assertEquals(VideoCodec.H264, codec)
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            assertTrue(sps.isNotEmpty()); assertTrue(pps.isNotEmpty())
            configured = true
            keyframe = requestKeyframe
            return 17
        }
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long): Boolean {
            check(configured && !closed)
            assertEquals(17L, formatRevision)
            if (updates++ == 0) randomAccess = frame.isRandomAccess(VideoCodec.H264)
            firstFrame.countDown()
            return true
        }
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot): Boolean {
            check(!closed)
            updates++
            firstFrame.countDown()
            if (snapshot.argb.any { it == android.graphics.Color.WHITE }) touchFeedback.countDown()
            return true
        }
        override fun close() { closed = true }
    }
    private fun provider(kind: VideoIngressKind, output: Output) = SyntheticSource(kind,
        { actual, width, height ->
            assertEquals(kind, actual); assertEquals(320, width); assertEquals(180, height); output
        }, { RuntimeEnvironment.getApplication().assets.open("projection/demo.h264clip") })

    @Test fun encodedClipStartsWithConfigurationAndIdrAndStopsBeforeOutputClose() {
        val output = Output()
        val session = provider(VideoIngressKind.ENCODED, output).open().get(5, TimeUnit.SECONDS)
        try {
            assertTrue(output.firstFrame.await(5, TimeUnit.SECONDS))
            assertTrue(output.randomAccess)
            assertEquals(0, session.capabilities.maxTouchContacts)
            assertFalse(session.touch(listOf(ProjectionContact(0, .5, .5, true))))
            assertNotNull(output.keyframe)
        } finally { session.stop().get(5, TimeUnit.SECONDS) }
        assertTrue(output.closed)
        assertSame(session.stop(), session.stop())
    }
    @Test fun framebufferAcceptsTouchThroughTheSameSessionContract() {
        val output = Output()
        val session = provider(VideoIngressKind.FRAMEBUFFER, output).open().get(5, TimeUnit.SECONDS)
        try {
            assertTrue(output.firstFrame.await(5, TimeUnit.SECONDS))
            assertEquals(10, session.capabilities.maxTouchContacts)
            assertTrue(session.touch(listOf(ProjectionContact(4, .5, .5, true))))
            assertTrue(output.touchFeedback.await(5, TimeUnit.SECONDS))
        } finally { session.stop().get(5, TimeUnit.SECONDS) }
        assertTrue(output.closed)
        assertFalse(session.inputReady)
    }
}
