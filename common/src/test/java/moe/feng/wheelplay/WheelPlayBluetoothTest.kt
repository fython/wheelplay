package moe.feng.wheelplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WheelPlayBluetoothTest {
    @Test fun deniedPrivilegedPermissionSkipsAdapterAndKeepsOptionalSettingFallback() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
            override fun getSystemService(name: String): Any? {
                check(name != Context.BLUETOOTH_SERVICE) { "Must not read the adapter without permission" }
                return super.getSystemService(name)
            }
        }
        Settings.Secure.putString(context.contentResolver, "bluetooth_address", "12:34:56:78:9A:BC")
        assertEquals("12:34:56:78:9A:BC", WheelPlayBluetooth.localAddress(context))
        Settings.Secure.putString(context.contentResolver, "bluetooth_address", "02:00:00:00:00:00")
        assertNull(WheelPlayBluetooth.localAddress(context))
        Settings.Secure.putString(context.contentResolver, "bluetooth_address", null)
        assertNull(WheelPlayBluetooth.localAddress(context))
    }
}
