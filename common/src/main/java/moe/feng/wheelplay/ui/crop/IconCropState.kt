// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui.crop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

internal typealias UpdateIconCropSelection = ((IconCropSelection) -> IconCropSelection) -> Unit

/** Coordinates are relative to the decoded source, independent of the window size. */
internal data class IconCropSelection(
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val zoom: Float = 1f,
) {
    fun sourceRect(width: Int, height: Int): Rect {
        val edge = min(width, height) / zoom.coerceIn(1f, MAX_ZOOM)
        val left = (centerX * width - edge / 2).coerceIn(0f, width - edge)
        val top = (centerY * height - edge / 2).coerceIn(0f, height - edge)
        return Rect(left, top, left + edge, top + edge)
    }

    fun transform(
        width: Int,
        height: Int,
        viewport: Rect,
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
    ): IconCropSelection {
        if (viewport.width <= 0f || !zoomChange.isFinite() || zoomChange <= 0f) return this
        val oldRect = sourceRect(width, height)
        val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val newEdge = min(width, height) / newZoom
        val anchor =
            oldRect.topLeft + (centroid - viewport.topLeft) * (oldRect.width / viewport.width)
        val topLeft = anchor - (centroid + pan - viewport.topLeft) * (newEdge / viewport.width)
        return IconCropSelection(
            (topLeft.x + newEdge / 2).coerceIn(newEdge / 2, width - newEdge / 2) / width,
            (topLeft.y + newEdge / 2).coerceIn(newEdge / 2, height - newEdge / 2) / height,
            newZoom,
        )
    }

    fun withZoom(width: Int, height: Int, value: Float): IconCropSelection {
        val rect = copy(zoom = value.coerceIn(1f, MAX_ZOOM)).sourceRect(width, height)
        return IconCropSelection(
            rect.center.x / width,
            rect.center.y / height,
            value.coerceIn(1f, MAX_ZOOM),
        )
    }

    /**
     * Render the same floating-point source rectangle used by the preview, without rounding drift.
     */
    fun render(source: Bitmap): Bitmap {
        val rect = sourceRect(source.width, source.height)
        val output = Bitmap.createBitmap(OUTPUT_PIXELS, OUTPUT_PIXELS, Bitmap.Config.ARGB_8888)
        val scale = OUTPUT_PIXELS / rect.width
        Canvas(output).apply {
            scale(scale, scale)
            translate(-rect.left, -rect.top)
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val OUTPUT_PIXELS = 1024
    }
}
