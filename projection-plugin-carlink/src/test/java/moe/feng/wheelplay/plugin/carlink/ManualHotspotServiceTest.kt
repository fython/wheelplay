package moe.feng.wheelplay.plugin.carlink

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import com.share.connect.wifiap.IWifiAp
import com.share.connect.wifiap.WifiApObserver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ManualHotspotServiceTest.HotspotShadow::class])
class ManualHotspotServiceTest {
    @Implements(WifiManager::class)
    class HotspotShadow : ShadowWifiManager() {
        @Implementation fun getWifiApState() = 13
    }
    @Test fun publishesUserCredentialsAndCloseLeavesSystemHotspotRunning() {
        SdkResources.hotspotCredentials = CarLinkSoftAp.Credentials("User hotspot", "password123")
        SdkResources.hotspotNetwork = CarLinkSoftAp.Network("wlan1", "192.168.43.1", 0)
        val controller = Robolectric.buildService(ManualHotspotService::class.java).create()
        val service = controller.get()
        val events = mutableListOf<String>()
        val binder = service.onBind(Intent()) as IWifiAp
        val observer = object : WifiApObserver {
            override fun asBinder() = Binder()
            override fun onOpenSuccess() { events += "open" }
            override fun onOpenFailed() { fail() }
            override fun onApCreated(address: String, ssid: String, password: String, mac: String, frequency: Int) {
                assertEquals("192.168.43.1", address)
                assertEquals("User hotspot", ssid)
                assertEquals("password123", password)
                assertEquals(0, frequency)
                events += "published"
            }
            override fun onApCreateFailed() { fail() }
            override fun onConnected() { events += "transport" }
            override fun onDisconnected(userClose: Boolean) { events += "disconnected" }
            override fun onAcceptFailed() { fail() }
        }
        try {
            binder.registerWifiApObserver(observer)
            binder.open()
            binder.createSoftAp(36, "SDK-generated-name", "not-user-password", false, true)
            assertEquals(listOf("open", "published", "transport"), events)
            binder.close()
            assertEquals(true, CarLinkSoftAp.enabled(service))
            assertFalse(binder.isUserClose)
        } finally {
            controller.destroy()
            SdkResources.hotspotCredentials = null
            SdkResources.hotspotNetwork = null
        }
        assertFalse(SdkResources.services.contains(SdkResources.names[3]))
    }
}
