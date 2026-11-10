package moe.feng.wheelplay

import android.view.ViewGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import com.shilapi.xcertplay.web.WebSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerNavigationTest : ComposeSettingsTest() {
    @Before fun provision() {
        val context = RuntimeEnvironment.getApplication()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        WheelPlayPreferences.saveAutoConnect(context, false)
    }
    @Test fun tabChangesKeepOneComposeHostAndNeverStartAnActivity() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0)
            repeat(3) { for (page in listOf("phone", "settings", "service")) {
                compose.onNodeWithTag("nav:$page").performClick().assertIsSelected()
                assertEquals(1, content.childCount); assertSame(root, content.getChildAt(0))
                assertNull(shadowOf(activity).nextStartedActivity)
            } }
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
    @Test fun settingsRetainTheirSubpageAndSavedValueAcrossTabs() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "iPhone 连接", compose)
            val enabled = !WheelPlayPreferences.autoConnect(activity)
            clickTag("toggle:AUTO_CONNECT"); assertEquals(enabled, WheelPlayPreferences.autoConnect(activity))
            compose.onNodeWithTag("nav:phone").performClick(); compose.onNodeWithTag("nav:settings").performClick()
            compose.onNodeWithTag("toggle:AUTO_CONNECT").assertExists()
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
    @Test fun pageScrollSurvivesTabReselectionSwitchingAndRecreation() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            compose.onNodeWithTag("action:STOP_SERVICE").performScrollTo()
            fun scroll() = compose.onNodeWithTag("page:service").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val offset = scroll(); assertTrue(offset > 0)
            compose.onNodeWithTag("nav:service").performClick(); assertEquals(offset, scroll())
            compose.onNodeWithTag("nav:phone").performClick(); compose.onNodeWithTag("nav:service").performClick()
            assertEquals(offset, scroll())
            controller.recreate(); assertEquals(offset, scroll())
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
    @Test fun composeNavigationAndPagesRenderInNarrowAndLandscapeWindows() {
        try {
            for ((width, height) in listOf(320 to 640, 800 to 400)) {
                RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h${height}dp-mdpi")
                val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
                try {
                    for (page in listOf("service", "phone", "settings")) {
                        compose.onNodeWithTag("nav:$page").performClick()
                        captureSettings(controller.get(), "shell-$page-$width")
                        listOf("service", "phone", "settings").forEach { compose.onNodeWithTag("nav:$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
                    }
                } finally { controller.pause().stop().destroy(); WebSession.stop() }
            }
        } finally { RuntimeEnvironment.setQualifiers("zh-rCN-w360dp-h800dp-mdpi") }
    }
}
