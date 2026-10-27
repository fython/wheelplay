package moe.feng.wheelplay

import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlaySize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayBrowserDisplaySizeTest {
    private fun host(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also {
            ReflectionHelpers.setField(it, "maximumDetectedWidthPixels", 3840)
            ReflectionHelpers.setField(it, "maximumDetectedHeightPixels", 2160)
            ReflectionHelpers.setField(it, "widthPhysicalMm", CarPlaySize.MEDIUM.widthMillimeters)
        }

    private fun physicalSize(host: CarPlayHostActivity, width: Int, height: Int): AirPlayPhysicalSizeMm {
        val sizeClass = CarPlayHostActivity::class.java.declaredClasses.single { it.simpleName == "DisplaySize" }
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        return CarPlayHostActivity::class.java.getDeclaredMethod("resolvePhysicalSize", sizeClass)
            .apply { isAccessible = true }.invoke(host, size) as AirPlayPhysicalSizeMm
    }

    @Test fun mediumBrowserCanvasKeepsItsPhysicalSizeAfterAFourKViewport() {
        assertEquals(AirPlayPhysicalSizeMm(300, 233), physicalSize(host(), 1184, 920))
    }

    @Test fun resizingTheBrowserOrReducingPixelsDoesNotEnlargeCarPlayControls() {
        val host = host()
        assertEquals(AirPlayPhysicalSizeMm(300, 169), physicalSize(host, 1920, 1080))
        assertEquals(AirPlayPhysicalSizeMm(300, 233), physicalSize(host, 1184, 920))
        assertEquals(AirPlayPhysicalSizeMm(300, 233), physicalSize(host, 592, 460))
    }

    @Test fun eachCarPlaySizePresetKeepsItsConfiguredWidth() {
        val host = host()
        for (preset in CarPlaySize.entries) {
            ReflectionHelpers.setField(host, "widthPhysicalMm", preset.widthMillimeters)
            assertEquals(preset.widthMillimeters, physicalSize(host, 1184, 920).widthMm)
        }
    }

    @Test fun aSavedHeightReferenceAlsoKeepsItsConfiguredLength() {
        val host = host()
        ReflectionHelpers.setField(host, "physicalSizeBasis", AirPlayPhysicalSizeBasis.HEIGHT)
        assertEquals(AirPlayPhysicalSizeMm(386, 300), physicalSize(host, 1184, 920))
    }
}
