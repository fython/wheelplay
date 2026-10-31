// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.web.WebListenSettings

@Composable
internal fun WebSettingsScreen(state: SettingsState, actions: SettingsActions) {
    val enabled = !state.runtime.webBusy
    SettingsGroup(R.string.settings_web_listeners) {
        SettingsTextPreference(
            actions,
            TextSetting.HTTP_PORT,
            R.string.settings_http_port,
            state.httpPort.toString(),
            enabled,
            numeric = true,
        )
        SettingsTextPreference(
            actions,
            TextSetting.HOSTNAME,
            R.string.settings_hostname,
            state.hostname,
            enabled,
            displayValue = state.hostname.ifEmpty { settingsString(R.string.settings_use_ip) },
        )
        SettingsTextPreference(
            actions,
            TextSetting.MDNS_HOSTNAME,
            R.string.settings_mdns_hostname,
            state.mdnsHostname,
            enabled,
        )
        SettingsDescription(R.string.settings_mdns_description)
        SettingsDescription(R.string.settings_web_description)
        SettingsToggle(
            state,
            actions,
            ToggleSetting.HTTPS,
            R.string.settings_https,
            R.string.settings_https_description,
            enabled,
        )
        if (state.checked(ToggleSetting.HTTPS)) {
            SettingsTextPreference(
                actions,
                TextSetting.HTTPS_PORT,
                R.string.settings_https_port,
                state.httpsPort.toString(),
                enabled,
                numeric = true,
            )
            Text(
                settingsString(R.string.settings_certificate_type),
                style = MaterialTheme.typography.titleMedium,
            )
            WebListenSettings.CertificateType.entries.forEach { type ->
                Row(
                    Modifier.fillMaxWidth()
                        .testTag("certificate:${type.name}")
                        .selectable(
                            state.certificateType == type,
                            enabled = enabled,
                            role = Role.RadioButton,
                        ) {
                            actions.choice(ChoiceSetting.CERTIFICATE, type.ordinal)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(state.certificateType == type, onClick = null, enabled = enabled)
                    Text(settingsString(type.labelRes), Modifier.padding(start = 12.dp))
                }
            }
            when (state.certificateType) {
                WebListenSettings.CertificateType.DEFAULT -> Unit
                WebListenSettings.CertificateType.PEM ->
                    SettingsButton(R.string.settings_select_pem, enabled) {
                        actions.command(SettingsCommand.SELECT_PEM)
                    }
                WebListenSettings.CertificateType.PKCS12 ->
                    SettingsButton(R.string.settings_select_pkcs12, enabled) {
                        actions.command(SettingsCommand.SELECT_PKCS12)
                    }
            }
            SettingsDescription(
                if (state.customCertificate) R.string.settings_certificate_custom
                else R.string.settings_certificate_default
            )
            SettingsDescription(R.string.settings_certificate_description)
        }
    }
}
