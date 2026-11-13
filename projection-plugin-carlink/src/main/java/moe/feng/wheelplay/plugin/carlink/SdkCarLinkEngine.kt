package moe.feng.wheelplay.plugin.carlink

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.share.connect.WifiOwnerConfig
import com.ucar.vehiclesdk.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Boundary to the pinned binary SDK. Never created during discovery or handshake. */
internal class SdkCarLinkEngine(private val context: Context, private val surface: Surface?,
    private val encodedOutput: android.os.ParcelFileDescriptor? = null,
    private val connection: CarLinkConnection = CarLinkConnection.P2P,
    private val hotspotCredentials: CarLinkSoftAp.Credentials? = null) : CarLinkEngine {
    private val encodedLock = Any()
    private var encodedVideo: EncodedCarLinkVideo? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sdkContext = SdkContext(context)
    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var adapter: UCarAdapter? = null
    private lateinit var config: CarLinkConfig
    @Volatile private var stopping = false
    @Volatile private var initialized = false
    private var callback: CarLinkEngine.Listener? = null
    private var usbPermission: CarLinkUsbPermission? = null
    private var usbScanner: SdkUsbScanner? = null
    private var stopFuture: CompletableFuture<Unit>? = null
    private val mode get() = UCarCommon.WorkMode.Builder().setSupportNormalCast(true).build()
    private val listener = object : ICarConnectListener {
        override fun onConnectStateChanged(deviceId: String?, state: Int, parameter: Int) {
            if (!stopping) {
                if (state == 2 || state == 4) main.post { usbPermission?.connected() }
                callback?.connection(state, parameter)
            }
        }
        override fun onConnectingProgress(deviceId: String?, progress: Int) {
            if (!stopping) callback?.progress(progress)
        }
        override fun onPinCode(pin: String?, carName: String?) {
            if (!stopping) callback?.pairing(pin.orEmpty(), carName ?: "CarLink")
        }
        override fun onReconfigureWifiInfo(value: WifiOwnerConfig?) {
            if (!stopping) callback?.failed("SDK requested unsupported SoftAP reconfiguration")
        }
        override fun onRethinkBluetoothConnect(connect: Boolean) = Unit
        override fun onSelectWorkMode(deviceId: String?, requested: UCarCommon.WorkMode?): UCarCommon.WorkMode = mode
    }

    override fun initialize(config: CarLinkConfig, listener: CarLinkEngine.Listener) {
        this.config = config
        callback = listener
        worker.execute {
            try {
                check(!stopping)
                if (connection == CarLinkConnection.P2P) {
                    checkNotNull(p2p) { "Wi-Fi Direct unavailable" }
                    channel = p2p.initialize(context, Looper.getMainLooper(), null)
                    check(!groupPresent()) { "An existing Wi-Fi Direct group is active; disconnect it first" }
                }
                if (connection == CarLinkConnection.SOFT_AP) {
                    val credentials = checkNotNull(hotspotCredentials)
                    SdkResources.hotspotCredentials = credentials
                    SdkResources.hotspotNetwork = CarLinkSoftAp.network(context, credentials)
                }
                if (stopping) return@execute
                val identity = context.settingsCall("config").getString("carId")!!
                val carId = identity.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val sdkConfig = UCarConfig.Builder()
                    .setCarBrMac(carId).setCarCustomField(byteArrayOf(0, 0))
                    .setScreenWidth(config.width).setScreenHeight(config.height)
                    .setVideoDisplayWidth(config.width).setVideoDisplayHeight(config.height)
                    .setDpi(config.dpi).setFps(30).setSupportedWorkModes(mode)
                    .setSupportP2P(connection == CarLinkConnection.P2P).setSupportSoftAP(connection == CarLinkConnection.SOFT_AP)
                    .setSupportRealWifiAddress(connection != CarLinkConnection.SOFT_AP)
                    .setSupportCamera(false).setSupportHevc(false).setSupportMic(false)
                    .setSupportVoiceWaken(false).setUseCustomAudioRecord(false)
                    .setSupportStereoRecord(false).setSupportLowLatencyDecodingMode(false)
                    .setSupportPOINav(false).setCcdFilePath("ccd.data").build()
                val sdk = UCarAdapter.getInstance()
                adapter = sdk
                if (connection == CarLinkConnection.SOFT_AP) {
                    // SDK debug logs include the password handed to its BLE/auth backend.
                    Class.forName("com.easy.logger.EasyLog").getMethod("setLevel", Int::class.javaPrimitiveType).invoke(null, 5)
                }
                sdk.registerCarConnectListener(this.listener)
                // From here even a partial/failed init owns resources and must be torn down.
                SdkResources.connection = connection
                initialized = true
                check(sdk.init(sdkContext, sdkConfig, object : ICarInitCallback {
                    override fun onInitSuccess() {
                        if (encodedOutput != null && !stopping) {
                            try {
                                synchronized(encodedLock) {
                                    if (stopping) return
                                    val video = EncodedCarLinkVideo(encodedOutput,
                                        { if (!stopping) callback?.surfaceReady() },
                                        { width, height -> context.settingsCall("encoded", android.os.Bundle().apply {
                                            putInt("encodedWidth", width); putInt("encodedHeight", height)
                                        }) },
                                        { if (!stopping) callback?.failed(it) })
                                    encodedVideo = video
                                    video.install(sdk)
                                }
                            } catch (error: Exception) {
                                listener.failed("当前 SDK 不支持 WebRTC 输出：${error.message}")
                                return
                            }
                        }
                        sdk.enableUsbDeviceDetection(false)
                        if (!stopping) listener.initialized()
                    }
                    override fun onInitFailed(code: Int) { if (!stopping) listener.failed("SDK initialization failed ($code)") }
                })) { "SDK rejected initialization" }
            } catch (failure: Throwable) { if (!stopping) listener.failed(failure.message ?: "Cannot initialize CarLink") }
        }
    }
    override fun advertise(): Boolean {
        if (stopping) return false
        if (connection.wireless) return adapter?.startAdvertise() == true
        if (adapter == null) return false
        main.post {
            if (!stopping && usbPermission == null) {
                try {
                    usbPermission = CarLinkUsbPermission(context, { enabled ->
                        worker.execute {
                            if (!stopping) {
                                try {
                                    val scanner = usbScanner ?: SdkUsbScanner().also { usbScanner = it }
                                    scanner.setEnabled(enabled) {
                                        val usb = context.getSystemService(android.hardware.usb.UsbManager::class.java)
                                        val device = usb?.deviceList?.values?.singleOrNull()
                                        if (device != null && usb.hasPermission(device) && CarLinkUsbPermission.isAccessory(device)) {
                                            SdkUsbScanner.preserveAttachedAccessory()
                                        }
                                    }
                                } catch (error: Exception) {
                                    if (!stopping) callback?.failed("USB 扫描恢复失败，请重新连接：${error.cause?.message ?: error.message}")
                                }
                            }
                        }
                    }, { if (!stopping) callback?.failed(it) },
                        { if (!stopping) callback?.usbPermissionWaiting(it) }).also { it.start() }
                } catch (error: Exception) {
                    if (!stopping) callback?.failed(error.message ?: "USB permission setup failed")
                }
            }
        }
        return true
    }
    override fun refreshAdvertise(): Boolean {
        if (stopping || !connection.wireless) return false
        return adapter?.let { it.stopAdvertise() && it.startAdvertise() } == true
    }
    override fun startCast(): Boolean {
        if (stopping) return false
        val sdk = adapter ?: return false
        if (encodedOutput != null) return encodedVideo?.startWithoutDecoder(sdk, config.width, config.height) == true
        val target = surface ?: return false
        return target.isValid && sdk.startCast(target, config.width, config.height) {
            if (!stopping) callback?.surfaceReady()
        }
    }
    override fun touch(event: TouchEvent): Boolean = !stopping && adapter?.sendTouchEvent(event.action, event.ids.size, event.ids, event.xs, event.ys) == true

    override fun stop(): CompletableFuture<Unit> {
        stopFuture?.let { return it }
        stopping = true
        callback = null
        synchronized(encodedLock) { encodedVideo?.close() }
        val result = CompletableFuture<Unit>()
        stopFuture = result
        // A separate watchdog prevents a wedged vendor call from manufacturing a clean result.
        val timeout = Runnable { result.completeExceptionally(IllegalStateException("SDK cleanup exceeded 10 seconds")) }
        main.postDelayed(timeout, 10_000)
        worker.execute {
            try {
                onMain {
                    usbPermission?.close()
                    usbPermission = null
                }.get(2, TimeUnit.SECONDS)
                val failures = mutableListOf<Throwable>()
                fun attempt(action: () -> Unit) { runCatching(action).onFailure { failures += it } }
                if (initialized) {
                    attempt { usbScanner?.setEnabled(false) }
                    attempt { adapter?.enableUsbDeviceDetection(false) }
                    attempt { adapter?.pauseCast() }
                    attempt { adapter?.stopAdvertise() }
                    attempt { adapter?.disconnect() }
                    attempt { adapter?.deInit() }
                    // deInit only waits briefly. A worker barrier observes its queued completion,
                    // including stopWfdServer's return, without requiring an unsolicited callback.
                    SdkVideoCleanup.awaitStopped(checkNotNull(adapter))
                    // Observe service onDestroy completion, not just stopService's return value.
                    onMain { SdkResources.stopServices(context) }.get(2, TimeUnit.SECONDS)
                    val until = SystemClock.elapsedRealtime() + 5_000
                    while (SdkResources.services.isNotEmpty() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                    check(SdkResources.services.isEmpty()) { "SDK services remain bound: ${SdkResources.services}" }
                    check(SdkResources.errors.isEmpty()) { "SDK service cleanup failed: ${SdkResources.errors.first().javaClass.simpleName}" }
                    if (connection == CarLinkConnection.P2P) {
                        if (groupPresent()) removeGroup()
                        // Two fresh asynchronous observations; a queued removal is not proof.
                        repeat(2) {
                            check(!groupPresent()) { "Wi-Fi Direct group still active" }
                            Thread.sleep(200)
                        }
                    }
                    check(failures.isEmpty()) { "SDK cleanup call failed: ${failures.first().javaClass.simpleName}" }
                }
                adapter?.unregisterCarConnectListener(listener)
                android.util.Log.i("CarLinkCleanup", "SDK worker drained; video, services and selected transport released; awaiting process exit")
                result.complete(Unit)
            } catch (failure: Throwable) {
                android.util.Log.e("CarLinkCleanup", "SDK cleanup failed", failure)
                result.completeExceptionally(failure)
            }
            finally {
                SdkResources.hotspotCredentials = null
                SdkResources.hotspotNetwork = null
                channel?.close()
                main.removeCallbacks(timeout)
                worker.shutdown()
            }
        }
        return result
    }

    private fun onMain(action: () -> Unit): CompletableFuture<Unit> = CompletableFuture<Unit>().also { f ->
        main.post { try { action(); f.complete(Unit) } catch (t: Throwable) { f.completeExceptionally(t) } }
    }
    private fun groupPresent(): Boolean {
        val answer = CompletableFuture<Boolean>()
        onMain {
            try {
                checkNotNull(p2p).requestGroupInfo(checkNotNull(channel)) { answer.complete(it != null) }
            } catch (denied: SecurityException) { answer.completeExceptionally(denied) }
        }.get(2, TimeUnit.SECONDS)
        return answer.get(2, TimeUnit.SECONDS)
    }
    private fun removeGroup() {
        val answer = CompletableFuture<Unit>()
        onMain {
            checkNotNull(p2p).removeGroup(checkNotNull(channel), object : WifiP2pManager.ActionListener {
                override fun onSuccess() { answer.complete(Unit) }
                override fun onFailure(reason: Int) { answer.completeExceptionally(IllegalStateException("P2P removal failed ($reason)")) }
            })
        }.get(2, TimeUnit.SECONDS)
        answer.get(2, TimeUnit.SECONDS)
    }
}
