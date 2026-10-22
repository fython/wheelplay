// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.*
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.web.RtcRecoveryPolicy
import com.shilapi.xcertplay.web.StreamTechnology

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
        state, actions, ChoiceSetting.IMAGE_FORMAT, R.string.settings_image_format,
        listOf("JPEG", "WebP"),
    )
    SettingsDescription(R.string.settings_image_format_description)
    SettingsTextPreference(
        actions, TextSetting.IMAGE_QUALITY, R.string.settings_image_quality,
        state.imageQuality.toString(), numeric = true,
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
        SafeAreaDialog(margins ?: CarPlaySafeMargins(), { editMargins = false }) {
            actions.margins(it)
        }
}

@Composable
private fun SafeAreaDialog(
    initial: CarPlaySafeMargins,
    onDismiss: () -> Unit,
    onSave: (CarPlaySafeMargins) -> Unit,
) {
    var top by rememberSaveable { mutableStateOf(initial.top.toString()) }
    var bottom by rememberSaveable { mutableStateOf(initial.bottom.toString()) }
    var left by rememberSaveable { mutableStateOf(initial.left.toString()) }
    var right by rememberSaveable { mutableStateOf(initial.right.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val fields = listOf(top, bottom, left, right)
    val parsed =
        runCatching {
                fields.map { it.toInt() }.let { CarPlaySafeMargins(it[0], it[1], it[2], it[3]) }
            }
            .getOrNull()
    val preview = parsed ?: initial
    val background = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = .4f)
    val previewDescription = settingsString(R.string.settings_safe_area_preview)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingsString(R.string.settings_safe_area)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(
                    Modifier.fillMaxWidth().height(112.dp).semantics {
                        contentDescription = previewDescription
                    }
                ) {
                    drawRect(background)
                    drawRect(
                        highlight,
                        Offset(size.width * preview.left / 100, size.height * preview.top / 100),
                        Size(
                            size.width * (100 - preview.left - preview.right) / 100,
                            size.height * (100 - preview.top - preview.bottom) / 100,
                        ),
                    )
                }
                SettingsDescription(R.string.settings_safe_area_hint)
                val labels =
                    listOf(
                        R.string.settings_margin_top,
                        R.string.settings_margin_bottom,
                        R.string.settings_margin_left,
                        R.string.settings_margin_right,
                    )
                val changes: List<(String) -> Unit> =
                    listOf({ top = it }, { bottom = it }, { left = it }, { right = it })
                fields.forEachIndexed { index, value ->
                    OutlinedTextField(
                        value,
                        {
                            changes[index](it)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(settingsString(labels[index])) },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = error != null,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val invalidMessage = settingsString(R.string.settings_safe_area_invalid)
            TextButton(
                onClick = {
                    if (parsed == null) error = invalidMessage
                    else {
                        onSave(parsed)
                        onDismiss()
                    }
                }
            ) {
                Text(settingsString(R.string.settings_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSave(CarPlaySafeMargins())
                        onDismiss()
                    }
                ) {
                    Text(settingsString(R.string.settings_restore_fullscreen))
                }
                TextButton(onClick = onDismiss) { Text(settingsString(R.string.settings_cancel)) }
            }
        },
    )
}
