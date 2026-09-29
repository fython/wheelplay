package com.shilapi.xcertplay.web

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
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

    @Synchronized fun start(context: Context) {
        if (server != null) return
        adaptiveBrowserSize = AirPlayPersistence.loadAdaptiveBrowserSize(context)
        audio.configure(AirPlayPersistence.loadBrowserAudioPlayback(context),
            AirPlayPersistence.loadBrowserMicrophone(context))
        code = (100000 + SecureRandom().nextInt(900000)).toString()
        val next = LanWebServer(context.applicationContext, code)
        try {
            next.start(5000, true); server = next; error = null
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
        catch (e: Exception) { next.stop(); error = "8080 端口启动失败：${e.javaClass.simpleName}" }
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
        val oldSecure = secureServer; secureServer = null; oldSecure?.stop(); tls = null; tlsError = null
        videoSource?.detach(); videoSource = null
        val old = server; server = null; old?.stop()
        browserViewport = null
        frame = null; videoActive = false; code = ""; generation++
    }

    fun inspectPairing(payload: String) = server?.pairing?.inspect(payload)
    fun approvePairing(payload: String) = server?.pairing?.approve(payload) == true

    fun addresses(context: Context): List<LanAddresses.Entry> = LanAddresses.discover(context)

    private const val MIN_VIEWPORT_PIXELS = 320
    private const val MAX_VIEWPORT_PIXELS = 8192
    private const val MIN_VIEWPORT_ASPECT = 0.5
    private const val MAX_VIEWPORT_ASPECT = 4.0
}
