package moe.feng.wheelplay

import android.Manifest
import android.content.Intent
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HostWirelessSettingsTest : ComposeSettingsTest() {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles(
            { identity.key.inputStream() }, { identity.certificate.inputStream() })
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveManualHotspotSsid(context, " CarPlay hotspot ")
        AirPlayPersistence.saveManualHotspotPassphrase(context, " password ")
        AirPlayPersistence.saveManualHotspotBand(context, ManualHotspotBand.GHZ_5)
        AirPlayPersistence.saveManualHotspotChannel(context, 149)
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotSecurity.WPA2)
        // Keep startup waiting for microphone permission: no real radio or decoder is started.
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After fun cleanup() { CarPlayBackgroundSession.clear() }

    private fun config(host: CarPlayHostActivity): CarPlayRuntimeConfig =
        ReflectionHelpers.callInstanceMethod(host, "createRuntimeConfig")

    @Test fun reusedHostSwitchesFromDirectToDeviceHotspotWhileStationWifiIsOff() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        try {
            val host = activity.get()
            assertFalse(host.isFinishing)
            assertEquals(WirelessHotspotMode.WIFI_P2P, config(host).wirelessHotspotMode)
            activity.pause()
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
            context.getSystemService(WifiManager::class.java).isWifiEnabled = false
            activity.newIntent(Intent(context, CarPlayHostActivity::class.java)).resume()
            val current = config(host)
            assertEquals(CarPlayTransport.WIRELESS, current.transport)
            assertEquals(WirelessHotspotMode.MANUAL, current.wirelessHotspotMode)
            assertEquals(" CarPlay hotspot ", current.manualHotspotSsid)
            assertEquals(" password ", current.manualHotspotPassphrase)
            assertEquals(ManualHotspotBand.GHZ_5, current.manualHotspotBand)
            assertEquals(149, current.manualHotspotChannel)
            assertEquals(ManualHotspotSecurity.WPA2, current.manualHotspotSecurity)
            assertFalse(context.getSystemService(WifiManager::class.java).isWifiEnabled)
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun switchingAnIdleWiredHostToWirelessRequestsTheNewTransportPermissions() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        try {
            val host = activity.get()
            assertEquals(CarPlayTransport.WIRED, config(host).transport)
            activity.pause()
            AirPlayPersistence.saveWirelessEnabled(context, true)
            AirPlayPersistence.saveMicrophoneSource(context, CarPlayMicrophoneSource.DISABLED)
            activity.newIntent(Intent(context, CarPlayHostActivity::class.java)).resume()
            assertEquals(CarPlayTransport.WIRELESS, config(host).transport)
            val permissions = shadowOf(host).lastRequestedPermission.requestedPermissions.toSet()
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), permissions)
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun reusedHostRefreshesEditedHotspotCredentialsAndTheReverseModeSwitch() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        try {
            val host = activity.get()
            assertEquals(" CarPlay hotspot ", config(host).manualHotspotSsid)
            activity.pause()
            AirPlayPersistence.saveManualHotspotSsid(context, "New hotspot")
            AirPlayPersistence.saveManualHotspotPassphrase(context, "new-password")
            activity.newIntent(Intent(context, CarPlayHostActivity::class.java)).resume()
            assertEquals("New hotspot", config(host).manualHotspotSsid)
            assertEquals("new-password", config(host).manualHotspotPassphrase)
            activity.pause()
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
            activity.newIntent(Intent(context, CarPlayHostActivity::class.java)).resume()
            assertEquals(WirelessHotspotMode.WIFI_P2P, config(host).wirelessHotspotMode)
        } finally { activity.pause().stop().destroy() }
    }
}
