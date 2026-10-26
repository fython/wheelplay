package moe.feng.wheelplay.projection.desktop

import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopDisplayConfigTest {
    @Test fun acceptsDisplayLimitsInEitherOrientation() {
        DesktopDisplayConfig(320, 320, 72)
        DesktopDisplayConfig(3840, 2160, 640)
        DesktopDisplayConfig(2160, 3840, 160)
    }

    @Test fun rejectsInvalidDimensionsDensityAndExcessivePixelCount() {
        for ((width, height, dpi) in listOf(
            Triple(0, 720, 160), Triple(1280, -1, 160), Triple(319, 720, 160),
            Triple(1280, 3841, 160), Triple(3840, 2161, 160), Triple(3840, 3840, 160),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, 160), Triple(1280, 720, 71), Triple(1280, 720, 641),
        )) {
            assertThrows(IllegalArgumentException::class.java) { DesktopDisplayConfig(width, height, dpi) }
        }
    }
}
