// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.shilapi.xcertplay.host.R

/**
 * Transient platform flows, including certificate passwords, are never saved or held by a
 * ViewModel.
 */
internal class SettingsDialogHost {
    internal sealed interface Request {
        val onCancel: () -> Unit

        data class Input(
            val title: String,
            val initial: String,
            val secret: Boolean,
            val preserveWhitespace: Boolean,
            val onSave: (String) -> Unit,
            override val onCancel: () -> Unit,
        ) : Request

        data class Selection(
            val title: String,
            val options: List<String>,
            val onSelect: (Int) -> Unit,
            override val onCancel: () -> Unit,
        ) : Request

        data class Confirmation(
            val title: String,
            val message: String,
            val onConfirm: () -> Unit,
            override val onCancel: () -> Unit = {},
        ) : Request
    }

    var request by mutableStateOf<Request?>(null)
        private set

    fun input(
        title: String,
        initial: String,
        secret: Boolean,
        preserveWhitespace: Boolean,
        onCancel: () -> Unit,
        onSave: (String) -> Unit,
    ) {
        request = Request.Input(title, initial, secret, preserveWhitespace, onSave, onCancel)
    }

    fun select(
        title: String,
        options: List<String>,
        onCancel: () -> Unit,
        onSelect: (Int) -> Unit,
    ) {
        request = Request.Selection(title, options, onSelect, onCancel)
    }

    fun confirm(title: String, message: String, onConfirm: () -> Unit) {
        request = Request.Confirmation(title, message, onConfirm)
    }

    fun complete(current: Request, action: () -> Unit) {
        if (request !== current) return
        request = null
        action()
    }

    fun cancel(current: Request? = request) {
        if (request !== current) return
        request = null
        current?.onCancel?.invoke()
    }
}

@Composable
internal fun SettingsPlatformDialog(host: SettingsDialogHost) {
    val current = host.request ?: return
    key(current) {
        when (current) {
            is SettingsDialogHost.Request.Input ->
                SettingsTextDialog(
                    current.title,
                    current.initial,
                    current.secret,
                    onDismiss = { host.cancel(current) },
                ) { raw ->
                    host.complete(current) {
                        current.onSave(
                            if (current.secret || current.preserveWhitespace) raw else raw.trim()
                        )
                    }
                    null
                }
            is SettingsDialogHost.Request.Selection ->
                SettingsChoiceDialog(current.title, current.options, 0, { host.cancel(current) }) {
                    index ->
                    host.complete(current) { current.onSelect(index) }
                }
            is SettingsDialogHost.Request.Confirmation ->
                AlertDialog(
                    onDismissRequest = { host.cancel(current) },
                    title = { Text(current.title) },
                    text = { Text(current.message) },
                    confirmButton = {
                        TextButton(onClick = { host.complete(current, current.onConfirm) }) {
                            Text(settingsString(R.string.settings_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { host.cancel(current) }) {
                            Text(settingsString(R.string.settings_cancel))
                        }
                    },
                )
        }
    }
}
