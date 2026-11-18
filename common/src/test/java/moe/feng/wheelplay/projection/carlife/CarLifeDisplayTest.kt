package moe.feng.wheelplay.projection.carlife

import moe.feng.wheelplay.carlife.VideoSize
import org.junit.Assert.*
import org.junit.Test

class CarLifeDisplayTest {
    @Test fun fixedModeDoesNotWaitForBrowserAndIgnoresItsOldViewport() {
        assertEquals(1280 to 720, carLifeViewport(false, null))
        assertEquals(1280 to 720, carLifeViewport(false, 2160 to 848))
        assertEquals(null, carLifeViewport(true, null))
        assertEquals(2160 to 848, carLifeViewport(true, 2160 to 848))
    }

    @Test fun defaultAndAlignedBrowserSizesArePreserved() {
        assertEquals(VideoSize.DEFAULT, CarLifeDisplay.forViewport(null))
        for (size in listOf(800 to 480, 1280 to 720, 1920 to 720, 1920 to 1080)) {
            assertEquals(VideoSize(size.first, size.second), CarLifeDisplay.forViewport(size))
        }
    }
    @Test fun smallViewportJitterKeepsTheSameRequestedSize() {
        assertEquals(CarLifeDisplay.forViewport(1280 to 720), CarLifeDisplay.forViewport(1287 to 727))
        assertNotEquals(CarLifeDisplay.forViewport(1280 to 720), CarLifeDisplay.forViewport(1288 to 720))
    }
    @Test fun oversizedRequestsRemainBoundedAndKeepAspectRatio() {
        for (size in listOf(3840 to 2160, 4096 to 1024, 1080 to 2400)) {
            val result = CarLifeDisplay.forViewport(size)
            assertTrue(result.width.toLong() * result.height <= 1920L * 1080)
            assertTrue(result.width % 8 == 0 && result.height % 8 == 0)
            assertEquals(size.first.toDouble() / size.second, result.width.toDouble() / result.height, .03)
        }
        assertThrows(IllegalArgumentException::class.java) { CarLifeDisplay.forViewport(0 to 720) }
    }
}
