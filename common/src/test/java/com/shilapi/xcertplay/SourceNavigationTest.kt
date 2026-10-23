package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.web.WebSession
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceNavigationTest {
    @Before fun provision() {
        val context = RuntimeEnvironment.getApplication()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        DiPlayPreferences.saveAutoConnect(context, false)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tabs(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single()
    private fun page(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
    private fun cards(activity: DiPlayActivity) = views(page(activity)).filterIsInstance<MaterialCardView>()
    private fun buttons(activity: DiPlayActivity) = views(page(activity)).filterIsInstance<Button>()
    private fun capture(activity: DiPlayActivity, name: String) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/material-ui/sources").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun cardsOnlyNavigateAndKeepConnectionControlsInTheirOwnPages() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val nav = tabs(activity)
            assertEquals("输入源", nav.menu.findItem(2).title)
            nav.selectedItemId = 2
            val titles = cards(activity).map { views(it).filterIsInstance<TextView>().first().text.toString() }
            assertEquals(listOf("CarPlay (iPhone)", "Android Auto", "ICCOA CarLink", "VNC", "插件输入源", "诊断输入源"), titles)
            assertTrue(buttons(activity).isEmpty())
            val initialSession = projectionSessions.snapshot()
            capture(activity, "index")
            cards(activity)[0].performClick()
            assertEquals(2, nav.selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接 iPhone" })
            assertFalse(buttons(activity).any { it.text == "连接本机 Android Auto" })
            capture(activity, "carplay")
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(6, cards(activity).size)
            cards(activity)[1].performClick()
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            assertTrue(buttons(activity).any { it.text == "通过 USB 连接 Android Auto" })
            assertFalse(buttons(activity).any { it.text == "连接 iPhone" })
            capture(activity, "android-auto")
            buttons(activity).single { it.text == "返回输入源" }.performClick()
            cards(activity)[2].performClick()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "暂不可用" })
            assertFalse(buttons(activity).any { it.text == "停止投影" })
            buttons(activity).single { it.text == "插件输入源" }.performClick()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "未发现兼容的插件服务。" })
            assertTrue(buttons(activity).any { it.text == "刷新已安装插件" })
            assertSame(initialSession, projectionSessions.snapshot())
            capture(activity, "plugins-empty")
            buttons(activity).single { it.text == "返回输入源" }.performClick()
            cards(activity)[3].performClick()
            assertTrue(buttons(activity).any { it.text == "连接 VNC" })
            capture(activity, "vnc")
            buttons(activity).single { it.text == "连接 VNC" }.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val fields = views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>()
            assertEquals(3, fields.size)
            assertEquals("", fields[2].text.toString())
            fields[0].setText("hostname"); fields[1].setText("5900"); fields[2].setText("secret")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing); assertNotNull(fields[0].error)
            dialog.dismiss()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("", fields[2].text.toString())
            assertSame(initialSession, projectionSessions.snapshot())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun detailSurvivesRecreationAndBackAndTabReselectionReturnToIndex() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            tabs(activity).selectedItemId = 2
            cards(activity)[1].performClick()
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            controller.recreate()
            activity = controller.get()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            tabs(activity).selectedItemId = 2
            assertTrue(buttons(activity).isEmpty())
            cards(activity)[0].performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).isEmpty())
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, tabs(activity).selectedItemId)
            // The launch-page shortcut should open CarPlay details and select the Sources tab.
            buttons(activity).single { it.text == "管理 iPhone 连接" }.performClick()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接 iPhone" })
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
}
