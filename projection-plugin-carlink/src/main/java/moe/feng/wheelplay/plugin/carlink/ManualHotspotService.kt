package moe.feng.wheelplay.plugin.carlink

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.share.connect.wifiap.IWifiAp
import com.share.connect.wifiap.WifiApObserver
import java.util.concurrent.CopyOnWriteArrayList

/** Replaces only the SDK's privileged AP backend, retaining its BLE/authentication protocol. */
class ManualHotspotService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<WifiApObserver>()
    @Volatile private var published = false
    @Volatile private var closedByUser = false
    private val binder = object : IWifiAp.Stub() {
        override fun registerWifiApObserver(observer: WifiApObserver) { observers.addIfAbsent(observer) }
        override fun unregisterWifiApObserver(observer: WifiApObserver) { observers.remove(observer) }
        override fun open() { notifyObservers { it.onOpenSuccess() } }
        override fun close() { published = false; main.removeCallbacksAndMessages(null) }
        override fun cancelConnect() = close()
        override fun isUserClose() = closedByUser
        override fun createSoftAp(channel: Int, ssid: String?, password: String?, coexist: Boolean, prefix: Boolean) {
            val credentials = SdkResources.hotspotCredentials
            val network = SdkResources.hotspotNetwork
            if (credentials == null || network == null || CarLinkSoftAp.enabled(this@ManualHotspotService) == false) {
                notifyObservers { it.onApCreateFailed() }
                return
            }
            closedByUser = false
            published = true
            notifyObservers { it.onApCreated(network.address, credentials.ssid, credentials.password, "02:00:00:00:00:00", network.frequency) }
            // The manually owned LAN is already ready. This only advances SDK transport
            // setup; host READY still requires authentication, WFD and the first video frame.
            notifyObservers { it.onConnected() }
            main.removeCallbacks(monitor)
            main.postDelayed(monitor, 1_000)
        }
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (!published) return
            val original = SdkResources.hotspotNetwork
            val current = runCatching { CarLinkSoftAp.network(this@ManualHotspotService, checkNotNull(SdkResources.hotspotCredentials)) }.getOrNull()
            if (current == null || current.interfaceName != original?.interfaceName || current.address != original.address) {
                published = false
                closedByUser = true
                notifyObservers { it.onDisconnected(true) }
            } else main.postDelayed(this, 1_000)
        }
    }
    private fun notifyObservers(action: (WifiApObserver) -> Unit) {
        observers.forEach { observer -> runCatching { action(observer) }.onFailure { observers.remove(observer) } }
    }
    override fun onCreate() { super.onCreate(); SdkResources.services += SdkResources.names[3] }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() = SdkResources.destroy(SdkResources.names[3]) {
        published = false
        main.removeCallbacksAndMessages(null)
        observers.clear()
        super.onDestroy()
    }
}
