package moe.feng.wheelplay

import androidx.compose.ui.test.*
import com.shilapi.xcertplay.web.WebSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserDeviceManagementTest : ComposeSettingsTest() {
    @Test
    fun settingsManageBrowserNamesIndividualRemovalAndRemoveAll() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val devices = WebSession.rememberedBrowsers(activity)
            val original = devices.remember("Chrome · Android", "192.168.1.2")!!
            // Refresh after an external service update, as returning to the Activity would.
            controller.pause().resume()
            SettingsNavigation.open(activity, "浏览器设备", compose)
            clickTag("settings:devices")
            clickTag("browser:${original.device.id}")
            clickText("重命名")
            input("中控屏")
            saveInput()
            assertEquals("中控屏", devices.list().single().name)
            clickTag("browser:${original.device.id}")
            clickText("移除设备")
            assertTrue(devices.list().isEmpty())
            assertNull(devices.authenticate(original.device.id, original.secret, "client"))
            clickText("关闭")
            devices.remember("Browser A", "client")
            devices.remember("Browser B", "client")
            controller.pause().resume()
            clickTag("settings:devices")
            clickText("移除全部设备")
            clickText("确认")
            assertTrue(devices.list().isEmpty())
            clickText("关闭")
        } finally {
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }
}
