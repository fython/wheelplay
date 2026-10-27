// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.shilapi.xcertplay.host.R

@Composable
internal fun HelpSettingsScreen(state: SettingsState, actions: SettingsActions) {
    var fingerprint by remember { mutableStateOf(false) }
    SettingsGroup(R.string.settings_permissions_help) {
        SettingsDescription(R.string.settings_permissions_description)
        SettingsRow(
            settingsString(R.string.settings_fingerprint),
            settingsString(R.string.settings_fingerprint_description),
            "settings:fingerprint",
        ) {
            fingerprint = true
        }
        SettingsButton(R.string.settings_app_permissions) {
            actions.command(SettingsCommand.APP_PERMISSIONS)
        }
        SettingsButton(R.string.settings_bluetooth_settings) {
            actions.command(SettingsCommand.BLUETOOTH_SETTINGS)
        }
        SettingsButton(R.string.settings_wireless_help) {
            actions.command(SettingsCommand.WIRELESS_HELP)
        }
    }
    SettingsGroup(R.string.settings_about_diagnostics) {
        Text(
            "${settingsString(R.string.app_name)} · ${state.runtime.version}",
            style = MaterialTheme.typography.titleMedium,
        )
        SettingsButton(
            if (state.runtime.exporting) R.string.settings_exporting else R.string.settings_export,
            !state.runtime.exporting,
        ) {
            actions.command(SettingsCommand.EXPORT_REPORT)
        }
        SettingsDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) R.string.settings_export_downloads
            else R.string.settings_export_document
        )
        SettingsDescription(R.string.settings_licenses)
    }
    SettingsGroup(R.string.settings_open_source) {
        SettingsRow(
            settingsString(R.string.settings_source),
            "github.com/fython/wheelplay",
            "settings:source",
        ) {
            actions.command(SettingsCommand.SOURCE)
        }
    }
    if (fingerprint)
        AlertDialog(
            onDismissRequest = { fingerprint = false },
            title = { Text(settingsString(R.string.settings_fingerprint)) },
            text = {
                Text(
                    if (!state.checked(ToggleSetting.HTTPS))
                        settingsString(R.string.settings_fingerprint_disabled)
                    else
                        state.runtime.fingerprint?.let {
                            settingsString(R.string.settings_fingerprint_value, it)
                        } ?: settingsString(R.string.settings_fingerprint_pending)
                )
            },
            confirmButton = {
                TextButton(onClick = { fingerprint = false }) {
                    Text(settingsString(R.string.settings_close))
                }
            },
        )
}
