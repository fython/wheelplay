package moe.feng.wheelplay

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationSourceSettingsTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun withSettings(block: (WheelPlayActivity) -> Unit) {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            SettingsNavigation.open(activity, "车厂与定位", compose)
            block(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(shadowOf(activity).lastRequestedPermission)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun edit(index: Int, save: Boolean = true) {
        clickTag("choice:LOCATION")
        chooseOption(index, save)
    }

    @Test
    fun defaultUsesIphoneAndDoesNotRequestLocationPermission() = withSettings {
        compose.onNodeWithTag("choice:LOCATION").assertTextContains("iPhone 自身定位（默认）")
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test
    fun changingSourcesPersistsAcrossScreensAndAppliesOnNextConnection() {
        withSettings {
            edit(1)
            assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        }
        withSettings {
            compose.onNodeWithTag("choice:LOCATION").assertTextContains("Android 设备定位")
            edit(0)
            assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        }
    }

    @Test
    fun cancellingSelectionKeepsPreviousLocationSource() {
        AirPlayPersistence.saveLocationReportingEnabled(context, true)
        withSettings {
            edit(0, false)
            assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
            compose.onNodeWithTag("choice:LOCATION").assertTextContains("Android 设备定位")
        }
    }
}
