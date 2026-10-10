package com.shilapi.xcertplay.web

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RtcRecoveryPolicyTest {
    @Test fun defaultAndSavedPoliciesSurviveReload() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(RtcRecoveryPolicy.BALANCED, AirPlayPersistence.loadRtcRecoveryPolicy(context))
        for (policy in RtcRecoveryPolicy.entries) {
            AirPlayPersistence.saveRtcRecoveryPolicy(context, policy)
            assertEquals(policy, AirPlayPersistence.loadRtcRecoveryPolicy(context))
        }
    }

    @Test fun unknownSavedPolicyUsesDefaultRatherThanAnUnboundedTimeout() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("web_rtc_recovery", "unknown").apply()
        assertEquals(RtcRecoveryPolicy.BALANCED, AirPlayPersistence.loadRtcRecoveryPolicy(context))
    }
}
