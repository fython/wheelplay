// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.web.RtcRecoveryPolicy
import com.shilapi.xcertplay.web.StreamTechnology
import moe.feng.wheelplay.*

@Composable
internal fun DisplaySettingsScreen(state: SettingsState, actions: SettingsActions) {
    var editMargins by rememberSaveable { mutableStateOf(false) }
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.APPEARANCE,
        R.string.settings_appearance,
        CarPlayAppearance.entries.map { settingsString(it.labelRes) },
    )
    SettingsDescription(R.string.settings_appearance_description)
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.DISPLAY_SIZE,
        R.string.settings_display_size,
        CarPlaySize.entries.map { settingsString(it.labelRes) },
    )
    SettingsDescription(R.string.settings_display_size_description)
    val margins = state.margins
    val summary =
        if (margins == null && state.legacySafeArea)
            settingsString(R.string.settings_legacy_safe_area)
        else if (margins == null || margins.isFullScreen)
            settingsString(R.string.settings_fullscreen)
        else
            settingsString(
                R.string.settings_safe_area_value,
                margins.top,
                margins.bottom,
                margins.left,
                margins.right,
            )
    SettingsRow(settingsString(R.string.settings_safe_area), summary, "settings:safe-area") {
        editMargins = true
    }
    SettingsDescription(R.string.settings_safe_area_description)
    SettingsToggle(
        state,
        actions,
        ToggleSetting.ADAPTIVE_SIZE,
        R.string.settings_adaptive_size,
        R.string.settings_adaptive_size_description,
    )
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.RESOLUTION,
        R.string.settings_resolution,
        listOf(
            settingsString(R.string.settings_original_resolution),
            settingsString(R.string.settings_resolution_lower),
            settingsString(R.string.settings_resolution_lowest),
        ),
    )
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.FPS,
        R.string.settings_fps,
        listOf(settingsString(R.string.settings_fps_30), settingsString(R.string.settings_fps_60)),
    )
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.STREAM,
        R.string.settings_stream,
        StreamTechnology.entries.map { settingsString(it.labelRes) },
    )
    SettingsDescription(R.string.settings_stream_description)
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.IMAGE_FORMAT,
        R.string.settings_image_format,
        listOf("JPEG", "WebP"),
    )
    SettingsDescription(R.string.settings_image_format_description)
    SettingsTextPreference(
        actions,
        TextSetting.IMAGE_QUALITY,
        R.string.settings_image_quality,
        state.imageQuality.toString(),
        numeric = true,
    )
    SettingsDescription(R.string.settings_image_quality_description)
    SettingsChoice(
        state,
        actions,
        ChoiceSetting.RECOVERY,
        R.string.settings_recovery,
        RtcRecoveryPolicy.entries.map { settingsString(it.labelRes) },
    )
    SettingsDescription(R.string.settings_recovery_description)
    SettingsToggle(
        state,
        actions,
        ToggleSetting.HEVC,
        R.string.settings_hevc,
        R.string.settings_hevc_description,
    )
    SettingsToggle(
        state,
        actions,
        ToggleSetting.RIGHT_HAND,
        R.string.settings_right_hand,
        R.string.settings_right_hand_description,
    )
    if (editMargins)
        SafeAreaDialog(
            initial = margins ?: state.legacyMargins ?: CarPlaySafeMargins(),
            aspectRatio = state.safeAreaAspectRatio,
            legacy = margins == null && state.legacySafeArea,
            onDismiss = { editMargins = false },
        ) {
            actions.margins(it)
        }
}
