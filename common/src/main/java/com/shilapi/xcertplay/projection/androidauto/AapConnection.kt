package com.shilapi.xcertplay.projection.androidauto

import android.content.*
import android.hardware.usb.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

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

/** Android Auto identity over the shared AOA host implementation. */
internal class AapUsbConnection(context: Context, device: UsbDevice) :
    com.shilapi.xcertplay.projection.usb.AoaUsbConnection(context, device,
        listOf("Android", "Android Auto", "WheelPlay", "1.0", "https://github.com/fython/wheelplay", "WheelPlay"),
        "Android Auto"), AapConnection {
    companion object {
        fun isAccessory(device: UsbDevice) = com.shilapi.xcertplay.projection.usb.AoaUsbConnection.isAccessory(device)
        fun candidates(context: Context) = com.shilapi.xcertplay.projection.usb.AoaUsbConnection.candidates(context)
    }
}
