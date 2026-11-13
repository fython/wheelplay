package moe.feng.wheelplay.plugin.carlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper

/** The vendor scanner reads serial numbers before checking permission. Gate it here. */
internal class CarLinkUsbPermission(
    private val context: Context,
    private val scan: (Boolean) -> Unit,
    private val failed: (String) -> Unit,
    private val permissionWaiting: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = requireNotNull(context.getSystemService(UsbManager::class.java))
    private val action = "${context.packageName}.USB_PERMISSION"
    private var registered = false
    private var finished = false
    private var pending: UsbDevice? = null
    private var scanning = false
    private var topology = emptySet<String>()
    private var request: PendingIntent? = null
    private var requestId = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action || finished || intent.getIntExtra("requestId", -1) != requestId) return
            val device = pending ?: return
            pending = null
            // Re-query the OS; never trust an intent's claim that access was granted.
            if (manager.deviceList.containsKey(device.deviceName) && !manager.hasPermission(device)) {
                finished = true
                failed("USB 访问未获授权；请重新连接并允许访问发送手机。")
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            try {
                val devices = manager.deviceList.values.toList()
                val current = devices.map { it.deviceName }.toSet()
                if (current != topology) {
                    topology = current
                    if (scanning) { scan(false); scanning = false }
                    if (pending?.deviceName !in current) { pending = null; request?.cancel(); request = null }
                }
                // The SDK enumerates all devices, so an unrelated unapproved device would
                // also terminate its scanner at getSerialNumber(). Do not ask to access it.
                check(devices.all(::isPhone)) { "请移除其他 USB 设备，仅连接一台发送手机。" }
                check(devices.size <= 1) { "请仅连接一台 USB 发送手机。" }
                val device = devices.firstOrNull()
                if (device != null && !manager.hasPermission(device)) {
                    if (scanning) { scan(false); scanning = false }
                    if (pending == null) {
                        permissionWaiting(true)
                        pending = device
                        request = PendingIntent.getBroadcast(context, ++requestId,
                            Intent(action).setPackage(context.packageName).putExtra("requestId", requestId),
                            PendingIntent.FLAG_IMMUTABLE).also { manager.requestPermission(device, it) }
                    }
                } else if (device != null && !scanning) {
                    permissionWaiting(false)
                    scan(true)
                    scanning = true
                }
                main.postDelayed(this, 500)
            } catch (error: Exception) {
                finished = true
                failed(error.message ?: "无法申请 USB 访问权限")
            }
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        main.post(poll)
    }

    fun connected() { finished = true; main.removeCallbacks(poll) }

    fun close() {
        finished = true
        main.removeCallbacksAndMessages(null)
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        pending = null
        request?.cancel()
        request = null
    }

    companion object {
        fun isAccessory(device: UsbDevice): Boolean = device.vendorId == 6353 && device.productId in setOf(11520, 11521, 11524, 11525)
        // The pinned SDK's phone vendor allowlist plus standard PTP interfaces.
        fun isPhone(device: UsbDevice): Boolean = device.vendorId in setOf(6353, 8921, 11669, 10007, 10864, 10821) ||
            (0 until device.interfaceCount).any {
                val intf = device.getInterface(it)
                intf.interfaceClass == 6 && intf.interfaceSubclass == 6 && intf.interfaceProtocol == 1
            }
    }
}
