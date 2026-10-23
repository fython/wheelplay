package com.shilapi.xcertplay.projection.androidauto

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import androidx.core.content.ContextCompat
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface AapConnection : AutoCloseable {
    fun connect()
    val input: InputStream
    val output: OutputStream
}
internal class AapTcpConnection(private val host: String, private val port: Int) : AapConnection {
    private val socket = Socket()
    override fun connect() { require(validAddress(host)) { "Enter a numeric IP address" }; socket.connect(InetSocketAddress(host, port), 10000); socket.tcpNoDelay = true; socket.soTimeout = 15000 }
    override val input get() = socket.getInputStream()
    override val output get() = socket.getOutputStream()
    override fun close() = socket.close()
    companion object {
        fun validAddress(host: String): Boolean = host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
        }
    }
}

/** Android USB host -> phone in Android Open Accessory mode; no USB accessory permission on the host. */
internal class AapUsbConnection(private val context: Context, private val device: UsbDevice) : AapConnection {
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
    private fun checkOpen() { check(!closed.get()) { "Android Auto stopped" } }
    private fun open(device: UsbDevice): UsbDeviceConnection {
        checkOpen()
        if (!manager.hasPermission(device)) {
            val action = context.packageName + ".AA_USB." + UUID.randomUUID()
            val wait = CountDownLatch(1); permissionWait = wait
            val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (i.action == action) wait.countDown() } }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            try { manager.requestPermission(device, pending); check(wait.await(45, TimeUnit.SECONDS)) { "USB permission timed out" } }
            finally { context.unregisterReceiver(receiver); pending.cancel(); permissionWait = null }
            checkOpen(); check(manager.hasPermission(device)) { "USB permission denied" }
        }
        return checkNotNull(manager.openDevice(device)) { "Cannot open USB phone" }.also {
            connection = it
            if (closed.get()) { it.close(); error("Android Auto stopped") }
        }
    }
    override fun connect() {
        var selected = device
        var link = open(selected)
        if (!isAccessory(selected)) {
            val version = ByteArray(2)
            check(link.controlTransfer(0xc0, 51, 0, 0, version, 2, 2000) == 2 && version[0].toInt() > 0) { "Phone does not support Android Open Accessory" }
            listOf("Android", "Android Auto", "WheelPlay", "1.0", "https://github.com/fython/wheelplay", "WheelPlay").forEachIndexed { index, value ->
                val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
                check(link.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 2000) == bytes.size)
            }
            check(link.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) >= 0) { "AOA mode switch failed" }
            link.close(); connection = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (true) {
                checkOpen()
                val devices = manager.deviceList.values.filter { isAccessory(it) }
                check(devices.size <= 1) { "Connect only one Android Auto phone" }
                if (devices.size == 1) { selected = devices.single(); break }
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Phone did not re-enumerate in accessory mode" }
                Thread.sleep(100)
            }
            link = open(selected)
        }
        val iface = (0 until selected.interfaceCount).map(selected::getInterface).firstOrNull { i ->
            (0 until i.endpointCount).map(i::getEndpoint).count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } == 2
        } ?: error("Missing AOA bulk interface")
        incoming = (0 until iface.endpointCount).map(iface::getEndpoint).first { it.direction == UsbConstants.USB_DIR_IN }
        outgoing = (0 until iface.endpointCount).map(iface::getEndpoint).first { it.direction == UsbConstants.USB_DIR_OUT }
        check(link.claimInterface(iface, true)) { "USB interface is busy" }
    }
    // Buffer whole USB transfers: reading just the four-byte AAP header would discard the rest of a USB packet.
    override val input = object : InputStream() {
        private val buffer = ByteArray(16384)
        private var offset = 0; private var size = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            checkOpen()
            if (offset == size) {
                size = connection!!.bulkTransfer(incoming, buffer, buffer.size, 15000); offset = 0
                if (size <= 0) throw IOException("USB disconnected or timed out")
            }
            val n = minOf(length, size - offset); buffer.copyInto(bytes, start, offset, offset + n); offset += n; return n
        }
    }
    override val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, start: Int, length: Int) {
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
