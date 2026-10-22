package com.shilapi.xcertplay

import androidx.compose.ui.test.*
import com.shilapi.xcertplay.web.*
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
class ImageStreamingSettingsUiTest : ComposeSettingsTest() {
    @Test fun userCanChooseFormatAndSaveValidatedQualityFromDisplaySettings() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val app = RuntimeEnvironment.getApplication()
        try {
            SettingsNavigation.open(controller.get(), "画面与串流", compose)
            clickTag("choice:IMAGE_FORMAT"); chooseOption(0)
            assertEquals(ImageFormat.JPEG, AirPlayPersistence.loadImageFormat(app))
            clickTag("text:IMAGE_QUALITY"); input("101"); saveInput()
            compose.onNodeWithText("请输入 1 到 100 之间的整数。").assertExists()
            assertEquals(75, AirPlayPersistence.loadImageQuality(app))
            input("32"); saveInput()
            assertEquals(ImageEncodingOptions(ImageFormat.JPEG, 32), AirPlayPersistence.imageEncoding(app, ImageFormat.WEBP))
            clickTag("text:IMAGE_QUALITY"); input("90"); cancelInput()
            assertEquals(32, AirPlayPersistence.loadImageQuality(app))
            SettingsNavigation.open(controller.get(), "画面与串流", compose)
            compose.onNodeWithTag("text:IMAGE_QUALITY").performScrollTo().assertTextContains("32")
            clickTag("choice:IMAGE_FORMAT"); chooseOption(1)
            assertEquals(ImageEncodingOptions(ImageFormat.WEBP, 32), AirPlayPersistence.imageEncoding(app, ImageFormat.WEBP))
            captureSettings(controller.get(), "image-streaming-settings")
        } finally { controller.pause().stop().destroy() }
    }
}
