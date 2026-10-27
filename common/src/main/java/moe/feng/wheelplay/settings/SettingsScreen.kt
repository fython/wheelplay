// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import moe.feng.wheelplay.ui.theme.WheelPlayTheme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shilapi.xcertplay.host.R
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SettingsRoute(
    model: SettingsViewModel,
    actions: SettingsActions,
    dialogs: SettingsDialogHost,
    showDialogs: Boolean = true,
    onScroll: (Int) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    WheelPlayTheme {
        SettingsScreen(state, actions, model::open, model::scrollPosition) { category, offset ->
            model.rememberScroll(category, offset)
            onScroll(offset)
        }
        if (showDialogs) SettingsPlatformDialog(dialogs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    onCategory: (SettingsCategory?) -> Unit,
    initialScroll: (SettingsCategory?) -> Int,
    onScroll: (SettingsCategory?, Int) -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints {
            val gutter = maxOf(if (maxWidth < 360.dp) 16.dp else 24.dp, (maxWidth - 760.dp) / 2)
            key(state.category) {
                val category = state.category
                val scroll = rememberScrollState(initialScroll(category))
                LaunchedEffect(scroll) {
                    snapshotFlow { scroll.value }
                        .distinctUntilChanged()
                        .collect { onScroll(category, it) }
                }
                DisposableEffect(scroll) { onDispose { onScroll(category, scroll.value) } }
                Column(Modifier.fillMaxSize()) {
                    if (category != null)
                        TopAppBar(
                            title = { Text(settingsString(category.title)) },
                            navigationIcon = {
                                IconButton(
                                    onClick = { onCategory(null) },
                                    modifier = Modifier.testTag("settings:back"),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_settings_back),
                                        settingsString(R.string.settings_back),
                                    )
                                }
                            },
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        )
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .testTag("settings:page")
                            .verticalScroll(scroll)
                            .padding(horizontal = gutter)
                            .padding(top = 24.dp, bottom = 32.dp)
                    ) {
                        if (category == null) {
                            Text(
                                settingsString(R.string.settings_title),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            SettingsDescription(R.string.settings_index_summary)
                            SettingsIndexScreen(state, actions, onCategory)
                        } else {
                            SettingsDescription(
                                when (category) {
                                    SettingsCategory.DISPLAY -> R.string.settings_display_effect
                                    SettingsCategory.AUDIO -> R.string.settings_audio_effect
                                    SettingsCategory.CONNECTION,
                                    SettingsCategory.VEHICLE -> R.string.settings_next_connection
                                    else -> category.summary
                                }
                            )
                            when (category) {
                                SettingsCategory.CONNECTION ->
                                    ConnectionSettingsScreen(state, actions)
                                SettingsCategory.DISPLAY -> DisplaySettingsScreen(state, actions)
                                SettingsCategory.AUDIO -> AudioSettingsScreen(state, actions)
                                SettingsCategory.VEHICLE -> VehicleSettingsScreen(state, actions)
                                SettingsCategory.BROWSER -> BrowserSettingsScreen(state, actions)
                                SettingsCategory.WEB -> WebSettingsScreen(state, actions)
                                SettingsCategory.TESLA -> TeslaSettingsScreen(state, actions)
                                SettingsCategory.AUTHENTICATION ->
                                    AuthenticationSettingsScreen(state, actions)
                                SettingsCategory.HELP -> HelpSettingsScreen(state, actions)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsIndexScreen(
    state: SettingsState,
    actions: SettingsActions,
    onCategory: (SettingsCategory?) -> Unit,
) {
    Card(
        onClick = { actions.command(SettingsCommand.UPSTREAM_PROJECT) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                settingsString(R.string.settings_upstream_title),
                style = MaterialTheme.typography.titleLarge,
            )
            SettingsDescription(R.string.settings_upstream_description)
        }
    }
    SettingsCategory.entries.forEach { category ->
        Row(
            Modifier.fillMaxWidth()
                .testTag("category:${category.name}")
                .clickable(role = Role.Button) { onCategory(category) }
                .heightIn(min = 80.dp)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(settingsString(category.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    settingsString(
                        if (
                            category == SettingsCategory.AUTHENTICATION &&
                                state.runtime.authenticationError != null
                        )
                            R.string.settings_auth_missing_summary
                        else category.summary
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(R.drawable.ic_settings_chevron),
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Settings", locale = "zh", widthDp = 360, heightDp = 800)
@Preview(
    name = "Settings large text",
    locale = "zh",
    widthDp = 320,
    heightDp = 640,
    fontScale = 1.3f,
)
@Composable
private fun SettingsIndexPreview() {
    WheelPlayTheme { SettingsScreen(SettingsState(), previewActions, {}, { 0 }, { _, _ -> }) }
}

@Preview(name = "Audio", locale = "zh", widthDp = 360, heightDp = 800)
@Composable
private fun AudioSettingsPreview() {
    WheelPlayTheme {
        SettingsScreen(
            SettingsState(
                category = SettingsCategory.AUDIO,
                choices = ChoiceSetting.entries.associateWith { 0 },
                toggles = ToggleSetting.entries.associateWith { false },
            ),
            previewActions,
            {},
            { 0 },
            { _, _ -> },
        )
    }
}

private val previewActions =
    SettingsActions(
        { _, _ -> },
        { _, _ -> },
        { _, _ -> null },
        { _, _ -> null },
        {},
        {},
        {},
        { _, _ -> },
        {},
    )
