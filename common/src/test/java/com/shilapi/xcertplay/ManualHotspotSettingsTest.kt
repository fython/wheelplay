package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
class ManualHotspotSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val password = " password "
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun row(activity: DiPlayActivity, title: String) = views(activity.window.decorView)
        .filterIsInstance<ServerUi.PreferenceRow>().single { it.contentDescription.toString().startsWith("$title，") }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun input() = views(dialog().window!!.decorView).filterIsInstance<EditText>().single()
    private fun press(button: Int = AlertDialog.BUTTON_POSITIVE) {
        dialog().getButton(button).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private fun selectManual(activity: DiPlayActivity) {
        row(activity, "连接网络").performClick()
        val list = dialog().listView
        list.performItemClick(list.getChildAt(1), 1, 1L)
        press()
    }
    private fun enterCredentials(activity: DiPlayActivity, ssid: String) {
        selectManual(activity)
        input().setText(ssid); press()
        input().setText(password); press()
    }
    private fun withSettings(block: (DiPlayActivity) -> Unit) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = 3
            block(activity)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    private fun configureManual(ssid: String = "Original") {
        AirPlayPersistence.saveManualHotspotSsid(context, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(context, password)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
    }

    @Test fun firstSetupPreservesSsidAndPasswordWhitespaceAcrossReopening() {
        val ssid = " CarPlayTest "
        withSettings { activity ->
            enterCredentials(activity, ssid)
            assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
            assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
            assertEquals(password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        }
        withSettings { activity ->
            row(activity, "热点名称").performClick()
            assertEquals(ssid, input().text.toString())
            press(AlertDialog.BUTTON_NEGATIVE)
        }
    }

    @Test fun editingPreservesLeadingTrailingAndMultibyteSsidWhitespace() {
        configureManual()
        withSettings { activity ->
            for (ssid in listOf("CarPlayTest ", " CarPlayTest", " CarPlayTest ", " 车机热点 ", "Normal")) {
                row(activity, "热点名称").performClick()
                input().setText(ssid); press()
                assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
                row(activity, "热点名称").performClick()
                assertEquals(ssid, input().text.toString())
                press(AlertDialog.BUTTON_NEGATIVE)
            }
        }
    }

    @Test fun validationCountsWhitespaceAndUtf8BytesAndRejectsBlankAndNulNames() {
        configureManual()
        withSettings { activity ->
            val boundary = " " + "x".repeat(30) + " "
            row(activity, "热点名称").performClick()
            input().setText(boundary); press()
            assertEquals(boundary, AirPlayPersistence.loadManualHotspotSsid(context))
            for (invalid in listOf("", "   ", "x".repeat(32) + " ", "界".repeat(11), "Test\u0000SSID")) {
                row(activity, "热点名称").performClick()
                input().setText(invalid); press()
                assertEquals(boundary, AirPlayPersistence.loadManualHotspotSsid(context))
            }
        }
    }

    @Test fun invalidFirstSetupDoesNotEnableManualModeOrSaveCredentials() {
        withSettings { activity ->
            for (invalid in listOf("   ", "x".repeat(32) + " ", "Test\u0000SSID")) {
                enterCredentials(activity, invalid)
                assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
                assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
                assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(context))
            }
        }
    }

    @Test fun cancellingEitherFirstSetupDialogDoesNotSaveCredentials() {
        withSettings { activity ->
            selectManual(activity)
            input().setText(" CarPlayTest "); press(AlertDialog.BUTTON_NEGATIVE)
            assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
            selectManual(activity)
            input().setText(" CarPlayTest "); press()
            input().setText(password); press(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
            assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
            assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(context))
        }
    }

    @Test fun cancellingAnEditPreservesThePreviousSsidAndPasswordsRemainUntrimmed() {
        val ssid = " Original "
        configureManual(ssid)
        withSettings { activity ->
            row(activity, "热点名称").performClick()
            input().setText(" Changed "); press(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
            row(activity, "热点密码").performClick()
            val replacement = " new password "
            input().setText(replacement); press()
            assertEquals(replacement, AirPlayPersistence.loadManualHotspotPassphrase(context))
            assertEquals(ssid, AirPlayPersistence.loadManualHotspotSsid(context))
        }
    }
}
