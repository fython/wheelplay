package com.shilapi.xcertplay.projection.androidauto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayMicrophoneSource
import com.shilapi.xcertplay.androidauto.*
import com.shilapi.xcertplay.projection.*
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.shared.R as UiR

internal enum class AndroidAutoMode { LOCAL, USB, TCP }

internal class AndroidAutoSource(private val context: Context, private val mode: AndroidAutoMode,
    private val outputFactory: () -> ProjectionVideoOutput) : ProjectionSourceProvider {
    override val descriptor = when (mode) {
        AndroidAutoMode.LOCAL -> SourceDescriptor(LocalAndroidAuto.SOURCE_ID, context.getString(UiR.string.aa_local_title))
        AndroidAutoMode.USB -> SourceDescriptor(ProjectionSourceIds.ANDROID_AUTO_USB, "Android Auto · USB")
        AndroidAutoMode.TCP -> SourceDescriptor(ProjectionSourceIds.ANDROID_AUTO_TCP, "Android Auto · Head Unit Server (TCP)")
    }
    override val resources = buildSet {
        add(ProjectionResource.DISPLAY); add(ProjectionResource.MICROPHONE)
        if (mode == AndroidAutoMode.USB) add(ProjectionResource.USB)
        if (mode == AndroidAutoMode.TCP) add(ProjectionResource.WIRELESS)
    }
    override fun probe(): SourceAvailability {
        if (mode == AndroidAutoMode.LOCAL && !LocalAndroidAuto.isInstalledAndEnabled(context))
            return SourceAvailability.Unavailable(context.getString(UiR.string.aa_local_unavailable))
        if (mode == AndroidAutoMode.USB && AapUsbConnection.candidates(context).size != 1) return SourceAvailability.Unavailable("Connect exactly one Android phone by USB")
        if (mode == AndroidAutoMode.TCP && context.getSharedPreferences("projection", 0).getString("aa_host", "").isNullOrBlank())
            return SourceAvailability.Unavailable("Configure the phone's Head Unit Server address first")
        return SourceAvailability.Ready
    }
    internal fun tcpEndpoint(): Pair<String, Int> {
        check(mode != AndroidAutoMode.USB)
        if (mode == AndroidAutoMode.LOCAL) return "127.0.0.1" to 5277
        val prefs = context.getSharedPreferences("projection", 0)
        return prefs.getString("aa_host", "")!! to prefs.getInt("aa_port", 5277)
    }
    override fun open(): CompletableFuture<ProjectionSession> = try {
        val connection = if (mode == AndroidAutoMode.USB) AapUsbConnection(context, AapUsbConnection.candidates(context).single()) else {
            val (host, port) = tcpEndpoint()
            AapTcpConnection(host, port)
        }
        val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            AirPlayPersistence.loadMicrophoneSource(context) == CarPlayMicrophoneSource.ANDROID
        val session = AndroidAutoSession(context, descriptor, connection, microphone, outputFactory)
        // The runtime admits this session immediately, so Stop can interrupt connect/USB permission/handshake.
        CompletableFuture.completedFuture(session)
    } catch (failure: Throwable) { CompletableFuture<ProjectionSession>().also { it.completeExceptionally(failure) } }
}

internal class AndroidAutoSession(private val context: Context, override val descriptor: SourceDescriptor,
    private val connection: AapConnection, private val micEnabled: Boolean,
    private val outputFactory: () -> ProjectionVideoOutput) : ProjectionSession {
    override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.ENCODED), 10, nightMode = true,
        audioPlayback = true, microphone = micEnabled, browserAudio = false, browserMicrophone = false, cancellation = ProjectionCancellation.NATIVE)
    @Volatile override var statusText = "Android Auto: connecting"
        private set
    @Volatile private var ready = false
    @Volatile private var lifecycleFailure: ProjectionFailure? = null
    override val state get() = ProjectionSessionState(
        when { finished.isDone -> if (lifecycleFailure == null) ProjectionPhase.STOPPED else ProjectionPhase.FAILED
            stopping.get() -> ProjectionPhase.STOPPING
            inputBound -> ProjectionPhase.RUNNING
            else -> ProjectionPhase.CONNECTING }, capabilities, inputReady, lifecycleFailure)
    @Volatile private var inputBound = false
    private var videoFocusMode = 1
    override val inputReady get() = ready && !stopping.get()
    private val stopping = AtomicBoolean()
    private val finished = CompletableFuture<Unit>()
    private val outbound = ArrayBlockingQueue<Triple<Int, Int, ByteArray>>(256)
    private val frames = ArrayBlockingQueue<AapMediaAssembler.AccessUnit>(8)
    private val touches = AapTouch(AapServices.WIDTH, AapServices.HEIGHT)
    private val sessions = HashMap<Int, Long>()
    private val opened = HashSet<Int>()
    private val assemblers = HashMap<Int, AapMediaAssembler>()
    private var audio: AapAudio? = null
    private var output: ProjectionVideoOutput? = null
    private var writer: Thread? = null
    private var renderer: Thread? = null
    private var reader: Thread? = null
    private val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    @Volatile private var waitForIdr = true
    @Volatile private var night = false
    @Volatile private var nightSubscribed = false
    private val videoRecovery = AapVideoRecovery(android.os.SystemClock::elapsedRealtime) { mode ->
        if (mode == 2) { frames.clear(); waitForIdr = true }
        send(2, 0x8008, AapProto.build { number(1, mode.toLong()); number(2, if (mode == 1) 1 else 0) })
    }
    private val micCredits = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var micWindow = 0
    override fun activate() = start()
    @Synchronized fun start() {
        if (reader != null || stopping.get()) { if (reader == null) finished.complete(Unit); return }
        reader = Thread({ run() }, "aa-session").apply { start() }
    }
    private fun state(value: String) { statusText = value; projectionSessions.changed(this) }
    private fun run() {
        var failure: Throwable? = null
        var connected = false
        try {
            connection.connect()
            connected = true
            state("Android Auto: negotiating")
            val input = DataInputStream(connection.input)
            val tls = AapTls()
            // Overall handshake deadline also interrupts a peer that slowly drips bytes.
            val timer = Executors.newSingleThreadScheduledExecutor()
            val timeout = timer.schedule({ connection.close() }, 20, TimeUnit.SECONDS)
            try { tls.handshake(input, connection.output) } finally { timeout.cancel(false); timer.shutdownNow() }
            check(!stopping.get())
            output = outputFactory()
            val video = AapVideo(output!!) { requestKeyframe() }
            audio = AapAudio(context, { bytes ->
                if (!microphoneAllowed()) false
                else if (micCredits.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0) true
                else send(7, 0, ByteBuffer.allocate(8 + bytes.size).putLong(android.os.SystemClock.elapsedRealtimeNanos() / 1000).put(bytes).array())
            }, { focus -> send(0, 19, AapProto.build { number(1, focus.toLong()); number(2, 1) }) }, { error ->
                workerFailure.compareAndSet(null, error); connection.close()
            }, ::microphoneAllowed)
            writer = Thread({
                try {
                    while (!stopping.get()) {
                        videoRecovery.tick()
                        val (channel, type, body) = outbound.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (channel == 7 && type == 0 && !microphoneAllowed()) continue
                        val encrypted = tls.encrypt(AapFraming.message(type, body))
                        AapFraming.write(connection.output, channel, if (channel != 0 && type in 2..31) 15 else 11, encrypted)
                        if (channel == 0 && type == 16) { stopping.set(true); connection.close() }
                    }
                } catch (error: Throwable) { if (!stopping.get()) { workerFailure.compareAndSet(null, error); connection.close() } }
            }, "aa-send").apply { start() }
            renderer = Thread({
                try {
                    while (!stopping.get()) {
                        val frame = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        val types = AapVideo.annexB(frame.data).map { it[0].toInt() and 31 }
                        if (waitForIdr && 5 !in types && 7 !in types && 8 !in types) continue
                        if (5 in types) waitForIdr = false
                        if (video.accept(frame) && 5 in types) videoRecovery.recovered()
                    }
                } catch (error: Throwable) { if (!stopping.get()) { workerFailure.compareAndSet(null, error); connection.close() } }
            }, "aa-video").apply { start() }
            state("Android Auto: waiting for phone projection")
            while (!stopping.get()) {
                val packet = AapFraming.read(input)
                require(packet.flags and 8 != 0) { "Expected encrypted AAP packet" }
                val payload = tls.decrypt(packet.payload)
                if (packet.channel in setOf(2, 4, 5, 6) && (packet.flags and 3 in 0..2 || AapFraming.type(payload) in 0..1)) {
                    val channel = packet.channel
                    require(channel in opened && sessions.containsKey(channel))
                    val unit = assemblers.getOrPut(channel) { AapMediaAssembler() }.accept(packet.flags, payload)
                    // ACK per transport fragment, independently of browser speed and decoder queue.
                    send(channel, 0x8004, AapProto.build { number(1, sessions[channel]!!); number(2, 1) })
                    if (unit != null) {
                        if (channel == 2) {
                            if (!frames.offer(unit)) { frames.clear(); waitForIdr = true; requestKeyframe() }
                        } else {
                            require(unit.data.size % (if (channel == 6) 4 else 2) == 0)
                            audio?.offer(channel, unit.data)
                        }
                    }
                    continue
                }
                require(packet.flags and 3 == 3) { "Fragmented control message is unsupported" }
                dispatch(packet.channel, AapFraming.type(payload), payload.copyOfRange(2, payload.size))
            }
        } catch (caught: Throwable) {
            val error = workerFailure.get() ?: caught
            if (!stopping.get()) {
                failure = error
                lifecycleFailure = projectionFailure(error)
                if (!connected && descriptor.id == LocalAndroidAuto.SOURCE_ID) {
                    context.getSharedPreferences("projection", 0).edit().remove(LocalAndroidAuto.SETUP_SEEN).apply()
                    state(context.getString(UiR.string.aa_local_server_unavailable))
                } else state("Android Auto: ${error.message ?: error.javaClass.simpleName}")
                Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, statusText, android.widget.Toast.LENGTH_LONG).show() }
            }
        } finally {
            ready = false; stopping.set(true); videoRecovery.close()
            val cleanup = runCatching {
                connection.close()
                writer?.join(5000); renderer?.join(5000)
                check(writer?.isAlive != true && renderer?.isAlive != true) { "AA workers did not stop" }
                audio?.close(); output?.close()
                outbound.clear(); frames.clear()
            }
            if (cleanup.isFailure) {
                lifecycleFailure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, cleanup.exceptionOrNull().toString(), "android-auto")
                finished.completeExceptionally(ProjectionCleanupException("Android Auto cleanup failed", cleanup.exceptionOrNull()))
                projectionSessions.changed(this)
            } else {
                finished.complete(Unit)
                projectionSessions.ended(this)
            }
            failure?.let { android.util.Log.w("WheelPlay-AA", "Protocol session ended", it) }
        }
    }
    private fun dispatch(channel: Int, type: Int, body: ByteArray) {
        when {
            type == 7 -> {
                val id = AapProto.number(body, 2).toInt()
                val supported = channel == id && (id in AapServices.channels || id == 7 && micEnabled)
                if (supported) opened += id
                send(channel, 8, AapProto.build { number(1, if (supported) 0 else -4) })
            }
            channel == 0 -> when (type) {
                5 -> send(0, 6, AapServices.discovery(micEnabled))
                11 -> send(0, 12, body)
                13 -> send(0, 14, AapProto.build { number(1, 1) })
                15 -> { ready = false; send(0, 16, byteArrayOf()) }
                18 -> send(0, 19, AapProto.build { number(1, audio!!.requestFocus(AapProto.number(body, 1).toInt()).toLong()); number(2, 0) })
            }
            channel !in opened -> error("AAP message on unopened channel")
            channel == 3 && type == 0x8002 -> {
                send(3, 0x8003, AapProto.build { number(1, 0) }); inputBound = true; ready = videoFocusMode == 1; state("Android Auto connected · audio on host")
            }
            channel == 1 && type == 0x8001 -> {
                val supported = AapProto.number(body, 1) == 10L
                send(1, 0x8002, AapProto.build { number(1, if (supported) 0 else -9) })
                if (supported) { nightSubscribed = true; send(1, 0x8003, AapServices.night(night)) }
            }
            channel in 2..7 && channel != 3 -> when (type) {
                0x8000 -> {
                    send(channel, 0x8003, AapProto.build { number(1, 2); number(2, 16); number(3, 0) })
                    if (channel == 2) requestKeyframe()
                    if (channel in 4..6) send(0, 19, AapProto.build { number(1, audio!!.requestFocus(1).toLong()); number(2, 1) })
                }
                0x8001 -> {
                    require(AapProto.number(body, 2) == 0L)
                    sessions[channel] = AapProto.number(body, 1)
                    assemblers.remove(channel)
                }
                0x8002 -> {
                    sessions.remove(channel); assemblers.remove(channel)
                    if (channel == 7) audio?.microphone(false)
                    if (channel in 4..6) audio?.stopChannel(channel)
                }
                0x8007 -> if (channel == 2) {
                    val mode = AapProto.number(body, 2).toInt()
                    require(mode in 1..4)
                    if (mode != 1) cancelInput(ProjectionCancelReason.FOCUS_LOST)
                    videoFocusMode = mode
                    videoRecovery.setProjected(mode == 1 || mode == 4)
                    ready = inputBound && mode == 1
                    send(2, 0x8008, AapProto.build { number(1, mode.toLong()); number(2, 0) })
                    if (mode == 2) stop()
                }
                0x8005 -> if (channel == 7 && micEnabled) {
                    val requested = AapProto.number(body, 1) != 0L
                    val open = requested && microphoneAllowed()
                    micWindow = AapProto.number(body, 4, 16).toInt().coerceIn(1, 32)
                    micCredits.set(if (open) micWindow else 0)
                    send(7, 0x8006, AapProto.build { number(1, if (requested && !open) 1 else 0); number(2, 0) })
                    audio?.microphone(open)
                }
                0x8004 -> if (channel == 7) {
                    val count = AapProto.number(body, 2).toInt()
                    require(count in 0..32)
                    micCredits.getAndUpdate { minOf(micWindow, it + count) }
                }

            }
        }
    }
    private fun microphoneAllowed(): Boolean = micEnabled && !stopping.get() &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        AirPlayPersistence.loadMicrophoneSource(context) == CarPlayMicrophoneSource.ANDROID

    private fun send(channel: Int, type: Int, body: ByteArray): Boolean {
        if (stopping.get()) return false
        if (outbound.offer(Triple(channel, type, body))) return true
        // Never drop a boundary event while keeping the session alive with a stuck finger.
        state("Android Auto control queue overflow"); connection.close(); return false
    }
    private fun requestKeyframe() = videoRecovery.request()
    @Synchronized override fun touch(contacts: List<ProjectionContact>): Boolean {
        if (!inputReady) return false
        return touches.update(contacts).all { send(3, 0x8001, touches.encode(it, android.os.SystemClock.elapsedRealtimeNanos())) }
    }
    @Synchronized override fun cancelInput(reason: ProjectionCancelReason): Boolean {
        if (!inputBound || stopping.get()) return false
        return touches.cancel().all { send(3, 0x8001, touches.encode(it, android.os.SystemClock.elapsedRealtimeNanos())) }
    }
    override fun setNightMode(night: Boolean): ProjectionCommandResult {
        this.night = night
        return if (nightSubscribed && send(1, 0x8003, AapServices.night(night))) ProjectionCommandResult.SENT else ProjectionCommandResult.UNAVAILABLE
    }
    @Synchronized override fun stop(): CompletableFuture<Unit> {
        cancelInput(ProjectionCancelReason.SOURCE_SWITCH)
        stopping.set(true); ready = false; videoRecovery.close(); connection.close()
        if (reader == null) finished.complete(Unit)
        return finished
    }
}
