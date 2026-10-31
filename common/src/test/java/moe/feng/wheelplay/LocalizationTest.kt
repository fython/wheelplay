package moe.feng.wheelplay

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.i18n.UiException
import com.shilapi.xcertplay.i18n.UiText
import com.shilapi.xcertplay.i18n.userMessage
import com.shilapi.xcertplay.network.TeslaHttpConfig
import moe.feng.wheelplay.settings.ChoiceSetting
import com.shilapi.xcertplay.shared.R as UiR
import com.shilapi.xcertplay.web.BrowserStrings
import com.shilapi.xcertplay.web.WebSession
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalizationTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun localized(tag: String): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
        )

    @Test
    fun systemLocaleSelectsResourcesAndUnsupportedLocalesFallBackToEnglish() {
        assertEquals("Settings", localized("en-US").getString(R.string.settings_title))
        assertEquals("设置", localized("zh-CN").getString(R.string.settings_title))
        assertEquals("設定", localized("zh-TW").getString(R.string.settings_title))
        assertEquals("設定", localized("zh-HK").getString(R.string.settings_title))
        assertEquals("Settings", localized("fr-FR").getString(R.string.settings_title))
        assertEquals("Audio & microphone", localized("en").getString(R.string.settings_audio))
        assertEquals("音訊與麥克風", localized("zh-TW").getString(R.string.settings_audio))
    }

    @Test
    fun mdnsLabelsAndValidationFollowSystemResources() {
        for ((tag, title, invalid) in
            listOf(
                Triple(
                    "en-US",
                    "Local mDNS hostname",
                    "Enter a single-label .local hostname, such as wheelplay.local",
                ),
                Triple("zh-CN", "局域网 mDNS 域名", "请输入单级 .local 域名，例如 wheelplay.local"),
                Triple("zh-TW", "區域網路 mDNS 主機名稱", "請輸入單一標籤的 .local 主機名稱，例如 wheelplay.local"),
            )) {
            val owner = localized(tag)
            assertEquals(title, owner.getString(R.string.settings_mdns_hostname))
            val failure =
                runCatching {
                        com.shilapi.xcertplay.web.WebListenSettings.saveMdnsHostname(
                            owner,
                            "invalid.example",
                        )
                    }
                    .exceptionOrNull()!!
            assertEquals(invalid, failure.userMessage(owner))
        }
    }

    @Test
    fun validationAndSessionStateResolveUsingTheCurrentContext() {
        val failure = runCatching { TeslaHttpConfig(address = "invalid") }.exceptionOrNull()!!
        assertTrue(failure is UiException)
        assertEquals(
            "Enter a unicast IPv4 address accessible through the hotspot",
            failure.userMessage(localized("en-US")),
        )
        assertEquals("请输入可供热点访问的 IPv4 单播地址", failure.userMessage(localized("zh-CN")))
        assertEquals("請輸入可供熱點存取的 IPv4 單播地址", failure.userMessage(localized("zh-TW")))
        WebSession.setStage(UiText(UiR.string.ui_iphone_disconnected))
        // Verify the localized detail, independently of a retained runtime failure that
        // intentionally takes precedence in stage() after another session test.
        assertEquals("iPhone disconnected", WebSession.stageDetail(localized("en-US")))
        assertEquals("iPhone 已中斷連線", WebSession.stageDetail(localized("zh-TW")))
    }

    @Test
    fun browserTemplatesAndMessagesFollowAndroidResources() {
        val template = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        for ((tag, expected) in
            listOf(
                "en-US" to "Connect your CarPlay display",
                "zh-CN" to "连接 CarPlay 车机屏幕",
                "zh-TW" to "連線 CarPlay 車機螢幕",
            )) {
            val owner = localized(tag)
            val html = BrowserStrings.render(owner, template)
            assertTrue(html.contains(expected))
            assertFalse(html.contains("{{"))
            assertEquals(
                owner.getString(UiR.string.web_start_display),
                BrowserStrings.messages(owner).getString("web_start_display"),
            )
        }
    }

    private fun settings(expectedTitle: String, audioTitle: String, output: String) {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            compose.onNodeWithTag("nav:settings").performClick()
            compose.waitForIdle()
            // Initial authentication setup may open its category; return using its stable semantic
            // ID.
            if (compose.onAllNodesWithTag("settings:back").fetchSemanticsNodes().isNotEmpty())
                clickTag("settings:back")
            compose.onNode(hasText(expectedTitle) and !hasTestTag("nav:settings")).assertExists()
            clickTag("category:AUDIO")
            compose.onNodeWithText(audioTitle).assertExists()
            clickTag("choice:${ChoiceSetting.AUDIO_OUTPUT}")
            compose.onNodeWithText(output).assertExists()
            compose.onNodeWithText(activity.getString(R.string.settings_cancel)).performClick()
            captureSettings(
                activity,
                "locale-" + activity.resources.configuration.locales[0].toLanguageTag(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun englishSettingsUseLocalizedRowsAndOptions() =
        settings("Settings", "Audio & microphone", "Head-unit browser")

    @Test
    @Config(qualifiers = "zh-rCN-mdpi")
    fun simplifiedChineseSettingsUseLocalizedRowsAndOptions() = settings("设置", "音频与麦克风", "车机浏览器")

    @Test
    @Config(qualifiers = "zh-rTW-mdpi")
    fun traditionalChineseSettingsUseLocalizedRowsAndOptions() = settings("設定", "音訊與麥克風", "車機瀏覽器")
}
