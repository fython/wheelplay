// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.web.RememberedBrowsers
import com.shilapi.xcertplay.web.WebListenSettings
import moe.feng.wheelplay.CarPlayResolutionSlot
import moe.feng.wheelplay.CarPlaySafeMargins

internal enum class SettingsCategory(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
) {
    CONNECTION(R.string.settings_connection, R.string.settings_connection_summary),
    DISPLAY(R.string.settings_display, R.string.settings_display_summary),
    AUDIO(R.string.settings_audio, R.string.settings_audio_summary),
    VEHICLE(R.string.settings_vehicle, R.string.settings_vehicle_summary),
    BROWSER(R.string.settings_browser, R.string.settings_browser_summary),
    WEB(R.string.settings_web, R.string.settings_web_summary),
    TESLA(R.string.settings_tesla, R.string.settings_tesla_summary),
    AUTHENTICATION(R.string.settings_authentication, R.string.settings_authentication_summary),
    HELP(R.string.settings_help, R.string.settings_help_summary),
}

internal enum class ChoiceSetting {
    APPEARANCE,
    DISPLAY_SIZE,
    RESOLUTION,
    FPS,
    STREAM,
    IMAGE_FORMAT,
    RECOVERY,
    AUDIO_BUFFER,
    AUDIO_OUTPUT,
    MICROPHONE,
    LOCATION,
    OFFLINE,
    WIRELESS,
    TESLA_MODE,
    CERTIFICATE,
}

internal enum class ToggleSetting {
    AUTO_CONNECT,
    AUTO_START,
    ADAPTIVE_SIZE,
    HEVC,
    RIGHT_HAND,
    NAVIGATION,
    HTTPS,
    TESLA,
}

internal enum class TextSetting {
    IMAGE_QUALITY,
    OEM_NAME,
    SSID,
    PASSWORD,
    HTTP_PORT,
    HTTPS_PORT,
    HOSTNAME,
    MDNS_HOSTNAME,
    TESLA_ADDRESS,
    HTTP_MAPPING,
    HTTPS_MAPPING,
}

internal enum class SettingsCommand {
    SELECT_PHONE,
    HOTSPOT_SETTINGS,
    REQUEST_ROOT,
    RETRY_ROOT,
    ENABLE_VPN,
    SELECT_ICON,
    IMPORT_APK,
    IMPORT_FILES,
    SELECT_PEM,
    SELECT_PKCS12,
    APP_PERMISSIONS,
    BLUETOOTH_SETTINGS,
    WIRELESS_HELP,
    EXPORT_REPORT,
    UPSTREAM_PROJECT,
    SOURCE,
    DOWNLOAD,
}

/** Immutable preferences and live service status. Credentials never enter SavedStateHandle. */
internal data class SettingsState(
    val category: SettingsCategory? = null,
    val choices: Map<ChoiceSetting, Int> = emptyMap(),
    val toggles: Map<ToggleSetting, Boolean> = emptyMap(),
    val imageQuality: Int = com.shilapi.xcertplay.web.ImageEncodingOptions.DEFAULT_QUALITY,
    val phoneName: String = "",
    val manufacturer: String = "",
    val iconPath: String? = null,
    val iconVersion: Long = 0,
    val margins: CarPlaySafeMargins? = null,
    val legacySafeArea: Boolean = false,
    val legacyMargins: CarPlaySafeMargins? = null,
    val safeAreaSlot: CarPlayResolutionSlot = CarPlayResolutionSlot.DEFAULT,
    val ssid: String = "",
    val password: String = "",
    val httpPort: Int = 8080,
    val httpsPort: Int = 8443,
    val hostname: String = "",
    val mdnsHostname: String = WebListenSettings.DEFAULT_MDNS_HOSTNAME,
    val certificateType: WebListenSettings.CertificateType =
        WebListenSettings.CertificateType.DEFAULT,
    val customCertificate: Boolean = false,
    val navigationAvailable: Boolean = false,
    val tesla: TeslaHttpConfig = TeslaHttpConfig(),
    val browsers: List<RememberedBrowsers.Device> = emptyList(),
    val runtime: SettingsRuntime = SettingsRuntime(),
) {
    fun selected(setting: ChoiceSetting) = choices.getValue(setting)

    fun checked(setting: ToggleSetting) = toggles.getValue(setting)
}

internal data class SettingsRuntime(
    val authenticationError: String? = null,
    val importing: Boolean = false,
    val webBusy: Boolean = false,
    val awaitingVpnConsent: Boolean = false,
    val exporting: Boolean = false,
    val rootGranted: Boolean = false,
    val rootChecking: Boolean = false,
    val rootStatus: String = "",
    val teslaStatus: String = "",
    val audioStatus: String = "",
    val microphoneStatus: String = "",
    val version: String = "",
    val fingerprint: String? = null,
)

/** The Activity executes platform operations; composables only describe UI and emit actions. */
internal class SettingsActions(
    val choice: (ChoiceSetting, Int) -> Unit,
    val toggle: (ToggleSetting, Boolean) -> Unit,
    val text: (TextSetting, String) -> String?,
    val credentials: (String, String) -> String?,
    val margins: (CarPlayResolutionSlot, CarPlaySafeMargins) -> Unit,
    val resetBranding: (Boolean) -> Unit,
    val command: (SettingsCommand) -> Unit,
    val renameBrowser: (String, String) -> Unit,
    val removeBrowser: (String?) -> Unit,
)
