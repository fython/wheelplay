package moe.feng.wheelplay.plugin.carlink

import org.junit.Assert.*
import org.junit.Test

class CarLinkDisplayTest {
    @Test fun defaultsWhenHostHasNoViewport() {
        assertEquals(CarLinkConfig(), CarLinkConfig.forDisplay(null))
        assertEquals(CarLinkConfig(), CarLinkConfig.forDisplay(0 to 720))
    }
    @Test fun usesBrowserShapeInsteadOfFixedLandscape() {
        assertEquals(CarLinkConfig(1440, 900), CarLinkConfig.forDisplay(1440 to 900))
        assertEquals(CarLinkConfig(728, 772), CarLinkConfig.forDisplay(729 to 773))
        assertEquals(CarLinkConfig(1080, 1080), CarLinkConfig.forDisplay(1200 to 1200))
    }
    @Test fun fitsBalancedLimitsWithoutStretchingNormalViewports() {
        assertEquals(CarLinkConfig(1920, 540), CarLinkConfig.forDisplay(3840 to 1080))
        assertEquals(CarLinkConfig(1920, 1080), CarLinkConfig.forDisplay(3840 to 2160))
        for (size in listOf(1 to 8192, 8192 to 1, 1 to 1, 8192 to 8192)) {
            val c = CarLinkConfig.forDisplay(size)
            assertTrue(c.width in 320..1920 && c.height in 240..1080)
            assertEquals(0, c.width % 2); assertEquals(0, c.height % 2)
        }
    }
    @Test fun nativeAndScaledModesPreserveBrowserRatioAndDpi() {
        assertEquals(CarLinkConfig(2560, 1280, 240), CarLinkConfig.forDisplay(2560 to 1280, "native", dpi = 240))
        assertEquals(CarLinkConfig(1920, 960, 320), CarLinkConfig.forDisplay(1280 to 640, "custom", 1.5f, 320))
        assertEquals(CarLinkConfig(640, 360), CarLinkConfig.forDisplay(null, "custom", .5f))
    }
    @Test fun largeAndMalformedInputsStayWithinAllocationBudget() {
        for (size in listOf(8192 to 8192, 8192 to 1, 1 to 8192, Int.MAX_VALUE to Int.MAX_VALUE)) {
            for (scale in listOf(.5f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val c = CarLinkConfig.forDisplay(size, "custom", scale)
                assertTrue(c.width.toLong() * c.height <= 8_388_608)
                assertTrue(c.width in 320..4096 && c.height in 240..4096)
                assertEquals(0, c.width % 2); assertEquals(0, c.height % 2)
            }
        }
    }
}
