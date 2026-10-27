package moe.feng.wheelplay

import androidx.compose.ui.test.*
import com.shilapi.xcertplay.web.WebSession
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.net.Socket
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerCardStateTest : ComposeSettingsTest() {
    @Before fun reset() {
        CarPlayBackgroundSession.clear(); WebSession.stop()
        val identity = SyntheticMfiIdentity.create()
        val app = RuntimeEnvironment.getApplication()
        MfiAssetStore(app.noBackupFilesDir).installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
    }
    @After fun cleanup() { CarPlayBackgroundSession.clear(); WebSession.stop() }
    private fun refresh() { shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); compose.waitForIdle() }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(predicate())
    }
    @Test fun pairingControlsDisappearForAuthenticatedViewerAndReturnAfterDisconnect() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            WebSession.start(controller.get()); refresh()
            compose.onNodeWithTag("action:SCAN").assertExists()
            Socket("127.0.0.1", 8080).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(("GET /stream?code=${WebSession.code} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:8080\r\nOrigin: http://127.0.0.1:8080\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) { val next = socket.getInputStream().read(); check(next >= 0); headers.append(next.toChar()) }
                assertTrue(headers.startsWith("HTTP/1.1 101")); waitUntil { WebSession.hasViewer }; refresh()
                compose.onNodeWithTag("action:SCAN").assertDoesNotExist(); compose.onNodeWithText(WebSession.code).assertDoesNotExist()
                compose.onNodeWithText("车机浏览器已连接").assertExists()
            }
            waitUntil { !WebSession.hasViewer }; refresh()
            compose.onNodeWithTag("action:SCAN").assertExists(); compose.onNodeWithText(WebSession.code).assertExists()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun selectedPhoneCanConnectAndRunningSessionsShowStopInsteadOfConnect() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            compose.onNodeWithTag("action:HOME_CONNECT").assertDoesNotExist()
            WheelPlayPreferences.savePhone(activity, "00:11:22:33:44:55", "测试 iPhone")
            AirPlayPersistence.saveWirelessHotspotMode(activity, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P); refresh()
            compose.onNodeWithTag("action:HOME_CONNECT").assertIsEnabled().performScrollTo().performClick()
            assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { completion: () -> Unit -> CarPlayBackgroundSession.clear(); completion() })
            refresh(); compose.onNodeWithTag("action:HOME_CONNECT").assertDoesNotExist(); compose.onNodeWithTag("action:HOME_STOP").assertExists()
            compose.onNodeWithTag("nav:phone").performClick(); compose.onNodeWithTag("source-carplay").performScrollTo().performClick()
            compose.onNodeWithTag("action:CARPLAY_CONNECT").assertDoesNotExist()
            compose.onNodeWithTag("action:CARPLAY_STOP").performScrollTo().performClick(); refresh()
            compose.onNodeWithTag("action:CARPLAY_CONNECT").assertExists(); compose.onNodeWithTag("action:CARPLAY_STOP").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
}
