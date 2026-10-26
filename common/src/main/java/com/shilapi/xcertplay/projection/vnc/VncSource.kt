package com.shilapi.xcertplay.projection.vnc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.vnc.RfbClient
import com.shilapi.xcertplay.vnc.RfbInput
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class VncEndpoint(val host: String, val port: Int, val readOnly: Boolean) {
    fun valid() = port in 1..65535 && address(host) != null
    companion object {
        // Numeric addresses only: no uninterruptible DNS lookup during source startup.
        fun address(host: String): InetAddress? = runCatching {
            val v4 = host.split('.').let { it.size == 4 && it.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && (p.toIntOrNull() ?: -1) in 0..255 } }
            val v6 = ':' in host && host.all { it in "0123456789abcdefABCDEF:." }
            if (!v4 && !v6) null else InetAddress.getByName(host)
        }.getOrNull()
    }
}

internal class VncSource(private val context: Context, private val output: (Int, Int) -> ProjectionVideoOutput) : ProjectionSourceProvider {
    override val descriptor = SourceDescriptor(ProjectionSourceIds.VNC_TCP, "VNC")
    override val resources = setOf(ProjectionResource.DISPLAY, ProjectionResource.WIRELESS)
    private var password = CharArray(0)
    fun endpoint(): VncEndpoint = context.getSharedPreferences("projection", 0).let {
        VncEndpoint(it.getString("vnc_host", "").orEmpty(), it.getInt("vnc_port", 5900), it.getBoolean("vnc_read_only", false))
    }
    @Synchronized fun configure(endpoint: VncEndpoint, secret: CharArray) {
        require(endpoint.valid())
        require(secret.size <= 8 && secret.all { it.code in 1..255 })
        password.fill('\u0000'); password = secret.copyOf()
        context.getSharedPreferences("projection", 0).edit().putString("vnc_host", endpoint.host)
            .putInt("vnc_port", endpoint.port).putBoolean("vnc_read_only", endpoint.readOnly).apply()
    }
    override fun probe() = if (endpoint().valid()) SourceAvailability.Ready else
        SourceAvailability.Unavailable(context.getString(com.shilapi.xcertplay.shared.R.string.vnc_invalid_address))
    @Synchronized override fun open(): CompletableFuture<ProjectionSession> {
        val secret = password; password = CharArray(0)
        return try {
            val target = endpoint(); require(target.valid())
            CompletableFuture.completedFuture(VncSession(context, target, secret, output))
        } catch (error: Throwable) {
            secret.fill('\u0000')
            CompletableFuture<ProjectionSession>().also { it.completeExceptionally(error) }
        }
    }
}

internal class VncSession(private val context: Context, private val endpoint: VncEndpoint,
    private val password: CharArray, private val outputFactory: (Int, Int) -> ProjectionVideoOutput) : ProjectionSession {
    override val descriptor = SourceDescriptor(ProjectionSourceIds.VNC_TCP, "VNC")
    override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.FRAMEBUFFER), if (endpoint.readOnly) 0 else 1,
        pointerInput = !endpoint.readOnly, keyboardInput = !endpoint.readOnly)
    @Volatile private var lifecycleFailure: ProjectionFailure? = null
    override val state get() = ProjectionSessionState(
        when { completion.isDone -> if (lifecycleFailure == null) ProjectionPhase.STOPPED else ProjectionPhase.FAILED
            stopping.get() -> ProjectionPhase.STOPPING
            ready -> ProjectionPhase.RUNNING
            else -> ProjectionPhase.CONNECTING }, capabilities, inputReady, lifecycleFailure)
    @Volatile private var ready = false
    override val inputReady get() = ready && !stopping.get() && !endpoint.readOnly
    @Volatile override var statusText = "VNC: connecting"
        private set
    private val stopping = AtomicBoolean()
    private val completion = CompletableFuture<Unit>()
    private val socket = Socket()
    private val queue = ArrayBlockingQueue<ByteArray>(256)
    private val failure = AtomicReference<Throwable?>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "vnc-deadline") }
    private var reader: Thread? = null
    @Volatile private var writer: Thread? = null
    private var input: RfbInput? = null
    private var touchDown = false
    private var touchX = 0.0
    private var touchY = 0.0

    @Synchronized override fun activate() {
        if (reader != null || stopping.get()) return
        reader = Thread(::run, "vnc-session").apply { start() }
    }
    private fun state(value: String) { statusText = value; projectionSessions.changed(this) }
    private fun send(bytes: ByteArray): Boolean {
        if (stopping.get() || socket.isClosed) return false
        if (queue.offer(bytes)) return true
        failure.compareAndSet(null, IllegalStateException("VNC input queue overflow; reconnect"))
        socket.close(); return false
    }
    private fun run() {
        var video: ProjectionVideoOutput? = null
        val deadline = timer.schedule({ socket.close() }, 20000, TimeUnit.MILLISECONDS)
        try {
            socket.connect(InetSocketAddress(checkNotNull(VncEndpoint.address(endpoint.host)), endpoint.port), 5000)
            socket.tcpNoDelay = true; socket.soTimeout = 10000
            val incoming = DataInputStream(BufferedInputStream(socket.getInputStream(), 16384))
            val outgoing = socket.getOutputStream()
            val client = RfbClient(incoming) { bytes ->
                if (writer == null) outgoing.write(bytes) else check(send(bytes)) { "VNC session stopped" }
            }
            state("VNC: authenticating")
            client.initialize(password)
            deadline.cancel(false)
            check(!stopping.get())
            fun attach() {
                ready = false
                synchronized(this) { input?.accept(ProjectionInput.Release); input = null; touchDown = false }
                video?.close(); video = null
                val fb = client.framebuffer
                video = outputFactory(fb.width, fb.height)
                synchronized(this) { input = RfbInput(fb.width, fb.height, ::send) }
            }
            attach()
            writer = Thread({
                try {
                    while (!socket.isClosed) {
                        val bytes = queue.poll(100, TimeUnit.MILLISECONDS)
                        if (bytes != null) outgoing.write(bytes)
                        if (stopping.get() && queue.isEmpty()) break
                    }
                } catch (error: Throwable) { if (!stopping.get()) failure.compareAndSet(null, error) }
                finally { socket.close() }
            }, "vnc-send").apply { start() }
            client.request(false)
            state("VNC: waiting for desktop")
            while (!stopping.get()) {
                socket.soTimeout = 0 // An unchanged desktop may legitimately remain idle indefinitely.
                val type = incoming.read()
                if (type < 0) throw EOFException("VNC server disconnected")
                socket.soTimeout = 15000
                // Also bound total message time: per-read SO_TIMEOUT alone allows a slow byte trickle.
                val payloadDeadline = timer.schedule({ socket.close() }, 15000, TimeUnit.MILLISECONDS)
                val update = try { client.message(type) } finally { payloadDeadline.cancel(false) }
                if (update == null) continue
                if (update.resized) attach()
                update.snapshot?.let {
                    check(video!!.framebuffer(it)) { "VNC video output rejected frame" }
                    if (!ready) { ready = true; state(if (endpoint.readOnly) "VNC connected · view only" else "VNC connected") }
                }
                // One outstanding update request, bounded to ~15 fps; never drop incremental rectangles.
                Thread.sleep(66)
                if (!stopping.get()) client.request(!update.resized)
            }
        } catch (caught: Throwable) {
            if (!stopping.get()) {
                val error = failure.get() ?: caught
                lifecycleFailure = projectionFailure(error)
                state("VNC: ${error.message ?: error.javaClass.simpleName}")
                val message = lifecycleFailure!!.message(context)
                Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
            }
        } finally {
            stopping.set(true); ready = false; password.fill('\u0000')
            deadline.cancel(false); timer.shutdownNow()
            val cleanup = runCatching {
                socket.close(); writer?.join(3000)
                check(writer?.isAlive != true) { "VNC writer did not stop" }
                video?.close(); queue.clear()
                synchronized(this) { input = null }
            }
            if (cleanup.isFailure) {
                lifecycleFailure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, cleanup.exceptionOrNull().toString(), "vnc")
                completion.completeExceptionally(ProjectionCleanupException("VNC cleanup failed", cleanup.exceptionOrNull()))
                projectionSessions.changed(this)
            }
            else { completion.complete(Unit); projectionSessions.ended(this) }
        }
    }
    @Synchronized override fun touch(contacts: List<ProjectionContact>): Boolean {
        if (!inputReady && contacts.isNotEmpty()) return false
        val point = contacts.singleOrNull()
        if (point != null) { touchX = point.x; touchY = point.y }
        val down = point?.down == true
        if (!touchDown && !down) return true
        touchDown = down
        return input?.accept(ProjectionInput.Pointer(touchX, touchY, if (down) 1 else 0)) ?: false
    }
    @Synchronized override fun input(event: ProjectionInput): Boolean {
        if (!inputReady && event != ProjectionInput.Release) return false
        if (event == ProjectionInput.Release) touchDown = false
        return input?.accept(event) ?: false
    }
    @Synchronized override fun cancelInput(reason: ProjectionCancelReason): Boolean {
        touchDown = false
        return input?.accept(ProjectionInput.Release) ?: false
    }
    @Synchronized override fun stop(): CompletableFuture<Unit> {
        if (stopping.get()) return completion
        input?.accept(ProjectionInput.Release)
        stopping.set(true); ready = false
        if (writer == null) socket.close()
        else runCatching { timer.schedule({ socket.close() }, 500, TimeUnit.MILLISECONDS) }
            .onFailure { socket.close() } // Natural teardown may already have shut down the timer.
        if (reader == null) { password.fill('\u0000'); timer.shutdownNow(); completion.complete(Unit) }
        return completion
    }
}
