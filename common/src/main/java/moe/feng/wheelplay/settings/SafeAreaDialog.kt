// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import moe.feng.wheelplay.CarPlayResolutionSlot
import moe.feng.wheelplay.CarPlaySafeMargins

@Composable
internal fun SafeAreaDialog(
    initial: CarPlaySafeMargins,
    slot: CarPlayResolutionSlot = CarPlayResolutionSlot.DEFAULT,
    legacy: Boolean = false,
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
    var edited by rememberSaveable { mutableStateOf(false) }
    fun update(value: CarPlaySafeMargins) {
        top = value.top.toString()
        bottom = value.bottom.toString()
        left = value.left.toString()
        right = value.right.toString()
        error = null
        edited = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingsString(R.string.settings_safe_area)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    settingsString(R.string.settings_safe_area_slot, slot.width, slot.height),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SafeAreaPreview(preview, slot.aspectRatio, parsed != null, ::update)
                SettingsDescription(R.string.settings_safe_area_hint)
                if (legacy) SettingsDescription(R.string.settings_safe_area_legacy_edit)
                TextButton(onClick = { update(CarPlaySafeMargins()) }) {
                    Text(settingsString(R.string.settings_restore_fullscreen))
                }
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
                            edited = true
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
                        if (edited && (legacy || parsed != initial)) onSave(parsed)
                        onDismiss()
                    }
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
