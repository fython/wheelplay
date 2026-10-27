package moe.feng.wheelplay

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.settings.SettingsEffect
import moe.feng.wheelplay.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafeAreaResolutionSlotTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    private val a = CarPlayResolutionSlot(1280, 720)
    private val b = CarPlayResolutionSlot(1920, 1080)

    @After
    fun cleanup() {
        WebSession.stop()
    }

    @Test
    fun matchingAspectRatiosStillHaveIndependentSettingsAndFullscreenOverridesLegacy() {
        val model = SettingsViewModel(app, SavedStateHandle())
        val marginsA = CarPlaySafeMargins(5, 10, 15, 20)
        val marginsB = CarPlaySafeMargins(20, 15, 10, 5)
        model.saveMargins(a, marginsA)
        model.saveMargins(b, marginsB)
        assertEquals(
            marginsA.rectangle(a.width, a.height),
            AirPlayPersistence.loadSafeAreaRect(app, a.width, a.height),
        )
        assertEquals(
            marginsB.rectangle(b.width, b.height),
            AirPlayPersistence.loadSafeAreaRect(app, b.width, b.height),
        )
        assertNull(AirPlayPersistence.loadSafeAreaRect(app, 2560, 1440))
        AirPlayPersistence.saveSafeAreaRect(app, b.width, b.height, SafeAreaRect(1, 2, 1800, 1000))
        model.saveMargins(b, CarPlaySafeMargins())
        assertNull(AirPlayPersistence.loadSafeAreaRect(app, b.width, b.height))
        assertEquals(marginsA, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
        // Reload persistence through a new settings model, then return to the first resolution.
        WebSession.reportBrowserViewport(b.width, b.height)
        val next = SettingsViewModel(app, SavedStateHandle())
        assertEquals(CarPlaySafeMargins(), next.state.value.margins)
        WebSession.reportBrowserViewport(a.width, a.height)
        next.reload()
        assertEquals(marginsA, next.state.value.margins)
    }

    @Test
    fun clearingOrWritingLegacyPixelsDoesNotEraseAnotherSlot() {
        val margins = CarPlaySafeMargins(left = 12)
        AirPlayPersistence.saveSafeMargins(app, margins, a.width, a.height)
        AirPlayPersistence.saveSafeMargins(app, margins, b.width, b.height)
        AirPlayPersistence.clearSafeAreaRect(app, b.width, b.height)
        assertNull(AirPlayPersistence.loadSafeMargins(app, b.width, b.height))
        assertEquals(margins, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
        AirPlayPersistence.saveSafeAreaRect(
            app,
            b.width,
            b.height,
            SafeAreaRect(10, 20, 1900, 1000),
        )
        assertEquals(margins, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
    }

    @Test
    fun oldGlobalMarginsMigrateOnlyToDefaultSlotWithoutOverwritingNewSettings() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val old = CarPlaySafeMargins(top = 8)
        prefs.edit().putString("carplay_safe_margins", old.encode()).commit()
        assertNull(AirPlayPersistence.loadSafeMargins(app, b.width, b.height))
        assertEquals(old, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
        assertFalse(prefs.contains("carplay_safe_margins"))
        val replacement = CarPlaySafeMargins(right = 9)
        AirPlayPersistence.saveSafeMargins(app, replacement, a.width, a.height)
        prefs.edit().putString("carplay_safe_margins", old.encode()).commit()
        assertEquals(replacement, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
    }

    @Test
    fun savingAnOldEditorTargetDoesNotOverwriteOrReconnectCurrentResolution() {
        val model = SettingsViewModel(app, SavedStateHandle())
        val captured = model.state.value.safeAreaSlot
        WebSession.reportBrowserViewport(b.width, b.height)
        model.reload()
        val margins = CarPlaySafeMargins(bottom = 14)
        assertEquals(SettingsEffect.None, model.saveMargins(captured, margins))
        assertEquals(b, model.state.value.safeAreaSlot)
        assertNull(model.state.value.margins)
        assertEquals(margins, AirPlayPersistence.loadSafeMargins(app, a.width, a.height))
        assertTrue(model.saveMargins(b, CarPlaySafeMargins(left = 10)) is SettingsEffect.Reconnect)
    }

    @Test
    fun slotNormalizationMatchesTheBaseCanvasAndKeepsOrientationDistinct() {
        assertEquals(
            CarPlayResolutionSlot(3840, 2160),
            CarPlayResolutionSlot.forBrowser(7680 to 4320),
        )
        assertEquals(
            CarPlayResolutionSlot(1280, 720),
            CarPlayResolutionSlot.forBrowser(1281 to 721),
        )
        assertEquals(a, CarPlayResolutionSlot.forBrowser(1920 to 1080, adaptive = false))
        assertNotEquals(CarPlayResolutionSlot(720, 1280), a)
    }
}
