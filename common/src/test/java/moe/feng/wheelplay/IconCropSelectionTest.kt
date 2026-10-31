package moe.feng.wheelplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import moe.feng.wheelplay.ui.crop.IconCropSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconCropSelectionTest {
    @Test
    fun panningSavesTheVisibleRegionAndCannotExposeEmptyEdges() {
        val source = Bitmap.createBitmap(360, 120, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawRect(0f, 0f, 120f, 120f, Paint().apply { color = Color.RED })
            drawRect(120f, 0f, 240f, 120f, Paint().apply { color = Color.GREEN })
            drawRect(240f, 0f, 360f, 120f, Paint().apply { color = Color.BLUE })
        }
        fun assertColor(selection: IconCropSelection, color: Int) {
            val result = selection.render(source)
            assertEquals(1024, result.width)
            assertEquals(1024, result.height)
            for (x in listOf(8, 512, 1015)) for (y in listOf(8, 512, 1015)) {
                assertEquals(color, result.getPixel(x, y))
            }
            result.recycle()
        }
        val viewport = Rect(24f, 100f, 324f, 400f)
        assertColor(IconCropSelection(), Color.GREEN)
        assertColor(
            IconCropSelection()
                .transform(360, 120, viewport, viewport.center, Offset(-10000f, 10000f), 1f),
            Color.BLUE,
        )
        assertColor(
            IconCropSelection()
                .transform(360, 120, viewport, viewport.center, Offset(10000f, -10000f), 1f),
            Color.RED,
        )
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test
    fun pinchKeepsSourcePointUnderMovingFingersAndClampsZoom() {
        val viewport = Rect(20f, 20f, 320f, 320f)
        val start = IconCropSelection(zoom = 2f)
        val centroid = Offset(120f, 160f)
        val pan = Offset(15f, -5f)
        val before = start.sourceRect(360, 240)
        val anchor = before.topLeft + (centroid - viewport.topLeft) * before.width / viewport.width
        val next = start.transform(360, 240, viewport, centroid, pan, 1.5f)
        val after = next.sourceRect(360, 240)
        val newAnchor =
            after.topLeft + (centroid + pan - viewport.topLeft) * after.width / viewport.width
        assertEquals(anchor.x, newAnchor.x, .001f)
        assertEquals(anchor.y, newAnchor.y, .001f)
        assertEquals(8f, next.transform(360, 240, viewport, centroid, Offset.Zero, 100f).zoom)
        assertEquals(1f, next.transform(360, 240, viewport, centroid, Offset.Zero, .001f).zoom)
    }

    @Test
    fun resizedViewportKeepsTheSameCropAndTransparentPixelsStayTransparent() {
        val selection = IconCropSelection(.4f, .6f, 2f)
        val small =
            selection.transform(
                320,
                640,
                Rect(0f, 0f, 100f, 100f),
                Offset(50f, 50f),
                Offset(10f, -20f),
                1.5f,
            )
        val large =
            selection.transform(
                320,
                640,
                Rect(0f, 0f, 400f, 400f),
                Offset(200f, 200f),
                Offset(40f, -80f),
                1.5f,
            )
        assertEquals(small, large)
        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val output = selection.render(transparent)
        assertEquals(0, Color.alpha(output.getPixel(512, 512)))
        assertFalse(transparent.isRecycled)
        transparent.recycle()
        output.recycle()
    }
}
