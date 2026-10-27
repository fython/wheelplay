package moe.feng.wheelplay

import androidx.compose.ui.test.*
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualHotspotSettingsTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val password = " password "

    private fun configureManual(ssid: String = "Original") {
        AirPlayPersistence.saveManualHotspotSsid(context, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(context, password)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
    }

    private fun withSettings(block: () -> Unit) {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            SettingsNavigation.open(controller.get(), "iPhone 连接", compose)
            block()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun selectManual() {
        clickTag("choice:WIRELESS")
        chooseOption(1)
    }

    private fun credentials(ssid: String) {
        compose.onNodeWithText("热点名称").performTextReplacement(ssid)
        compose.onNodeWithText("热点密码").performTextReplacement(password)
    }

    @Test
    fun switchingToDeviceHotspotStopsThePreviousSessionBeforeCheckingHotspotState() {
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        AirPlayPersistence.saveManualHotspotSsid(context, "CarPlay hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, password)
        var stopped = false
        var completeStop: (() -> Unit)? = null
        try {
            withSettings {
                ReflectionHelpers.setField(
                    CarPlayBackgroundSession,
                    "stopAction",
                    { completion: () -> Unit ->
                        stopped = true
                        completeStop = completion
                    },
                )
                selectManual()
                assertTrue(
                    "The old P2P session must release the radio before asking to enable the device hotspot",
                    stopped,
                )
                assertTrue(CarPlayBackgroundSession.hasSession())
                compose.onNodeWithText(context.getString(com.shilapi.xcertplay.shared.R.string.ui_hotspot_is_off)).assertDoesNotExist()
                compose.runOnIdle {
                    CarPlayBackgroundSession.clear()
                    completeStop!!.invoke()
                }
                compose.waitForIdle()
                assertFalse(CarPlayBackgroundSession.hasSession())
                compose.onNodeWithText(context.getString(com.shilapi.xcertplay.shared.R.string.ui_hotspot_is_off)).assertExists()
                assertEquals(
                    WirelessHotspotMode.MANUAL,
                    AirPlayPersistence.loadWirelessHotspotMode(context),
                )
            }
        } finally {
            CarPlayBackgroundSession.clear()
        }
    }

    @Test
    fun firstSetupPreservesSsidAndPasswordWhitespaceAcrossReopening() {
        val ssid = " CarPlayTest "
        withSettings {
            selectManual()
            credentials(ssid)
            saveInput()
            assertEquals(
                WirelessHotspotMode.MANUAL,
                AirPlayPersistence.loadWirelessHotspotMode(context),
            )
            assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
            assertEquals(password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        }
        withSettings {
            clickTag("text:SSID")
            compose.onNodeWithTag("settings:text-input").assertTextContains(ssid)
            cancelInput()
        }
    }

    @Test
    fun editingPreservesLeadingTrailingAndMultibyteSsidWhitespace() {
        configureManual()
        withSettings {
            for (ssid in
                listOf("CarPlayTest ", " CarPlayTest", " CarPlayTest ", " 车机热点 ", "Normal")) {
                clickTag("text:SSID")
                input(ssid)
                saveInput()
                assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
            }
        }
    }

    @Test
    fun validationCountsWhitespaceAndUtf8BytesAndRejectsBlankAndNulNames() {
        configureManual()
        withSettings {
            val boundary = " " + "x".repeat(30) + " "
            clickTag("text:SSID")
            input(boundary)
            saveInput()
            for (invalid in
                listOf("", "   ", "x".repeat(32) + " ", "界".repeat(11), "Test\u0000SSID")) {
                clickTag("text:SSID")
                input(invalid)
                saveInput()
                assertEquals(boundary, AirPlayPersistence.loadManualHotspotSsid(context))
                compose.onNodeWithTag("settings:text-input").assertExists()
                cancelInput()
            }
        }
    }

    @Test
    fun invalidFirstSetupDoesNotEnableManualModeOrSaveCredentials() = withSettings {
        for (invalid in listOf("   ", "x".repeat(32) + " ", "Test\u0000SSID")) {
            selectManual()
            credentials(invalid)
            saveInput()
            assertEquals(
                WirelessHotspotMode.WIFI_P2P,
                AirPlayPersistence.loadWirelessHotspotMode(context),
            )
            assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
            assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(context))
            cancelInput()
        }
    }

    @Test
    fun cancellingFirstSetupDoesNotSaveEitherCredential() = withSettings {
        selectManual()
        credentials(" CarPlayTest ")
        cancelInput()
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            AirPlayPersistence.loadWirelessHotspotMode(context),
        )
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test
    fun cancellingAnEditPreservesThePreviousSsidAndPasswordsRemainUntrimmed() {
        configureManual(" Original ")
        withSettings {
            clickTag("text:SSID")
            input(" Changed ")
            cancelInput()
            assertEquals(" Original ", AirPlayPersistence.loadManualHotspotSsid(context))
            clickTag("text:PASSWORD")
            input(" new password ")
            saveInput()
            assertEquals(" new password ", AirPlayPersistence.loadManualHotspotPassphrase(context))
        }
    }
}
