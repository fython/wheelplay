package com.shilapi.xcertplay.web

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlayContact
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Same-origin HTTP assets and a single paired, backpressured video/control WebSocket. */
internal class LanWebServer(private val context: Context, private val code: String, port: Int = 8080,
    private val secure: Boolean = false, val pairing: QrPairing = QrPairing()) : NanoWSD("0.0.0.0", port) {
    @Volatile private var viewer: Client? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var failures = 0
    @Volatile private var retryAt = 0L
    private val devices = WebSession.rememberedBrowsers(context)
    private val enrolled = mutableMapOf<String, RememberedBrowsers.Credential>()
    val hasViewer get() = viewer != null
    fun frameAvailable() { viewer?.frameAvailable() }
    fun disconnectViewer() { viewer?.disconnect("设备已移除") }

    fun disconnectUnauthorized() {
        viewer?.let { if (!it.authorized()) it.disconnect("设备已移除或配对已过期") }
    }

    @Synchronized private fun acceptCode(value: String?): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return false
        if (value != code) {
            if (++failures >= 5) { retryAt = now + 30000; failures = 0 }
            return false
        }
        failures = 0
        return true
    }

    init {
        timer.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            WebSession.touch.expire(now)
            disconnectUnauthorized()
            viewer?.let { if (now - it.lastSeen > 6000 || it.frameStalled(now)) it.disconnect("连接超时") }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/stream" && isWebsocketRequested(session)) {
            val origin = session.headers["origin"]
            val sameOrigin = runCatching {
                val parsed = URI(origin ?: "")
                parsed.scheme == (if (secure) "https" else "http") && parsed.rawAuthority.equals(session.headers["host"], true)
            }.getOrDefault(false)
            if (!sameOrigin) return response(Response.Status.FORBIDDEN, "Origin rejected")
            if (!pairing.authorized(session.parameters["token"]?.firstOrNull()) &&
                !acceptCode(session.parameters["code"]?.firstOrNull())) {
                return response(Response.Status.UNAUTHORIZED, "Pairing code required")
            }
            return super.serve(session)
        }
        // Never upgrade arbitrary paths into a control channel.
        if (isWebsocketRequested(session)) return response(Response.Status.NOT_FOUND, "Not found")
        return serveHttp(session)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        if (session.uri.startsWith("/pair/")) return servePairing(session)
        if (session.method != Method.GET) return response(Response.Status.METHOD_NOT_ALLOWED, "GET only")
        if (session.uri == "/tls.json") return newFixedLengthResponse(Response.Status.OK, "application/json",
            JSONObject().put("available", WebSession.tls != null).put("fingerprint", WebSession.tls?.fingerprint)
                .put("error", WebSession.tlsError).toString()).apply { addHeader("Cache-Control", "no-store") }
        if (session.uri == "/certificate.crt") {
            val bytes = WebSession.tls?.certificate ?: return response(Response.Status.NOT_FOUND, "HTTPS not ready")
            return newFixedLengthResponse(Response.Status.OK, "application/x-x509-ca-cert", bytes.inputStream(), bytes.size.toLong()).apply {
                addHeader("Content-Disposition", "attachment; filename=wheelplay-local-ca.crt")
                addHeader("Cache-Control", "no-store")
            }
        }
        val asset = when (session.uri) {
            "/", "/index.html" -> "index.html" to "text/html; charset=utf-8"
            "/app.js" -> "app.js" to "text/javascript; charset=utf-8"
            "/style.css" -> "style.css" to "text/css; charset=utf-8"
            "/browser-audio.js" -> "browser-audio.js" to "text/javascript; charset=utf-8"
            "/audio-worklet.js" -> "audio-worklet.js" to "text/javascript; charset=utf-8"
            else -> return response(Response.Status.NOT_FOUND, "Not found")
        }
        return context.assets.open("web/${asset.first}").use {
            val bytes = it.readBytes()
            newFixedLengthResponse(Response.Status.OK, asset.second, bytes.inputStream(), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            }
        }
    }

    private fun servePairing(session: IHTTPSession): Response {
        val id = session.headers["x-pair-id"] ?: session.parameters["id"]?.firstOrNull()
        val secret = session.headers["x-pair-secret"] ?: session.parameters["secret"]?.firstOrNull()
        if (session.uri == "/pair/qr" && session.method == Method.GET) {
            val request = pairing.get(id, secret) ?: return response(Response.Status.NOT_FOUND, "Expired")
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(request.payload,
                com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 4))
            val path = buildString {
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) append("M$x,$y h1v1h-1z")
                }
            }
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges"><path fill="white" d="M0,0h${matrix.width}v${matrix.height}H0z"/><path fill="black" d="$path"/></svg>"""
            return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg).apply {
                addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Referrer-Policy", "no-referrer")
            }
        }
        if (session.method != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        val sameOrigin = runCatching {
            val origin = URI(session.headers["origin"] ?: "")
            origin.scheme == (if (secure) "https" else "http") && origin.rawAuthority.equals(session.headers["host"], true)
        }.getOrDefault(false)
        if (!sameOrigin) return response(Response.Status.FORBIDDEN, "Origin rejected")
        val json = when (session.uri) {
            "/pair/request" -> {
                val request = pairing.create(session.remoteIpAddress)
                    ?: return response(Response.Status.FORBIDDEN, "Too many requests; try again in two minutes")
                JSONObject().put("id", request.id).put("secret", request.pollSecret).put("expiresIn", 120)
            }
            "/pair/status" -> {
                val request = pairing.get(id, secret) ?: return response(Response.Status.NOT_FOUND, "Expired")
                JSONObject().put("state", if (request.token == null) "pending" else "approved")
                    .apply { request.token?.let { put("token", it) } }
            }
            "/pair/code" -> {
                if (!acceptCode(session.headers["x-pair-code"]))
                    return response(Response.Status.UNAUTHORIZED, "Pairing code required")
                val token = pairing.issueToken() ?: return response(Response.Status.FORBIDDEN, "Too many sessions")
                JSONObject().put("token", token)
            }
            "/pair/remember" -> synchronized(devices) {
                val token = session.headers["x-pair-token"]
                if (token == null || !pairing.authorized(token))
                    return response(Response.Status.UNAUTHORIZED, "Pairing required")
                enrolled.entries.removeAll { !pairing.authorized(it.key) }
                val credential = enrolled[token] ?: run {
                    // Resumed tokens are already bound. They cannot create additional devices.
                    if (pairing.deviceId(token) != null)
                        return response(Response.Status.FORBIDDEN, "Device already remembered")
                    val name = browserName(session.headers["user-agent"].orEmpty())
                    devices.remember(name, session.remoteIpAddress)
                        ?: return response(Response.Status.FORBIDDEN, "Cannot remember device; check device limit")
                }.also { enrolled[token] = it }
                pairing.bindDevice(token, credential.device.id)
                JSONObject().put("id", credential.device.id).put("secret", credential.secret)
                    .put("name", credential.device.name)
            }
            "/pair/resume" -> synchronized(devices) {
                val device = devices.authenticate(session.headers["x-device-id"], session.headers["x-device-secret"], session.remoteIpAddress)
                    ?: return response(Response.Status.UNAUTHORIZED, "Device not remembered")
                val token = pairing.issueToken(device.id) ?: return response(Response.Status.FORBIDDEN, "Too many sessions")
                JSONObject().put("token", token).put("name", device.name)
            }
            "/pair/revoke" -> { pairing.revoke(session.headers["x-pair-token"]); JSONObject().put("state", "revoked") }
            "/pair/cancel" -> { pairing.cancel(id, secret); JSONObject().put("state", "cancelled") }
            else -> return response(Response.Status.NOT_FOUND, "Not found")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString()).apply {
            addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun browserName(agent: String): String {
        val browser = when {
            "Edg/" in agent -> "Edge"
            "Chrome/" in agent -> "Chrome"
            "Firefox/" in agent -> "Firefox"
            "Safari/" in agent -> "Safari"
            else -> "浏览器"
        }
        val platform = when {
            "Android" in agent -> "Android"
            "iPhone" in agent || "iPad" in agent -> "iOS"
            "Windows" in agent -> "Windows"
            "Macintosh" in agent -> "macOS"
            "Linux" in agent -> "Linux"
            else -> null
        }
        return if (platform == null) browser else "$browser · $platform"
    }

    private fun response(status: Response.Status, text: String) =
        newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text).apply { addHeader("Cache-Control", "no-store") }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    override fun stop() {
        viewer?.disconnect("服务已停止")
        if (!secure) pairing.clear()
        timer.shutdownNow()
        super.stop()
    }

    private inner class Client(handshake: IHTTPSession) : WebSocket(handshake) {
        private val token = handshake.parameters["token"]?.firstOrNull()?.takeIf { it.isNotEmpty() }
        fun authorized() = token == null || pairing.authorized(token)
        @Volatile var lastSeen = SystemClock.elapsedRealtime()
        fun frameStalled(now: Long) = sender.frameStalled(now)
        fun frameAvailable() = sender.frameAvailable()
        private val ended = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)
        private val sender = WebStreamSender(
            latestFrame = { if (viewer === this && !closing.get() && WebSession.videoSource?.jpegNeeded != false) WebSession.frame else null },
            write = { super.sendFrame(it) },
            onFailure = { disconnect("串流连接中断") },
        )
        private val rtcLock = Any()
        private var rtcSource: WebVideoSource? = null
        private var rtcConfig: WebVideoSource.Config? = null
        private var rtc: RtcVideoStream? = null
        private var rtcAttempted = false
        private var rtcId = 0L
        private var rtcAnswered = false

        private fun reconcileVideo() = synchronized(rtcLock) {
            val source = WebSession.videoSource
            if (rtcSource !== source || rtcConfig !== source?.config) {
                stopRtc()
                rtcSource = source; rtcConfig = source?.config; rtcAttempted = false
            }
        }

        private fun stopRtc() = synchronized(rtcLock) {
            val old = rtc
            rtc = null
            if (old != null) {
                rtcSource?.detach(old); old.close()
                reply(WebStreamSender.Control.RTC, JSONObject().put("type", "rtc-stop").put("id", rtcId).toString())
            }
            sender.frameAvailable()
        }

        private fun startRtc() = synchronized(rtcLock) {
            reconcileVideo()
            if (rtcAttempted || ended.get() || !WebSession.videoActive) return
            val source = rtcSource ?: return
            val config = rtcConfig ?: return
            rtcAttempted = true; rtcAnswered = false
            val id = ++rtcId
            val stream = RtcVideoStream(source, config, offer = { sdp ->
                synchronized(rtcLock) {
                    if (!ended.get() && rtc != null && rtcId == id) reply(WebStreamSender.Control.RTC,
                        JSONObject().put("type", "rtc-offer").put("id", id).put("codec", config.codec.name).put("sdp", sdp).toString())
                }
            }, failure = { synchronized(rtcLock) { if (rtcId == id) stopRtc() } })
            rtc = stream
            if (!source.attach(stream, config)) { stopRtc(); return }
            stream.start()
        }

        private fun reply(kind: WebStreamSender.Control, text: String, key: Any = kind, afterSend: () -> Unit = {}) {
            sender.control(kind, WebSocketFrame(WebSocketFrame.OpCode.Text, true, text), key, afterSend)
        }

        // NanoWSD also replies to RFC6455 Ping/Close on its reader thread. Route those writes too.
        override fun sendFrame(frame: WebSocketFrame) {
            when (frame.opCode) {
                WebSocketFrame.OpCode.Pong -> sender.control(WebStreamSender.Control.PONG, frame)
                WebSocketFrame.OpCode.Close -> sender.control(WebStreamSender.Control.CLOSE, frame) {
                    disconnect("连接已关闭")
                }
                else -> error("Application frames must use the stream sender")
            }
        }

        override fun close(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            // Keep the reader alive until the queued close reply is written; NanoWSD's default
            // close changes state immediately and would close the socket before this async write.
            if (closing.compareAndSet(false, true)) sendFrame(WebSocketFrame.CloseFrame(code, reason))
        }

        override fun onOpen() {
            if (!authorized()) { disconnect("配对已过期"); return }
            synchronized(this@LanWebServer) {
                if (viewer != null || !WebSession.touch.claim(this)) {
                    reply(WebStreamSender.Control.BUSY,
                        """{"type":"busy","message":"已有车机连接，请先在另一台车机断开"}""") {
                        disconnect("已有车机连接")
                    }
                    return
                }
                viewer = this
                WebSession.audio.attach(this, secure, { packet, sequence -> sender.audio(packet, sequence) }, { text ->
                    val data = JSONObject(text)
                    val kind = when (data.getString("type")) {
                        "mic-config" -> WebStreamSender.Control.MIC_CONFIG
                        "mic-stop" -> WebStreamSender.Control.MIC_STOP
                        "audio-route" -> {
                            if (!data.getBoolean("playback")) sender.clearAudio()
                            WebStreamSender.Control.AUDIO_ROUTE
                        }
                        "audio-stop" -> {
                            sender.clearAudioStream(data.getInt("stream"))
                            WebStreamSender.Control.AUDIO_STOP
                        }
                        else -> { sender.clearAudio(); WebStreamSender.Control.AUDIO_RESET }
                    }
                    reply(kind, text, if (kind == WebStreamSender.Control.AUDIO_STOP) kind to data.getInt("stream") else kind)
                })
            }
            sender.frameAvailable()
        }

        override fun onMessage(message: WebSocketFrame) {
            if (ended.get() || closing.get() || viewer !== this) return
            if (!authorized()) { disconnect("设备已移除或配对已过期"); return }
            try {
                if (message.opCode == WebSocketFrame.OpCode.Binary) {
                    require(message.binaryPayload.size <= 20_000)
                    lastSeen = SystemClock.elapsedRealtime()
                    WebSession.audio.input(this, message.binaryPayload)
                    return
                }
                require(message.opCode == WebSocketFrame.OpCode.Text && message.binaryPayload.size <= 32768)
                val data = JSONObject(message.textPayload)
                if (data.optString("type") != "rtc-answer") require(message.binaryPayload.size <= 2048)
                lastSeen = SystemClock.elapsedRealtime()
                when (data.getString("type")) {
                    "audio-ready" -> {
                        val playback = data.getBoolean("playback")
                        val microphone = data.getBoolean("microphone")
                        if (!playback) sender.clearAudio()
                        WebSession.audio.ready(this, playback, microphone)
                    }
                    "audio-ack" -> {
                        val sequence = data.getLong("sequence")
                        require(sequence in 0..0xffff_ffffL)
                        sender.acknowledgeAudio(sequence)
                    }
                    "phone-connect" -> WebSession.requestPhoneConnection { result ->
                        if (viewer === this && !ended.get() && !closing.get()) {
                            reply(WebStreamSender.Control.PHONE_CONNECT,
                                JSONObject().put("type", "phone-connect-result").put("message", result).toString())
                        }
                    }
                    "ack" -> sender.acknowledge()
                    "viewport" -> {
                        val width = data.getInt("width")
                        val height = data.getInt("height")
                        require(WebSession.reportBrowserViewport(width, height))
                    }
                    "ping" -> {
                        reconcileVideo()
                        val frame = WebSession.frame
                        synchronized(rtcLock) { reply(WebStreamSender.Control.STATUS,
                            JSONObject().put("type", "status").put("width", WebSession.width)
                            .put("height", WebSession.height).put("stage", WebSession.stage)
                            .put("streaming", WebSession.videoActive && (frame != null || rtc?.connected == true))
                            .put("rtcAvailable", rtcConfig != null && !rtcAttempted && WebSession.videoActive)
                            .put("rtcFrames", rtc?.sentFrames ?: 0)
                            .put("performance", JSONObject().put("source", rtcSource?.performanceSnapshot()?.let(::JSONObject))
                                .put("transport", rtc?.snapshot()?.let(::JSONObject)))
                            .put("transport", if (rtcSource?.jpegNeeded == false) "webrtc" else "jpeg")
                            .put("fallback", rtcSource?.technology == StreamTechnology.WEBRTC && (rtcAttempted && rtc == null || rtcConfig == null))
                            .toString()) }
                    }
                    "rtc-start" -> startRtc()
                    "rtc-answer" -> synchronized(rtcLock) {
                        if (data.getLong("id") == rtcId && rtc != null && !rtcAnswered) {
                            rtcAnswered = true
                            rtc?.answer(data.getString("sdp"))
                        }
                    }
                    "rtc-feedback" -> synchronized(rtcLock) {
                        if (data.getLong("id") == rtcId && rtc != null) {
                            fun counter(name: String): Long = data.getLong(name).also { require(it in 0..9_007_199_254_740_991L) }
                            val rtt = if (data.isNull("rttMs")) null else data.getDouble("rttMs").also { require(it.isFinite() && it in 0.0..60_000.0) }
                            rtc?.feedback(RtcReceiverHealth.Report(counter("packets"), counter("lost"), counter("decoded"), counter("nack"), rtt))
                        }
                    }
                    "rtc-ready" -> synchronized(rtcLock) {
                        if (data.getLong("id") == rtcId) { rtc?.displayed(); sender.acknowledge() }
                    }
                    "rtc-fallback" -> synchronized(rtcLock) {
                        if (data.getLong("id") == rtcId) stopRtc()
                    }
                    "touch" -> {
                        val contacts = data.getJSONArray("contacts")
                        require(contacts.length() <= 2)
                        val list = (0 until contacts.length()).map {
                            val point = contacts.getJSONObject(it)
                            AirPlayContact(point.getInt("id"), point.getDouble("x"), point.getDouble("y"), point.getBoolean("down"))
                        }
                        if (!WebSession.touch.touch(this, list, lastSeen)) {
                            reply(WebStreamSender.Control.INPUT_UNAVAILABLE, """{"type":"input-unavailable"}""")
                        }
                    }
                    else -> error("Unknown message")
                }
            } catch (_: Exception) { disconnect("无效的控制消息") }
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) = cleanup()
        override fun onException(exception: IOException) = cleanup()
        override fun onPong(pong: WebSocketFrame) { lastSeen = SystemClock.elapsedRealtime() }

        fun disconnect(reason: String) {
            cleanup()
            // Force close: no blocking close handshake on the watchdog/service thread.
            runCatching { handshakeRequest.inputStream.close() }
        }

        private fun cleanup() {
            if (!ended.compareAndSet(false, true)) return
            stopRtc()
            synchronized(this@LanWebServer) {
                if (viewer === this) { WebSession.audio.detach(this); viewer = null }
                WebSession.touch.drop(this)
            }
            sender.close()
        }
    }
}
