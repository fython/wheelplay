// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VehicleSettingsScreen(state: SettingsState, actions: SettingsActions) {
    var reset by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    val icon by
        produceState<ImageBitmap?>(null, state.iconPath, state.iconVersion) {
            value =
                withContext(Dispatchers.IO) {
                    (state.iconPath?.let { BitmapFactory.decodeFile(it) }
                            ?: BitmapFactory.decodeResource(context.resources, R.raw.ic_car_home))
                        ?.asImageBitmap()
                }
        }
    SettingsGroup(R.string.settings_oem) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            icon?.let {
                Image(it, settingsString(R.string.settings_icon_preview), Modifier.size(72.dp))
            }
            Column {
                Text(state.manufacturer, style = MaterialTheme.typography.titleLarge)
                SettingsDescription(
                    if (state.iconPath == null) R.string.settings_default_icon
                    else R.string.settings_custom_icon
                )
            }
        }
        SettingsDescription(R.string.settings_oem_description)
        SettingsTextPreference(
            actions,
            TextSetting.OEM_NAME,
            R.string.settings_oem_name,
            state.manufacturer,
        )
        SettingsButton(R.string.settings_select_icon) {
            actions.command(SettingsCommand.SELECT_ICON)
        }
        SettingsDescription(R.string.settings_icon_description)
        SettingsButton(R.string.settings_reset_icon, enabled = state.iconPath != null) {
            actions.resetBranding(false)
        }
        SettingsButton(R.string.settings_reset_branding) { reset = true }
    }
    SettingsGroup(R.string.settings_location) {
        SettingsChoice(
            state,
            actions,
            ChoiceSetting.LOCATION,
            R.string.settings_location_source,
            listOf(
                settingsString(R.string.settings_location_iphone),
                settingsString(R.string.settings_location_android),
            ),
        )
        SettingsDescription(R.string.settings_location_description)
    }
    if (state.navigationAvailable)
        SettingsGroup(R.string.settings_navigation) {
            Text(
                settingsString(R.string.settings_navigation_manufacturer, state.manufacturer),
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsToggle(
                state,
                actions,
                ToggleSetting.NAVIGATION,
                R.string.settings_navigation_output,
                R.string.settings_navigation_description,
            )
        }
    if (reset)
        SettingsConfirmation(
            R.string.settings_reset_branding_title,
            R.string.settings_reset_branding_message,
            { reset = false },
        ) {
            actions.resetBranding(true)
        }
}
