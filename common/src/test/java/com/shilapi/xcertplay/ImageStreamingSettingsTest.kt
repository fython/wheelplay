package com.shilapi.xcertplay

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.shilapi.xcertplay.settings.*
import com.shilapi.xcertplay.web.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageStreamingSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun preferencesSurviveReloadWithoutReconnectingOrOverridingBrowserCompatibility() {
        assertEquals(ImageFormat.WEBP, AirPlayPersistence.loadImageFormat(app))
        assertEquals(75, AirPlayPersistence.loadImageQuality(app))
        val model = SettingsViewModel(app, SavedStateHandle())
        assertEquals(SettingsEffect.None, model.choose(ChoiceSetting.IMAGE_FORMAT, ImageFormat.JPEG.ordinal))
        assertEquals(SettingsEffect.None, model.edit(TextSetting.IMAGE_QUALITY, " 42 "))
        val restored = SettingsViewModel(app, SavedStateHandle())
        assertEquals(ImageFormat.JPEG.ordinal, restored.state.value.selected(ChoiceSetting.IMAGE_FORMAT))
        assertEquals(42, restored.state.value.imageQuality)
        for (browser in ImageFormat.entries)
            assertEquals(ImageEncodingOptions(ImageFormat.JPEG, 42), AirPlayPersistence.imageEncoding(app, browser))
        model.choose(ChoiceSetting.IMAGE_FORMAT, ImageFormat.WEBP.ordinal)
        assertEquals(ImageEncodingOptions(ImageFormat.WEBP, 42), AirPlayPersistence.imageEncoding(app, ImageFormat.WEBP))
        assertEquals(ImageEncodingOptions(ImageFormat.JPEG, 42), AirPlayPersistence.imageEncoding(app, ImageFormat.JPEG))
    }

    @Test fun invalidInputLeavesSavedValueUntouchedAndBoundaryValuesAreAccepted() {
        val model = SettingsViewModel(app, SavedStateHandle())
        model.edit(TextSetting.IMAGE_QUALITY, "42")
        for (input in listOf("", "0", "-1", "101", "75.5", "a", "999999999999999")) {
            assertTrue(input, runCatching { model.edit(TextSetting.IMAGE_QUALITY, input) }.isFailure)
            assertEquals(42, AirPlayPersistence.loadImageQuality(app))
            assertEquals(42, model.state.value.imageQuality)
        }
        for (value in listOf(1, 100)) {
            model.edit(TextSetting.IMAGE_QUALITY, value.toString())
            assertEquals(value, AirPlayPersistence.loadImageQuality(app))
        }
    }

    @Test fun unknownOrInvalidStoredValuesRecoverToTheExistingDefaults() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        for (value in listOf(Int.MIN_VALUE, 0, 101, Int.MAX_VALUE)) {
            prefs.edit().putString("web_image_format", "unknown").putInt("web_image_quality", value).apply()
            assertEquals(ImageFormat.WEBP, AirPlayPersistence.loadImageFormat(app))
            assertEquals(75, AirPlayPersistence.loadImageQuality(app))
        }
    }
}
