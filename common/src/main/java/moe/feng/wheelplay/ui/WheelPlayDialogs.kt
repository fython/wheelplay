// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Transient requests: secrets and callbacks never enter saved state or a retained ViewModel. */
internal class WheelPlayDialogs {
    data class Action(val label: String, val run: () -> Unit)
    data class Field(val key: String, val label: String, val initial: String, val secret: Boolean = false, val numeric: Boolean = false)
    sealed interface Request {
        val title: String
        data class Message(override val title: String, val text: String, val actions: List<Action>, val onCancel: () -> Unit) : Request
        data class Choices(override val title: String, val options: List<String>, val selected: Int?, val extra: Action?, val onCancel: () -> Unit, val choose: (Int) -> Unit) : Request
        data class Form(override val title: String, val description: String, val fields: List<Field>, val checkLabel: String?, val checked: Boolean,
            val resetLabel: String?, val defaults: List<String>?, val submit: (List<String>, Boolean) -> String?) : Request
    }
    var request by mutableStateOf<Request?>(null)
        private set
    fun message(title: String, text: String, actions: List<Action>, onCancel: () -> Unit = {}) {
        cancel(); request = Request.Message(title, text, actions, onCancel)
    }
    fun choices(title: String, options: List<String>, selected: Int? = null, extra: Action? = null, onCancel: () -> Unit = {}, choose: (Int) -> Unit) {
        cancel(); request = Request.Choices(title, options, selected, extra, onCancel, choose)
    }
    fun form(title: String, description: String, fields: List<Field>, checkLabel: String? = null, checked: Boolean = false,
        resetLabel: String? = null, defaults: List<String>? = null, submit: (List<String>, Boolean) -> String?) {
        cancel(); request = Request.Form(title, description, fields, checkLabel, checked, resetLabel, defaults, submit)
    }
    fun complete(current: Request, action: () -> Unit) { if (request === current) { request = null; action() } }
    fun cancel() {
        val previous = request; request = null
        when (previous) { is Request.Message -> previous.onCancel(); is Request.Choices -> previous.onCancel(); else -> Unit }
    }
}

@Composable internal fun WheelPlayDialog(host: WheelPlayDialogs) {
    val request = host.request ?: return
    key(request) {
        when (request) {
            is WheelPlayDialogs.Request.Message -> AlertDialog(
                onDismissRequest = host::cancel, title = { Text(request.title) },
                text = { Text(request.text, Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { Column { request.actions.forEachIndexed { index, action ->
                    TextButton(onClick = { host.complete(request, action.run) }, modifier = Modifier.testTag("dialog:action:$index")) { Text(action.label) }
                } } }, dismissButton = { TextButton(onClick = host::cancel) { Text(stringResource(android.R.string.cancel)) } },
            )
            is WheelPlayDialogs.Request.Choices -> AlertDialog(
                onDismissRequest = host::cancel, title = { Text(request.title) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    request.options.forEachIndexed { index, option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dialog:choice:$index")
                            .selectable(request.selected == index, role = Role.RadioButton, onClick = { host.complete(request) { request.choose(index) } }),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (request.selected != null) RadioButton(selected = request.selected == index, onClick = null)
                            Text(option, Modifier.padding(8.dp))
                        }
                    }
                } }, confirmButton = { request.extra?.let { action -> TextButton(onClick = { host.complete(request, action.run) }) { Text(action.label) } } },
                dismissButton = { TextButton(onClick = host::cancel) { Text(stringResource(android.R.string.cancel)) } },
            )
            is WheelPlayDialogs.Request.Form -> {
                val values = remember { mutableStateListOf<String>().also { it.addAll(request.fields.map { field -> field.initial }) } }
                var checked by remember { mutableStateOf(request.checked) }
                var error by remember { mutableStateOf<String?>(null) }
                DisposableEffect(Unit) { onDispose { values.clear() } }
                AlertDialog(onDismissRequest = host::cancel, title = { Text(request.title) },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(request.description)
                        request.fields.forEachIndexed { index, field ->
                            OutlinedTextField(value = values[index], onValueChange = { values[index] = it; error = null },
                                label = { Text(field.label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("form:${field.key}"),
                                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(keyboardType = when { field.secret -> KeyboardType.Password; field.numeric -> KeyboardType.Number; else -> KeyboardType.Text }))
                        }
                        request.checkLabel?.let { label ->
                            Row(Modifier.fillMaxWidth().selectable(checked, role = Role.Checkbox, onClick = { checked = !checked }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null); Text(label)
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("form:error")) }
                        request.defaults?.let { defaults ->
                            TextButton(onClick = { values.clear(); values.addAll(defaults); error = null }, modifier = Modifier.testTag("form:reset")) { Text(request.resetLabel.orEmpty()) }
                        }
                    } },
                    confirmButton = { TextButton(onClick = {
                        error = request.submit(values.toList(), checked)
                        if (error == null) host.complete(request) {}
                    }, modifier = Modifier.testTag("dialog:confirm")) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = host::cancel) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
    }
}
