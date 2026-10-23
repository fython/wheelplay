package com.shilapi.xcertplay.projection.androidauto

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AapVideoRecoveryTest {
    private var time = 0L
    private val modes = mutableListOf<Int>()
    private val recovery = AapVideoRecovery({ time }) { modes += it }

    @Test fun ignoredNudgesEscalateAndLateIdrStillAllowsRegain() {
        recovery.request()
        time = 1500; recovery.request() // Repeated browser requests must not postpone recovery.
        time = 1999; recovery.tick()
        assertEquals(listOf(1, 1), modes)
        time = 2000; recovery.tick()
        assertEquals(2, modes.last())
        recovery.recovered() // An old frame arriving during release cannot cancel the regain.
        time = 2249; recovery.tick()
        assertEquals(2, modes.last())
        time = 2250; recovery.tick()
        assertEquals(1, modes.last())
        recovery.recovered()
        time = 100000; recovery.tick()
        assertEquals(listOf(1, 1, 2, 1), modes)
    }

    @Test fun quickIdrCancelsEscalationAndRequestsAreThrottled() {
        recovery.request()
        time = 499; recovery.request()
        recovery.recovered()
        time = 5000; recovery.tick()
        assertEquals(listOf(1), modes)
    }

    @Test fun stopAndPhoneNativeFocusCancelPendingRegain() {
        for (stop in listOf(false, true)) {
            modes.clear(); time = 0
            val policy = AapVideoRecovery({ time }) { modes += it }
            policy.request(); time = 2000; policy.tick()
            if (stop) policy.close() else policy.setProjected(false)
            time = 3000; policy.tick(); policy.request()
            assertEquals(listOf(1, 2), modes)
        }
    }

    @Test fun repeatedFailureRespectsCooldownAndEventuallyRequestsReconnect() {
        recovery.request()
        for (release in listOf(2000L, 12000L, 22000L)) {
            time = release - 1; recovery.tick()
            val before = modes.size
            time = release; recovery.tick()
            assertEquals(before + 1, modes.size)
            assertEquals(2, modes.last())
            time += 250; recovery.tick()
            assertEquals(1, modes.last())
        }
        time = 27249; recovery.tick()
        time = 27250
        val error = assertThrows(IOException::class.java) { recovery.tick() }
        assertTrue(error.message!!.contains("reconnect"))
        assertEquals(3, modes.count { it == 2 })
    }
}
