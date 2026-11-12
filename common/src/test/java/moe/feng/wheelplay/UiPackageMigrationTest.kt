package moe.feng.wheelplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UiPackageMigrationTest {
    @Test fun launcherAndLegacyShortcutsResolveToMigratedActivities() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.packageManager
        val launchers = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0)
        assertEquals(listOf(WheelPlayActivity::class.java.name), launchers.map { it.activityInfo.name })
        for ((oldName, target) in listOf(
            "com.shilapi.xcertplay.DiPlayActivity" to WheelPlayActivity::class.java.name,
            "com.shilapi.xcertplay.CarPlayHostActivity" to CarPlayHostActivity::class.java.name,
        )) {
            val info = manager.getActivityInfo(ComponentName(app.packageName, oldName), 0)
            assertEquals(target, info.targetActivity)
            assertTrue(info.exported)
        }
        val service = manager.getServiceInfo(ComponentName(app, WheelPlaySessionService::class.java), 0)
        assertFalse(service.exported)
    }

    @Test fun savedPhoneAndHomeSelectionRemainAvailableAfterRename() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("phone_address", "02:00:00:00:00:01")
            .putString("phone_name", "Saved iPhone")
            .putString("home_source", "vnc")
            .putBoolean("auto_connect", true)
            .commit()
        assertEquals("02:00:00:00:00:01", WheelPlayPreferences.phoneAddress(app))
        assertEquals("Saved iPhone", WheelPlayPreferences.phoneName(app))
        assertEquals("vnc", WheelPlayPreferences.homeSource(app))
        assertTrue(WheelPlayPreferences.autoConnect(app))
    }
}
