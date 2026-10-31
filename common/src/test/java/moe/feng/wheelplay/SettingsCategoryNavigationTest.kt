package moe.feng.wheelplay

import android.content.res.Configuration
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.host.R
import moe.feng.wheelplay.settings.SettingsCategory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCategoryNavigationTest : ComposeSettingsTest() {
    private fun back() = clickTag("settings:back")

    private fun scrollY() =
        compose
            .onNodeWithTag("settings:page")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
            .toInt()

    @Test
    fun indexShowsCategoriesAndOnlySelectedCategoryContainsControls() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "画面与串流", compose)
            compose.onNodeWithTag("choice:APPEARANCE").assertExists()
            compose.onNodeWithTag("choice:MICROPHONE").assertDoesNotExist()
            compose.onNodeWithTag("text:HTTP_PORT").assertDoesNotExist()
            back()
            SettingsCategory.entries.forEach {
                compose.onNodeWithTag("category:${it.name}").assertExists()
            }
            SettingsNavigation.open(activity, "音频与麦克风", compose)
            compose.onNodeWithTag("choice:AUDIO_OUTPUT").assertExists()
            compose.onNodeWithTag("choice:MICROPHONE").assertExists()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("category:DISPLAY").assertExists()
            compose.onNodeWithTag("nav:settings").assertIsSelected()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("nav:service").assertIsSelected()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun returningRestoresIndexScrollAndEachCategoryKeepsItsOwnScroll() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "画面与串流", compose)
            compose.onNodeWithTag("toggle:HEVC").performScrollTo()
            val displayOffset = scrollY()
            assertTrue(displayOffset > 0)
            back()
            compose.onNodeWithTag("category:HELP").performScrollTo()
            val indexOffset = scrollY()
            compose.onNodeWithTag("category:HELP").performClick()
            back()
            assertEquals(indexOffset, scrollY())
            SettingsNavigation.open(activity, "音频与麦克风", compose)
            assertEquals(0, scrollY())
            SettingsNavigation.open(activity, "画面与串流", compose)
            assertEquals(displayOffset, scrollY())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun categoriesKeepExistingSettingsAndUpstreamProjectLinkAccessible() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            compose.onNodeWithText("从 DiPlay APK 导入").assertExists()
            for ((category, tag) in
                listOf(
                    "iPhone 连接" to "settings:phone",
                    "画面与串流" to "choice:FPS",
                    "音频与麦克风" to "choice:MICROPHONE",
                    "车厂与定位" to "choice:LOCATION",
                    "浏览器设备" to "settings:devices",
                    "Web 服务" to "text:HTTP_PORT",
                    "Tesla 兼容（实验）" to "choice:TESLA_MODE",
                    "CarPlay 认证资源" to "settings:download",
                    "帮助与关于" to "settings:source",
                )) {
                SettingsNavigation.open(activity, category, compose)
                compose.onNodeWithTag(tag).assertExists()
            }
            back()
            compose.onNodeWithText("本项目基于 DiPlay 修改").performScrollTo().performClick()
            assertEquals(
                "https://github.com/shihabal3amri/DiPlay",
                shadowOf(activity).nextStartedActivity.dataString,
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun secondaryPageSurvivesTabsRotationAndActivityRecreation() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            SettingsNavigation.open(activity, "画面与串流", compose)
            compose.onNodeWithTag("toggle:HEVC").performScrollTo()
            val offset = scrollY()
            assertTrue(offset > 0)
            val host = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            compose.onNodeWithTag("nav:phone").performClick()
            compose.onNodeWithTag("nav:settings").performClick()

            compose.onNodeWithTag("choice:APPEARANCE").assertExists()
            assertSame(host, activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
            assertEquals(offset, scrollY())
            compose.runOnIdle {
                activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            }
            assertEquals(offset, scrollY())
            controller.recreate()
            activity = controller.get()
            compose.onNodeWithTag("choice:APPEARANCE").assertExists()
            compose.onNodeWithTag("nav:settings").assertIsSelected()
            assertEquals(offset, scrollY())
            back()
            compose.onNodeWithTag("category:AUTHENTICATION").assertExists()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun renderPagesAtPhoneNarrowLargeTextAndLandscapeSizes() {
        for ((width, height, fontScale) in
            listOf(Triple(360, 800, 1f), Triple(320, 640, 1.3f), Triple(800, 400, 1f))) {
            RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h${height}dp-mdpi")
            RuntimeEnvironment.setFontScale(fontScale)
            val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
            try {
                val activity = controller.get()
                SettingsNavigation.open(activity, "音频与麦克风", compose)
                captureSettings(activity, "audio-$width-$fontScale")
                back()
                captureSettings(activity, "index-$width-$fontScale")
                compose.onNodeWithTag("category:HELP").performScrollTo().assertIsDisplayed()
                val page = compose.onNodeWithTag("settings:page").fetchSemanticsNode().boundsInRoot
                assertTrue(page.height > 100)
            } finally {
                controller.pause().stop().destroy()
                RuntimeEnvironment.setFontScale(1f)
            }
        }
    }
}
