package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class RtcFrameQueueTest {
    private val frame = byteArrayOf(1, 2)
    @Test fun expirationDropsTheEntireReferenceChainAndWaitsForKeyframe() {
        val q = RtcFrameQueue(maxAgeNs = 50)
        assertFalse(q.offer(frame, 0, false, 0))
        assertTrue(q.offer(frame, 1, true, 0))
        assertTrue(q.offer(frame, 2, false, 10))
        assertNull(q.poll(50))
        assertEquals(2L, q.expired)
        assertEquals(0, q.bytes)
        assertFalse(q.offer(frame, 3, false, 51))
        assertTrue(q.offer(frame, 4, true, 52))
        assertEquals(4L, q.poll(53)!!.pts)
    }
    @Test fun producerAlsoExpiresOldFramesAndFreshKeyframeCanReplaceThem() {
        val q = RtcFrameQueue(maxAgeNs = 50)
        q.offer(frame, 1, true, 0)
        assertTrue(q.offer(frame, 2, true, 50))
        assertEquals(1L, q.expired)
        assertEquals(2L, q.poll(51)!!.pts)
    }
    @Test fun sizeOverflowOrSendFailureNeverContinuesWithPredictiveFrames() {
        val q = RtcFrameQueue(maxBytes = 4, maxFrames = 2)
        q.offer(frame, 0, true, 0); q.offer(frame, 1, false, 1)
        assertFalse(q.offer(frame, 2, false, 2))
        assertEquals(2L, q.overflow)
        assertFalse(q.offer(ByteArray(5), 3, true, 3))
        assertTrue(q.offer(frame, 4, true, 4))
        q.invalidate()
        assertFalse(q.offer(frame, 5, false, 5))
        assertEquals(0, q.size)
    }
    @Test fun missingRecoveryKeyframeHasABoundedDeadlineAndFreshKeyframeResetsIt() {
        val q = RtcFrameQueue()
        q.offer(frame, 0, true, 0); q.invalidate(1)
        assertFalse(q.offer(frame, 1, false, 1_000_000_001))
        assertFalse(q.recoveryExpired(2_999_999_999))
        assertTrue(q.recoveryExpired(3_000_000_001))
        assertTrue(q.offer(frame, 2, true, 3_000_000_002))
        assertFalse(q.recoveryExpired(3_000_000_003))
    }
    @Test fun retainedBuffersAreReleasedOnExpirationFailureAndAfterSending() {
        class Buffer : com.shilapi.xcertplay.media.CompressedVideoFrame {
            var references = 1
            override val size = 2
            override fun retain() = apply { references++ }
            override fun close() { references--; check(references >= 0) }
            override fun isRandomAccess(codec: com.shilapi.xcertplay.airplay.VideoCodec) = true
            override fun toByteArray() = byteArrayOf(1, 2)
        }
        val q = RtcFrameQueue(maxAgeNs = 50)
        val a = Buffer(); assertTrue(q.offer(a, 0, true, 0)); a.close()
        assertEquals(1, a.references); assertNull(q.poll(50)); assertEquals(0, a.references)
        val b = Buffer(); q.offer(b, 1, true, 51); b.close(); q.invalidate(); assertEquals(0, b.references)
        val c = Buffer(); q.offer(c, 2, true, 52); c.close()
        val sent = q.poll(53)!!; assertEquals(1, c.references); sent.data.close(); assertEquals(0, c.references)
        val rejected = Buffer(); assertFalse(q.offer(rejected, 3, true, 54, 3 * 1024 * 1024))
        assertEquals(1, rejected.references); rejected.close()
    }

}
