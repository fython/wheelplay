package moe.feng.wheelplay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.SavedStateHandle
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.settings.SettingsViewModel
import moe.feng.wheelplay.settings.safeAreaFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafeAreaEditorTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun open(activity: WheelPlayActivity) {
        SettingsNavigation.open(activity, "画面与串流", compose)
        clickTag("settings:safe-area")
    }

    @Test
    fun draggingAllEdgesUpdatesFieldsAndSavesOnlyOnConfirmation() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            open(controller.get())
            val preview = compose.onNodeWithTag("safe-area:preview")
            preview.performScrollTo()
            fun drag(edge: Int) {
                preview.performTouchInput {
                    val frame =
                        safeAreaFrame(Size(width.toFloat(), height.toFloat()), 16f / 9f, 24f)
                    val (start, end) =
                        when (edge) {
                            0 ->
                                Offset(frame.center.x, frame.top) to
                                    Offset(frame.center.x, frame.top + frame.height * .2f)
                            1 ->
                                Offset(frame.center.x, frame.bottom) to
                                    Offset(frame.center.x, frame.bottom - frame.height * .2f)
                            2 ->
                                Offset(frame.left, frame.center.y) to
                                    Offset(frame.left + frame.width * .2f, frame.center.y)
                            else ->
                                Offset(frame.right, frame.center.y) to
                                    Offset(frame.right - frame.width * .2f, frame.center.y)
                        }
                    swipe(start, end, 500)
                }
            }
            repeat(4, ::drag)
            assertNull(AirPlayPersistence.loadSafeMargins(context))
            saveInput()
            val saved = requireNotNull(AirPlayPersistence.loadSafeMargins(context))
            for (value in listOf(saved.top, saved.bottom, saved.left, saved.right)) {
                assertTrue("Dragged margin was $value", value in 19..21)
            }
            // The same percentages scale with the negotiated viewport, not the phone preview size.
            assertEquals(
                saved.rectangle(2000, 1000),
                AirPlayPersistence.loadSafeAreaRect(context, 2000, 1000),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun resetAndNumericDraftSurviveRecreationButCancelPreservesSavedMargins() {
        val saved = CarPlaySafeMargins(5, 10, 15, 20)
        AirPlayPersistence.saveSafeMargins(context, saved)
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            open(controller.get())
            compose.onNodeWithText("上边留白").performScrollTo().performTextReplacement("12")
            controller.recreate()
            compose.onNodeWithText("上边留白").assertTextContains("12")
            clickText("取消")
            assertEquals(saved, AirPlayPersistence.loadSafeMargins(context))
            clickTag("settings:safe-area")
            compose.onNodeWithText("恢复全屏").performScrollTo().performClick()
            assertEquals(saved, AirPlayPersistence.loadSafeMargins(context))
            clickText("取消")
            assertEquals(saved, AirPlayPersistence.loadSafeMargins(context))
            clickTag("settings:safe-area")
            compose.onNodeWithText("恢复全屏").performScrollTo().performClick()
            saveInput()
            assertEquals(CarPlaySafeMargins(), AirPlayPersistence.loadSafeMargins(context))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun accessibilityAdjustmentsRespectLimitsAndInvalidInputIsNotOverwritten() {
        AirPlayPersistence.saveSafeMargins(context, CarPlaySafeMargins(left = 39))
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            open(controller.get())
            val preview = compose.onNodeWithTag("safe-area:preview")
            repeat(3) {
                val action =
                    preview.fetchSemanticsNode().config[SemanticsActions.CustomActions].first {
                        it.label == "左边留白增加 1%"
                    }
                compose.runOnIdle { action.action() }
            }
            compose.onNodeWithText("左边留白").performScrollTo().assertTextContains("40")
            compose.onNodeWithText("上边留白").performScrollTo().performTextReplacement("41")
            preview.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
            saveInput()
            compose.onNodeWithText("每边留白须为 0–40% 的整数").assertExists()
            assertEquals(CarPlaySafeMargins(left = 39), AirPlayPersistence.loadSafeMargins(context))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun legacyPixelsArePreviewedButAnUneditedSaveKeepsOriginalPrecision() {
        val original = SafeAreaRect(101, 52, 1150, 650)
        AirPlayPersistence.saveSafeAreaRect(context, 1280, 720, original)
        val model = SettingsViewModel(context, SavedStateHandle())
        assertEquals(CarPlaySafeMargins(7, 10, 8, 10), model.state.value.legacyMargins)
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            open(controller.get())
            saveInput()
            assertNull(AirPlayPersistence.loadSafeMargins(context))
            assertEquals(original, AirPlayPersistence.loadSafeAreaRect(context, 1280, 720))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun draggingBeyondLimitsClampsWithoutCrossingOtherEdges() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            open(controller.get())
            val preview = compose.onNodeWithTag("safe-area:preview").performScrollTo()
            preview.performTouchInput {
                val frame = safeAreaFrame(Size(width.toFloat(), height.toFloat()), 16f / 9f, 24f)
                swipe(Offset(frame.left, frame.center.y), Offset(frame.right, frame.center.y), 500)
            }
            preview.assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "上 0% · 下 0% · 左 40% · 右 0%",
                )
            )
            preview.performTouchInput {
                val frame = safeAreaFrame(Size(width.toFloat(), height.toFloat()), 16f / 9f, 24f)
                swipe(
                    Offset(frame.left + frame.width * .4f, frame.center.y),
                    Offset(1f, frame.center.y),
                    500,
                )
            }
            saveInput()
            assertTrue(AirPlayPersistence.loadSafeMargins(context)?.isFullScreen != false)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun previewUsesAdaptiveViewportAndFallsBackWhenAdaptiveSizeIsDisabled() {
        try {
            AirPlayPersistence.saveAdaptiveBrowserSize(context, true)
            assertTrue(WebSession.reportBrowserViewport(2000, 1000))
            val model = SettingsViewModel(context, SavedStateHandle())
            assertEquals(2f, model.state.value.safeAreaAspectRatio, .001f)
            AirPlayPersistence.saveAdaptiveBrowserSize(context, false)
            model.reload()
            assertEquals(16f / 9f, model.state.value.safeAreaAspectRatio, .001f)
        } finally {
            WebSession.stop()
        }
    }

    @Test
    fun previewFitsPortraitAndUltrawideWithoutDistortion() {
        for (ratio in listOf(.5f, 16f / 9f, 4f)) {
            val frame = safeAreaFrame(Size(264f, 208f), ratio, 24f)
            assertEquals(ratio, frame.width / frame.height, .0001f)
            assertTrue(frame.left >= 24f && frame.top >= 24f)
            assertTrue(frame.right <= 240f && frame.bottom <= 184f)
        }
    }
}
