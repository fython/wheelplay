package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.WebListenSettings
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
class HttpsSettingsUiTest {
    @Before fun startWithoutRootAuthorization() {
        val original = RootAccess.authorize
        try {
            RootAccess.authorize = { throw java.io.IOException("Permission denied") }
            RootAccess.request()
        } finally { RootAccess.authorize = original }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun all(activity: DiPlayActivity) = views(activity.window.decorView)
    private fun openSettings(activity: DiPlayActivity) {
        all(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = 3
    }
    private fun rootButton(activity: DiPlayActivity) = all(activity).filterIsInstance<MaterialButton>()
        .single { it.text.toString() == "请求 Root 权限" }
    private fun waitForUi(activity: DiPlayActivity, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate() && rootButton(activity).isEnabled) return
            Thread.sleep(10)
        }
        fail("Settings update did not finish")
    }
    private fun hasRow(activity: DiPlayActivity, title: String) = all(activity).any {
        it.contentDescription?.toString()?.startsWith("$title，") == true
    }

    @Test fun startupChecksRootInBackgroundAndShowsSettingsWithoutPressingTheButton() {
        val original = RootAccess.authorize
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        RootAccess.startupChecked = false
        RootAccess.authorize = {
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS))
        }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(rootButton(activity).isEnabled)
            assertFalse(hasRow(activity, "映射 HTTP 端口"))
            assertTrue(hasRow(activity, "HTTP 端口"))
            release.countDown()
            waitForUi(activity) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
                RootAccess.granted && hasRow(activity, "映射 HTTP 端口")
            }
            RootAccess.checkOnStartup()
            assertEquals(1, requests.get())
            rootButton(activity).performClick()
            waitForUi(activity) { requests.get() == 2 && !RootAccess.checking }
        } finally {
            release.countDown()
            RootAccess.authorize = original
            controller.pause().stop().destroy(); WebSession.stop()
        }
    }

    @Test fun deniedStartupCheckDoesNotRepeatUntilManualRetry() {
        val original = RootAccess.authorize
        val requests = AtomicInteger()
        RootAccess.startupChecked = false
        RootAccess.authorize = { requests.incrementAndGet(); throw java.io.IOException("Permission denied") }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            waitForUi(activity) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
                requests.get() == 1 && !RootAccess.checking
            }
            RootAccess.checkOnStartup()
            assertEquals(1, requests.get())
            assertFalse(RootAccess.granted)
            assertFalse(hasRow(activity, "映射 HTTP 端口"))
            assertTrue(hasRow(activity, "Web 域名（可选）"))
            rootButton(activity).performClick()
            waitForUi(activity) { requests.get() == 2 && !RootAccess.checking }
        } finally { RootAccess.authorize = original; controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun rootRequestIsFirstAndMappingControlsAppearOnlyAfterAccessIsGranted() {
        val original = RootAccess.authorize
        var requests = 0
        RootAccess.authorize = { requests++ }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            assertEquals(0, requests)
            assertFalse(hasRow(activity, "映射 HTTP 端口"))
            assertFalse(hasRow(activity, "访问 IP 地址"))
            val button = rootButton(activity)
            val row = button.parent as ViewGroup
            val section = row.parent as ViewGroup
            assertSame(row, section.getChildAt(0))
            button.performClick()
            waitForUi(activity) { RootAccess.granted }
            assertEquals(1, requests)
            assertTrue(hasRow(activity, "映射 HTTP 端口"))
            assertTrue(hasRow(activity, "映射 HTTPS 端口"))
            assertTrue(hasRow(activity, "访问 IP 地址"))
            assertFalse(WebSession.running)
        } finally { RootAccess.authorize = original; controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun deniedRootKeepsMappingControlsHiddenAndWebSettingsAvailable() {
        val original = RootAccess.authorize
        RootAccess.authorize = { throw java.io.IOException("Permission denied") }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            rootButton(activity).performClick()
            waitForUi(activity) { RootAccess.status.contains("未授权") }
            assertFalse(RootAccess.granted)
            assertFalse(hasRow(activity, "映射 HTTP 端口"))
            assertFalse(hasRow(activity, "访问 IP 地址"))
            assertTrue(hasRow(activity, "HTTP 端口"))
            assertTrue(hasRow(activity, "Web 域名（可选）"))
            assertTrue(hasRow(activity, "Web HTTPS 端口"))
            assertTrue(all(activity).filterIsInstance<TextView>().any { it.text.toString().contains("Web 域名同时用于 HTTP 和 HTTPS") })
        } finally { RootAccess.authorize = original; controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun flatCertificateTypesOpenTheSystemDocumentPickerWithoutRoot() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            assertEquals(listOf("默认本地证书", "PEM", "PKCS#12"),
                all(activity).filterIsInstance<MaterialRadioButton>().map { it.text.toString() })
            for ((type, title) in listOf("PEM" to "选择 PEM 证书与私钥", "PKCS#12" to "选择 PKCS#12 文件")) {
                all(activity).filterIsInstance<MaterialRadioButton>().single { it.text.toString() == type }.performClick()
                all(activity).filterIsInstance<MaterialButton>().single { it.text.toString() == title }.performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals("*/*", intent.type)
                assertFalse(RootAccess.granted)
            }
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun httpsSwitchHidesItsPortAndCertificateTypesWhileHttpStaysInWebSection() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            val http = all(activity).single { it.contentDescription?.toString()?.startsWith("HTTP 端口，") == true }
            val web = http.parent as ViewGroup
            assertTrue(views(web).any { it.contentDescription?.toString()?.startsWith("Web 域名（可选），") == true })
            assertFalse(views(web).any { it === rootButton(activity) })
            all(activity).filterIsInstance<MaterialSwitch>().single { it.contentDescription == "启用 HTTPS" }.performClick()
            waitForUi(activity) { !WebListenSettings.httpsEnabled(activity) }
            assertFalse(hasRow(activity, "Web HTTPS 端口"))
            assertTrue(all(activity).filterIsInstance<MaterialRadioButton>().isEmpty())
            assertTrue(hasRow(activity, "HTTP 端口"))
            assertTrue(hasRow(activity, "Web 域名（可选）"))
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun httpsPortFieldShowsItsPersistedDefault() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            all(activity).single { it.contentDescription?.toString() == "Web HTTPS 端口，8443" }.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertEquals("8443", input.text.toString())
            dialog.dismiss()
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderWebAndTeslaSettingsBeforeAndAfterRootGrant() {
        val original = RootAccess.authorize
        RootAccess.authorize = { }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            openSettings(activity)
            fun capture(name: String) {
                val scroll = all(activity).filterIsInstance<ScrollView>().single { view ->
                    views(view).filterIsInstance<TextView>().any { it.text.toString() == "Web 监听" }
                }
                val body = scroll.getChildAt(0)
                body.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                body.layout(0, 0, body.measuredWidth, body.measuredHeight)
                val headings = views(body).filterIsInstance<TextView>()
                val top = headings.single { it.text.toString() == "Web 监听" }.top
                val bottom = headings.single { it.text.toString() == "画面与音频" }.top
                val bitmap = android.graphics.Bitmap.createBitmap(body.width, bottom - top, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(ServerUi.BG)
                canvas.translate(0f, -top.toFloat())
                body.draw(canvas)
                val file = java.io.File("build/reports/web-settings/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            capture("without-root")
            rootButton(activity).performClick()
            waitForUi(activity) { RootAccess.granted }
            capture("with-root")
        } finally { RootAccess.authorize = original; controller.pause().stop().destroy(); WebSession.stop() }
    }
}
