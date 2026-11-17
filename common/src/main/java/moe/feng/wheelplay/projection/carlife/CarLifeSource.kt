package moe.feng.wheelplay.projection.carlife

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.carlife.CarLifeRecovery
import moe.feng.wheelplay.carlife.VideoSize
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class CarLifeSource(private val context: Context) : ProjectionSourceProvider {
    override val descriptor = SourceDescriptor(ProjectionSourceIds.CARLIFE, "CarLife")
    override val resources get() = setOf(ProjectionResource.DISPLAY, ProjectionResource.MICROPHONE,
        if (CarLifeConnection.mode(context.getSharedPreferences("carlife_connection", Context.MODE_PRIVATE)) == "usb") ProjectionResource.USB else ProjectionResource.WIRELESS)
    override fun probe() = SourceAvailability.Ready
    override fun open(): CompletableFuture<ProjectionSession> = try {
        CompletableFuture.completedFuture(CarLifeProjectionSession(context, descriptor, CarLifeConnection.load(context)))
    } catch (error: Exception) { CompletableFuture<ProjectionSession>().also { it.completeExceptionally(error) } }
}

/** Admitted by ProjectionRuntime; all platform resources belong to this session, in the host process. */
internal class CarLifeProjectionSession(private val context: Context, override val descriptor: SourceDescriptor,
    private val connection: CarLifeConnection) : ProjectionSession {
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean()
    private val activated = AtomicBoolean()
    private val finished = CompletableFuture<Unit>()
    private val lifecycle = Any()
    @Volatile private var failure: ProjectionFailure? = null
    @Volatile private var ready = false
    @Volatile override var statusText = "正在等待浏览器画面尺寸"
        private set
    @Volatile private var receiver: CarLifeRecovery? = null
    private var audio: CarLifeAudio? = null
    private var video: CarLifeVideo? = null
    private var viewportSubscription: AutoCloseable? = null
    private var requestedSize: VideoSize? = null
    private var negotiatedSize: VideoSize? = null
    @Volatile override var pendingDisplayResize: Pair<Int, Int>? = null
        private set
    private val touch = CarLifeTouch { action, x, y, px, py -> receiver?.touch(action, x, y, px, py); Unit }
    override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.ENCODED), maxTouchContacts = 2,
        audioPlayback = true, microphone = connection.microphone)
    override val inputReady get() = ready && !stopping.get()
    override val state get() = ProjectionSessionState(when {
        failure != null -> ProjectionPhase.FAILED
        finished.isDone -> ProjectionPhase.STOPPED
        stopping.get() -> ProjectionPhase.STOPPING
        inputReady -> ProjectionPhase.RUNNING
        else -> ProjectionPhase.CONNECTING
    }, capabilities, inputReady, failure)
    override val media: CarLifeMediaSource = CarLifeMediaSource({ inputReady }, { id, key ->
        receiver?.mediaKey(key)?.also { future -> future.whenComplete { sent, error -> main.post {
            mediaResult(id, error == null && sent == true)
        } } } != null
    }, publishNativeSession = connection.remote)
    private fun mediaResult(id: Long, sent: Boolean) = media.result(id, sent)

    override fun activate() {
        if (!activated.compareAndSet(false, true)) return
        context.status("SOURCE_PREPARE transport=${if (connection.usb) "usb" else if (connection.remote) "wifi" else "local"} protocol=${connection.protocol}")
        main.post {
            if (stopping.get()) return@post
            viewportSubscription = WebSession.subscribeBrowserViewport { w, h -> main.post {
                carLifeViewport(WebSession.adaptiveBrowserSize, if (w != null && h != null) w to h else null)?.let(::viewport)
            } }
            carLifeViewport(WebSession.adaptiveBrowserSize, WebSession.currentBrowserViewport)?.let(::viewport)
        }
    }
    private fun viewport(size: Pair<Int, Int>) {
        synchronized(lifecycle) {
            if (stopping.get()) return
            val target = CarLifeDisplay.forViewport(size)
            if (requestedSize != null) {
                pendingDisplayResize = size.takeIf { target != requestedSize }; projectionSessions.changed(this); return
            }
            requestedSize = target
            try {
                val sound = CarLifeAudio(context, { !stopping.get() }, ::fail, captureAllowed = { inputReady }); audio = sound
                receiver = CarLifeRecovery(object : CarLifeRecovery.Listener {
                    override fun media(value: moe.feng.wheelplay.carlife.CarLifeMedia.Snapshot) {
                        main.post { if (!stopping.get()) media.update(value) }
                    }
                    override fun waiting(reason: String) {
                        ready = false; touch.release()
                        main.post { if (!stopping.get()) { media.reset(); statusText = reason; projectionSessions.changed(this@CarLifeProjectionSession) } }
                        context.status("SOURCE_WAITING $reason")
                    }
                    override fun log(message: String) { context.status(message) }
                    override fun configured(width: Int, height: Int) {
                        synchronized(lifecycle) {
                            if (stopping.get()) return
                            val confirmed = VideoSize(width, height)
                            check(negotiatedSize == null || negotiatedSize == confirmed) { "CarLife 视频尺寸改变，请重新连接" }
                            negotiatedSize = confirmed; touch.resize(width, height)
                            if (video == null) video = CarLifeVideo(width, height, ::fail)
                        }
                    }
                    override fun video(bytes: ByteArray, ptsUs: Long) {
                        synchronized(lifecycle) {
                            if (stopping.get()) return
                            checkNotNull(video).frame(bytes, ptsUs)
                            val first = !ready
                            ready = true; statusText = "CarLife 已连接"
                            if (first) main.post { projectionSessions.changed(this@CarLifeProjectionSession) }
                        }
                    }
                    override fun failed(message: String) = fail(message)
                    override fun ended() { if (!stopping.get()) fail("CarLife 源已断开，请重新连接") }
                }, sound, target, connection.protocol, sound.takeIf { connection.microphone },
                    moe.feng.wheelplay.carlife.CarLifeTransport.Factory {
                        if (connection.usb) carLifeUsbTransport(context) { message ->
                            main.post { if (!stopping.get()) { statusText = message; context.status(message); projectionSessions.changed(this) } }
                        } else moe.feng.wheelplay.carlife.CarLifeTcpTransport(connection.host)
                    })
                statusText = if (connection.usb) "正在连接 USB CarLife" else if (connection.remote) "正在连接 ${connection.host}" else "正在连接本机 CarLife"
                receiver!!.start()
            } catch (error: Exception) { fail(error.message ?: "CarLife 启动失败") }
        }
    }
    private fun fail(message: String) {
        if (stopping.get()) return
        failure = ProjectionFailure(ProjectionErrorCategory.CONNECTION, message, "carlife")
        statusText = message; context.status("SOURCE_FAILURE $message")
        main.post { projectionSessions.changed(this) }
        stop()
    }
    override fun touch(contacts: List<ProjectionContact>): Boolean {
        if (!inputReady) return false
        touch.accept(contacts); return true
    }
    override fun stop(): CompletableFuture<Unit> {
        if (!stopping.compareAndSet(false, true)) return finished
        val mainCleanup = CompletableFuture<Unit>()
        main.post {
            runCatching { viewportSubscription?.close(); viewportSubscription = null; media.close() }
                .onSuccess { mainCleanup.complete(Unit) }.onFailure { mainCleanup.completeExceptionally(it) }
        }
        Thread({
            val errors = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { runCatching(action).onFailure { errors += it } }
            synchronized(lifecycle) { cleanup { touch.release() } }
            cleanup { receiver?.stop()?.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            cleanup { audio?.close() }
            cleanup { video?.close() }
            cleanup { mainCleanup.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            val error = errors.firstOrNull()
            if (error == null) { finished.complete(Unit); main.post { projectionSessions.ended(this) } }
            else { errors.drop(1).forEach(error::addSuppressed); finished.completeExceptionally(ProjectionCleanupException("CarLife cleanup failed", error)) }
        }, "carlife-cleanup").start()
        return finished
    }
}

/** Fixed-size mode can connect without a browser; adaptive mode waits for a real viewport. */
internal fun carLifeViewport(adaptive: Boolean, size: Pair<Int, Int>?): Pair<Int, Int>? =
    if (adaptive) size else VideoSize.DEFAULT.let { it.width to it.height }
