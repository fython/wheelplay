// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

@Composable
internal fun WheelPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Color(0xffb4d9b1),
                onPrimary = Color(0xff20391f),
                secondaryContainer = Color(0xff3c4b39),
                onSecondaryContainer = Color(0xffd9e7d3),
                background = Color(0xff11161a),
                surface = Color(0xff11161a),
                surfaceContainer = Color(0xff1c2428),
                onSurface = Color(0xffe2e8e3),
                onSurfaceVariant = Color(0xffc1c9be),
                outline = Color(0xff8b9389),
            ),
        content = content,
    )
}
