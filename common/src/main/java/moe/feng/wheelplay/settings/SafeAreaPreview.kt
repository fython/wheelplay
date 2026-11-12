// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.feng.wheelplay.CarPlaySafeMargins

internal enum class SafeAreaEdge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

/**
 * A padded, aspect-correct viewport keeps even zero-margin handles away from the touch boundary.
 */
internal fun safeAreaFrame(size: Size, aspect: Float, padding: Float): Rect {
    val availableWidth = (size.width - padding * 2).coerceAtLeast(1f)
    val availableHeight = (size.height - padding * 2).coerceAtLeast(1f)
    val ratio = aspect.takeIf { it.isFinite() && it > 0 } ?: (16f / 9f)
    val width = minOf(availableWidth, availableHeight * ratio)
    val height = width / ratio
    return Rect(
        (size.width - width) / 2,
        (size.height - height) / 2,
        (size.width + width) / 2,
        (size.height + height) / 2,
    )
}

internal fun CarPlaySafeMargins.move(edge: SafeAreaEdge, percent: Float): CarPlaySafeMargins {
    val value = percent.roundToInt().coerceIn(0, 40)
    return when (edge) {
        SafeAreaEdge.TOP -> copy(top = value)
        SafeAreaEdge.BOTTOM -> copy(bottom = value)
        SafeAreaEdge.LEFT -> copy(left = value)
        SafeAreaEdge.RIGHT -> copy(right = value)
    }
}

@Composable
internal fun SafeAreaPreview(
    margins: CarPlaySafeMargins,
    aspectRatio: Float,
    enabled: Boolean,
    onChange: (CarPlaySafeMargins) -> Unit,
) {
    val current by rememberUpdatedState(margins)
    val change by rememberUpdatedState(onChange)
    val padding = with(LocalDensity.current) { 24.dp.toPx() }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val foreground = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val description = settingsString(R.string.settings_safe_area_preview)
    val summary =
        settingsString(
            R.string.settings_safe_area_value,
            margins.top,
            margins.bottom,
            margins.left,
            margins.right,
        )
    val edgeLabels =
        listOf(
                R.string.settings_margin_top,
                R.string.settings_margin_bottom,
                R.string.settings_margin_left,
                R.string.settings_margin_right,
            )
            .map { settingsString(it) }
    val increase = edgeLabels.map { settingsString(R.string.settings_safe_area_increase, it) }
    val decrease = edgeLabels.map { settingsString(R.string.settings_safe_area_decrease, it) }
    Canvas(
        Modifier.fillMaxWidth()
            .height(208.dp)
            .testTag("safe-area:preview")
            .semantics {
                contentDescription = description
                stateDescription = summary
                if (enabled)
                    customActions =
                        SafeAreaEdge.entries.flatMapIndexed { index, edge ->
                            val value =
                                listOf(current.top, current.bottom, current.left, current.right)[
                                    index]
                            listOf(
                                CustomAccessibilityAction(increase[index]) {
                                    change(current.move(edge, value + 1f))
                                    true
                                },
                                CustomAccessibilityAction(decrease[index]) {
                                    change(current.move(edge, value - 1f))
                                    true
                                },
                            )
                        }
            }
            .pointerInput(aspectRatio, padding, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val frame =
                        safeAreaFrame(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            aspectRatio,
                            padding,
                        )
                    val bounds =
                        Rect(
                            frame.left + frame.width * current.left / 100,
                            frame.top + frame.height * current.top / 100,
                            frame.right - frame.width * current.right / 100,
                            frame.bottom - frame.height * current.bottom / 100,
                        )
                    val point = down.position
                    if (!frame.inflate(padding).contains(point)) return@awaitEachGesture
                    val edge =
                        listOf(
                                SafeAreaEdge.TOP to abs(point.y - bounds.top),
                                SafeAreaEdge.BOTTOM to abs(point.y - bounds.bottom),
                                SafeAreaEdge.LEFT to abs(point.x - bounds.left),
                                SafeAreaEdge.RIGHT to abs(point.x - bounds.right),
                            )
                            .filter { it.second <= padding }
                            .minByOrNull { it.second }
                            ?.first ?: return@awaitEachGesture
                    // Preserve the grab offset; touching the 48dp hit area must not jump the
                    // boundary.
                    val grabOffset =
                        when (edge) {
                            SafeAreaEdge.TOP -> point.y - bounds.top
                            SafeAreaEdge.BOTTOM -> point.y - bounds.bottom
                            SafeAreaEdge.LEFT -> point.x - bounds.left
                            SafeAreaEdge.RIGHT -> point.x - bounds.right
                        }
                    down.consume()
                    drag(down.id) { event ->
                        event.consume()
                        val value =
                            when (edge) {
                                SafeAreaEdge.TOP ->
                                    (event.position.y - grabOffset - frame.top) * 100 / frame.height
                                SafeAreaEdge.BOTTOM ->
                                    (frame.bottom - event.position.y + grabOffset) * 100 /
                                        frame.height
                                SafeAreaEdge.LEFT ->
                                    (event.position.x - grabOffset - frame.left) * 100 / frame.width
                                SafeAreaEdge.RIGHT ->
                                    (frame.right - event.position.x + grabOffset) * 100 /
                                        frame.width
                            }
                        change(current.move(edge, value))
                    }
                }
            }
    ) {
        val frame = safeAreaFrame(size, aspectRatio, padding)
        val safe =
            Rect(
                frame.left + frame.width * margins.left / 100,
                frame.top + frame.height * margins.top / 100,
                frame.right - frame.width * margins.right / 100,
                frame.bottom - frame.height * margins.bottom / 100,
            )
        drawRect(background, frame.topLeft, frame.size)
        drawRect(outline, frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
        drawRect(foreground.copy(alpha = .18f), safe.topLeft, safe.size)
        drawRect(foreground, safe.topLeft, safe.size, style = Stroke(2.dp.toPx()))
        listOf(
                Offset(safe.center.x, safe.top),
                Offset(safe.center.x, safe.bottom),
                Offset(safe.left, safe.center.y),
                Offset(safe.right, safe.center.y),
            )
            .forEach { drawCircle(foreground, 6.dp.toPx(), it) }
    }
}
