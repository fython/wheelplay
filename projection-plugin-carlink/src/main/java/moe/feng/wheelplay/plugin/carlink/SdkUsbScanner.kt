package moe.feng.wheelplay.plugin.carlink

/**
 * UCarAdapter.enableUsbDeviceDetection(true) silently refuses USB_CONNECTING/PREPARED.
 * Permission re-enumeration must resume the AOA scanner without resetting that handshake.
 * Only used on the engine worker, after Android has granted access to the attached phone.
 */
internal class SdkUsbScanner(private val manager: Any = manager()) {
    fun setEnabled(enabled: Boolean, beforeStart: () -> Unit = {}) {
        val type = manager.javaClass
        val previous = type.getDeclaredField("mAOAConnectThread").apply { isAccessible = true }.get(manager) as? Thread
        type.getMethod("stopUsbDeviceScanning").invoke(manager)
        // The SDK's two-second detach timer cannot cover an Android permission dialog.
        // Our session retains the bounded startup deadline while the user grants access.
        type.getMethod("cancelDeviceReattachTimer").invoke(manager)
        if (previous != null && previous !== Thread.currentThread()) {
            previous.interrupt()
            previous.join(2_000)
            check(!previous.isAlive) { "USB 扫描线程未停止，请重新连接" }
        }
        if (enabled) {
            beforeStart()
            type.getMethod("startUsbDeviceScanning").invoke(manager)
        }
    }

    companion object {
        fun preserveAttachedAccessory() {
            val type = Class.forName("com.ucar.connect.aoa.AOAHostSetup")
            val setup = type.getMethod("getInstance").invoke(null)
            // On retry the phone may already be an AOA accessory. The SDK otherwise
            // sends USBDEVFS_RESET, invalidating its own fd and causing ENODEV/10007.
            type.getDeclaredField("mIsResetCalled").apply { isAccessible = true }.setBoolean(setup, true)
        }
        private fun manager(): Any = Class.forName("com.ucar.connect.aoa.AOAConnectManager")
            .getMethod("getInstance").invoke(null)!!
    }
}
