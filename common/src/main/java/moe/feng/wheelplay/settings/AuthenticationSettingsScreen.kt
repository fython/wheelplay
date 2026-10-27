// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.shilapi.xcertplay.host.R

@Composable
internal fun AuthenticationSettingsScreen(state: SettingsState, actions: SettingsActions) {
    var manualImport by remember { mutableStateOf(false) }
    val ready = state.runtime.authenticationError == null
    Text(
        settingsString(
            when {
                state.runtime.importing -> R.string.settings_importing
                ready -> R.string.settings_auth_ready
                else -> R.string.settings_auth_missing
            }
        ),
        color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    SettingsDescription(R.string.settings_auth_description)
    SettingsButton(R.string.settings_import_apk, !state.runtime.importing) {
        actions.command(SettingsCommand.IMPORT_APK)
    }
    SettingsButton(R.string.settings_import_files, !state.runtime.importing) { manualImport = true }
    SettingsRow(
        settingsString(R.string.settings_upstream_download),
        settingsString(R.string.settings_upstream_download_description),
        "settings:download",
    ) {
        actions.command(SettingsCommand.DOWNLOAD)
    }
    SettingsDescription(R.string.settings_auth_storage)
    if (manualImport)
        SettingsConfirmation(
            R.string.settings_import_files_title,
            R.string.settings_import_files_message,
            { manualImport = false },
        ) {
            actions.command(SettingsCommand.IMPORT_FILES)
        }
}
