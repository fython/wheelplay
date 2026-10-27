package moe.feng.wheelplay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import java.io.File
import moe.feng.wheelplay.ui.crop.IconCropViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconCropScreenTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun source(width: Int = 360, height: Int = 120, jpeg: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, width / 3f, height.toFloat(), Paint().apply { color = Color.RED })
            drawRect(
                width / 3f,
                0f,
                width * 2 / 3f,
                height.toFloat(),
                Paint().apply { color = Color.GREEN },
            )
            drawRect(
                width * 2 / 3f,
                0f,
                width.toFloat(),
                height.toFloat(),
                Paint().apply { color = Color.BLUE },
            )
        }
        return File(context.cacheDir, if (jpeg) "crop-source.jpg" else "crop-source.png").also {
            file ->
            file.outputStream().use {
                bitmap.compress(
                    if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    100,
                    it,
                )
            }
            bitmap.recycle()
        }
    }

    private fun waitForImage() {
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("crop:preview").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun dragZoomResetAndRecreationSaveTheVisibleRegion() {
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(source())),
                )
                .setup()
        try {
            waitForImage()
            compose.onNodeWithTag("crop:zoom").performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(2f)
            }
            compose.onNodeWithTag("crop:preview").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("crop:reset").performClick()
            val model = ViewModelProvider(controller.get())[IconCropViewModel::class.java]
            assertEquals(1f, model.selection.zoom)
            assertEquals(
                Color.GREEN,
                model.selection.render(model.bitmap!!).let { bitmap ->
                    bitmap.getPixel(512, 512).also { bitmap.recycle() }
                },
            )
            compose.onNodeWithTag("crop:zoom").performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(2f)
            }
            compose.onNodeWithTag("crop:preview").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("crop:preview").performTouchInput { swipeLeft() }
            val selected = model.selection
            controller.recreate()
            waitForImage()
            assertEquals(
                selected,
                ViewModelProvider(controller.get())[IconCropViewModel::class.java].selection,
            )
            captureSettings(controller.get(), "crop-portrait")
            compose.onNodeWithTag("crop:save").performClick()
            compose.waitUntil(8_000) {
                compose.waitForIdle()
                controller.get().isFinishing
            }
            assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
            assertTrue(
                shadowOf(controller.get())
                    .resultIntent
                    .getBooleanExtra(ImageCropActivity.EXTRA_CROP_COMPLETE, false)
            )
            val result =
                BitmapFactory.decodeFile(
                    AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.path
                )
            for (x in listOf(8, 512, 1015)) assertEquals(Color.BLUE, result.getPixel(x, 512))
            result.recycle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h360dp-mdpi")
    fun landscapeControlsRemainVisibleAndCancelKeepsTheExistingIcon() {
        val file = source()
        val original = file.readBytes()
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(file)),
                )
                .setup()
        try {
            waitForImage()
            compose.onNodeWithTag("crop:save").assertIsDisplayed()
            compose.onNodeWithTag("crop:reset").assertIsDisplayed()
            compose.onNodeWithTag("crop:zoom").assertIsDisplayed().performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                it(4f)
            }
            captureSettings(controller.get(), "crop-landscape")
            compose.onNodeWithText("取消").performClick()
            assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
            assertArrayEquals(
                original,
                AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun jpegOrientationIsAppliedBeforeCropping() {
        val file = source(jpeg = true)
        ExifInterface(file.path).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(file)),
                )
                .setup()
        try {
            waitForImage()
            val bitmap = ViewModelProvider(controller.get())[IconCropViewModel::class.java].bitmap!!
            assertEquals(120, bitmap.width)
            assertEquals(360, bitmap.height)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun missingUriFinishesAndCanBeDestroyedSafely() {
        val controller = Robolectric.buildActivity(ImageCropActivity::class.java).setup()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-mdpi")
    fun largeTextKeepsSaveAndScrollableZoomAccessible() {
        RuntimeEnvironment.setFontScale(2f)
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(source())),
                )
                .setup()
        try {
            waitForImage()
            compose.onNodeWithTag("crop:save").assertIsDisplayed()
            compose.onNodeWithTag("crop:preview").assertIsDisplayed()
            compose.onNodeWithTag("crop:zoom").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("crop:reset").performScrollTo().assertIsDisplayed()
            captureSettings(controller.get(), "crop-large-text")
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun oversizedImageIsSampledAndSelectionRestoresWithoutTheOldViewModel() {
        val intent = Intent().setData(Uri.fromFile(source(8192, 64)))
        val first = Robolectric.buildActivity(ImageCropActivity::class.java, intent).setup()
        waitForImage()
        val model = ViewModelProvider(first.get())[IconCropViewModel::class.java]
        assertTrue(model.bitmap!!.width <= 2048)
        assertTrue(model.bitmap!!.height > 0)
        compose.onNodeWithTag("crop:zoom").performSemanticsAction(SemanticsActions.SetProgress) {
            it(3f)
        }
        val selected = model.selection
        val state = android.os.Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        val next =
            Robolectric.buildActivity(ImageCropActivity::class.java, intent)
                .create(state)
                .start()
                .resume()
                .visible()
        try {
            waitForImage()
            val restored = ViewModelProvider(next.get())[IconCropViewModel::class.java]
            assertNotSame(model, restored)
            assertEquals(selected, restored.selection)
        } finally {
            next.pause().stop().destroy()
        }
    }
}
