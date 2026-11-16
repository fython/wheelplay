package com.shilapi.xcertplay.projection.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginKeyframeWaitTest {
    @Test fun offlineAfterPlaybackDoesNotExpire() {
        val wait = PluginKeyframeWait()
        val browser = Any()
        assertFalse(wait.expired(browser, false, 0))
        assertFalse(wait.expired(null, true, 60_000))
        assertFalse(wait.expired(null, true, 600_000))
    }

    @Test fun connectedBrowserWithoutFirstFrameStillTimesOut() {
        val wait = PluginKeyframeWait()
        val browser = Any()
        assertFalse(wait.expired(browser, true, 0))
        assertFalse(wait.expired(browser, true, 45_000))
        assertTrue(wait.expired(browser, true, 45_001))
    }

    @Test fun reconnectGetsFullBudgetEvenAfterAnInterruptedWait() {
        val wait = PluginKeyframeWait()
        val browser = Any()
        assertFalse(wait.expired(browser, true, 0))
        assertFalse(wait.expired(null, true, 40_000))
        assertFalse(wait.expired(browser, true, 600_000))
        assertFalse(wait.expired(browser, true, 645_000))
        assertTrue(wait.expired(browser, true, 645_001))
    }

    @Test fun replacementDecoderGetsFullBudgetWithoutAnObservedOfflineTick() {
        val wait = PluginKeyframeWait()
        assertFalse(wait.expired(Any(), true, 0))
        val replacement = Any()
        assertFalse(wait.expired(replacement, true, 60_000))
        assertTrue(wait.expired(replacement, true, 105_001))
    }

    @Test fun successfulDisplayClearsWaitAndRecoveryStartsFresh() {
        val wait = PluginKeyframeWait()
        val browser = Any()
        assertFalse(wait.expired(browser, true, 0))
        assertFalse(wait.expired(browser, false, 40_000))
        assertFalse(wait.expired(browser, true, 600_000))
        assertTrue(wait.expired(browser, true, 645_001))
    }
}
