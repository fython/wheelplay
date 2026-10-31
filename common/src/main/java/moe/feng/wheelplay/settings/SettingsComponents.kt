// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R

@Composable
internal fun settingsString(@StringRes id: Int, vararg args: Any) =
    if (args.isEmpty()) stringResource(id) else stringResource(id, *args)

@Composable
internal fun SettingsDescription(@StringRes text: Int, vararg args: Any) {
    Text(
        settingsString(text, *args),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
internal fun SettingsGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            settingsString(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    value: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
        )
        Icon(
            painterResource(R.drawable.ic_settings_chevron),
            null,
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingsToggle(
    state: SettingsState,
    actions: SettingsActions,
    setting: ToggleSetting,
    @StringRes title: Int,
    @StringRes description: Int,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth()
            .testTag("toggle:${setting.name}")
            .toggleable(state.checked(setting), enabled = enabled, role = Role.Switch) {
                actions.toggle(setting, it)
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(settingsString(title), style = MaterialTheme.typography.titleMedium)
            Text(
                settingsString(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.checked(setting), onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun SettingsChoice(
    state: SettingsState,
    actions: SettingsActions,
    setting: ChoiceSetting,
    @StringRes title: Int,
    options: List<String>,
    enabled: Boolean = true,
    onSelected: (Int) -> Unit = { actions.choice(setting, it) },
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        settingsString(title),
        options[state.selected(setting)].substringBefore(" · "),
        "choice:${setting.name}",
        enabled,
    ) {
        open = true
    }
    if (open)
        SettingsChoiceDialog(
            settingsString(title),
            options,
            state.selected(setting),
            { open = false },
        ) {
            open = false
            onSelected(it)
        }
}

@Composable
internal fun SettingsChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var pending by rememberSaveable(title) { mutableIntStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("option:$index")
                            .selectable(pending == index, role = Role.RadioButton) {
                                pending = index
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(pending == index, onClick = null)
                        Text(option, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pending) }) {
                Text(settingsString(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(settingsString(R.string.settings_cancel)) }
        },
    )
}

@Composable
internal fun SettingsTextPreference(
    actions: SettingsActions,
    setting: TextSetting,
    @StringRes title: Int,
    value: String,
    enabled: Boolean = true,
    secret: Boolean = false,
    numeric: Boolean = false,
    displayValue: String = value,
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(settingsString(title), displayValue, "text:${setting.name}", enabled) {
        open = true
    }
    if (open)
        SettingsTextDialog(
            settingsString(title),
            value,
            secret,
            numeric,
            onDismiss = { open = false },
        ) {
            actions.text(setting, it)
        }
}

@Composable
internal fun SettingsTextDialog(
    title: String,
    initial: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
) {
    // Passwords stay in memory and are never written into saved-instance-state.
    var value by remember(title) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                {
                    value = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth().testTag("settings:text-input"),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (secret) KeyboardType.Password
                            else if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                visualTransformation =
                    if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = onSave(value)
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

@Composable
internal fun SettingsButton(@StringRes title: Int, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.padding(vertical = 4.dp), enabled = enabled) {
        Text(settingsString(title))
    }
}

@Composable
internal fun SettingsConfirmation(
    @StringRes title: Int,
    @StringRes message: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingsString(title)) },
        text = { Text(settingsString(message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(settingsString(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(settingsString(R.string.settings_cancel)) }
        },
    )
}
