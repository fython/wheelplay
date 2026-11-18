package moe.feng.wheelplay

import android.bluetooth.BluetoothManager
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.content.Context
import android.provider.Settings

internal object WheelPlayBluetooth {
    fun localAddress(context: Context): String? {
        // LOCAL_MAC_ADDRESS is privileged. Ordinary installations skip this API and use the
        // existing optional setting fallback; never request a permission they cannot be granted.
        val adapter = if ((Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
            context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
            try {
                // Optional privileged-device path, guarded above. Do not request this privileged
                // permission in the ordinary APK; lint requires a declaration even with this guard.
                @android.annotation.SuppressLint("MissingPermission")
                val address = context.getSystemService(BluetoothManager::class.java)?.adapter?.address
                address
            }
            catch (_: SecurityException) { null }
        } else null
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
