package moe.feng.wheelplay.projection.carlife

import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.projection.usb.AoaUsbConnection
import moe.feng.wheelplay.carlife.CarLifeTransport
import moe.feng.wheelplay.carlife.CarLifeUsbTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Select one phone per attempt, including after accessory re-enumeration. No source app ADB commands. */
internal fun carLifeUsbTransport(context: Context, status: (String) -> Unit): CarLifeTransport =
    CarLifeUsbTransport(object : CarLifeUsbTransport.Link {
        @Volatile private var closed = false
        @Volatile private var connection: AoaUsbConnection? = null
        override fun connect() {
            check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) { "此设备不支持 USB Host / OTG" }
            val devices = AoaUsbConnection.candidates(context)
            check(devices.size == 1) { if (devices.isEmpty()) "请通过 USB / OTG 连接一台源手机后重试" else "请只连接一台 USB 源手机" }
            val link = AoaUsbConnection(context, devices.single(),
                listOf("Baidu", "CarLife", "WheelPlay", "1.0.0", "https://github.com/fython/wheelplay", "WheelPlay"), "CarLife", status)
            connection = link
            if (closed) { link.close(); throw IOException("CarLife USB stopped") }
            link.connect()
        }
        override fun input(): InputStream = checkNotNull(connection).input
        override fun output(): OutputStream = checkNotNull(connection).output
        override fun close() { closed = true; connection?.close() }
    })
