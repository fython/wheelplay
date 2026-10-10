package com.shilapi.xcertplay.web

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayBootstrap
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
    @Volatile var httpPort = WebListenSettings.DEFAULT_HTTP_PORT
        private set
    @Volatile var httpsPort = WebListenSettings.DEFAULT_HTTPS_PORT
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
    val running get() = server?.ready == true
    val httpsReady get() = tls != null && secureServer?.ready == true
    val httpListenerPort get() = server?.takeIf { it.ready }?.listeningPort
    val httpsListenerPort get() = secureServer?.takeIf { it.ready }?.listeningPort
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
        if (server?.ready == true) return
        if (server != null) stop()
        if (runCatching { DiPlayBootstrap.ensure(context) }.isFailure) {
            error = "认证资源未就绪，请先在设置中导入认证资源"
            return
        }
        adaptiveBrowserSize = AirPlayPersistence.loadAdaptiveBrowserSize(context)
        audio.configure(AirPlayPersistence.loadBrowserAudioPlayback(context),
            AirPlayPersistence.loadBrowserMicrophone(context))
        code = (100000 + SecureRandom().nextInt(900000)).toString()
        val port = WebListenSettings.httpPort(context)
        val next = LanWebServer(context.applicationContext, code, port)
        try {
            next.start(5000, true); server = next; httpPort = port; error = null
            httpsPort = WebListenSettings.httpsPort(context)
            tlsError = null
            TeslaHttpCompatibility.start(context)
            val token = ++serverGeneration
            if (!WebListenSettings.httpsEnabled(context)) return
            Thread({
                var secure: LanWebServer? = null
                try {
                    val endpoint = createTls(context.applicationContext)
                    synchronized(this) {
                        if (serverGeneration != token || server == null || !WebListenSettings.httpsEnabled(context)) return@Thread
                        secure = LanWebServer(context.applicationContext, code, httpsPort, true, next.pairing)
                        secure!!.makeSecure(endpoint.sockets, null)
                        secure!!.start(5000, true)
                        secureServer = secure; tls = endpoint; tlsError = null
                        TeslaHttpCompatibility.retry()
                    }
                } catch (error: Exception) {
                    secure?.stopPreservingPairing()
                    synchronized(this) { if (serverGeneration == token) tlsError = "HTTPS 启动失败：${error.message ?: error.javaClass.simpleName}" }
                }
            }, "wheelplay-lan-tls").apply { isDaemon = true; start() }
        }
        catch (e: Exception) { next.stop(); error = "$port 端口启动失败：${e.message ?: e.javaClass.simpleName}" }
    }

    /** Bind before releasing the old listener, preserving media and pairing on failure. */
    @Synchronized fun setHttpPort(context: Context, port: Int): String? {
        if (port !in 1024..65535) return "Web HTTP 监听端口必须为 1024–65535"
        if (port == WebListenSettings.httpsPort(context)) return "HTTP 与 HTTPS 不能使用相同端口"
        val current = server
        val previousPort = httpPort
        if (current != null && port != httpPort) {
            val next = LanWebServer(context.applicationContext, code, port, pairing = current.pairing)
            try {
                next.start(5000, true)
                WebListenSettings.saveHttpPort(context, port)
            } catch (e: Exception) {
                next.stopPreservingPairing()
                return "$port 端口启动失败：${e.message ?: e.javaClass.simpleName}；原端口 $httpPort 继续运行"
            }
            try { current.stopPreservingPairing() }
            catch (error: Exception) {
                runCatching { next.stopPreservingPairing() }
                this.error = "原端口规则清理失败，请检查规则或重新启动设备：${error.message}"
                return this.error
            }
            server = next
            httpPort = port
        }
        if (current == null || port == previousPort) WebListenSettings.saveHttpPort(context, port)
        httpPort = port
        TeslaHttpCompatibility.retry()
        return null
    }

    fun setHttpsPort(context: Context, port: Int): String? {
        if (port !in 1024..65535) return "Web HTTPS 监听端口必须为 1024–65535"
        if (port == WebListenSettings.httpPort(context)) return "HTTP 与 HTTPS 不能使用相同端口"
        return runCatching {
            if (!WebListenSettings.httpsEnabled(context)) {
                WebListenSettings.saveHttpsPort(context, port); httpsPort = port; return null
            }
            val endpoint = tls ?: createTls(context)
            replaceHttps(context, port, endpoint) { WebListenSettings.saveHttpsPort(context, port) }
                .also { if (it == null) TeslaHttpCompatibility.retry() }
        }.getOrElse { "HTTPS 配置失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun installCertificate(context: Context, prepared: LanTls.Prepared): String? =
        replaceHttps(context, WebListenSettings.httpsPort(context), prepared.endpoint) { LanTls.saveCustom(context, prepared) }

    @Synchronized fun setHttpsEnabled(context: Context, enabled: Boolean): String? = runCatching {
        if (enabled) {
            replaceHttps(context, WebListenSettings.httpsPort(context), tls ?: createTls(context), activate = true) {
                WebListenSettings.saveHttpsEnabled(context, true)
            }.also { if (it == null) TeslaHttpCompatibility.retry() }
        } else {
            WebListenSettings.saveHttpsEnabled(context, false)
            serverGeneration++
            val old = secureServer; secureServer = null; tls = null; tlsError = null
            old?.stopPreservingPairing()
            TeslaHttpCompatibility.retry()
            null
        }
    }.getOrElse { "HTTPS 开关更新失败：${it.message ?: it.javaClass.simpleName}" }

    fun setHostname(context: Context, hostname: String): String? = runCatching {
        WebListenSettings.saveHostname(context, hostname)
        refreshGeneratedCertificate(context)
        null
    }.getOrElse { it.message ?: "Web 域名保存失败" }

    fun restoreDefaultCertificate(context: Context): String? = runCatching {
        val config = TeslaHttpCompatibility.config(context)
        val endpoint = LanTls.createDefault(context, (addresses(context).map { it.address } + config.address).distinct(), WebListenSettings.hostname(context))
        replaceHttps(context, WebListenSettings.httpsPort(context), endpoint) { LanTls.clearCustom(context) }
    }.getOrElse { "默认 HTTPS 证书恢复失败：${it.message ?: it.javaClass.simpleName}" }

    private fun createTls(context: Context): LanTls.Endpoint {
        val config = TeslaHttpCompatibility.config(context)
        return LanTls.create(context, (addresses(context).map { it.address } + config.address).distinct(), WebListenSettings.hostname(context))
    }

    /** A changed port binds first. Certificate replacement on the same port rolls back on failure. */
    @Synchronized private fun replaceHttps(context: Context, port: Int, endpoint: LanTls.Endpoint, activate: Boolean = false, persist: () -> Unit): String? {
        val http = server
        if (http == null || !activate && !WebListenSettings.httpsEnabled(context)) return runCatching { persist(); httpsPort = port; null }
            .getOrElse { "HTTPS 配置保存失败：${it.javaClass.simpleName}" }
        if (port == httpPort) return "HTTP 与 HTTPS 不能使用相同端口"
        val old = secureServer
        val oldTls = tls
        val oldPort = httpsPort
        val samePort = old != null && oldPort == port
        val next = LanWebServer(context.applicationContext, code, port, true, http.pairing)
        if (samePort) { old!!.stopPreservingPairing(); secureServer = null }
        try {
            next.makeSecure(endpoint.sockets, null)
            next.start(5000, true)
            persist()
        } catch (error: Exception) {
            next.stopPreservingPairing()
            if (samePort && oldTls != null) {
                val restored = LanWebServer(context.applicationContext, code, oldPort, true, http.pairing)
                try {
                    restored.makeSecure(oldTls.sockets, null); restored.start(5000, true)
                    secureServer = restored
                } catch (_: Exception) {
                    restored.stopPreservingPairing(); tls = null
                    tlsError = "HTTPS 原监听器恢复失败，请重新启动服务"
                    return "HTTPS 更新失败，原证书和端口配置保留；请重新启动服务"
                }
            }
            return "HTTPS 更新失败：${error.message ?: error.javaClass.simpleName}；原配置保留"
        }
        serverGeneration++ // Discard older initialization only after a successful replacement.
        secureServer = next; tls = endpoint; httpsPort = port; tlsError = null
        if (!samePort) old?.stopPreservingPairing()
        return null
    }

    fun refreshGeneratedCertificate(context: Context) {
        val generation = synchronized(this) { if (server == null || !WebListenSettings.httpsEnabled(context) || LanTls.hasCustom(context)) return; serverGeneration }
        val hostname = WebListenSettings.hostname(context)
        Thread({
            val config = TeslaHttpCompatibility.config(context)
            val result = runCatching { createTls(context) }
            synchronized(this) {
                if (serverGeneration != generation || server == null || LanTls.hasCustom(context) ||
                    TeslaHttpCompatibility.config(context) != config || WebListenSettings.hostname(context) != hostname ||
                    !WebListenSettings.httpsEnabled(context)) return@Thread
                result.onSuccess { replaceHttps(context, httpsPort, it) {} }
                    .onFailure { tlsError = "HTTPS 证书刷新失败：${it.javaClass.simpleName}" }
            }
        }, "https-address-refresh").apply { isDaemon = true; start() }
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
        val oldSecure = secureServer; secureServer = null
        val cleanupError = runCatching { oldSecure?.stop() }.exceptionOrNull()
        tls = null; tlsError = cleanupError?.message
        videoSource?.detach(); videoSource = null
        val old = server; server = null
        runCatching { old?.stop() }.exceptionOrNull()?.let { error = it.message }
        browserViewport = null
        frame = null; videoActive = false; code = ""; generation++
    }

    fun inspectPairing(payload: String) = server?.pairing?.inspect(payload)
    fun approvePairing(payload: String) = server?.pairing?.approve(payload) == true

    fun addresses(context: Context): List<LanAddresses.Entry> {
        val status = TeslaHttpCompatibility.status
        return LanAddresses.discover(context, status.address, WebListenSettings.hostname(context), httpPort, status.httpMappingPort)
    }

    fun httpsPortFor(context: Context, authority: String?, status: com.shilapi.xcertplay.network.TeslaHttpStatus = TeslaHttpCompatibility.status): Int {
        val host = runCatching { java.net.URI("http://$authority") }.getOrNull() ?: return httpsPort
        val http = if (host.port < 0) 80 else host.port
        return status.httpsMappingPort?.takeIf {
            status.address != null && http == status.httpMappingPort &&
                (host.host == status.address || host.host?.equals(WebListenSettings.hostname(context), true) == true)
        } ?: httpsPort
    }

    private const val MIN_VIEWPORT_PIXELS = 320
    private const val MAX_VIEWPORT_PIXELS = 8192
    private const val MIN_VIEWPORT_ASPECT = 0.5
    private const val MAX_VIEWPORT_ASPECT = 4.0
}
