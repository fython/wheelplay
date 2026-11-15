package moe.feng.wheelplay.plugin.sample

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import java.io.File
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import moe.feng.wheelplay.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressLint("RestrictedApi")
class SampleSettingsProviderTest {
    @Test fun schemaAndRealRemoteComposeDocumentEditTheSamePreferences() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildContentProvider(SampleSettingsProvider::class.java).create()
        val provider = controller.get()
        val source = ComponentName(context, SampleProjectionService::class.java).flattenToString()
        fun request(method: String = ProjectionSettings.GET, extras: Bundle = ProjectionSettings.request(remote = true)) =
            provider.call(method, source, extras)
        val first = request()
        assertEquals(first.getString("message"), "ok", first.getString("status"))
        val schema = PluginSettingsSchema.fromJson(first.getString("schema")!!)
        assertEquals(true, schema.fields.first { it.key == "counter" }.value)
        val bytes = first.getByteArray("remote_document")!!
        val document = RemoteDocument(bytes)
        assertTrue(document.canBeDisplayed(CoreDocument.MAJOR_VERSION, CoreDocument.MINOR_VERSION, 0))
        val player = RemoteComposePlayer(context)
        var action: Int? = null
        player.setDocument(document)
        player.addIdActionListener { id, _ -> action = id }
        val activityController = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        activityController.get().setContentView(player)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(720, document.document.width)
        assertEquals(360, document.document.height)
        player.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
        player.layout(0, 0, 720, 360)
        val bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
        player.draw(Canvas(bitmap))
        assertEquals(Color.rgb(24, 32, 48), bitmap.getPixel(4, 4))
        assertEquals(Color.rgb(49, 75, 106), bitmap.getPixel(20, 120))
        assertTrue(document.document.clickAreas.any { it.id == 1 })
        val image = File("build/reports/plugin-settings/sample-remote-compose.png")
        requireNotNull(image.parentFile).mkdirs()
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val time = SystemClock.uptimeMillis()
        for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + 16, kind, 100f, 150f, 0)
            player.dispatchTouchEvent(event)
            event.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, action)
        val changed = request(ProjectionSettings.ACTION, ProjectionSettings.request(schema.revision, true).apply {
            putInt("action_id", action!!)
        })
        assertEquals("ok", changed.getString("status"))
        val updated = PluginSettingsSchema.fromJson(changed.getString("schema")!!)
        assertEquals(false, updated.fields.first { it.key == "counter" }.value)
        assertFalse(bytes.contentEquals(changed.getByteArray("remote_document")!!))
        val afterSchemaWrite = request(ProjectionSettings.SET, ProjectionSettings.request(updated.revision, true).apply {
            putString("key", "fps"); putString("value", "25")
        })
        assertEquals(25, PluginSettingsSchema.fromJson(afterSchemaWrite.getString("schema")!!)
            .fields.first { it.key == "fps" }.value)
        assertFalse(changed.getByteArray("remote_document")!!.contentEquals(afterSchemaWrite.getByteArray("remote_document")!!))
        bitmap.recycle()
        activityController.pause().stop().destroy()
        controller.shutdown()
    }
}
