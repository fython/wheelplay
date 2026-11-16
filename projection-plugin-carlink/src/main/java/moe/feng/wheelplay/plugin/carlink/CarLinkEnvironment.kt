package moe.feng.wheelplay.plugin.carlink

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.system.Os
import android.system.OsConstants

internal object CarLinkEnvironment {
    fun permissions(connection: CarLinkConnection = CarLinkConnection.P2P): Array<String> = buildList {
        if (!connection.wireless) return@buildList
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()
    fun unsupported(abis: List<String>, pageSize: Long): String? = when {
        "arm64-v8a" !in abis -> "接收设备需要支持 arm64-v8a"
        pageSize != 4096L -> "当前 SDK 仅支持 4 KB 内存页（此设备为 $pageSize 字节）"
        else -> null
    }
    fun problems(context: Context, connection: CarLinkConnection = CarLinkConnection.P2P): List<String> = buildList {
        unsupported(Build.SUPPORTED_ABIS.toList(), Os.sysconf(OsConstants._SC_PAGESIZE))?.let { add(it) }
        if (connection == CarLinkConnection.USB) {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) add("此设备不支持 USB Host / OTG")
            return@buildList
        }
        if (connection == CarLinkConnection.SOFT_AP) CarLinkSoftAp.problem(context)?.let { add(it) }
        if (connection == CarLinkConnection.P2P && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) add("此设备不支持 Wi-Fi Direct，请选择手动热点或 USB")
        val missing = permissions(connection).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) add("请授予附近设备与定位权限；若已拒绝，请在系统应用设置中允许")
        if (missing.isEmpty()) {
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (bt?.isEnabled != true) add("请开启蓝牙")
            else if (bt.bluetoothLeAdvertiser == null) add("此设备不支持蓝牙低功耗广播，请选择 USB")
        }
        if (connection == CarLinkConnection.P2P && context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) add("请开启 Wi-Fi")
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) add("请开启系统定位服务以发现无线设备")
    }
}
