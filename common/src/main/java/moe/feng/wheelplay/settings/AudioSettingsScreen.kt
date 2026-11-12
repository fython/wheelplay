// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import moe.feng.wheelplay.*
import com.shilapi.xcertplay.host.R

@Composable
internal fun AudioSettingsScreen(state: SettingsState, actions: SettingsActions) {
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.AUDIO_BUFFER,
        R.string.settings_audio_buffer,
        listOf(
            settingsString(R.string.settings_buffer_default),
            "500 ms",
            settingsString(R.string.settings_buffer_stable),
        ),
    )
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.AUDIO_OUTPUT,
        R.string.settings_audio_output,
        CarPlayAudioOutput.entries.map { settingsString(it.labelRes) },
    )
    Text(state.runtime.audioStatus, style = MaterialTheme.typography.bodyMedium)
    SettingsDescription(R.string.settings_audio_output_description)
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.MICROPHONE,
        R.string.settings_microphone,
        CarPlayMicrophoneSource.entries.map { settingsString(it.labelRes) },
    )
    Text(state.runtime.microphoneStatus, style = MaterialTheme.typography.bodyMedium)
    SettingsDescription(R.string.settings_microphone_description)
}
