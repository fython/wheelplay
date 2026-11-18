package moe.feng.wheelplay.projection.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeDiscoveryTest {
    @Test fun onlyAcceptsExactAnnouncementWithinReceivedLength() {
        val announcement = "CarlifeHost".toByteArray()
        assertTrue(CarLifeDiscovery.isAnnouncement(announcement, announcement.size))
        val buffer = announcement + ByteArray(32)
        assertTrue(CarLifeDiscovery.isAnnouncement(buffer, announcement.size))
        assertFalse(CarLifeDiscovery.isAnnouncement(buffer, buffer.size))
        assertFalse(CarLifeDiscovery.isAnnouncement(announcement, announcement.size - 1))
        assertFalse(CarLifeDiscovery.isAnnouncement("CarLifeHost".toByteArray(), announcement.size))
        assertFalse(CarLifeDiscovery.isAnnouncement(ByteArray(2), announcement.size))
    }
}
