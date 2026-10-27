package moe.feng.wheelplay

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.web.WebSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CarPlayExperienceSettingsTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    private fun row(activity: WheelPlayActivity, title: String): SemanticsNodeInteraction {
        val (category, tag) =
            when (title) {
                "音频输出" -> "音频与麦克风" to "choice:AUDIO_OUTPUT"
                "麦克风来源" -> "音频与麦克风" to "choice:MICROPHONE"
                "浏览器离线后的处理" -> "浏览器设备" to "choice:OFFLINE"
                "画面安全区域" -> "画面与串流" to "settings:safe-area"
                else -> "画面与串流" to "choice:APPEARANCE"
            }
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
            SettingsNavigation.open(activity, category, compose)
        return compose.onNodeWithTag(tag)
    }

    private fun openSettings(activity: WheelPlayActivity) {
        SettingsNavigation.open(activity, "画面与串流", compose)
    }

    private fun choose(activity: WheelPlayActivity, title: String, index: Int, save: Boolean = true) {
        row(activity, title).performScrollTo().performClick()
        chooseOption(index, save)
    }

    @Test
    fun hostBlockedByMissingAuthenticationDoesNotRequestMicrophonePermission() {
        AirPlayPersistence.saveMicrophoneSource(context, CarPlayMicrophoneSource.BROWSER)
        val controller =
            Robolectric.buildActivity(
                    CarPlayHostActivity::class.java,
                    android.content.Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"),
                )
                .setup()
        val host = controller.get()
        assertTrue(host.isFinishing)
        assertNull(shadowOf(host).lastRequestedPermission)
        controller.pause().stop().destroy()
    }

    @Test
    fun defaultsPreserveExistingBehaviorAndLegacyBrowserMicrophoneMigrates() {
        assertEquals(CarPlayAppearance.SYSTEM, AirPlayPersistence.loadAppearance(context))
        assertEquals(
            BrowserOfflinePolicy.KEEP,
            AirPlayPersistence.loadBrowserOfflinePolicy(context),
        )
        assertNull(AirPlayPersistence.loadSafeMargins(context))
        assertEquals(
            CarPlayMicrophoneSource.ANDROID,
            AirPlayPersistence.loadMicrophoneSource(context),
        )
        context
            .getSharedPreferences("xcertplay_airplay", 0)
            .edit()
            .putBoolean("browser_microphone", true)
            .commit()
        assertEquals(
            CarPlayMicrophoneSource.BROWSER,
            AirPlayPersistence.loadMicrophoneSource(context),
        )
        WebSession.setMicrophoneSource(context, CarPlayMicrophoneSource.DISABLED)
        WebSession.setMediaBufferMillis(context, 1000)
        WebSession.setBrowserAudioPlayback(context, true)
        assertEquals(
            CarPlayMicrophoneSource.DISABLED,
            AirPlayPersistence.loadMicrophoneSource(context),
        )
        WebSession.audio.createRoute().use { assertFalse(it.microphoneEnabled) }
        assertFalse(AirPlayPersistence.loadBrowserMicrophone(context))
        WebSession.setMicrophoneSource(context, CarPlayMicrophoneSource.ANDROID)
    }

    @Test
    fun sourceAndAppearanceChoicesSaveAndCancellationDoesNotOverwriteThem() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        openSettings(activity)
        choose(activity, "CarPlay 昼夜模式", 2)
        assertEquals(CarPlayAppearance.NIGHT, AirPlayPersistence.loadAppearance(context))
        choose(activity, "CarPlay 昼夜模式", 1, save = false)
        assertEquals(CarPlayAppearance.NIGHT, AirPlayPersistence.loadAppearance(context))
        choose(activity, "音频输出", 1)
        assertTrue(AirPlayPersistence.loadBrowserAudioPlayback(context))
        choose(activity, "麦克风来源", 2)
        assertEquals(
            CarPlayMicrophoneSource.DISABLED,
            AirPlayPersistence.loadMicrophoneSource(context),
        )
        choose(activity, "浏览器离线后的处理", 2)
        assertEquals(
            BrowserOfflinePolicy.FIVE_MINUTES,
            AirPlayPersistence.loadBrowserOfflinePolicy(context),
        )
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
        val next = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        openSettings(next.get())
        row(next.get(), "CarPlay 昼夜模式").assertTextContains("始终夜间")
        row(next.get(), "麦克风来源").assertTextContains("禁用麦克风")
        next.pause().stop().destroy()
        WebSession.setMicrophoneSource(context, CarPlayMicrophoneSource.ANDROID)
    }

    @Test
    fun safeMarginsRetainLegacyMappingUntilEditedAndScaleAcrossResolutions() {
        val legacy = SafeAreaRect(100, 50, 1100, 650)
        AirPlayPersistence.saveSafeAreaRect(context, 1280, 720, legacy)
        assertEquals(legacy, AirPlayPersistence.loadSafeAreaRect(context, 1280, 720))
        AirPlayPersistence.saveSafeMargins(context, CarPlaySafeMargins(10, 20, 5, 15))
        assertEquals(
            SafeAreaRect(100, 100, 1700, 800),
            AirPlayPersistence.loadSafeAreaRect(context, 2000, 1000),
        )
        assertEquals(
            SafeAreaRect(50, 50, 850, 400),
            AirPlayPersistence.loadSafeAreaRect(context, 1000, 500),
        )
        assertNotNull(CarPlaySafeMargins(40, 40, 40, 40).rectangle(1, 1))
        assertNull(CarPlaySafeMargins.decode("50,0,0,0"))
        AirPlayPersistence.saveSafeMargins(context, CarPlaySafeMargins())
        assertNull(AirPlayPersistence.loadSafeAreaRect(context, 1280, 720))
    }

    @Test
    fun safeAreaEditorRejectsInvalidInputAndRestoresFullScreen() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            clickTag("settings:safe-area")
            compose.onNodeWithText("上边留白").performTextReplacement("41")
            saveInput()
            compose.onNodeWithText("每边留白须为 0–40% 的整数").assertExists()
            assertNull(AirPlayPersistence.loadSafeMargins(context))
            for ((label, value) in
                listOf("上边留白" to "10", "下边留白" to "20", "左边留白" to "5", "右边留白" to "15")) {
                compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
            }
            saveInput()
            assertEquals(
                CarPlaySafeMargins(10, 20, 5, 15),
                AirPlayPersistence.loadSafeMargins(context),
            )
            clickTag("settings:safe-area")
            clickText("恢复全屏")
            assertEquals(CarPlaySafeMargins(), AirPlayPersistence.loadSafeMargins(context))
            row(activity, "画面安全区域").assertTextContains("全屏")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun phoneReceivesSafeAreaAndNoMicrophoneFormatsWhenDisabled() {
        AirPlayPersistence.saveMicrophoneSource(context, CarPlayMicrophoneSource.DISABLED)
        AirPlayPersistence.saveSafeMargins(context, CarPlaySafeMargins(10, 20, 5, 15))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val type = CarPlayHostActivity::class.java
        type.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        type
            .getDeclaredField("airPlayIdentity")
            .apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(context))
        val sizeType = type.declaredClasses.single { it.simpleName == "DisplaySize" }
        val size =
            sizeType
                .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(1280, 720)
        val config =
            type
                .getDeclaredMethod("createAirPlayConfig", sizeType)
                .apply { isAccessible = true }
                .invoke(host, size) as AirPlayConfig
        assertFalse(config.microphone)
        assertEquals(false, config.main.safeAreaDrawOutside)
        assertTrue(config.main.safeArea!!.left > 0)
        val info = AirPlayInfoPlist.build(config)
        val formats = info["audioFormats"] as List<*>
        assertTrue(formats.all { !(it as Map<*, *>).containsKey("audioInputFormats") })
        val display = (info["displays"] as List<*>).first() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).first() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(false, safe["drawUIOutsideSafeArea"])
        assertTrue((safe["widthPixels"] as Int) < config.main.widthPixels)
    }
}
