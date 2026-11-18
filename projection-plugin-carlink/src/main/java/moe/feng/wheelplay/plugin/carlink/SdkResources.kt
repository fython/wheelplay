package moe.feng.wheelplay.plugin.carlink

import android.app.AppComponentFactory
import android.app.Service
import android.content.*
import android.os.Build
import android.os.Handler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Owns SDK-created Android bindings; the pinned SDK does not unbind all of them itself. */
internal object SdkResources {
    @Volatile var closing = false
    @Volatile var connection = CarLinkConnection.P2P
    @Volatile var hotspotCredentials: CarLinkSoftAp.Credentials? = null
    @Volatile var hotspotNetwork: CarLinkSoftAp.Network? = null
    val contexts = CopyOnWriteArrayList<SdkContext>()
    val services = ConcurrentHashMap.newKeySet<String>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val names = listOf("com.share.connect.ShareLinkService", "com.share.connect.ble.BluetoothLeService",
        "com.share.connect.wifip2p.WifiP2pService", "com.share.connect.wifiap.WifiApService")
    fun destroy(name: String, action: () -> Unit) {
        try { action() } catch (failure: Throwable) { errors += failure }
        finally { services -= name }
    }
    fun stopServices(context: Context) {
        closing = true
        contexts.forEach { it.detach() }
        names.forEach { name ->
            runCatching { context.stopService(Intent().setClassName(context.packageName, name)) }
                .onFailure { errors += it }
        }
    }
}

internal class SdkContext(base: Context) : ContextWrapper(base) {
    private val connections = linkedSetOf<ServiceConnection>()
    private val receivers = linkedSetOf<BroadcastReceiver>()
    init { SdkResources.contexts += this }
    override fun getApplicationContext(): Context = this
    @Synchronized override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        if (SdkResources.closing) return false
        return super.bindService(intent, connection, flags).also { if (it) connections += connection }
    }
    @Synchronized override fun unbindService(connection: ServiceConnection) {
        if (connections.remove(connection)) super.unbindService(connection)
    }
    @Synchronized override fun startService(intent: Intent): ComponentName? =
        if (SdkResources.closing) null else super.startService(intent)
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        registerReceiver(receiver, filter, null, null)
    @Synchronized override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?): Intent? {
        if (SdkResources.closing && receiver != null) return null
        // SDK listens to protected Bluetooth/Wi-Fi system broadcasts, including privileged senders.
        val result = if (Build.VERSION.SDK_INT >= 33) super.registerReceiver(receiver, filter, permission, scheduler, RECEIVER_EXPORTED)
            else super.registerReceiver(receiver, filter, permission, scheduler)
        if (receiver != null) receivers += receiver
        return result
    }
    @Synchronized override fun unregisterReceiver(receiver: BroadcastReceiver) {
        if (receivers.remove(receiver)) super.unregisterReceiver(receiver)
    }
    @Synchronized fun detach() {
        connections.toList().forEach { connection ->
            runCatching { super.unbindService(connection) }.onFailure { SdkResources.errors += it }
        }
        connections.clear()
        receivers.toList().forEach { receiver ->
            runCatching { super.unregisterReceiver(receiver) }.onFailure { SdkResources.errors += it }
        }
        receivers.clear()
    }
}

/** Keeps the SDK's fixed component names, but observes completion of each onDestroy hook. */
class CarLinkComponentFactory : AppComponentFactory() {
    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service = when (className) {
        "com.share.connect.ShareLinkService" -> TrackedShareLink()
        "com.share.connect.ble.BluetoothLeService" -> TrackedBle()
        "com.share.connect.wifip2p.WifiP2pService" -> TrackedP2p()
        "com.share.connect.wifiap.WifiApService" -> ManualHotspotService()
        else -> super.instantiateService(cl, className, intent)
    }
}

class TrackedShareLink : com.share.connect.ShareLinkService() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(SdkContext(base)) }
    override fun onCreate() { SdkResources.services += SdkResources.names[0]; super.onCreate() }
    override fun onDestroy() = SdkResources.destroy(SdkResources.names[0]) { super.onDestroy() }
}
class TrackedBle : com.share.connect.ble.BluetoothLeService() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(SdkContext(base)) }
    override fun onCreate() { SdkResources.services += SdkResources.names[1]; super.onCreate() }
    override fun onDestroy() = SdkResources.destroy(SdkResources.names[1]) { super.onDestroy() }
}
class TrackedP2p : com.share.connect.wifip2p.WifiP2pService() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(SdkContext(base)) }
    override fun onCreate() { SdkResources.services += SdkResources.names[2]; super.onCreate() }
    override fun onDestroy() = SdkResources.destroy(SdkResources.names[2]) { super.onDestroy() }
}
