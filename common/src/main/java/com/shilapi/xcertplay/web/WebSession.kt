package com.shilapi.xcertplay.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.BrowserOfflinePolicy
import com.shilapi.xcertplay.CarPlayAppearance
import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.projection.ProjectionContact
import com.shilapi.xcertplay.projection.ProjectionSession
import com.shilapi.xcertplay.CarPlayMicrophoneSource
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.i18n.*
import com.shilapi.xcertplay.isDarkMode
import com.shilapi.xcertplay.shared.R as UiR
import java.security.SecureRandom

/** Service-owned web endpoint; media sinks only publish immutable latest frames. */
internal object WebSession {
    data class Frame(
        val jpeg: ByteArray, val at: Long, val sequence: Long,
        val sessionId: Long = 0, val videoEpoch: Long = 0,
        val width: Int = 1280, val height: Int = 720,
        val format: ImageFormat = ImageFormat.JPEG,
        val quality: Int = ImageEncodingOptions.DEFAULT_QUALITY,
    )

    data class MediaSnapshot(
        val sessionId: Long, val videoEpoch: Long, val width: Int, val height: Int,
        val sourceState: String, val inputReady: Boolean, val frame: Frame?, val source: WebVideoSource?,
        val sourceId: String? = null, val sourceName: String? = null, val maxTouchContacts: Int = 0,
    )

    private var mediaSessionId = SecureRandom().nextLong().ushr(11)
    private var mediaEnded = true

    /** One coherent configuration; a static source need not publish new frames. */
    @Synchronized fun mediaSnapshot(): MediaSnapshot = MediaSnapshot(
        mediaSessionId, generation, width, height,
        when {
            mediaEnded -> "stopped"
            videoActive -> "ready"
            frame == null -> "starting"
            else -> "recovering"
        },
        !mediaEnded && videoActive && projectionSessions.inputReady(inputSession),
        frame, videoSource,
        inputSession?.descriptor?.id, inputSession?.descriptor?.displayName,
        inputSession?.capabilities?.maxTouchContacts ?: 0,
    )

    @Synchronized fun mediaMatches(sessionId: Long, epoch: Long): Boolean =
        !mediaEnded && videoActive && sessionId == mediaSessionId && epoch == generation

    /** Keep version validation and delivery in the same configuration critical section. */
    @Synchronized fun mediaTouch(client: Any, sessionId: Long, epoch: Long,
        contacts: List<ProjectionContact>, at: Long): Boolean {
        if (!mediaMatches(sessionId, epoch) ||
            !projectionSessions.inputReady(inputSession)) return false
        return touch.touch(client, contacts, at)
    }

    @Volatile
    var frame: Frame? = null
        private set

    @Volatile
    var width = 1280
        private set

    @Volatile
    var height = 720
        private set

    @Volatile var videoActive = false
    @Volatile private var stageText = UiText(UiR.string.projection_waiting)

    fun stageDetail(context: Context): String = stageText.resolve(context)

    fun stage(context: Context): String {
        val session = projectionSessions.snapshot()
        return when {
            session == null -> stageText.resolve(context)
            session.statusText != null -> session.statusText!!
            else -> context.getString(UiR.string.projection_active, session.descriptor.displayName)
        }
    }

    fun setStage(value: UiText) {
        stageText = value
    }

    @Volatile
    var error: String? = null
        private set

    @Volatile private var server: LanWebServer? = null
    private var mdns: WebMdns? = null
    @Volatile
    var httpPort = WebListenSettings.DEFAULT_HTTP_PORT
        private set

    @Volatile
    var httpsPort = WebListenSettings.DEFAULT_HTTPS_PORT
        private set

    @Volatile private var secureServer: LanWebServer? = null
    @Volatile
    var tls: LanTls.Endpoint? = null
        private set

    @Volatile
    var tlsError: String? = null
        private set

    private var serverGeneration = 0L
    @Volatile
    var code = ""
        private set

    private var generation = 0L
    private var sequence = 0L
    @Volatile
    var videoSource: WebVideoSource? = null
        private set

    @Volatile
    var adaptiveBrowserSize = true
        private set

    @Volatile private var browserViewport: Pair<Int, Int>? = null
    val currentBrowserViewport: Pair<Int, Int>?
        get() = browserViewport

    @Volatile private var browserViewportListener: ((Int?, Int?) -> Unit)? = null
    @Volatile private var inputSession: ProjectionSession? = null
    val maxTouchContacts get() = projectionSessions.snapshot()?.capabilities?.maxTouchContacts ?: 0
    val touch = TouchLease(maxContacts = { maxTouchContacts }) { contacts ->
        val target = inputSession
        target != null && projectionSessions.touch(target, contacts)
    }
    val running
        get() = server?.ready == true

    val httpsReady
        get() = tls != null && secureServer?.ready == true

    val httpListenerPort
        get() = server?.takeIf { it.ready }?.listeningPort

    val httpsListenerPort
        get() = secureServer?.takeIf { it.ready }?.listeningPort

    val hasViewer
        get() = server?.hasViewer == true || secureServer?.hasViewer == true

    val imageFormat: ImageFormat
        get() = if (server?.hasViewer == true) server?.imageFormat ?: ImageFormat.JPEG
            else secureServer?.imageFormat ?: ImageFormat.JPEG

    val audio = BrowserAudioBridge()
    val location = BrowserLocationBridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var experienceContext: Context? = null
    private var offlineSubscription: AutoCloseable? = null
    private val preferenceExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "projection-preferences").apply { isDaemon = true }
        }
    private val offlineMonitor =
        BrowserOfflineMonitor(
            mainHandler,
            { projectionSessions.snapshot() },
            { hasViewer },
        ) { expected ->
            if (
                experienceContext != null &&
                    projectionSessions.snapshot() === expected
            ) {
                projectionSessions.stop(expected as ProjectionSession)

            }
        }

    fun viewerStateChanged() {
        val refresh = {
            experienceContext?.let {
                offlineMonitor.refresh(
                    AirPlayPersistence.loadBrowserOfflinePolicy(it).timeoutMillis
                )
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh()
        else mainHandler.post { refresh() }
    }

    fun setBrowserOfflinePolicy(context: Context, value: BrowserOfflinePolicy) {
        AirPlayPersistence.saveBrowserOfflinePolicy(context, value)
        viewerStateChanged()
    }

    fun setAppearance(context: Context, value: CarPlayAppearance) {
        AirPlayPersistence.saveAppearance(context, value)
        refreshAppearance(context)
    }

    fun refreshAppearance(context: Context) {
        val controller = projectionSessions.snapshot() ?: return
        preferenceExecutor.execute {
            if (projectionSessions.snapshot() === controller) {
                val night =
                    AirPlayPersistence.loadAppearance(context)
                        .night(isDarkMode(context.resources.configuration.uiMode))
                projectionSessions.setNightMode(controller, night)
            }
        }
    }

    private fun configureAudio(context: Context) {
        val source = AirPlayPersistence.loadMicrophoneSource(context)
        val capabilities = projectionSessions.snapshot()?.capabilities
        audio.configure(
            capabilities?.audioPlayback == true && AirPlayPersistence.loadBrowserAudioPlayback(context),
            capabilities?.microphone == true && source == CarPlayMicrophoneSource.BROWSER,
            AirPlayPersistence.loadMediaBufferMillis(context),
            capabilities?.microphone == true && source != CarPlayMicrophoneSource.DISABLED,
        )
    }

    private var phoneConnectHandler: (() -> String)? = null
    private var lastPhoneConnectAt: Long? = null

    private var browserDevices: RememberedBrowsers? = null
    private var browserDevicesContext: Context? = null

    @Synchronized
    fun rememberedBrowsers(context: Context): RememberedBrowsers {
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
        server?.disconnectUnauthorized()
        secureServer?.disconnectUnauthorized()
    }

    fun forgetAllBrowsers(context: Context) {
        val devices = rememberedBrowsers(context)
        synchronized(devices) {
            devices.clear()
            server?.pairing?.clear()
        }
        server?.disconnectViewer()
        secureServer?.disconnectViewer()
    }

    // Accessed on the main thread so lifecycle changes and connection requests are ordered.
    fun setPhoneConnectHandler(handler: (() -> String)?) {
        phoneConnectHandler = handler
    }

    fun requestPhoneConnection(context: Context, reply: (String) -> Unit) {
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            val message =
                when {
                    projectionSessions.hasSession() ->
                        context.getString(
                            UiR.string.projection_busy
                        )
                    phoneConnectHandler == null ->
                        context.getString(
                            UiR.string.ui_bring_wheelplay_to_the_foreground_on_android_then_tap
                        )
                    lastPhoneConnectAt?.let { now - it < 3000 } == true ->
                        context.getString(UiR.string.ui_connection_requested_please_wait)
                    else -> {
                        lastPhoneConnectAt = now
                        runCatching { phoneConnectHandler!!.invoke() }
                            .getOrElse {
                                context.getString(
                                    UiR.string
                                        .ui_could_not_start_connection_check_connection_settings_on_android
                                )
                            }
                    }
                }
            reply(message)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (server?.ready == true) return
        if (server != null) stop()
        adaptiveBrowserSize = AirPlayPersistence.loadAdaptiveBrowserSize(context)
        configureAudio(context)
        code = (100000 + SecureRandom().nextInt(900000)).toString()
        val port = WebListenSettings.httpPort(context)
        val next = LanWebServer(context.applicationContext, code, port)
        try {
            next.start(5000, true)
            server = next
            httpPort = port
            error = null
            mdns = WebMdns(context.applicationContext)
            experienceContext = context.applicationContext
            var previousSource = inputSession
            offlineSubscription = projectionSessions.subscribe {
                val changed = synchronized(this) {
                    // Read under the configuration lock: a delayed notification cannot restore an old route.
                    val current = projectionSessions.snapshot()
                    if (previousSource === current) false else {
                        touch.reset()
                        inputSession = current
                        if (current == null) {
                            stageText = UiText(UiR.string.projection_waiting)
                            endVideo(generation)
                        }
                        previousSource = current
                        true
                    }
                }
                if (changed) {
                    // Versionless clients cannot distinguish old touches after a source switch.
                    server?.disconnectViewer()
                    secureServer?.disconnectViewer()
                    configureAudio(context)
                    refreshAppearance(context)
                }
                viewerStateChanged()
            }
            httpsPort = WebListenSettings.httpsPort(context)
            tlsError = null
            TeslaHttpCompatibility.start(context)
            val token = ++serverGeneration
            if (!WebListenSettings.httpsEnabled(context)) return
            Thread(
                    {
                        var secure: LanWebServer? = null
                        try {
                            val endpoint = createTls(context.applicationContext)
                            synchronized(this) {
                                if (
                                    serverGeneration != token ||
                                        server == null ||
                                        !WebListenSettings.httpsEnabled(context)
                                )
                                    return@Thread
                                secure =
                                    LanWebServer(
                                        context.applicationContext,
                                        code,
                                        httpsPort,
                                        true,
                                        next.pairing,
                                    )
                                secure!!.makeSecure(endpoint.sockets, null)
                                secure!!.start(5000, true)
                                secureServer = secure
                                tls = endpoint
                                tlsError = null
                                TeslaHttpCompatibility.retry()
                            }
                        } catch (error: Exception) {
                            secure?.stopPreservingPairing()
                            synchronized(this) {
                                if (serverGeneration == token)
                                    tlsError =
                                        context.getString(
                                            UiR.string.error_https_start,
                                            error.userMessage(context),
                                        )
                            }
                        }
                    },
                    "wheelplay-lan-tls",
                )
                .apply {
                    isDaemon = true
                    start()
                }
        } catch (e: Exception) {
            next.stop()
            error = context.getString(UiR.string.error_listener_start, port, e.userMessage(context))
        }
    }

    /** Bind before releasing the old listener, preserving media and pairing on failure. */
    @Synchronized
    fun setHttpPort(context: Context, port: Int): String? {
        if (port !in 1024..65535)
            return context.getString(UiR.string.ui_web_http_listener_port_must_be_between_1024_and)
        if (port == WebListenSettings.httpsPort(context))
            return context.getString(UiR.string.ui_http_and_https_cannot_use_the_same_port)
        val current = server
        val previousPort = httpPort
        if (current != null && port != httpPort) {
            val next =
                LanWebServer(context.applicationContext, code, port, pairing = current.pairing)
            try {
                next.start(5000, true)
                WebListenSettings.saveHttpPort(context, port)
            } catch (e: Exception) {
                next.stopPreservingPairing()
                return context.getString(
                    UiR.string.error_listener_start_kept_previous,
                    port,
                    e.userMessage(context),
                    httpPort,
                )
            }
            try {
                current.stopPreservingPairing()
            } catch (error: Exception) {
                runCatching { next.stopPreservingPairing() }
                this.error =
                    context.getString(
                        UiR.string.ui_could_not_clean_up_the_original_port_rules_check,
                        error.userMessage(context),
                    )
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
        if (port !in 1024..65535)
            return context.getString(UiR.string.ui_web_https_listener_port_must_be_between_1024_and)
        if (port == WebListenSettings.httpPort(context))
            return context.getString(UiR.string.ui_http_and_https_cannot_use_the_same_port)
        return runCatching {
                if (!WebListenSettings.httpsEnabled(context)) {
                    WebListenSettings.saveHttpsPort(context, port)
                    httpsPort = port
                    return null
                }
                val endpoint = tls ?: createTls(context)
                replaceHttps(context, port, endpoint) {
                        WebListenSettings.saveHttpsPort(context, port)
                    }
                    .also { if (it == null) TeslaHttpCompatibility.retry() }
            }
            .getOrElse {
                context.getString(UiR.string.error_https_configuration, it.userMessage(context))
            }
    }

    fun installCertificate(context: Context, prepared: LanTls.Prepared): String? =
        replaceHttps(context, WebListenSettings.httpsPort(context), prepared.endpoint) {
            LanTls.saveCustom(context, prepared)
        }

    @Synchronized
    fun setHttpsEnabled(context: Context, enabled: Boolean): String? =
        runCatching {
                if (enabled) {
                    replaceHttps(
                            context,
                            WebListenSettings.httpsPort(context),
                            tls ?: createTls(context),
                            activate = true,
                        ) {
                            WebListenSettings.saveHttpsEnabled(context, true)
                        }
                        .also { if (it == null) TeslaHttpCompatibility.retry() }
                } else {
                    WebListenSettings.saveHttpsEnabled(context, false)
                    serverGeneration++
                    val old = secureServer
                    secureServer = null
                    tls = null
                    tlsError = null
                    old?.stopPreservingPairing()
                    TeslaHttpCompatibility.retry()
                    null
                }
            }
            .getOrElse { context.getString(UiR.string.error_https_toggle, it.userMessage(context)) }

    fun setHostname(context: Context, hostname: String): String? =
        runCatching {
                WebListenSettings.saveHostname(context, hostname)
                refreshGeneratedCertificate(context)
                null
            }
            .getOrElse { it.userMessage(context) }

    @Synchronized
    fun setMdnsHostname(context: Context, hostname: String): String? =
        runCatching {
                WebListenSettings.saveMdnsHostname(context, hostname)
                mdns?.close()
                mdns = if (server != null) WebMdns(context.applicationContext) else null
                null
            }
            .getOrElse {
                it.userMessage(context).ifEmpty {
                    context.getString(R.string.settings_mdns_save_failed)
                }
            }

    fun restoreDefaultCertificate(context: Context): String? =
        runCatching {
                val config = TeslaHttpCompatibility.config(context)
                val endpoint =
                    LanTls.createDefault(
                        context,
                        (addresses(context).map { it.address } + config.accessAddress).distinct(),
                        WebListenSettings.hostname(context),
                    )
                replaceHttps(context, WebListenSettings.httpsPort(context), endpoint) {
                    LanTls.clearCustom(context)
                }
            }
            .getOrElse {
                context.getString(
                    UiR.string.error_default_certificate_restore,
                    it.userMessage(context),
                )
            }

    private fun createTls(context: Context): LanTls.Endpoint {
        val config = TeslaHttpCompatibility.config(context)
        return LanTls.create(
            context,
            (addresses(context).map { it.address } + config.accessAddress).distinct(),
            WebListenSettings.hostname(context),
        )
    }

    /**
     * A changed port binds first. Certificate replacement on the same port rolls back on failure.
     */
    @Synchronized
    private fun replaceHttps(
        context: Context,
        port: Int,
        endpoint: LanTls.Endpoint,
        activate: Boolean = false,
        persist: () -> Unit,
    ): String? {
        val http = server
        if (http == null || !activate && !WebListenSettings.httpsEnabled(context))
            return runCatching {
                    persist()
                    httpsPort = port
                    null
                }
                .getOrElse {
                    context.getString(UiR.string.error_https_settings_save, it.javaClass.simpleName)
                }
        if (port == httpPort)
            return context.getString(UiR.string.ui_http_and_https_cannot_use_the_same_port)
        val old = secureServer
        val oldTls = tls
        val oldPort = httpsPort
        val samePort = old != null && oldPort == port
        val next = LanWebServer(context.applicationContext, code, port, true, http.pairing)
        if (samePort) {
            old!!.stopPreservingPairing()
            secureServer = null
        }
        try {
            next.makeSecure(endpoint.sockets, null)
            next.start(5000, true)
            persist()
        } catch (error: Exception) {
            next.stopPreservingPairing()
            if (samePort && oldTls != null) {
                val restored =
                    LanWebServer(context.applicationContext, code, oldPort, true, http.pairing)
                try {
                    restored.makeSecure(oldTls.sockets, null)
                    restored.start(5000, true)
                    secureServer = restored
                } catch (_: Exception) {
                    restored.stopPreservingPairing()
                    tls = null
                    tlsError =
                        context.getString(
                            UiR.string.ui_could_not_restore_the_previous_https_listener_restart_the
                        )
                    return context.getString(
                        UiR.string.ui_https_update_failed_original_certificate_and_ports_were_kept
                    )
                }
            }
            return context.getString(
                UiR.string.error_https_update_kept_previous,
                error.userMessage(context),
            )
        }
        serverGeneration++ // Discard older initialization only after a successful replacement.
        secureServer = next
        tls = endpoint
        httpsPort = port
        tlsError = null
        if (!samePort) old?.stopPreservingPairing()
        return null
    }

    fun refreshGeneratedCertificate(context: Context) {
        val generation =
            synchronized(this) {
                if (
                    server == null ||
                        !WebListenSettings.httpsEnabled(context) ||
                        LanTls.hasCustom(context)
                )
                    return
                serverGeneration
            }
        val hostname = WebListenSettings.hostname(context)
        Thread(
                {
                    val config = TeslaHttpCompatibility.config(context)
                    val result = runCatching { createTls(context) }
                    synchronized(this) {
                        if (
                            serverGeneration != generation ||
                                server == null ||
                                LanTls.hasCustom(context) ||
                                TeslaHttpCompatibility.config(context) != config ||
                                WebListenSettings.hostname(context) != hostname ||
                                !WebListenSettings.httpsEnabled(context)
                        )
                            return@Thread
                        result
                            .onSuccess { replaceHttps(context, httpsPort, it) {} }
                            .onFailure {
                                tlsError =
                                    context.getString(
                                        UiR.string.error_certificate_refresh,
                                        it.javaClass.simpleName,
                                    )
                            }
                    }
                },
                "https-address-refresh",
            )
            .apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun setBrowserAudioPlayback(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveBrowserAudioPlayback(context, enabled)
        configureAudio(context)
    }

    @Synchronized
    fun setBrowserMicrophone(context: Context, enabled: Boolean) {
        setMicrophoneSource(
            context,
            if (enabled) CarPlayMicrophoneSource.BROWSER else CarPlayMicrophoneSource.ANDROID,
        )
    }

    @Synchronized
    fun setMicrophoneSource(context: Context, source: CarPlayMicrophoneSource) {
        AirPlayPersistence.saveMicrophoneSource(context, source)
        configureAudio(context)
    }

    @Synchronized
    fun setMediaBufferMillis(context: Context, millis: Int) {
        AirPlayPersistence.saveMediaBufferMillis(context, millis)
        configureAudio(context)
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
        if (
            width !in MIN_VIEWPORT_PIXELS..MAX_VIEWPORT_PIXELS ||
                height !in MIN_VIEWPORT_PIXELS..MAX_VIEWPORT_PIXELS
        )
            return false
        val ratio = width.toDouble() / height
        if (ratio !in MIN_VIEWPORT_ASPECT..MAX_VIEWPORT_ASPECT) return false
        val size = width to height
        browserViewport = size
        if (adaptiveBrowserSize) browserViewportListener?.invoke(width, height)
        return true
    }

    @Synchronized
    fun beginVideo(w: Int, h: Int): Long {
        touch.cancel()
        if (mediaEnded) mediaSessionId = SecureRandom().nextLong().ushr(11)
        mediaEnded = false
        videoSource?.detach()
        videoSource = null
        width = w
        height = h
        frame = null
        videoActive = false
        return ++generation
    }

    @Synchronized
    fun setVideoActive(owner: Long, active: Boolean) {
        if (owner == generation && !mediaEnded) videoActive = active
    }

    @Synchronized
    fun attachVideoSource(owner: Long, source: WebVideoSource) {
        if (owner == generation) videoSource = source
    }

    fun publish(owner: Long, jpeg: ByteArray, format: ImageFormat = ImageFormat.JPEG,
        quality: Int = ImageEncodingOptions.DEFAULT_QUALITY) {
        val endpoint =
            synchronized(this) {
                if (owner != generation || server == null) return
                frame = Frame(jpeg, SystemClock.elapsedRealtime(), ++sequence,
                    mediaSessionId, generation, width, height, format, quality)
                server
            }
        // Notify outside the session monitor; encoding never waits for a socket write.
        endpoint?.frameAvailable()
        secureServer?.frameAvailable()
    }

    @Synchronized
    fun endVideo(owner: Long) {
        if (owner == generation && !mediaEnded) {
            touch.cancel()
            mediaEnded = true
            videoSource?.detach()
            videoSource = null
            generation++
            frame = null
            videoActive = false
        }
    }

    @Synchronized
    fun stop() {
        mediaEnded = true
        mdns?.close()
        mdns = null
        experienceContext = null
        offlineSubscription?.close()
        offlineSubscription = null
        inputSession = null
        if (Looper.myLooper() == Looper.getMainLooper()) offlineMonitor.close()
        else mainHandler.post { if (experienceContext == null) offlineMonitor.close() }
        serverGeneration++
        TeslaHttpCompatibility.stop()
        val oldSecure = secureServer
        secureServer = null
        val cleanupError = runCatching { oldSecure?.stop() }.exceptionOrNull()
        tls = null
        tlsError = cleanupError?.message
        videoSource?.detach()
        videoSource = null
        val old = server
        server = null
        runCatching { old?.stop() }.exceptionOrNull()?.let { error = it.message }
        browserViewport = null
        frame = null
        videoActive = false
        code = ""
        generation++
    }

    fun inspectPairing(payload: String) = server?.pairing?.inspect(payload)

    fun approvePairing(payload: String) = server?.pairing?.approve(payload) == true

    fun addresses(context: Context): List<LanAddresses.Entry> {
        val status = TeslaHttpCompatibility.status
        return LanAddresses.discover(
            context,
            status.address,
            WebListenSettings.hostname(context),
            httpPort,
            status.httpMappingPort,
            status.mode,
        )
    }

    fun httpsPortFor(
        context: Context,
        authority: String?,
        status: com.shilapi.xcertplay.network.TeslaHttpStatus = TeslaHttpCompatibility.status,
    ): Int {
        val host = runCatching { java.net.URI("http://$authority") }.getOrNull() ?: return httpsPort
        val http = if (host.port < 0) 80 else host.port
        return status.httpsMappingPort?.takeIf {
            status.address != null &&
                http == status.httpMappingPort &&
                (host.host == status.address ||
                    host.host?.equals(WebListenSettings.hostname(context), true) == true)
        } ?: httpsPort
    }

    private const val MIN_VIEWPORT_PIXELS = 320
    private const val MAX_VIEWPORT_PIXELS = 8192
    private const val MIN_VIEWPORT_ASPECT = 0.5
    private const val MAX_VIEWPORT_ASPECT = 4.0
}
