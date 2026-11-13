package moe.feng.wheelplay.plugin.carlink

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLinkSoftApTest {
    @Test fun rejectsInvalidCredentialsWithoutExposingPasswords() {
        assertNull(CarLinkSoftAp.Credentials("My hotspot", "password123").problem())
        assertNotNull(CarLinkSoftAp.Credentials("a".repeat(8), "password123").problem())
        assertNull(CarLinkSoftAp.Credentials("a".repeat(9), "password123").problem())
        assertNull(CarLinkSoftAp.Credentials("a".repeat(32), "password123").problem())
        assertNotNull(CarLinkSoftAp.Credentials("a".repeat(33), "password123").problem())
        assertNotNull(CarLinkSoftAp.Credentials(" ", "password123").problem())
        assertNotNull(CarLinkSoftAp.Credentials("热".repeat(11), "password123").problem())
        assertNotNull(CarLinkSoftAp.Credentials("My hotspot", "short").problem())
        assertNotNull(CarLinkSoftAp.Credentials("My hotspot", "pass\nword123").problem())
        assertFalse(CarLinkSoftAp.Credentials("My hotspot", "secret-password").toString().contains("secret-password"))
    }
    @Test fun savesCredentialsAtomicallyAndPreservesThemAcrossOtherSettings() {
        val controller = Robolectric.buildContentProvider(CarLinkSettingsProvider::class.java).create()
        val provider = controller.get()
        try {
            provider.call("save", null, Bundle().apply {
                putString("hotspotSsid", "My hotspot"); putString("hotspotPassword", "password123")
            })
            provider.call("save", null, Bundle().apply { putString("connection", "softap"); putInt("dpi", 240) })
            val saved = provider.call("config", null, null)
            assertEquals("My hotspot", saved.getString("hotspotSsid"))
            assertEquals("password123", saved.getString("hotspotPassword"))
            assertEquals(240, saved.getInt("dpi"))
            assertThrows(IllegalArgumentException::class.java) {
                provider.call("save", null, Bundle().apply {
                    putString("hotspotSsid", "Changed"); putString("hotspotPassword", "bad")
                })
            }
            assertEquals("My hotspot", provider.call("config", null, null).getString("hotspotSsid"))
            assertEquals("password123", provider.call("config", null, null).getString("hotspotPassword"))
            assertFalse(provider.call("status", null, null).containsKey("hotspotPassword"))
        } finally { controller.shutdown() }
    }
    @Test fun selectsHotspotWithoutMistakingWifiUplinkOrP2pForIt() {
        val uplink = CarLinkSoftAp.Candidate("wlan0", "192.168.1.2")
        val ap = CarLinkSoftAp.Candidate("wlan1", "192.168.43.1")
        val p2p = CarLinkSoftAp.Candidate("p2p0", "192.168.49.1")
        val ethernet = CarLinkSoftAp.Candidate("eth0", "192.168.2.2")
        assertSame(ap, CarLinkSoftAp.select(listOf(uplink, ap, p2p, ethernet), setOf("wlan0"), null))
        assertNull(CarLinkSoftAp.select(listOf(uplink, p2p, ethernet), setOf("wlan0"), null))
        assertNull(CarLinkSoftAp.select(listOf(uplink, ap), emptySet(), null))
        assertSame(ap, CarLinkSoftAp.select(listOf(uplink, ap), emptySet(), setOf("wlan1")))
    }
}
