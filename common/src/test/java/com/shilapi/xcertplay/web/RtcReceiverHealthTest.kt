package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class RtcReceiverHealthTest {
    private fun report(packets: Long, decoded: Long, lost: Long = 0, rtt: Double? = null) =
        RtcReceiverHealth.Report(packets, lost, decoded, 0, rtt)
    @Test fun staticScreensNeverTriggerRecovery() {
        val health = RtcReceiverHealth()
        health.update(report(10, 10), 10, 0)
        repeat(10) { assertEquals(RtcReceiverHealth.Action.NONE, health.update(report(10, 10), 10, it * 1000L)) }
    }
    @Test fun stalledDecoderRequestsKeyframeThenFallsBackWhileSourceKeepsSending() {
        val health = RtcReceiverHealth()
        health.update(report(10, 10), 10, 0)
        assertEquals(RtcReceiverHealth.Action.NONE, health.update(report(20, 10), 20, 1000))
        assertEquals(RtcReceiverHealth.Action.KEYFRAME, health.update(report(40, 10), 40, 3000))
        assertEquals(RtcReceiverHealth.Action.FALLBACK, health.update(report(60, 10), 60, 5000))
    }
    @Test fun lastMissingFrameOnIdleSourceDoesNotTriggerFallback() {
        val health = RtcReceiverHealth()
        health.update(report(10, 10), 10, 0)
        health.update(report(11, 10), 11, 1000)
        assertEquals(RtcReceiverHealth.Action.NONE, health.update(report(11, 10), 11, 5000))
    }
    @Test fun lossOrRttMarksCongestionWithoutRequestingKeyframesWhenDecodingProgresses() {
        val health = RtcReceiverHealth()
        health.update(report(10, 10), 10, 0)
        assertEquals(RtcReceiverHealth.Action.NONE, health.update(report(100, 20, 20), 20, 1000))
        assertTrue(health.congested)
        health.update(report(200, 30, 20), 30, 2000); assertFalse(health.congested)
        health.update(report(300, 40, 20, 300.0), 40, 3000); assertTrue(health.congested)
    }
}
