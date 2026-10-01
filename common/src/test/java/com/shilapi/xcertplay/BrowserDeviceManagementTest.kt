package com.shilapi.xcertplay

import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shilapi.xcertplay.web.WebSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
class BrowserDeviceManagementTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun select(index: Int) {
        val list = dialog().listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun press(which: Int) {
        dialog().getButton(which).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun settingsManageBrowserNamesIndividualRemovalAndRemoveAll() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val devices = WebSession.rememberedBrowsers(activity)
            val original = devices.remember("Chrome · Android", "192.168.1.2")!!
            views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = 3
            val entry = views(activity.window.decorView).single { it.contentDescription?.toString()?.startsWith("连接过的设备，") == true }
            entry.performClick()
            assertTrue(dialog().listView.adapter.getItem(0).toString().contains("Chrome · Android"))
            select(0)
            press(DialogInterface.BUTTON_NEUTRAL)
            views(dialog().window!!.decorView).filterIsInstance<EditText>().single().setText("中控屏")
            press(DialogInterface.BUTTON_POSITIVE)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("中控屏", devices.list().single().name)
            assertTrue(dialog().listView.adapter.getItem(0).toString().contains("中控屏"))
            select(0)
            press(DialogInterface.BUTTON_POSITIVE)
            assertTrue(devices.list().isEmpty())
            assertNull(devices.authenticate(original.device.id, original.secret, "client"))
            dialog().dismiss()
            devices.remember("Browser A", "client")
            devices.remember("Browser B", "client")
            entry.performClick(); select(2)
            press(DialogInterface.BUTTON_POSITIVE)
            assertTrue(devices.list().isEmpty())
            dialog().dismiss()
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
}
