// SPDX-License-Identifier: AGPL-3.0-only
// Canvas overlay and grid drawing adapted from crop-kit (MIT), Copyright (c) 2022 Tanish Ranjan.
// See docs/CROP-KIT-LICENSE.txt and the packaged third-party notice.
package moe.feng.wheelplay.ui.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.shared.R as UiR
import kotlin.math.min

@Composable
internal fun IconCropScreen(
    bitmap: Bitmap?,
    selection: IconCropSelection,
    error: Int?,
    saving: Boolean,
    onSelectionChange: UpdateIconCropSelection,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Text(
                stringResource(UiR.string.ui_crop_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                val toolsMaxHeight = maxHeight * .48f
                val tools: @Composable (Modifier) -> Unit = { modifier ->
                    CropTools(bitmap, selection, error, saving, onSelectionChange, modifier)
                }
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier, contentAlignment = Alignment.Center) {
                        if (bitmap != null) {
                            CropCanvas(
                                bitmap,
                                selection,
                                !saving,
                                onSelectionChange,
                                Modifier.fillMaxSize(),
                            )
                        } else if (error == null) {
                            CircularProgressIndicator(Modifier.size(40.dp))
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        preview(Modifier.weight(1f).fillMaxHeight())
                        tools(Modifier.width(280.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        preview(Modifier.weight(1f).fillMaxWidth())
                        tools(Modifier.fillMaxWidth().heightIn(max = toolsMaxHeight))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !saving) {
                    Text(stringResource(UiR.string.ui_cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = bitmap != null && !saving,
                    modifier = Modifier.testTag("crop:save"),
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (saving) UiR.string.ui_crop_saving else UiR.string.ui_save_icon
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CropTools(
    bitmap: Bitmap?,
    selection: IconCropSelection,
    error: Int?,
    saving: Boolean,
    onSelectionChange: UpdateIconCropSelection,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(stringResource(UiR.string.ui_crop_format), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                error
                    ?: if (bitmap == null) UiR.string.ui_reading_image
                    else UiR.string.ui_drag_to_reposition_and_pinch_to_zoom_saved_as
            ),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (error == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (bitmap != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(UiR.string.ui_crop_zoom_value, (selection.zoom * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(
                    onClick = { onSelectionChange { IconCropSelection() } },
                    enabled = !saving,
                    modifier = Modifier.testTag("crop:reset"),
                ) {
                    Text(stringResource(UiR.string.ui_crop_reset))
                }
            }
            val zoomLabel = stringResource(UiR.string.ui_crop_zoom)
            Slider(
                value = selection.zoom,
                onValueChange = {
                    onSelectionChange { current ->
                        current.withZoom(bitmap.width, bitmap.height, it)
                    }
                },
                valueRange = 1f..IconCropSelection.MAX_ZOOM,
                enabled = !saving,
                modifier =
                    Modifier.testTag("crop:zoom").semantics { contentDescription = zoomLabel },
            )
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    selection: IconCropSelection,
    enabled: Boolean,
    onSelectionChange: UpdateIconCropSelection,
    modifier: Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val onChange by rememberUpdatedState(onSelectionChange)
    val description = stringResource(UiR.string.ui_crop_preview)
    val actions =
        listOf(
            stringResource(UiR.string.ui_crop_move_left) to Offset(-1f, 0f),
            stringResource(UiR.string.ui_crop_move_right) to Offset(1f, 0f),
            stringResource(UiR.string.ui_crop_move_up) to Offset(0f, -1f),
            stringResource(UiR.string.ui_crop_move_down) to Offset(0f, 1f),
        )
    Canvas(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clipToBounds()
            .testTag("crop:preview")
            .semantics {
                contentDescription = description
                if (enabled)
                    customActions =
                        actions.map { (label, direction) ->
                            CustomAccessibilityAction(label) {
                                onChange { current ->
                                    current.transform(
                                        bitmap.width,
                                        bitmap.height,
                                        Rect(0f, 0f, 100f, 100f),
                                        Offset(50f, 50f),
                                        direction * 10f,
                                        1f,
                                    )
                                }
                                true
                            }
                        }
            }
            .pointerInput(bitmap, enabled) {
                if (enabled)
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val viewport =
                            cropViewport(
                                Size(size.width.toFloat(), size.height.toFloat()),
                                24.dp.toPx(),
                            )
                        onChange { current ->
                            current.transform(
                                bitmap.width,
                                bitmap.height,
                                viewport,
                                centroid,
                                pan,
                                zoom,
                            )
                        }
                    }
            }
    ) {
        val viewport = cropViewport(size, 24.dp.toPx())
        if (viewport.width <= 0) return@Canvas
        // Checkerboard makes transparency visible without baking a background into the saved PNG.
        val tile = 12.dp.toPx()
        clipPath(Path().apply { addRect(viewport) }) {
            for (y in 0..(viewport.height / tile).toInt()) {
                for (x in 0..(viewport.width / tile).toInt()) {
                    drawRect(
                        if ((x + y) % 2 == 0) Color(0xff41494d) else Color(0xff30383c),
                        viewport.topLeft + Offset(x * tile, y * tile),
                        Size(tile, tile),
                    )
                }
            }
        }
        val source = selection.sourceRect(bitmap.width, bitmap.height)
        val scale = viewport.width / source.width
        withTransform({
            translate(viewport.left - source.left * scale, viewport.top - source.top * scale)
            scale(scale, scale, Offset.Zero)
        }) {
            drawImage(image)
        }
        clipPath(Path().apply { addRect(viewport) }, clipOp = ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = .65f))
        }
        drawRect(Color.White, viewport.topLeft, viewport.size, style = Stroke(2.dp.toPx()))
        for (i in 1..2) {
            val step = viewport.width * i / 3
            drawLine(
                Color.White.copy(alpha = .45f),
                Offset(viewport.left + step, viewport.top),
                Offset(viewport.left + step, viewport.bottom),
                1.dp.toPx(),
            )
            drawLine(
                Color.White.copy(alpha = .45f),
                Offset(viewport.left, viewport.top + step),
                Offset(viewport.right, viewport.top + step),
                1.dp.toPx(),
            )
        }
    }
}

private fun cropViewport(size: Size, padding: Float): Rect {
    val side = (min(size.width, size.height) - 2 * padding).coerceAtLeast(0f)
    return Rect(Offset((size.width - side) / 2, (size.height - side) / 2), Size(side, side))
}
