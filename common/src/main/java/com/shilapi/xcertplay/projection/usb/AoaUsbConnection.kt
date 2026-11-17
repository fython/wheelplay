package com.shilapi.xcertplay.projection.usb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import androidx.core.content.ContextCompat
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android USB host -> phone in Android Open Accessory mode; no USB accessory permission on the host. */
internal open class AoaUsbConnection(private val context: Context, private val device: UsbDevice,
    private val identity: List<String>, private val label: String,
    private val status: (String) -> Unit = {}) : AutoCloseable {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val closed = AtomicBoolean()
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var permissionWait: CountDownLatch? = null
    private lateinit var incoming: UsbEndpoint
    private lateinit var outgoing: UsbEndpoint
    companion object {
        fun isAccessory(device: UsbDevice) = device.vendorId == 0x18d1 && device.productId in setOf(0x2d00, 0x2d01, 0x2d04, 0x2d05)
        fun candidates(context: Context) = context.getSystemService(UsbManager::class.java).deviceList.values.filter {
            it.vendorId != 0x05ac && (isAccessory(it) || it.deviceClass == 0 || it.deviceClass == 0xef)
        }.sortedBy { it.deviceName }
    }
    private fun checkOpen() { check(!closed.get()) { "$label stopped" } }
    private fun open(device: UsbDevice): UsbDeviceConnection {
        checkOpen()
        if (!manager.hasPermission(device)) {
            val action = context.packageName + ".AOA_USB." + UUID.randomUUID()
            val wait = CountDownLatch(1); permissionWait = wait
            try {
                // close() either sees this latch, or this check observes the earlier close.
                checkOpen()
                val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (i.action == action) wait.countDown() } }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                    val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                    try {
                        checkOpen()
                        status("请允许 USB 访问；切换到配件模式时可能需要再次授权")
                        manager.requestPermission(device, pending)
                        check(wait.await(45, TimeUnit.SECONDS)) { "USB permission timed out" }
                    } finally { pending.cancel() }
                } finally { context.unregisterReceiver(receiver) }
            } finally { permissionWait = null }
            checkOpen(); check(manager.hasPermission(device)) { "USB permission denied" }
        }
        return checkNotNull(manager.openDevice(device)) { "Cannot open USB phone" }.also {
            connection = it
            if (closed.get()) { it.close(); error("$label stopped") }
        }
    }
    open fun connect() {
        require(identity.size == 6) { "AOA requires six identity strings" }
        var selected = device
        var link = open(selected)
        if (!isAccessory(selected)) {
            status("正在切换 $label USB 配件模式")
            val version = ByteArray(2)
            check(link.controlTransfer(0xc0, 51, 0, 0, version, 2, 2000) == 2 && version[0].toInt() > 0) { "Phone does not support Android Open Accessory" }
            identity.forEachIndexed { index, value ->
                val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
                check(link.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 2000) == bytes.size)
            }
            check(link.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) >= 0) { "AOA mode switch failed" }
            link.close(); connection = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (true) {
                checkOpen()
                val devices = manager.deviceList.values.filter { isAccessory(it) }
                check(devices.size <= 1) { "请只连接一台源手机" }
                if (devices.size == 1) { selected = devices.single(); break }
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Phone did not re-enumerate in accessory mode" }
                Thread.sleep(100)
            }
            link = open(selected)
        }
        val iface = (0 until selected.interfaceCount).map(selected::getInterface).firstOrNull { i ->
            i.interfaceClass == 255 && i.interfaceSubclass == 255 && i.interfaceProtocol == 0 &&
                (0 until i.endpointCount).map(i::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                    .map { it.direction }.toSet() == setOf(UsbConstants.USB_DIR_IN, UsbConstants.USB_DIR_OUT)
        } ?: error("Missing AOA bulk interface")
        incoming = (0 until iface.endpointCount).map(iface::getEndpoint).first { it.direction == UsbConstants.USB_DIR_IN }
        outgoing = (0 until iface.endpointCount).map(iface::getEndpoint).first { it.direction == UsbConstants.USB_DIR_OUT }
        check(link.claimInterface(iface, true)) { "USB interface is busy" }
        status("USB 已连接，等待源手机确认 $label")
    }
    // Buffer whole USB transfers: reading just the four-byte AAP header would discard the rest of a USB packet.
    open val input = object : InputStream() {
        private val buffer = ByteArray(16384)
        private var offset = 0; private var size = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            if (start < 0 || length < 0 || start > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            checkOpen()
            if (offset == size) {
                size = connection!!.bulkTransfer(incoming, buffer, buffer.size, 15000); offset = 0
                if (size <= 0) throw IOException("USB disconnected or timed out")
            }
            val n = minOf(length, size - offset); buffer.copyInto(bytes, start, offset, offset + n); offset += n; return n
        }
    }
    open val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, start: Int, length: Int) {
            if (start < 0 || length < 0 || start > bytes.size - length) throw IndexOutOfBoundsException()
            var offset = start
            while (offset < start + length) {
                checkOpen()
                val n = connection!!.bulkTransfer(outgoing, bytes, offset, minOf(16384, start + length - offset), 2000)
                if (n <= 0) throw IOException("USB write failed")
                offset += n
            }
        }
    }
    override fun close() { closed.set(true); permissionWait?.countDown(); connection?.close() }
}
