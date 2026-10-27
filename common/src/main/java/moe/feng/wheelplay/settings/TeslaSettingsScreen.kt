// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.TeslaHttpMode

@Composable
internal fun TeslaSettingsScreen(state: SettingsState, actions: SettingsActions) {
    val config = state.tesla
    val enabled = !state.runtime.webBusy && !state.runtime.awaitingVpnConsent
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.TESLA_MODE,
        R.string.settings_connection_mode,
        TeslaHttpMode.entries.map { settingsString(it.labelRes) },
        enabled,
    )
    SettingsDescription(R.string.settings_tesla_description)
    if (config.mode == TeslaHttpMode.VPN) {
        SettingsToggle(
            state,
            actions,
            ToggleSetting.TESLA,
            R.string.settings_enable_vpn,
            R.string.settings_enable_vpn_description,
            enabled,
        )
        Text(state.runtime.teslaStatus, style = MaterialTheme.typography.bodyMedium)
        SettingsTextPreference(
            actions,
            TextSetting.TESLA_ADDRESS,
            R.string.settings_access_ip,
            config.vpnAddress,
            enabled,
        )
        SettingsDescription(R.string.settings_vpn_description, config.vpnAddress, state.httpPort)
        SettingsDescription(R.string.settings_vpn_ports_description)
        SettingsButton(R.string.settings_retry_vpn, enabled) {
            actions.command(SettingsCommand.ENABLE_VPN)
        }
    } else {
        SettingsButton(R.string.settings_request_root, enabled && !state.runtime.rootChecking) {
            actions.command(SettingsCommand.REQUEST_ROOT)
        }
        Text(state.runtime.rootStatus, style = MaterialTheme.typography.bodyMedium)
        if (state.runtime.rootGranted) {
            SettingsToggle(
                state,
                actions,
                ToggleSetting.TESLA,
                R.string.settings_enable_root,
                R.string.settings_enable_root_description,
                enabled,
            )
            Text(state.runtime.teslaStatus, style = MaterialTheme.typography.bodyMedium)
            SettingsTextPreference(
                actions,
                TextSetting.HTTP_MAPPING,
                R.string.settings_http_mapping,
                config.httpMappingPort.toString(),
                enabled,
                numeric = true,
                displayValue = "${config.httpMappingPort} → ${state.httpPort}",
            )
            SettingsTextPreference(
                actions,
                TextSetting.HTTPS_MAPPING,
                R.string.settings_https_mapping,
                config.httpsMappingPort.toString(),
                enabled,
                numeric = true,
                displayValue =
                    "${config.httpsMappingPort} → " +
                        if (state.checked(ToggleSetting.HTTPS)) state.httpsPort.toString()
                        else settingsString(R.string.settings_https_disabled),
            )
            SettingsTextPreference(
                actions,
                TextSetting.TESLA_ADDRESS,
                R.string.settings_access_ip,
                config.address,
                enabled,
            )
            SettingsDescription(R.string.settings_root_description)
            SettingsButton(R.string.settings_retry_root, enabled) {
                actions.command(SettingsCommand.RETRY_ROOT)
            }
        }
    }
    SettingsButton(R.string.settings_open_hotspot) {
        actions.command(SettingsCommand.HOTSPOT_SETTINGS)
    }
}
