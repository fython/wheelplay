package moe.feng.wheelplay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import java.io.ByteArrayOutputStream
import java.io.File
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
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OemSettingsTest : ComposeSettingsTest() {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    private fun button(activity: Activity, title: String) =
        views(activity.window.decorView).filterIsInstance<Button>().single {
            it.text.toString() == title
        }

    private fun openSettings(activity: WheelPlayActivity) {
        SettingsNavigation.open(activity, "车厂与定位", compose)
    }

    private fun png(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    @Test
    fun legacyDefaultMigratesOnceAndExplicitBydSurvivesReload() {
        context
            .getSharedPreferences("xcertplay_airplay", 0)
            .edit()
            .putString("oem_label", "BYD")
            .commit()
        assertEquals("NIO", AirPlayPersistence.loadSimulatedManufacturerName(context))
        AirPlayPersistence.saveSimulatedManufacturerName(context, " BYD ")
        repeat(2) { assertEquals("BYD", AirPlayPersistence.loadSimulatedManufacturerName(context)) }
        AirPlayPersistence.saveSimulatedManufacturerName(context, "   ")
        assertEquals("NIO", AirPlayPersistence.loadSimulatedManufacturerName(context))
    }

    @Test
    fun interruptedIconWriteRecoversLastCompleteImageAndResetRemovesIt() {
        val original = png()
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        val file = AirPlayPersistence.loadCustomAirPlayIconFile(context)!!
        AtomicFile(file).startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(
            original,
            AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes(),
        )
        AirPlayPersistence.clearCustomAirPlayIcon(context)
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
    }

    @Test
    fun phoneConfigurationAdvertisesCustomNameAndIcon() {
        val original = png()
        AirPlayPersistence.saveSimulatedManufacturerName(context, "比亚迪")
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        // Build the real phone configuration without starting Bluetooth/USB or a session.
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val type = CarPlayHostActivity::class.java
        type.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        type
            .getDeclaredField("airPlayIdentity")
            .apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(context))
        val sizeType = type.declaredClasses.single { it.simpleName == "DisplaySize" }
        val size =
            sizeType
                .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(1280, 720)
        val config =
            type
                .getDeclaredMethod("createAirPlayConfig", sizeType)
                .apply { isAccessible = true }
                .invoke(host, size) as AirPlayConfig
        val runtime =
            type.getDeclaredMethod("createRuntimeConfig").apply { isAccessible = true }.invoke(host)
                as CarPlayRuntimeConfig
        assertEquals("比亚迪", runtime.identification.manufacturer)
        val info = AirPlayInfoPlist.build(config)
        assertEquals("比亚迪", info["oemIconLabel"])
        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(64, icon["widthPixels"])
        assertEquals(64, icon["heightPixels"])
        assertArrayEquals(original, icon["imageData"] as ByteArray)
    }

    @Test
    fun nameEditorPersistsAndNewSettingsScreenShowsSavedName() {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        openSettings(controller.get())
        clickTag("text:OEM_NAME")
        input("  比亚迪  ")
        saveInput()
        assertEquals("比亚迪", AirPlayPersistence.loadSimulatedManufacturerName(context))
        controller.pause().stop().destroy()
        val next = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try {
            openSettings(next.get())
            compose.onNodeWithTag("text:OEM_NAME").assertTextContains("比亚迪")
        } finally {
            next.pause().stop().destroy()
        }
    }

    @Test
    fun previewAndIndependentIconResetKeepCustomNameAndConfirmedResetRestoresBoth() {
        AirPlayPersistence.saveSimulatedManufacturerName(context, "Custom car")
        AirPlayPersistence.saveCustomAirPlayIcon(context, png())
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            compose.waitUntil(8_000) {
                compose
                    .onAllNodesWithContentDescription("CarPlay 车厂图标预览")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("恢复默认名称与图标").performScrollTo().performClick()
            clickText("取消")
            assertEquals("Custom car", AirPlayPersistence.loadSimulatedManufacturerName(context))
            assertNotNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
            compose.onNodeWithText("恢复默认图标").performScrollTo().performClick()
            assertEquals("Custom car", AirPlayPersistence.loadSimulatedManufacturerName(context))
            assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
            compose.onNodeWithText("恢复默认图标").assertIsNotEnabled()
            AirPlayPersistence.saveCustomAirPlayIcon(context, png())
            controller.pause().resume()
            compose.onNodeWithText("恢复默认名称与图标").performScrollTo().performClick()
            clickText("确认")
            assertEquals("NIO", AirPlayPersistence.loadSimulatedManufacturerName(context))
            assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pickerRequestsOnlyImagesAndCancellationKeepsCurrentIcon() {
        val original = png()
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            compose.onNodeWithText("选择车厂图标").performScrollTo().performClick()
            val picker = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_GET_CONTENT, picker.action)
            assertEquals("image/*", picker.type)
            shadowOf(activity).receiveResult(picker, Activity.RESULT_CANCELED, null)
            assertArrayEquals(
                original,
                AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes(),
            )
            assertNull(shadowOf(activity).nextStartedActivity)
            compose.onNodeWithText("选择车厂图标").performScrollTo().performClick()
            val nextPicker = shadowOf(activity).nextStartedActivity
            val uri = Uri.parse("content://images/chosen.png")
            shadowOf(activity).receiveResult(nextPicker, Activity.RESULT_OK, Intent().setData(uri))
            val crop = shadowOf(activity).nextStartedActivity
            assertEquals(ImageCropActivity::class.java.name, crop.component!!.className)
            assertEquals(uri, crop.data)
            assertTrue(crop.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            shadowOf(activity).receiveResult(crop, Activity.RESULT_CANCELED, null)
            assertArrayEquals(
                original,
                AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun waitForText(activity: Activity, text: String) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (
                views(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == text
                }
            )
                return
            Thread.sleep(10)
        }
        fail("Image loading should complete: $text")
    }

    @Test
    fun selectedImageIsCroppedToDurableSquarePngWithoutChangingName() {
        AirPlayPersistence.saveSimulatedManufacturerName(context, "Custom car")
        val source = File(context.cacheDir, "oem-source.png").apply { writeBytes(png(240, 120)) }
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(source)),
                )
                .setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 360, 800)
        waitForText(activity, "拖动调整位置，双指缩放；保存为正方形图标")
        button(activity, "保存图标").performClick()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        source.delete()
        val file = AirPlayPersistence.loadCustomAirPlayIconFile(context)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(1024, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)
        assertEquals("Custom car", AirPlayPersistence.loadSimulatedManufacturerName(context))
        controller.pause().stop().destroy()
    }

    @Test
    fun invalidImageOrCancelledCropDoesNotReplaceSavedIcon() {
        val original = png()
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        val source = File(context.cacheDir, "invalid-image.png").apply { writeText("not an image") }
        val controller =
            Robolectric.buildActivity(
                    ImageCropActivity::class.java,
                    Intent().setData(Uri.fromFile(source)),
                )
                .setup()
        val activity = controller.get()
        waitForText(activity, "无法读取图片，请返回重新选择 PNG 或 JPEG 图片")
        button(activity, "保存图标").performClick()
        assertFalse(activity.isFinishing)
        button(activity, "取消").performClick()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertArrayEquals(
            original,
            AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes(),
        )
        controller.pause().stop().destroy()
    }
}
