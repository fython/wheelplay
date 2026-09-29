package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shilapi.xcertplay.web.WebSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.net.Socket
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerCardStateTest {
    private val activities = mutableListOf<ActivityController<DiPlayActivity>>()
    private val output = File("build/reports/material-ui/cards").apply { mkdirs() }

    @Before fun resetSession() {
        CarPlayBackgroundSession.clear()
        WebSession.stop()
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @After fun cleanup() {
        activities.forEach { it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
        WebSession.stop()
    }

    private fun activity() = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        .also { activities.add(it) }.get()
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: DiPlayActivity, text: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == text }
    private fun refresh() = shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Session state should settle", predicate())
    }

    private fun render(activity: DiPlayActivity, name: String, bottom: Boolean = false): ViewGroup {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        if (bottom) views(root).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }.apply {
            scrollTo(0, getChildAt(0).height)
        }
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return root
    }

    @Test fun browserPairingControlsCollapseForAnAuthenticatedViewerAndReturnAfterDisconnect() {
        val activity = activity()
        WebSession.start(activity)
        refresh()
        val scan = button(activity, "扫描二维码配对")
        val controls = scan.parent as LinearLayout
        val code = views(controls).filterIsInstance<TextView>().single { it.text.toString() == WebSession.code }
        val card = controls.parent as LinearLayout
        render(activity, "browser-unpaired")
        val expandedHeight = card.height
        assertTrue(scan.isShown); assertTrue(code.isShown)
        Socket("127.0.0.1", 8080).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write(("GET /stream?code=${WebSession.code} HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8080\r\nOrigin: http://127.0.0.1:8080\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val next = socket.getInputStream().read(); check(next >= 0); headers.append(next.toChar())
            }
            assertTrue(headers.startsWith("HTTP/1.1 101"))
            waitUntil { WebSession.hasViewer }; refresh()
            render(activity, "browser-paired")
            assertFalse(scan.isShown); assertFalse(code.isShown)
            assertTrue("Paired card must reclaim the control group's space", card.height < expandedHeight)
            assertTrue(views(card).filterIsInstance<TextView>().any { it.isShown && it.text == "车机浏览器已连接" })
        }
        waitUntil { !WebSession.hasViewer }; refresh()
        render(activity, "browser-disconnected")
        assertTrue(scan.isShown); assertTrue(code.isShown)
        assertEquals(expandedHeight, card.height)
    }

    @Test fun selectedPhoneCanConnectFromLaunchAndRunningSessionsHideBothConnectActions() {
        val activity = activity()
        val connect = button(activity, "连接")
        val wireless = button(activity, "连接 iPhone")
        val disconnect = button(activity, "断开 iPhone")
        assertEquals(View.GONE, connect.visibility)
        DiPlayPreferences.savePhone(activity, "00:11:22:33:44:55", "测试 iPhone")
        // Unit builds omit runtime authentication assets; model a provisioned APK.
        ReflectionHelpers.setField(activity, "setupError", null)
        AirPlayPersistence.saveWirelessHotspotMode(activity,
            com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P)
        refresh()
        assertEquals(View.VISIBLE, connect.visibility); assertTrue(connect.isEnabled)
        render(activity, "phone-selected", bottom = true)
        connect.performClick()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        // Model a controller with a registered stop callback, including connection-in-progress.
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction",
            { completion: () -> Unit -> CarPlayBackgroundSession.clear(); completion() })
        refresh()
        assertEquals(View.GONE, connect.visibility); assertEquals(View.GONE, wireless.visibility)
        assertEquals(View.VISIBLE, disconnect.visibility)
        CarPlayBackgroundSession.active = true; refresh()
        val tabs = views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single()
        tabs.selectedItemId = 2
        render(activity, "phone-connected")
        assertFalse(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "返回服务面板" })
        disconnect.performClick(); refresh()
        assertEquals(View.VISIBLE, connect.visibility); assertEquals(View.VISIBLE, wireless.visibility)
        assertEquals(View.GONE, disconnect.visibility)
        activity.getSharedPreferences("diplay", 0).edit().remove("phone_address").apply(); refresh()
        assertEquals(View.GONE, connect.visibility)
    }

    @Test fun otherAddressesIsACompactTrailingPillWithHoverAndPressFeedback() {
        val activity = activity()
        val ui = ServerUi(activity)
        var clicks = 0
        val row = ui.DisclosureRow("其他地址") { clicks++ }
        val parent = ui.column().apply { addView(row, ui.secondaryButtonLayout()) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 320, 80)
        assertEquals(320, row.right); assertTrue(row.width < 160); assertTrue(row.height >= 48)
        fun image(name: String): Bitmap {
            val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            row.draw(Canvas(bitmap))
            File(output, "addresses-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        image("normal").let { assertEquals(0, Color.alpha(it.getPixel(8, row.height / 2))); it.recycle() }
        row.isHovered = true
        image("hover").let {
            assertTrue(Color.alpha(it.getPixel(8, row.height / 2)) > 0)
            assertEquals(0, Color.alpha(it.getPixel(0, 0))); it.recycle()
        }
        row.isHovered = false; row.isPressed = true
        image("pressed").let {
            assertTrue(Color.alpha(it.getPixel(8, row.height / 2)) > 0)
            assertEquals(0, Color.alpha(it.getPixel(0, 0))); it.recycle()
        }
        row.isPressed = false; row.performClick(); assertEquals(1, clicks)
        assertEquals(Button::class.java.name, row.accessibilityClassName)
    }
}
