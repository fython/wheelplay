package com.shilapi.xcertplay.projection.androidauto

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AapUsbConnectionTest.UsbPermissions::class])
class AapUsbConnectionTest {
    @Before fun grantCompatReceiverPermission() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Implements(UsbManager::class)
    class UsbPermissions {
        var onCheck: () -> Unit = {}
        var onRequest: () -> Unit = {}
        var requests = 0
        @Implementation fun hasPermission(device: UsbDevice): Boolean { onCheck(); return false }
        @Implementation fun requestPermission(device: UsbDevice, intent: PendingIntent) { requests++; onRequest() }
    }

    @Test fun closeBetweenOpenCheckAndLatchRegistrationDoesNotWaitForPermission() {
        val context = RuntimeEnvironment.getApplication()
        val permissions = Shadow.extract<UsbPermissions>(context.getSystemService(UsbManager::class.java))
        val connection = AapUsbConnection(context, Shadow.newInstanceOf(UsbDevice::class.java))
        permissions.onCheck = { connection.close() }
        stoppedPromptly(connection)
        assertEquals(0, permissions.requests)
    }

    @Test fun closeAfterPermissionRequestWakesTheRegisteredWaiter() {
        val context = RuntimeEnvironment.getApplication()
        val permissions = Shadow.extract<UsbPermissions>(context.getSystemService(UsbManager::class.java))
        val connection = AapUsbConnection(context, Shadow.newInstanceOf(UsbDevice::class.java))
        permissions.onRequest = { connection.close() }
        stoppedPromptly(connection)
        assertEquals(1, permissions.requests)
    }

    private fun stoppedPromptly(connection: AapUsbConnection) {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit { connection.connect() }
            val error = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertEquals("Android Auto stopped", error.cause?.message)
        } finally { connection.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
