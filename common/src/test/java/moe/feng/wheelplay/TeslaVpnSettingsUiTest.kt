package moe.feng.wheelplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.network.TeslaHttpMode
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.TeslaHttpCompatibility
import com.shilapi.xcertplay.web.WebSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    qualifiers = "zh-rCN-w360dp-h800dp",
    shadows = [TeslaVpnSettingsUiTest.ConsentShadow::class],
)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TeslaVpnSettingsUiTest : ComposeSettingsTest() {
    @Implements(VpnService::class)
    class ConsentShadow : org.robolectric.shadows.ShadowService() {
        companion object {
            @JvmStatic
            @Implementation
            fun prepare(context: Context): Intent = Intent("wheelplay.test.VPN_CONSENT")
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    private fun all(activity: WheelPlayActivity) = views(activity.window.decorView)

    private fun openSettings(activity: WheelPlayActivity) {
        SettingsNavigation.open(activity, "Tesla 兼容（实验）", compose)
    }

    private fun changeMode(index: Int) {
        clickTag("choice:TESLA_MODE")
        chooseOption(index)
    }

    private fun enable() {
        clickTag("toggle:TESLA")
    }

    @Test
    fun nonRootUserCanSelectVpnAndDeniedConsentKeepsItDisabled() {
        val original = RootAccess.authorize
        var rootRequests = 0
        RootAccess.startupChecked = false
        RootAccess.authorize = {
            rootRequests++
            throw java.io.IOException("No root")
        }
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            assertFalse(TeslaHttpCompatibility.config(activity).enabled)
            changeMode(1)
            assertEquals(TeslaHttpMode.VPN, TeslaHttpCompatibility.config(activity).mode)
            compose.onNodeWithText("请求 Root 权限").assertDoesNotExist()
            enable()
            val consent = shadowOf(activity).nextStartedActivity
            assertEquals("wheelplay.test.VPN_CONSENT", consent.action)
            assertFalse(TeslaHttpCompatibility.config(activity).enabled)
            shadowOf(activity).receiveResult(consent, Activity.RESULT_CANCELED, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(TeslaHttpCompatibility.config(activity).enabled)
            assertEquals(0, rootRequests)
        } finally {
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun acceptedConsentEnablesVpnAndSwitchingModesTurnsItOff() {
        val context = RuntimeEnvironment.getApplication()
        TeslaHttpCompatibility.save(context, TeslaHttpConfig(mode = TeslaHttpMode.VPN))
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            enable()
            val consent = shadowOf(activity).nextStartedActivity
            shadowOf(activity).receiveResult(consent, Activity.RESULT_OK, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(TeslaHttpCompatibility.config(activity).enabled)
            changeMode(0)
            assertEquals(TeslaHttpMode.ROOT, TeslaHttpCompatibility.config(activity).mode)
            assertFalse(TeslaHttpCompatibility.config(activity).enabled)
        } finally {
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }
}
