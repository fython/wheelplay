// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.shared.R as UiR
import moe.feng.wheelplay.settings.*
import moe.feng.wheelplay.ui.theme.WheelPlayTheme

internal typealias UiDispatch = (UiAction, String?) -> Unit

@Composable
internal fun WheelPlayScreen(
    page: String, mode: AaConnection, state: WheelPlayUiState, settings: SettingsViewModel,
    settingsActions: SettingsActions, dialogs: SettingsDialogHost, sourceDialogs: WheelPlayDialogs, snackbar: SnackbarHostState, dispatch: UiDispatch,
) {
    WheelPlayTheme {
        val savedPages = rememberSaveableStateHolder()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    listOf(Triple("service", UiR.string.ui_start, R.drawable.ic_server_launch),
                        Triple("phone", UiR.string.source_tab, R.drawable.ic_server_phone),
                        Triple("settings", R.string.settings_title, R.drawable.ic_server_settings)).forEach { (id, title, icon) ->
                        NavigationBarItem(
                            selected = page == id || (id == "phone" && SourcePage.entries.any { it.id == page }),
                            onClick = { dispatch(UiAction.NAVIGATE, id) },
                            icon = { Icon(painterResource(icon), null) }, label = { Text(stringResource(title)) },
                            modifier = Modifier.testTag("nav:$id"),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                savedPages.SaveableStateProvider(page) {
                    if (page == "settings") SettingsRoute(settings, settingsActions, dialogs, showDialogs = false) {}
                    else PageContent(page) {
                        when (page) {
                            "service" -> HomeScreen(state.home, dispatch)
                            "phone" -> SourceIndex(state.sources, dispatch)
                            else -> SourcePage.entries.find { it.id == page }?.let { SourceDetail(it, mode, state.sources, dispatch) }
                        }
                    }
                }
            }
        }
        // Certificate/import flows may finish while another tab is selected.
        SettingsPlatformDialog(dialogs)
        WheelPlayDialog(sourceDialogs)
    }
}

@Composable
private fun PageContent(page: String, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = maxOf(if (maxWidth < 360.dp) 16.dp else 24.dp, (maxWidth - 760.dp) / 2)
        Column(Modifier.fillMaxSize().testTag("page:$page").verticalScroll(rememberScrollState())
            .padding(horizontal = gutter).padding(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable private fun Description(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Heading(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
@Composable private fun SourcePage.name() = if (title != 0) stringResource(title) else fallback

@Composable
private fun Action(label: String, action: UiAction, dispatch: UiDispatch, argument: String? = null,
    primary: Boolean = false, enabled: Boolean = true, tag: String = "action:${action.name}") {
    val modifier = Modifier.testTag(tag).heightIn(min = if (primary) 56.dp else 48.dp)
    if (primary) Button(onClick = { dispatch(action, argument) }, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(label) }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { dispatch(action, argument) }, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

@Composable private fun CardSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(if (LocalConfiguration.current.screenWidthDp < 360) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading(title); content()
        }
    }
}

@Composable private fun Disclosure(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) UiR.string.ui_expanded else UiR.string.ui_collapsed)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag(tag).semantics { stateDescription = expansion }) {
        Text(title, Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_settings_chevron), null, Modifier.size(20.dp).rotate(if (expanded) 270f else 90f))
    }
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable private fun HomeScreen(state: HomeUi, dispatch: UiDispatch) {
    Heading(stringResource(R.string.app_name))
    Text(stringResource(UiR.string.home_projection_headline), style = MaterialTheme.typography.headlineSmall)
    CardSection(stringResource(UiR.string.ui_head_unit_access)) {
        Description(stringResource(UiR.string.ui_local_network_address))
        SelectionContainer { Text(state.address, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
        if (state.addressDescription.isNotEmpty()) Description(state.addressDescription)
        Action(stringResource(UiR.string.ui_try_in_this_device_s_browser), UiAction.BROWSER, dispatch, enabled = state.browserEnabled)
        if (state.otherAddresses.isNotEmpty()) Disclosure(stringResource(UiR.string.ui_other_addresses), "home:addresses") {
            state.otherAddresses.forEach { entry -> Description(entry.description); SelectionContainer { Text(entry.url) } }
        }
    }
    CardSection(stringResource(UiR.string.ui_browser_pairing)) {
        if (!state.viewerConnected) {
            Description(stringResource(UiR.string.ui_scan_the_qr_code_on_the_head_unit_web))
            Action(stringResource(UiR.string.ui_scan_qr_code_to_pair), UiAction.SCAN, dispatch, primary = true)
            Description(stringResource(UiR.string.ui_or_enter_the_pairing_code_on_the_web_page))
            SelectionContainer { Text(state.code, style = MaterialTheme.typography.headlineLarge) }
        }
        Description(stringResource(if (state.viewerConnected) UiR.string.ui_head_unit_browser_connected else UiR.string.ui_waiting_for_head_unit_browser))
    }
    CardSection(stringResource(UiR.string.home_source_title)) {
        Action(state.sourceName, UiAction.CHOOSE_HOME, dispatch)
        Text(state.stage)
        if (state.showConnect) Action(state.connectLabel, UiAction.HOME_CONNECT, dispatch, primary = true, enabled = state.connectEnabled)
        state.stopLabel?.let { Action(it, UiAction.HOME_STOP, dispatch) }
        Action(stringResource(UiR.string.home_source_settings), UiAction.HOME_SETTINGS, dispatch)
    }
    if (state.showAudioHint) Description(stringResource(UiR.string.ui_audio_and_microphone_use_this_device_by_default_enable))
    Action(stringResource(UiR.string.ui_stop_service), UiAction.STOP_SERVICE, dispatch)
}

@Composable private fun SourceIndex(state: SourcesUi, dispatch: UiDispatch) {
    Text(stringResource(UiR.string.source_tab), style = MaterialTheme.typography.headlineSmall)
    Description(state.overview)
    SourceRows(listOf(SourcePage.CARPLAY, SourcePage.ANDROID_AUTO, SourcePage.ICCOA, SourcePage.VNC, SourcePage.DESKTOP), state, dispatch)
    Heading(stringResource(UiR.string.source_tools))
    SourceRows(listOf(SourcePage.PLUGINS, SourcePage.DIAGNOSTICS), state, dispatch)
}

@Composable private fun SourceRows(pages: List<SourcePage>, state: SourcesUi, dispatch: UiDispatch) {
    BoxWithConstraints {
        // Available content width, including the centered tablet gutter, determines the grid.
        val count = if (maxWidth >= 552.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.chunked(count).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { source ->
                        Card(onClick = { dispatch(UiAction.NAVIGATE, source.id) }, modifier = Modifier.weight(1f).fillMaxHeight().testTag(source.id),
                            shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(source.icon), null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(source.name(), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(source.summary), style = MaterialTheme.typography.bodyMedium)
                                    state.flags[source]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                                }
                                Icon(painterResource(R.drawable.ic_settings_chevron), null, Modifier.size(20.dp))
                            }
                        }
                    }
                    if (row.size < count) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun SessionSummary(page: SourcePage, state: SourcesUi, dispatch: UiDispatch) {
    val session = state.sessions[page.group] ?: return
    Description(session.message)
    if (session.canStop) Action(stringResource(UiR.string.projection_stop), UiAction.STOP_SOURCE, dispatch, page.group)
}

@Composable private fun SourceDetail(page: SourcePage, mode: AaConnection, state: SourcesUi, dispatch: UiDispatch) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { dispatch(UiAction.NAVIGATE, "phone") }, modifier = Modifier.testTag("source:back")) {
            Icon(painterResource(R.drawable.ic_settings_back), stringResource(UiR.string.source_back))
        }
        Text(page.name(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
    if (page != SourcePage.CARPLAY) SessionSummary(page, state, dispatch)
    when (page) {
        SourcePage.CARPLAY -> CarPlayScreen(state, dispatch)
        SourcePage.ANDROID_AUTO -> AndroidAutoScreen(mode, dispatch)
        SourcePage.ICCOA, SourcePage.PLUGINS -> PluginsScreen(page == SourcePage.ICCOA, state, dispatch)
        SourcePage.VNC -> {
            Description(stringResource(UiR.string.source_vnc_brief))
            Action(stringResource(UiR.string.vnc_connect), UiAction.VNC, dispatch, primary = true)
            Disclosure(stringResource(UiR.string.source_vnc_help), "source:help") {
                Description(stringResource(UiR.string.vnc_hint)); Description(stringResource(UiR.string.vnc_security_hint))
            }
        }
        SourcePage.DESKTOP -> {
            Description(stringResource(UiR.string.desktop_hint))
            Action(state.desktopDisplay, UiAction.DESKTOP_DISPLAY, dispatch)
            Description(state.desktopStatus)
            Action(stringResource(UiR.string.desktop_open_shizuku), UiAction.DESKTOP_SHIZUKU, dispatch)
            Action(stringResource(UiR.string.desktop_authorize), UiAction.DESKTOP_AUTHORIZE, dispatch)
            Action(stringResource(UiR.string.desktop_choose_app), UiAction.DESKTOP_APP, dispatch)
            if (state.desktopApp.isNotEmpty()) Description(state.desktopApp)
            Action(stringResource(UiR.string.desktop_start), UiAction.DESKTOP_START, dispatch, primary = true, enabled = state.desktopEnabled)
        }
        SourcePage.DIAGNOSTICS -> {
            Description(stringResource(UiR.string.projection_demo_hint))
            Action(stringResource(UiR.string.projection_choose_demo), UiAction.DEMO, dispatch)
        }
    }
}

@Composable private fun CarPlayScreen(state: SourcesUi, dispatch: UiDispatch) {
    Description(state.carPlayStatus)
    CardSection(stringResource(UiR.string.ui_wireless_connection)) {
        Description(stringResource(UiR.string.ui_pair_the_iphone_with_this_android_device_over_bluetooth))
        Action("iPhone · ${state.phone}", UiAction.CHOOSE_PHONE, dispatch)
        if (state.carPlayRunning) Action(stringResource(UiR.string.ui_disconnect_iphone), UiAction.CARPLAY_STOP, dispatch)
        else Action(stringResource(UiR.string.ui_connect_iphone), UiAction.CARPLAY_CONNECT, dispatch, primary = true, enabled = state.carPlayEnabled)
        state.hotspotHint?.let {
            Description(it); Action(stringResource(UiR.string.ui_open_hotspot_settings), UiAction.HOTSPOT, dispatch)
        }
    }
    CardSection(stringResource(UiR.string.ui_usb_connection)) {
        Description(stringResource(UiR.string.ui_connect_the_iphone_to_this_device_with_a_data))
        Action(stringResource(UiR.string.ui_connect_over_usb), UiAction.CARPLAY_USB, dispatch)
    }
    state.setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Disclosure(stringResource(UiR.string.source_connection_help), "source:help") {
        Description(stringResource(UiR.string.ui_this_device_receives_carplay_the_head_unit_browser_provides))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AndroidAutoScreen(mode: AaConnection, dispatch: UiDispatch) {
    Heading(stringResource(UiR.string.source_connection_method))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AaConnection.entries.forEachIndexed { index, item ->
            SegmentedButton(selected = mode == item, onClick = { dispatch(UiAction.AA_MODE, item.name) },
                shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}, modifier = Modifier.testTag("aa:${item.name}")) {
                Text(stringResource(when (item) { AaConnection.LOCAL -> UiR.string.source_aa_local; AaConnection.USB -> UiR.string.source_aa_usb; AaConnection.NETWORK -> UiR.string.source_aa_network }))
            }
        }
    }
    key(mode) {
        Description(stringResource(when (mode) { AaConnection.LOCAL -> UiR.string.source_aa_local_brief; AaConnection.USB -> UiR.string.aa_usb_hint; AaConnection.NETWORK -> UiR.string.source_aa_network_brief }))
        when (mode) {
            AaConnection.LOCAL -> {
                Action(stringResource(UiR.string.aa_local_connect), UiAction.AA_LOCAL, dispatch, primary = true)
                Action(stringResource(UiR.string.aa_local_help), UiAction.AA_HELP, dispatch)
            }
            AaConnection.USB -> Action(stringResource(UiR.string.aa_usb_connect), UiAction.AA_USB, dispatch, primary = true)
            AaConnection.NETWORK -> Action(stringResource(UiR.string.aa_remote_connect), UiAction.AA_NETWORK, dispatch, primary = true)
        }
        Disclosure(stringResource(UiR.string.source_connection_help), "source:help") {
            Description(stringResource(when (mode) { AaConnection.LOCAL -> UiR.string.aa_local_summary; AaConnection.USB -> UiR.string.source_aa_usb_help; AaConnection.NETWORK -> UiR.string.aa_tcp_hint }))
        }
    }
}

@Composable private fun PluginsScreen(carLink: Boolean, state: SourcesUi, dispatch: UiDispatch) {
    val plugins = state.plugins.filter { !carLink || it.carLink }
    if (plugins.isEmpty()) {
        Heading(stringResource(if (carLink) UiR.string.plugin_iccoa_missing else UiR.string.source_no_plugins))
        Description(stringResource(UiR.string.source_plugin_empty_hint))
        Disclosure(stringResource(UiR.string.source_install_help), "plugin:install-help") {
            Description(stringResource(if (carLink) UiR.string.plugin_iccoa_hint else UiR.string.source_plugin_install_steps))
        }
    }
    plugins.forEach { plugin -> key(plugin.id) {
        CardSection(plugin.name) {
            plugin.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Description(plugin.problem ?: stringResource(if (plugin.enabled) UiR.string.plugin_enabled else UiR.string.source_plugin_enable_hint))
            if (plugin.problem == null) {
                if (!plugin.enabled) Action(stringResource(UiR.string.plugin_enable), UiAction.PLUGIN_ENABLE, dispatch, plugin.id, primary = true, tag = "plugin:enable:${plugin.packageName}")
                else if (plugin.canConnect) Action(stringResource(UiR.string.plugin_connect), UiAction.PLUGIN_CONNECT, dispatch, plugin.id, primary = true, tag = "plugin:connect:${plugin.packageName}")
            }
            if (plugin.hasSettings) Action(stringResource(UiR.string.plugin_settings), UiAction.PLUGIN_SETTINGS, dispatch, plugin.id, tag = "plugin:settings:${plugin.packageName}")
            Disclosure(stringResource(UiR.string.source_plugin_manage), "plugin:manage:${plugin.packageName}") {
                SelectionContainer { Text(plugin.packageName) }
                if (plugin.enabled) Action(stringResource(UiR.string.plugin_disable), UiAction.PLUGIN_DISABLE, dispatch, plugin.id, tag = "plugin:disable:${plugin.packageName}")
            }
        }
    } }
    Action(stringResource(UiR.string.plugin_refresh), UiAction.PLUGIN_REFRESH, dispatch)
    if (carLink) {
        if (plugins.isEmpty()) Action(stringResource(UiR.string.plugin_sources), UiAction.NAVIGATE, dispatch, SourcePage.PLUGINS.id)
    } else Disclosure(stringResource(UiR.string.source_plugin_capabilities), "plugin:capabilities") {
        Description(stringResource(UiR.string.plugin_install_hint))
    }
}
