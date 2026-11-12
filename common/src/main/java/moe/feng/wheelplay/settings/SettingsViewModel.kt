// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.network.TeslaHttpMode
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.web.*
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.feng.wheelplay.*

internal sealed interface SettingsEffect {
    data object None : SettingsEffect

    data class Reconnect(val wireless: Boolean? = null, val stopBeforeConnect: Boolean = false) :
        SettingsEffect

    data class WebUpdate(val message: String, val operation: () -> String?) : SettingsEffect

    data class TeslaEnabled(val enabled: Boolean) : SettingsEffect
}

/** Owns preference persistence and navigation. It never retains an Activity or a View. */
internal class SettingsViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application
    private val mutableState =
        MutableStateFlow(
            readPreferences(
                SettingsState(
                    category =
                        savedState.get<String>("settingsCategory")?.let { name ->
                            SettingsCategory.entries.firstOrNull { it.name == name }
                        }
                )
            )
        )
    val state = mutableState.asStateFlow()

    fun open(category: SettingsCategory?) {
        savedState["settingsCategory"] = category?.name
        mutableState.value = mutableState.value.copy(category = category)
    }

    fun scrollPosition(category: SettingsCategory?) =
        savedState.get<Int>("scroll:${category?.name ?: "index"}") ?: 0

    fun rememberScroll(category: SettingsCategory?, position: Int) {
        savedState["scroll:${category?.name ?: "index"}"] = position
    }

    fun reload() {
        mutableState.value = readPreferences(mutableState.value)
    }

    fun updateRuntime(runtime: SettingsRuntime) {
        mutableState.value = mutableState.value.copy(runtime = runtime)
    }

    private fun readPreferences(previous: SettingsState): SettingsState {
        val (width, height) = safeAreaDisplaySize()
        val margins = AirPlayPersistence.loadSafeMargins(app, width, height)
        val legacyRect =
            if (margins == null) AirPlayPersistence.loadSafeAreaRect(app, width, height) else null
        fun percent(pixels: Int, size: Int) =
            kotlin.math.round(pixels * 100.0 / size).toInt().coerceIn(0, 40)
        return previous.copy(
            choices =
                mapOf(
                    ChoiceSetting.APPEARANCE to AirPlayPersistence.loadAppearance(app).ordinal,
                    ChoiceSetting.DISPLAY_SIZE to
                        CarPlaySize.fromWidthMillimeters(
                                AirPlayPersistence.loadWidthPhysicalMm(app)
                            )
                            .ordinal,
                    ChoiceSetting.RESOLUTION to
                        listOf(10, 8, 6)
                            .indexOf(AirPlayPersistence.loadDisplayScaleTenths(app))
                            .coerceAtLeast(0),
                    ChoiceSetting.FPS to if (AirPlayPersistence.loadFps(app) == 60) 1 else 0,
                    ChoiceSetting.STREAM to AirPlayPersistence.loadStreamTechnology(app).ordinal,
                    ChoiceSetting.IMAGE_FORMAT to AirPlayPersistence.loadImageFormat(app).ordinal,
                    ChoiceSetting.RECOVERY to AirPlayPersistence.loadRtcRecoveryPolicy(app).ordinal,
                    ChoiceSetting.AUDIO_BUFFER to
                        MediaAudioBuffer.presets
                            .indexOf(AirPlayPersistence.loadMediaBufferMillis(app))
                            .coerceAtLeast(0),
                    ChoiceSetting.AUDIO_OUTPUT to
                        if (AirPlayPersistence.loadBrowserAudioPlayback(app)) 1 else 0,
                    ChoiceSetting.MICROPHONE to
                        AirPlayPersistence.loadMicrophoneSource(app).ordinal,
                    ChoiceSetting.LOCATION to
                        if (AirPlayPersistence.loadLocationReportingEnabled(app)) 1 else 0,
                    ChoiceSetting.OFFLINE to
                        AirPlayPersistence.loadBrowserOfflinePolicy(app).ordinal,
                    ChoiceSetting.WIRELESS to
                        if (
                            AirPlayPersistence.loadWirelessHotspotMode(app) ==
                                WirelessHotspotMode.MANUAL
                        )
                            1
                        else 0,
                    ChoiceSetting.TESLA_MODE to TeslaHttpCompatibility.config(app).mode.ordinal,
                    ChoiceSetting.CERTIFICATE to WebListenSettings.certificateType(app).ordinal,
                ),
            toggles =
                mapOf(
                    ToggleSetting.AUTO_CONNECT to WheelPlayPreferences.autoConnect(app),
                    ToggleSetting.AUTO_START to AirPlayPersistence.loadAutoStartOnBoot(app),
                    ToggleSetting.ADAPTIVE_SIZE to AirPlayPersistence.loadAdaptiveBrowserSize(app),
                    ToggleSetting.HEVC to AirPlayPersistence.loadHevcEnabled(app),
                    ToggleSetting.RIGHT_HAND to AirPlayPersistence.loadRightHandDrive(app),
                    ToggleSetting.NAVIGATION to BydOutputSettings.enabled(app),
                    ToggleSetting.HTTPS to WebListenSettings.httpsEnabled(app),
                    ToggleSetting.TESLA to TeslaHttpCompatibility.config(app).enabled,
                ),
            imageQuality = AirPlayPersistence.loadImageQuality(app),
            phoneName =
                if (WheelPlayPreferences.phoneAddress(app) == null)
                    app.getString(R.string.settings_unselected)
                else WheelPlayPreferences.phoneName(app),
            manufacturer = AirPlayPersistence.loadSimulatedManufacturerName(app),
            iconPath = AirPlayPersistence.loadCustomAirPlayIconFile(app)?.absolutePath,
            iconVersion = AirPlayPersistence.loadCustomAirPlayIconFile(app)?.lastModified() ?: 0,
            margins = margins,
            legacySafeArea = legacyRect != null,
            legacyMargins =
                legacyRect?.let {
                    CarPlaySafeMargins(
                        percent(it.top, height),
                        percent(height - it.bottom, height),
                        percent(it.left, width),
                        percent(width - it.right, width),
                    )
                },
            safeAreaSlot = CarPlayResolutionSlot(width, height),
            ssid = AirPlayPersistence.loadManualHotspotSsid(app),
            password = AirPlayPersistence.loadManualHotspotPassphrase(app),
            httpPort = WebListenSettings.httpPort(app),
            httpsPort = WebListenSettings.httpsPort(app),
            hostname = WebListenSettings.hostname(app),
            mdnsHostname = WebListenSettings.mdnsHostname(app),
            certificateType = WebListenSettings.certificateType(app),
            customCertificate = LanTls.hasCustom(app),
            navigationAvailable = BydOutputSettings.available(app),
            tesla = TeslaHttpCompatibility.config(app),
            browsers = WebSession.rememberedBrowsers(app).list(),
        )
    }

    private fun safeAreaDisplaySize(): CarPlayResolutionSlot =
        CarPlayResolutionSlot.forBrowser(
            WebSession.currentBrowserViewport,
            AirPlayPersistence.loadAdaptiveBrowserSize(app),
        )

    fun choose(setting: ChoiceSetting, index: Int): SettingsEffect {
        if (state.value.selected(setting) == index) return SettingsEffect.None
        val effect =
            when (setting) {
                ChoiceSetting.APPEARANCE -> {
                    WebSession.setAppearance(app, CarPlayAppearance.entries[index])
                    SettingsEffect.None
                }
                ChoiceSetting.DISPLAY_SIZE -> {
                    AirPlayPersistence.saveWidthPhysicalMm(
                        app,
                        CarPlaySize.entries[index].widthMillimeters,
                    )
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.RESOLUTION -> {
                    AirPlayPersistence.saveDisplayScaleTenths(app, listOf(10, 8, 6)[index])
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.FPS -> {
                    AirPlayPersistence.saveFps(app, if (index == 1) 60 else 30)
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.STREAM -> {
                    AirPlayPersistence.saveStreamTechnology(app, StreamTechnology.entries[index])
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.IMAGE_FORMAT -> {
                    AirPlayPersistence.saveImageFormat(app, ImageFormat.entries[index])
                    SettingsEffect.None
                }
                ChoiceSetting.RECOVERY -> {
                    AirPlayPersistence.saveRtcRecoveryPolicy(app, RtcRecoveryPolicy.entries[index])
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.AUDIO_BUFFER -> {
                    WebSession.setMediaBufferMillis(app, MediaAudioBuffer.presets[index])
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.AUDIO_OUTPUT -> {
                    WebSession.setBrowserAudioPlayback(app, index == 1)
                    SettingsEffect.None
                }
                ChoiceSetting.MICROPHONE -> {
                    WebSession.setMicrophoneSource(app, CarPlayMicrophoneSource.entries[index])
                    SettingsEffect.Reconnect()
                }
                ChoiceSetting.LOCATION -> {
                    AirPlayPersistence.saveLocationReportingEnabled(app, index == 1)
                    SettingsEffect.None
                }
                ChoiceSetting.OFFLINE -> {
                    WebSession.setBrowserOfflinePolicy(app, BrowserOfflinePolicy.entries[index])
                    SettingsEffect.None
                }
                ChoiceSetting.WIRELESS -> {
                    if (index == 1)
                        require(
                            ManualHotspotValidation.validate(
                                state.value.ssid,
                                state.value.password,
                            ) == null
                        )
                    AirPlayPersistence.saveWirelessHotspotMode(
                        app,
                        if (index == 1) WirelessHotspotMode.MANUAL else WirelessHotspotMode.WIFI_P2P,
                    )
                    SettingsEffect.Reconnect(wireless = true, stopBeforeConnect = true)
                }
                ChoiceSetting.TESLA_MODE -> {
                    TeslaHttpCompatibility.save(
                        app,
                        TeslaHttpCompatibility.config(app)
                            .copy(mode = TeslaHttpMode.entries[index], enabled = false),
                    )
                    SettingsEffect.None
                }
                ChoiceSetting.CERTIFICATE -> {
                    val type = WebListenSettings.CertificateType.entries[index]
                    if (type == WebListenSettings.CertificateType.DEFAULT)
                        SettingsEffect.WebUpdate(
                            app.getString(R.string.settings_default_certificate_restored)
                        ) {
                            WebSession.restoreDefaultCertificate(app).also {
                                if (it == null) WebListenSettings.saveCertificateType(app, type)
                            }
                        }
                    else {
                        WebListenSettings.saveCertificateType(app, type)
                        SettingsEffect.None
                    }
                }
            }
        reload()
        return effect
    }

    fun toggle(setting: ToggleSetting, checked: Boolean): SettingsEffect {
        val effect =
            when (setting) {
                ToggleSetting.AUTO_CONNECT -> {
                    WheelPlayPreferences.saveAutoConnect(app, checked)
                    SettingsEffect.None
                }
                ToggleSetting.AUTO_START -> {
                    AirPlayPersistence.saveAutoStartOnBoot(app, checked)
                    SettingsEffect.None
                }
                ToggleSetting.ADAPTIVE_SIZE -> {
                    AirPlayPersistence.saveAdaptiveBrowserSize(app, checked)
                    WebSession.setAdaptiveBrowserSize(checked)
                    SettingsEffect.None
                }
                ToggleSetting.HEVC -> {
                    AirPlayPersistence.saveHevcEnabled(app, checked)
                    SettingsEffect.None
                }
                ToggleSetting.RIGHT_HAND -> {
                    AirPlayPersistence.saveRightHandDrive(app, checked)
                    SettingsEffect.None
                }
                ToggleSetting.NAVIGATION -> {
                    BydOutputSettings.setEnabled(app, checked)
                    SettingsEffect.None
                }
                ToggleSetting.HTTPS ->
                    SettingsEffect.WebUpdate(
                        if (checked) app.getString(R.string.settings_https_enabled)
                        else app.getString(R.string.settings_https_disabled)
                    ) {
                        WebSession.setHttpsEnabled(app, checked)
                    }
                ToggleSetting.TESLA -> SettingsEffect.TeslaEnabled(checked)
            }
        reload()
        return effect
    }

    fun edit(setting: TextSetting, value: String): SettingsEffect {
        val effect =
            when (setting) {
                TextSetting.IMAGE_QUALITY -> {
                    val quality = value.trim().toIntOrNull()
                    require(quality != null && quality in ImageEncodingOptions.QUALITY_RANGE) {
                        app.getString(R.string.settings_image_quality_invalid)
                    }
                    AirPlayPersistence.saveImageQuality(app, quality)
                    SettingsEffect.None
                }
                TextSetting.OEM_NAME -> {
                    AirPlayPersistence.saveSimulatedManufacturerName(app, value.trim())
                    SettingsEffect.None
                }
                TextSetting.SSID -> {
                    saveCredentials(value, state.value.password)
                    SettingsEffect.None
                }
                TextSetting.PASSWORD -> {
                    saveCredentials(state.value.ssid, value)
                    SettingsEffect.None
                }
                TextSetting.HTTP_PORT,
                TextSetting.HTTPS_PORT -> {
                    val port = value.trim().toIntOrNull()
                    require(port != null && port in 1024..65535) {
                        app.getString(R.string.settings_listener_port_invalid)
                    }
                    SettingsEffect.WebUpdate(app.getString(R.string.settings_listener_port_saved)) {
                        if (setting == TextSetting.HTTP_PORT) WebSession.setHttpPort(app, port)
                        else WebSession.setHttpsPort(app, port)
                    }
                }
                TextSetting.HOSTNAME ->
                    SettingsEffect.WebUpdate(app.getString(R.string.settings_hostname_saved)) {
                        WebSession.setHostname(app, value.trim().lowercase(Locale.ROOT))
                    }
                TextSetting.MDNS_HOSTNAME -> {
                    val name = value.trim().lowercase(Locale.ROOT)
                    require(WebListenSettings.validMdnsHostname(name)) {
                        app.getString(R.string.settings_mdns_invalid)
                    }
                    SettingsEffect.WebUpdate(app.getString(R.string.settings_mdns_saved)) {
                        WebSession.setMdnsHostname(app, name)
                    }
                }
                TextSetting.TESLA_ADDRESS -> {
                    val config = TeslaHttpCompatibility.config(app)
                    TeslaHttpCompatibility.save(
                        app,
                        if (config.mode == TeslaHttpMode.VPN) config.copy(vpnAddress = value.trim())
                        else config.copy(address = value.trim()),
                    )
                    SettingsEffect.None
                }
                TextSetting.HTTP_MAPPING,
                TextSetting.HTTPS_MAPPING -> {
                    val port = value.trim().toIntOrNull()
                    require(port != null && port in 1..1023) {
                        app.getString(R.string.settings_mapping_port_invalid)
                    }
                    val config = TeslaHttpCompatibility.config(app)
                    TeslaHttpCompatibility.save(
                        app,
                        if (setting == TextSetting.HTTP_MAPPING) config.copy(httpMappingPort = port)
                        else config.copy(httpsMappingPort = port),
                    )
                    SettingsEffect.None
                }
            }
        reload()
        return effect
    }

    /** Validate both fields before writing either, preserving SSID/password whitespace. */
    fun saveCredentials(ssid: String, password: String) {
        ManualHotspotValidation.validate(ssid, password)?.let {
            throw com.shilapi.xcertplay.i18n.UiException(it)
        }
        AirPlayPersistence.saveManualHotspotSsid(app, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(app, password)
        AirPlayPersistence.saveManualHotspotSecurity(
            app,
            ManualHotspotValidation.securityFor(password),
        )
        AirPlayPersistence.saveManualHotspotBand(app, ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(app, 0)
        reload()
    }

    fun saveMargins(slot: CarPlayResolutionSlot, margins: CarPlaySafeMargins): SettingsEffect {
        AirPlayPersistence.saveSafeMargins(app, margins, slot.width, slot.height)
        reload()
        return if (slot == safeAreaDisplaySize()) SettingsEffect.Reconnect()
        else SettingsEffect.None
    }

    fun resetBranding(includeName: Boolean) {
        AirPlayPersistence.clearCustomAirPlayIcon(app)
        if (includeName)
            AirPlayPersistence.saveSimulatedManufacturerName(
                app,
                AirPlayPersistence.DEFAULT_SIMULATED_MANUFACTURER_NAME,
            )
        reload()
    }

    fun renameBrowser(id: String, name: String) {
        WebSession.rememberedBrowsers(app).rename(id, name.trim())
        reload()
    }

    fun removeBrowser(id: String?) {
        if (id == null) WebSession.forgetAllBrowsers(app) else WebSession.forgetBrowser(app, id)
        reload()
    }
}
