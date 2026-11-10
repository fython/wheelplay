// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui

import com.shilapi.xcertplay.projection.ProjectionSourceIds as Id
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.shared.R as UiR

internal enum class SourcePage(val id: String, val title: Int, val fallback: String, val summary: Int, val icon: Int, val group: String) {
    CARPLAY("source-carplay", UiR.string.source_carplay, "", UiR.string.source_carplay_summary, R.drawable.ic_server_phone, Id.CARPLAY),
    ANDROID_AUTO("source-android-auto", 0, "Android Auto", UiR.string.source_aa_summary, R.drawable.ic_server_phone, Id.ANDROID_AUTO),
    ICCOA("source-iccoa", 0, "ICCOA CarLink", UiR.string.source_carlink_brief, R.drawable.ic_server_wifi, Id.ICCOA),
    VNC("source-vnc", 0, "VNC", UiR.string.vnc_summary, R.drawable.ic_server_server, Id.VNC),
    DESKTOP("source-desktop", UiR.string.desktop_title, "", UiR.string.source_desktop_brief, R.drawable.ic_server_phone, Id.ANDROID_DESKTOP),
    DIAGNOSTICS("source-diagnostics", UiR.string.source_diagnostics, "", UiR.string.source_diagnostics_summary, R.drawable.ic_server_server, Id.DEMO),
    PLUGINS("source-plugins", UiR.string.plugin_sources, "", UiR.string.plugin_summary, R.drawable.ic_server_settings, Id.PLUGIN),
}
internal enum class AaConnection { LOCAL, USB, NETWORK }
internal enum class UiAction {
    NAVIGATE, AA_MODE, BROWSER, SCAN, CHOOSE_HOME, HOME_CONNECT, HOME_STOP, HOME_SETTINGS, STOP_SERVICE,
    CHOOSE_PHONE, CARPLAY_CONNECT, CARPLAY_USB, CARPLAY_STOP, HOTSPOT,
    AA_LOCAL, AA_USB, AA_NETWORK, AA_HELP, VNC, STOP_SOURCE,
    PLUGIN_REFRESH, PLUGIN_ENABLE, PLUGIN_DISABLE, PLUGIN_SETTINGS, PLUGIN_CONNECT,
    DESKTOP_DISPLAY, DESKTOP_SHIZUKU, DESKTOP_AUTHORIZE, DESKTOP_APP, DESKTOP_START, DEMO,
}
internal data class AddressUi(val description: String, val url: String)
internal data class HomeUi(
    val address: String = "", val addressDescription: String = "", val otherAddresses: List<AddressUi> = emptyList(),
    val browserEnabled: Boolean = false, val code: String = "", val viewerConnected: Boolean = false,
    val sourceName: String = "", val stage: String = "", val connectLabel: String = "",
    val showConnect: Boolean = false, val connectEnabled: Boolean = false, val stopLabel: String? = null,
    val showAudioHint: Boolean = true,
)
internal data class SessionUi(val message: String = "", val canStop: Boolean = false)
internal data class PluginUi(
    val id: String, val name: String, val packageName: String, val carLink: Boolean,
    val enabled: Boolean, val problem: String?, val failure: String?, val hasSettings: Boolean, val canConnect: Boolean,
)
internal data class SourcesUi(
    val overview: String = "", val flags: Map<SourcePage, String> = emptyMap(),
    val sessions: Map<String, SessionUi> = emptyMap(), val plugins: List<PluginUi> = emptyList(),
    val carPlayStatus: String = "", val carPlayRunning: Boolean = false, val carPlayEnabled: Boolean = false,
    val phone: String = "", val setupError: String? = null, val hotspotHint: String? = null,
    val desktopStatus: String = "", val desktopEnabled: Boolean = false, val desktopDisplay: String = "", val desktopApp: String = "",
)
internal data class WheelPlayUiState(val home: HomeUi = HomeUi(), val sources: SourcesUi = SourcesUi())
