// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

@Composable
internal fun ConnectionSettingsScreen(state: SettingsState, actions: SettingsActions) {
    var credentials by remember { mutableStateOf(false) }
    SettingsGroup(R.string.settings_auto_connection) {
        SettingsToggle(
            state,
            actions,
            ToggleSetting.AUTO_CONNECT,
            R.string.settings_auto_connect,
            R.string.settings_auto_connect_description,
        )
        SettingsToggle(
            state,
            actions,
            ToggleSetting.AUTO_START,
            R.string.settings_auto_start,
            R.string.settings_auto_start_description,
        )
        SettingsRow(settingsString(R.string.settings_iphone), state.phoneName, "settings:phone") {
            actions.command(SettingsCommand.SELECT_PHONE)
        }
    }
    SettingsGroup(R.string.settings_wireless_connection) {
        SettingsChoice(
            state,
            actions,
            ChoiceSetting.WIRELESS,
            R.string.settings_network,
            listOf(
                settingsString(R.string.settings_wifi_direct),
                settingsString(R.string.settings_hotspot),
            ),
        ) { index ->
            if (index == 1 && ManualHotspotValidation.validate(state.ssid, state.password) != null)
                credentials = true
            else actions.choice(ChoiceSetting.WIRELESS, index)
        }
        if (state.selected(ChoiceSetting.WIRELESS) == 0)
            SettingsDescription(R.string.settings_wifi_direct_description)
        else {
            SettingsTextPreference(actions, TextSetting.SSID, R.string.settings_ssid, state.ssid)
            SettingsTextPreference(
                actions,
                TextSetting.PASSWORD,
                R.string.settings_password,
                state.password,
                secret = true,
                displayValue =
                    if (state.password.isEmpty()) settingsString(R.string.settings_not_set)
                    else "••••••••",
            )
            SettingsDescription(R.string.settings_hotspot_description)
            SettingsButton(R.string.settings_open_hotspot) {
                actions.command(SettingsCommand.HOTSPOT_SETTINGS)
            }
        }
    }
    if (credentials)
        HotspotCredentialsDialog(state, { credentials = false }) { ssid, password ->
            actions.credentials(ssid, password)?.let {
                return@HotspotCredentialsDialog it
            }
            actions.choice(ChoiceSetting.WIRELESS, 1)
            null
        }
}

@Composable
private fun HotspotCredentialsDialog(
    state: SettingsState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> String?,
) {
    var ssid by remember { mutableStateOf(state.ssid) }
    var password by remember { mutableStateOf(state.password) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingsString(R.string.settings_hotspot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    ssid,
                    {
                        ssid = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(settingsString(R.string.settings_ssid)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    password,
                    {
                        password = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(settingsString(R.string.settings_password)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = onSave(ssid, password)
                    if (error == null) onDismiss()
                }
            ) {
                Text(settingsString(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(settingsString(R.string.settings_cancel)) }
        },
    )
}
