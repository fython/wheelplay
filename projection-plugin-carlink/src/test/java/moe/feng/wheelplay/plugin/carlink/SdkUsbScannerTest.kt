package moe.feng.wheelplay.plugin.carlink

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SdkUsbScannerTest {
    class Manager {
        @JvmField var mAOAConnectThread: Thread? = null
        var previous: Thread? = null
        var starts = 0
        var reattachTimerActive = true
        var state = "usb_connecting"
        fun stopUsbDeviceScanning() { previous = mAOAConnectThread; mAOAConnectThread = null }
        fun cancelDeviceReattachTimer() { reattachTimerActive = false }
        fun startUsbDeviceScanning() {
            check(previous?.isAlive != true)
            starts++
        }
    }
    @Test fun resumesDuringAccessoryHandshakeAfterCancelledThreadExits() {
        val manager = Manager()
        val running = CountDownLatch(1)
        val old = Thread {
            running.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
        }
        manager.mAOAConnectThread = old
        old.start(); running.await()
        var prepared = false
        SdkUsbScanner(manager).setEnabled(true) {
            assertFalse(old.isAlive)
            prepared = true
        }
        assertFalse(old.isAlive)
        assertEquals(1, manager.starts)
        assertEquals("usb_connecting", manager.state)
        assertTrue(prepared)
        assertFalse(manager.reattachTimerActive)
    }
    @Test fun stoppingDoesNotRestartTheScanner() {
        val manager = Manager().apply { state = "usb_prepare_connecting" }
        SdkUsbScanner(manager).setEnabled(false)
        assertEquals(0, manager.starts)
        assertEquals("usb_prepare_connecting", manager.state)
        assertFalse(manager.reattachTimerActive)
    }
}
