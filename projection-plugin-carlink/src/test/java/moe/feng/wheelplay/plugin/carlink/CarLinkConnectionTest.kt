package moe.feng.wheelplay.plugin.carlink

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarLinkConnectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun grantManifestPermission() {
        // Robolectric does not automatically grant this merged signature permission.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private fun device(name: String, vendor: Int = 6353): UsbDevice = ReflectionHelpers.callConstructor(
        UsbDevice::class.java, from(String::class.java, name), from(Int::class.javaPrimitiveType!!, vendor),
        from(Int::class.javaPrimitiveType!!, 11520), from(Int::class.javaPrimitiveType!!, 0),
        from(Int::class.javaPrimitiveType!!, 0), from(Int::class.javaPrimitiveType!!, 0),
        from(String::class.java, "Test"), from(String::class.java, "Phone"),
        from(String::class.java, "1.0"), from(String::class.java, "test-serial"),
    )

    @Test fun usbDoesNotRequireWirelessPermissionsAndHotspotNeedsCredentials() {
        assertTrue(CarLinkEnvironment.permissions(CarLinkConnection.USB).isEmpty())
        assertTrue(CarLinkEnvironment.permissions(CarLinkConnection.P2P).isNotEmpty())
        assertTrue(CarLinkSoftAp.credentials(android.os.Bundle()).problem()!!.contains("热点名称"))
        assertEquals(CarLinkConnection.P2P, CarLinkConnection.fromKey(null))
        assertThrows(IllegalArgumentException::class.java) { CarLinkConnection.fromKey("auto") }
    }

    @Test fun usbScanWaitsForAuthorizationAndRestartsAfterAccessoryReenumeration() {
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        val first = device("/dev/bus/usb/001/001")
        usb.addOrUpdateUsbDevice(first, false)
        val scans = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        val gate = CarLinkUsbPermission(context, { scans += it }, { errors += it })
        gate.start()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(scans.isEmpty())
            usb.addOrUpdateUsbDevice(first, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(true), scans)
            usb.removeUsbDevice(first)
            val accessory = device("/dev/bus/usb/001/002")
            usb.addOrUpdateUsbDevice(accessory, false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(true, false), scans)
            usb.addOrUpdateUsbDevice(accessory, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(true, false, true), scans)
            assertTrue(errors.isEmpty())
            gate.connected()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(3, scans.size)
        } finally { gate.close() }
    }

    @Test fun usbStopCancelsPermissionPolling() {
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        val phone = device("/dev/bus/usb/001/001")
        usb.addOrUpdateUsbDevice(phone, false)
        val scans = mutableListOf<Boolean>()
        val gate = CarLinkUsbPermission(context, { scans += it }, { fail(it) })
        gate.start()
        shadowOf(Looper.getMainLooper()).idle()
        gate.close()
        usb.addOrUpdateUsbDevice(phone, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(scans.isEmpty())
    }

    @Test fun usbDenialEndsAttemptWithoutRepeatedDialogsOrTrustingGrantExtra() {
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        usb.addOrUpdateUsbDevice(device("/dev/bus/usb/001/001"), false)
        val scans = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        val gate = CarLinkUsbPermission(context, { scans += it }, { errors += it })
        gate.start()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            context.sendBroadcast(Intent("${context.packageName}.USB_PERMISSION")
                .putExtra("requestId", 1).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertTrue(scans.isEmpty())
            assertTrue(errors.single().contains("未获授权"))
        } finally { gate.close() }
    }

    @Test fun usbRejectsMultiplePhonesBeforeStartingScanner() {
        val usb = shadowOf(context.getSystemService(UsbManager::class.java))
        usb.addOrUpdateUsbDevice(device("/dev/bus/usb/001/001"), true)
        usb.addOrUpdateUsbDevice(device("/dev/bus/usb/001/002"), true)
        val errors = mutableListOf<String>()
        val gate = CarLinkUsbPermission(context, { fail("Scanner must not start") }, { errors += it })
        gate.start()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(errors.single().contains("一台"))
        } finally { gate.close() }
    }
}
