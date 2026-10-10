package com.shilapi.xcertplay.web

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.network.TeslaHttpConfig
import java.security.SecureRandom

/** Service-owned web endpoint; media sinks only publish immutable latest frames. */
internal object WebSession {
    data class Frame(val jpeg: ByteArray, val at: Long, val sequence: Long)
    @Volatile var frame: Frame? = null
        private set
    @Volatile var width = 1280
        private set
    @Volatile var height = 720
        private set
    @Volatile var videoActive = false
    @Volatile var stage = "等待连接 iPhone"
    @Volatile var error: String? = null
        private set
    @Volatile private var server: LanWebServer? = null
    @Volatile var httpPort = TeslaHttpConfig.DEFAULT_PORT
        private set
    @Volatile private var secureServer: LanWebServer? = null
    @Volatile var tls: LanTls.Endpoint? = null
        private set
    @Volatile var tlsError: String? = null
        private set
    private var serverGeneration = 0L
    @Volatile var code = ""
        private set
    private var generation = 0L
    private var sequence = 0L
    @Volatile var videoSource: WebVideoSource? = null
        private set
    @Volatile var adaptiveBrowserSize = true
        private set
    @Volatile private var browserViewport: Pair<Int, Int>? = null
    val currentBrowserViewport: Pair<Int, Int>? get() = browserViewport
    @Volatile private var browserViewportListener: ((Int?, Int?) -> Unit)? = null
    val touch = TouchLease { contacts ->
        CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(contacts) ?: false
    }
    val running get() = server != null
    val hasViewer get() = server?.hasViewer == true || secureServer?.hasViewer == true
    val audio = BrowserAudioBridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var phoneConnectHandler: (() -> String)? = null
    private var lastPhoneConnectAt: Long? = null

    private var browserDevices: RememberedBrowsers? = null
    private var browserDevicesContext: Context? = null

    @Synchronized fun rememberedBrowsers(context: Context): RememberedBrowsers {
        val application = context.applicationContext
        if (browserDevicesContext !== application) {
            browserDevicesContext = application
            browserDevices = RememberedBrowsers(application)
        }
        return browserDevices!!
    }

    fun forgetBrowser(context: Context, id: String) {
        val devices = rememberedBrowsers(context)
        synchronized(devices) {
            devices.remove(id)
            server?.pairing?.revokeDevice(id)
        }
        server?.disconnectUnauthorized(); secureServer?.disconnectUnauthorized()
    }

    fun forgetAllBrowsers(context: Context) {
        val devices = rememberedBrowsers(context)
        synchronized(devices) {
            devices.clear()
            server?.pairing?.clear()
        }
        server?.disconnectViewer(); secureServer?.disconnectViewer()
    }

    // Accessed on the main thread so lifecycle changes and connection requests are ordered.
    fun setPhoneConnectHandler(handler: (() -> String)?) { phoneConnectHandler = handler }

    fun requestPhoneConnection(reply: (String) -> Unit) {
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            val message = when {
                CarPlayBackgroundSession.hasSession() -> "iPhone 会话已启动，请等待连接完成"
                phoneConnectHandler == null -> "请先将 Android 上的 WheelPlay 打开到前台，再点击连接 iPhone"
                lastPhoneConnectAt?.let { now - it < 3000 } == true -> "连接请求已提交，请稍候"
                else -> {
                    lastPhoneConnectAt = now
                    runCatching { phoneConnectHandler!!.invoke() }
                        .getOrElse { "无法启动连接，请在 Android 上检查连接设置" }
                }
            }
            reply(message)
        }
    }

    @Synchronized fun start(context: Context) {
        if (server != null) return
        adaptiveBrowserSize = AirPlayPersistence.loadAdaptiveBrowserSize(context)
        audio.configure(AirPlayPersistence.loadBrowserAudioPlayback(context),
            AirPlayPersistence.loadBrowserMicrophone(context))
        code = (100000 + SecureRandom().nextInt(900000)).toString()
        val port = TeslaHttpCompatibility.config(context).port
        val next = LanWebServer(context.applicationContext, code, port)
        try {
            next.start(5000, true); server = next; httpPort = port; error = null
            TeslaHttpCompatibility.start(context)
            val token = ++serverGeneration
            Thread({
                var secure: LanWebServer? = null
                try {
                    val endpoint = LanTls.create(context.applicationContext, addresses(context).map { it.address })
                    synchronized(this) {
                        if (serverGeneration != token || server == null) return@Thread
                        secure = LanWebServer(context.applicationContext, code, 8443, true, next.pairing)
                        secure!!.makeSecure(endpoint.sockets, null)
                        secure!!.start(5000, true)
                        secureServer = secure; tls = endpoint; tlsError = null
                    }
                } catch (error: Exception) {
                    secure?.stop()
                    synchronized(this) { if (serverGeneration == token) tlsError = "HTTPS 启动失败：${error.javaClass.simpleName}" }
                }
            }, "wheelplay-lan-tls").apply { isDaemon = true; start() }
        }
        catch (e: Exception) { next.stop(); error = "$port 端口启动失败：${e.message ?: e.javaClass.simpleName}" }
    }

    /** Bind before releasing the old listener, preserving media and pairing on failure. */
    @Synchronized fun setHttpPort(context: Context, port: Int): String? {
        val config = TeslaHttpCompatibility.config(context).copy(port = port)
        val current = server
        if (current != null && port != httpPort) {
            val next = LanWebServer(context.applicationContext, code, port, pairing = current.pairing)
            try {
                next.start(5000, true)
            } catch (e: Exception) {
                next.stopPreservingPairing()
                return "$port 端口启动失败：${e.message ?: e.javaClass.simpleName}；原端口 $httpPort 继续运行"
            }
            server = next
            httpPort = port
            current.stopPreservingPairing()
        }
        TeslaHttpCompatibility.save(context, config)
        return null
    }

    @Synchronized fun setBrowserAudioPlayback(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveBrowserAudioPlayback(context, enabled)
        audio.configure(enabled, AirPlayPersistence.loadBrowserMicrophone(context))
    }

    @Synchronized fun setBrowserMicrophone(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveBrowserMicrophone(context, enabled)
        audio.configure(AirPlayPersistence.loadBrowserAudioPlayback(context), enabled)
    }

    fun setAdaptiveBrowserSize(enabled: Boolean) {
        adaptiveBrowserSize = enabled
        val size = if (enabled) browserViewport else null
        browserViewportListener?.invoke(size?.first, size?.second)
    }

    fun setBrowserViewportListener(listener: ((Int?, Int?) -> Unit)?) {
        browserViewportListener = listener
        val size = if (adaptiveBrowserSize) browserViewport else null
        listener?.invoke(size?.first, size?.second)
    }

    fun reportBrowserViewport(width: Int, height: Int): Boolean {
        if (width !in MIN_VIEWPORT_PIXELS..MAX_VIEWPORT_PIXELS ||
            height !in MIN_VIEWPORT_PIXELS..MAX_VIEWPORT_PIXELS) return false
        val ratio = width.toDouble() / height
        if (ratio !in MIN_VIEWPORT_ASPECT..MAX_VIEWPORT_ASPECT) return false
        val size = width to height
        browserViewport = size
        if (adaptiveBrowserSize) browserViewportListener?.invoke(width, height)
        return true
    }

    @Synchronized fun beginVideo(w: Int, h: Int): Long {
        videoSource?.detach(); videoSource = null
        width = w; height = h; frame = null; videoActive = false
        return ++generation
    }
    @Synchronized fun attachVideoSource(owner: Long, source: WebVideoSource) {
        if (owner == generation) videoSource = source
    }
    fun publish(owner: Long, jpeg: ByteArray) {
        val endpoint = synchronized(this) {
            if (owner != generation || server == null) return
            frame = Frame(jpeg, SystemClock.elapsedRealtime(), ++sequence)
            server
        }
        // Notify outside the session monitor; encoding never waits for a socket write.
        endpoint?.frameAvailable()
        secureServer?.frameAvailable()
    }
    @Synchronized fun endVideo(owner: Long) { if (owner == generation) {
        videoSource?.detach(); videoSource = null
        generation++; frame = null; videoActive = false
    } }
    @Synchronized fun stop() {
        serverGeneration++
        TeslaHttpCompatibility.stop()
        val oldSecure = secureServer; secureServer = null; oldSecure?.stop(); tls = null; tlsError = null
        videoSource?.detach(); videoSource = null
        val old = server; server = null; old?.stop()
        browserViewport = null
        frame = null; videoActive = false; code = ""; generation++
    }

    fun inspectPairing(payload: String) = server?.pairing?.inspect(payload)
    fun approvePairing(payload: String) = server?.pairing?.approve(payload) == true

    fun addresses(context: Context): List<LanAddresses.Entry> = LanAddresses.discover(
        context, TeslaHttpCompatibility.status.address, TeslaHttpCompatibility.config(context).hostname, httpPort)

    private const val MIN_VIEWPORT_PIXELS = 320
    private const val MAX_VIEWPORT_PIXELS = 8192
    private const val MIN_VIEWPORT_ASPECT = 0.5
    private const val MAX_VIEWPORT_ASPECT = 4.0
}
