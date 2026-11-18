package moe.feng.wheelplay.settings

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.projection.plugin.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import moe.feng.wheelplay.plugin.*
import moe.feng.wheelplay.ui.theme.WheelPlayTheme

@Composable
internal fun PluginSettingsDialog(plugin: InstalledProjectionPlugin, lifecycle: Lifecycle,
    onDismiss: () -> Unit, onExternal: (() -> Unit)?,
    requestSettings: suspend (PluginSettingsClient, String, Bundle) -> PluginSettingsSnapshot =
        { client, method, extras -> client.requestAsync(method, extras) }) {
    val context = LocalContext.current.applicationContext
    val client = remember(plugin) { PluginSettingsClient(context, plugin) {
        projectionPlugins(context).entries().contains(plugin)
    } }
    var active by remember { mutableStateOf(false) }
    var snapshot by remember(plugin) { mutableStateOf<PluginSettingsSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadPending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PluginSetting?>(null) }
    var editingRevision by remember { mutableStateOf(0L) }
    val commands = remember(plugin) { Channel<Pair<String, Bundle>>(8) }
    fun submit(method: String = ProjectionSettings.GET, extras: Bundle = ProjectionSettings.request(remote = remote)) {
        if (!active) return
        if (method == ProjectionSettings.GET && busy) { reloadPending = true; return }
        // Sending can resume the consumer inline, including a fast reply or failure.
        // Mark busy first so we cannot overwrite the consumer's completed state.
        val wasBusy = busy
        busy = true
        if (commands.trySend(method to extras).isFailure) {
            busy = wasBusy
            error = context.getString(R.string.plugin_settings_busy)
        }
    }
    val reload by rememberUpdatedState({ submit() })

    DisposableEffect(client, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload() }
        }
        var observing = false
        fun update() {
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (started == observing) return
            if (started) {
                try {
                    context.contentResolver.registerContentObserver(client.uri, true, observer)
                    observing = true; active = true; reload()
                } catch (failure: Exception) { error = failure.message.orEmpty(); active = false }
            } else {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
                observing = false; active = false; snapshot = null; editing = null; busy = false
                while (commands.tryReceive().isSuccess) { /* Discard queued writes on leaving the screen. */ }
            }
        }
        val listener = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(listener)
        update()
        onDispose {
            lifecycle.removeObserver(listener)
            if (observing) runCatching { context.contentResolver.unregisterContentObserver(observer) }
            active = false; commands.close()
        }
    }

    LaunchedEffect(client, active) {
        if (!active) return@LaunchedEffect
        for ((method, extras) in commands) {
            busy = true
            try {
                val result = withTimeout(5000) { requestSettings(client, method, extras) }
                snapshot = result
                error = if (result.conflict) context.getString(R.string.plugin_settings_conflict) else null
            } catch (_: TimeoutCancellationException) {
                snapshot = null; error = context.getString(R.string.plugin_settings_timeout)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snapshot = null; error = failure.message.orEmpty().take(256) }
            finally { busy = false }
            if (reloadPending) { reloadPending = false; submit() }
        }
    }
    LaunchedEffect(plugin, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (!projectionPlugins(context).entries().contains(plugin)) {
                snapshot = null; error = context.getString(R.string.plugin_settings_changed)
                break
            }
        }
    }

    fun save(field: PluginSetting, value: Any?, expectedRevision: Long? = null) {
        val revision = expectedRevision ?: snapshot?.schema?.revision ?: return
        submit(ProjectionSettings.SET, ProjectionSettings.request(revision, remote).apply {
            putString("key", field.key)
            putString("value", ProjectionSettings.encodeValue(value))
        })
    }

    WheelPlayTheme {
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(snapshot?.schema?.title ?: plugin.name) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_settings_close)) } },
            dismissButton = {
                if (onExternal != null) TextButton(onClick = onExternal) {
                    Text(stringResource(R.string.plugin_settings_external))
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                    .testTag("plugin:embedded-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (error != null) TextButton(enabled = active && !busy, onClick = { submit() }) {
                        Text(stringResource(R.string.plugin_settings_retry))
                    }
                    val state = snapshot
                    // Keep a route back to schema if the remote version/document is rejected.
                    if (remote || state?.supportsRemoteCompose == true) Row {
                        TextButton(enabled = !busy, onClick = { remote = false; submit() }) {
                            Text(stringResource(R.string.plugin_settings_schema))
                        }
                        TextButton(enabled = !busy && state?.supportsRemoteCompose == true, onClick = { remote = true; submit() }) {
                            Text(stringResource(R.string.plugin_settings_remote))
                        }
                    }
                    if (remote && state?.document != null) {
                        RemoteSettingsPlayer.Content(state.document, active && !busy,
                            onAction = { id, value ->
                                submit(ProjectionSettings.ACTION, ProjectionSettings.request(state.schema.revision, true).apply {
                                    putInt("action_id", id); putString("action_value", value)
                                })
                            }, onNamedAction = { name, value ->
                                runCatching {
                                    submit(ProjectionSettings.ACTION, ProjectionSettings.request(state.schema.revision, true).apply {
                                        putString("action_name", name); putString("action_json", ProjectionSettings.encodeValue(value))
                                    })
                                }.onFailure { error = it.message.orEmpty() }
                            }, onFailure = { error = it })
                    } else if (!remote) state?.schema?.fields?.forEach { field ->
                        Column(Modifier.fillMaxWidth().testTag("plugin:setting:${field.key}")) {
                            val enabled = active && !busy && field.enabled
                            if (field.type == SettingType.BOOLEAN) Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(field.title, Modifier.weight(1f))
                                Switch(field.value as Boolean, onCheckedChange = { save(field, it) }, enabled = enabled,
                                    modifier = Modifier.testTag("plugin:setting:${field.key}:switch"))
                            } else {
                                Text(field.title, style = MaterialTheme.typography.titleSmall)
                                if (field.type != SettingType.ACTION) Text(field.choices.find { it.value == field.value }?.label
                                    ?: field.value.toString())
                                TextButton(enabled = enabled, onClick = {
                                    if (field.type == SettingType.ACTION) save(field, null) else {
                                        editingRevision = state.schema.revision; editing = field
                                    }
                                }) { Text(stringResource(if (field.type == SettingType.ACTION)
                                    R.string.plugin_settings_run else R.string.plugin_settings_edit)) }
                            }
                            if (field.summary.isNotEmpty()) Text(field.summary)
                            if (field.restartRequired) Text(stringResource(R.string.plugin_settings_reconnect))
                        }
                    }
                }
            })
        editing?.let { field ->
            if (field.type == SettingType.CHOICE) {
                AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.title) },
                    text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        field.choices.forEach { choice -> TextButton(onClick = { editing = null; save(field, choice.value, editingRevision) }) {
                            Text(choice.label)
                        } }
                    } }, confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.plugin_settings_close)) } })
            } else {
                var input by remember(field) { mutableStateOf(field.value.toString()) }
                val value: Any? = if (field.type == SettingType.INTEGER) input.toLongOrNull() else input
                val valid = runCatching { field.validateValue(value) }.isSuccess
                AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.title) },
                    text = { OutlinedTextField(input, onValueChange = { input = it.take(4096) }, isError = !valid,
                        modifier = Modifier.testTag("plugin:setting-input")) },
                    confirmButton = { TextButton(enabled = valid, onClick = { editing = null; save(field, value, editingRevision) }) {
                        Text(stringResource(R.string.plugin_settings_save))
                    } }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.plugin_settings_close)) } })
            }
        }
    }
}
