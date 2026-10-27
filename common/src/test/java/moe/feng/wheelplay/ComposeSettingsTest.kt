package moe.feng.wheelplay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import java.io.File
import org.junit.Rule

/**
 * Every settings interaction goes through the rendered Compose UI. Text-input dialog tests use the
 * default display width because custom width qualifiers trigger Robolectric's dialog measurement
 * loop: https://github.com/robolectric/robolectric/issues/8460. Responsive screenshot coverage
 * lives separately in SettingsCategoryNavigationTest.
 */
open class ComposeSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    protected fun clickTag(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "settings:back") node.performScrollTo()
        node.performClick()
    }

    protected fun clickText(text: String) {
        compose.onNodeWithText(text).performClick()
    }

    protected fun chooseOption(index: Int, save: Boolean = true) {
        clickTag("option:$index")
        compose.onNodeWithText(if (save) "保存" else "取消").performClick()
    }

    protected fun input(value: String) {
        compose.onNodeWithTag("settings:text-input").performTextReplacement(value)
    }

    protected fun saveInput() {
        compose.onNodeWithText("保存").performClick()
    }

    protected fun cancelInput() {
        compose.onNodeWithText("取消").performClick()
    }

    protected fun captureSettings(activity: WheelPlayActivity, name: String) {
        compose.waitForIdle()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = activity.resources.displayMetrics
        val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
        val height = (activity.resources.configuration.screenHeightDp * metrics.density).toInt()
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                width,
                android.view.View.MeasureSpec.EXACTLY,
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                height,
                android.view.View.MeasureSpec.EXACTLY,
            ),
        )
        root.layout(0, 0, width, height)
        compose.waitForIdle()
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        root.draw(android.graphics.Canvas(bitmap))
        val output = File("build/reports/compose-settings/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
