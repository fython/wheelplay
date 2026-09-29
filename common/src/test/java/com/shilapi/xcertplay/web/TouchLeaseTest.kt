package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test

class TouchLeaseTest {
    private val reports = mutableListOf<List<AirPlayContact>>()
    private val lease = TouchLease { reports.add(it); true }
    private val owner = Any()
    private fun finger(id: Int = 0, x: Double = .5, down: Boolean = true) = AirPlayContact(id, x, .5, down)

    @Test fun onlyOwnerCanControlAndRelease() {
        val other = Any()
        assertTrue(lease.claim(owner)); assertFalse(lease.claim(other))
        assertFalse(lease.touch(other, listOf(finger()), 0))
        lease.drop(other); assertFalse(lease.claim(other))
        assertTrue(lease.touch(owner, listOf(finger()), 0))
        lease.drop(owner); assertTrue(reports.last().isEmpty()); assertTrue(lease.claim(other))
    }
    @Test fun firstFingerLiftingDoesNotMoveSecondIntoFirstSlot() {
        lease.claim(owner)
        lease.touch(owner, listOf(finger(0), finger(1, .8)), 0)
        lease.touch(owner, listOf(finger(1, .8), finger(0, down = false)), 1)
        assertFalse(reports.last()[0].down)
        assertEquals(1, reports.last()[1].id)
        assertEquals(.8, reports.last()[1].x, 0.0)
    }
    @Test fun watchdogReleasesAndHeartbeatKeepsLongPress() {
        lease.claim(owner); lease.touch(owner, listOf(finger()), 0)
        lease.touch(owner, listOf(finger()), 1400)
        lease.expire(2000); assertTrue(reports.last()[0].down)
        lease.expire(3001); assertTrue(reports.last().isEmpty())
        val count = reports.size; lease.expire(5000); assertEquals(count, reports.size)
    }
    @Test fun rejectsNonFiniteOutOfRangeDuplicateAndTooManyContacts() {
        lease.claim(owner)
        for (contacts in listOf(listOf(finger(x=Double.NaN)), listOf(finger(x=1.1)),
            listOf(finger(x=Double.POSITIVE_INFINITY)), listOf(finger(), finger()),
            listOf(finger(3)), listOf(finger(),finger(1),finger(2)))) {
            assertFalse(lease.touch(owner, contacts, 0))
        }
        assertTrue(reports.isEmpty())
    }
}
