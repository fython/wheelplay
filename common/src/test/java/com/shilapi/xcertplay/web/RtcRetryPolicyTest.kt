package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class RtcRetryPolicyTest {
    @Test fun transientFailuresBackOffAndCapAtThirtySeconds() {
        var time = 0L
        val policy = RtcRetryPolicy { time }
        assertTrue(policy.available)
        for (delay in listOf(5000L, 10000L, 20000L, 30000L, 30000L, 30000L)) {
            assertTrue(policy.start())
            assertFalse("Only one negotiation can run at a time", policy.start())
            policy.finish()
            assertEquals(delay, policy.retryAfterMs)
            assertFalse(policy.available)
            assertFalse("Explicit starts cannot bypass the cooldown", policy.start())
            time += delay - 1
            assertEquals(1L, policy.retryAfterMs)
            assertFalse(policy.available)
            time++
            assertTrue(policy.available)
        }
    }

    @Test fun repeatedStatusReadsAndStopsDoNotPostponeOrAdvanceTheNextAttempt() {
        var time = 100L
        val policy = RtcRetryPolicy { time }
        policy.start(); policy.finish()
        time += 2000
        repeat(10) {
            policy.finish()
            assertEquals(3000L, policy.retryAfterMs)
            assertFalse(policy.available)
        }
        time += 3000
        assertTrue(policy.start()); policy.finish()
        assertEquals(10000L, policy.retryAfterMs)
    }

    @Test fun permanentRejectionBlocksOnlyThisConfigurationEvenWhenItArrivesAfterNativeFailure() {
        var time = 0L
        val policy = RtcRetryPolicy { time }
        policy.start(); policy.finish(); policy.finish(retryable = false)
        time = 1_000_000
        assertTrue(policy.blocked); assertFalse(policy.available); assertFalse(policy.start())
        policy.reset()
        policy.finish(retryable = false) // A late stop from a retired configuration is harmless.
        assertFalse(policy.blocked); assertTrue(policy.available); assertFalse(policy.attempted)
        assertTrue(policy.start()); policy.finish()
        assertEquals(5000L, policy.retryAfterMs)
    }

    @Test fun aFirstFrameDoesNotResetBackoffDuringFlappingButAStableMinuteDoes() {
        var time = 0L
        val policy = RtcRetryPolicy { time }
        policy.start(); policy.finish(); time += 5000
        policy.start(); policy.ready(); time += 30_000; policy.finish()
        assertEquals(10000L, policy.retryAfterMs)
        time += 10000
        policy.start(); policy.ready(); time += 59_999; policy.finish()
        assertEquals(20000L, policy.retryAfterMs)
        time += 20000
        policy.start(); policy.ready(); time += 60_000; policy.finish()
        assertEquals(5000L, policy.retryAfterMs)
    }

    @Test fun negotiationTimeAloneCannotCountAsStablePlayback() {
        var time = 0L
        val policy = RtcRetryPolicy { time }
        policy.start(); policy.finish(); time += 5000
        policy.start(); time += 60_000; policy.finish()
        assertEquals(10000L, policy.retryAfterMs)
    }

    @Test fun aNewConfigurationOrClientStartsImmediatelyWithoutInheritingOldFailures() {
        var time = 0L
        val policy = RtcRetryPolicy { time }
        repeat(4) {
            policy.start(); policy.finish(); time += policy.retryAfterMs
        }
        policy.start(); policy.finish()
        assertEquals(30000L, policy.retryAfterMs)
        policy.reset(); assertTrue(policy.available); assertEquals(0L, policy.retryAfterMs)
        assertTrue(RtcRetryPolicy { time }.available)
    }
}
