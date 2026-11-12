// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.shilapi.xcertplay.i18n.UiText
import com.shilapi.xcertplay.shared.R as UiR

internal data class CarPlayConnectionUiState(
    val stage: UiText = UiText(UiR.string.host_preparing_carplay),
    val failed: Boolean = false,
    val wifiResetRequired: Boolean = false,
    val stopping: Boolean = false,
    val requestChannelPermission: Boolean = false,
)

@Composable
internal fun CarPlayConnectionDialog(
    state: CarPlayConnectionUiState,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onSettings: () -> Unit,
    onResetWifi: () -> Unit,
    onChannelPermission: (Boolean) -> Unit,
) {
    if (state.requestChannelPermission) {
        AlertDialog(
            onDismissRequest = { onChannelPermission(false) },
            title = { Text(stringResource(UiR.string.host_improve_hotspot_channel_detection)) },
            text = {
                Text(
                    stringResource(
                        UiR.string.ui_android_requires_precise_location_permission_to_read_wi_fi
                    ),
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { onChannelPermission(true) }) {
                    Text(stringResource(UiR.string.host_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { onChannelPermission(false) }) {
                    Text(stringResource(UiR.string.host_continue_with_auto))
                }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
        return
    }
    AlertDialog(
        modifier = Modifier.testTag("carplay:connection-dialog"),
        onDismissRequest = { if (!state.stopping) onCancel() },
        properties =
            DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = !state.stopping),
        title = {
            Text(
                stringResource(
                    when {
                        state.stopping -> UiR.string.carplay_connection_stopping
                        state.failed -> UiR.string.carplay_connection_failed
                        else -> UiR.string.host_connecting_to_iphone
                    }
                )
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    if (state.stopping) stringResource(UiR.string.carplay_connection_stopping_hint)
                    else state.stage.resolve(LocalContext.current),
                    Modifier.testTag("carplay:connection-stage").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                if (!state.failed || state.stopping) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("carplay:connection-progress")
                    )
                }
            }
        },
        confirmButton = {
            if (!state.stopping) {
                TextButton(
                    onClick =
                        when {
                            state.wifiResetRequired -> onResetWifi
                            state.failed -> onSettings
                            else -> onBackground
                        },
                    modifier = Modifier.testTag("carplay:connection-action"),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.wifiResetRequired -> UiR.string.ui_reset_carplay_wi_fi
                                state.failed -> UiR.string.carplay_connection_settings
                                else -> UiR.string.carplay_connection_background
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.stopping,
                modifier = Modifier.testTag("carplay:connection-cancel"),
            ) {
                Text(stringResource(UiR.string.carplay_connection_cancel))
            }
        },
    )
}
