package moe.feng.wheelplay.plugin

import android.app.Service
import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional service base. Hooks run serially off the main/Binder threads and must return promptly.
 * onStopProjection completes only after SDK shutdown, producers and owned services have stopped.
 * A failed cleanup keeps this service quarantined until its process is restarted.
 */
abstract class ProjectionPluginService : Service() {
    protected abstract fun videoInfo(): PluginVideoInfo
    protected abstract fun onStartProjection(surface: Surface, events: Events)
    protected abstract fun onStopProjection(): CompletableFuture<Unit>
    /** Requires a private process dedicated to this source and its SDK services. */
    protected open val terminateProcessAfterStop: Boolean = false
    protected open fun projectionProcessName(): String = Application.getProcessName()
    protected open fun exitProjectionProcess() { android.os.Process.killProcess(android.os.Process.myPid()) }
    protected open fun onTouch(contacts: List<PluginContact>) = Unit
    protected open fun onNightMode(night: Boolean) = Unit

    /** Override to apply additional host signing-certificate policy if required by your SDK. */
    protected open fun isHostAllowed(uid: Int): Boolean = packageManager.getPackagesForUid(uid)?.any {
        it == "moe.feng.wheelplay" || it == "moe.feng.wheelplay.debug"
    } == true

    interface Events {
        fun state(state: Int, message: String = "")
        /** Request teardown on remote disconnect or an unrecoverable SDK failure. */
        fun ended(message: String = "")
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    @Volatile private var client: Client? = null
    private var destroyed = false
    @Volatile private var unbound = false
    private var exitArmed = false
    private val exitRequested = AtomicBoolean()
    private val bindingGeneration = AtomicLong()
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val exitFallback = Runnable { exitIfArmed() }
    private val abandonedProcess = Runnable {
        // Runs independently of a possibly blocked SDK hook. No clean acknowledgement is synthesized.
        if (unbound && client?.info?.processExitRequired == true && exitRequested.compareAndSet(false, true)) {
            exitProjectionProcess()
        }
    }
    private fun exitIfArmed() {
        if (!exitArmed || !exitRequested.compareAndSet(false, true)) return
        handler.removeCallbacks(exitFallback)
        lifecycleHandler.removeCallbacks(abandonedProcess)
        exitProjectionProcess()
    }
    private val queuedCommands = AtomicInteger()
    private val overflowReported = AtomicBoolean()
    private inner class Client(val token: String, val uid: Int, val host: IProjectionHost, val info: PluginVideoInfo, val generation: Long) {
        var surface: Surface? = null
        var started = false
        var stopping = false
        var ready = false
        var cleanupComplete = false
        val death = IBinder.DeathRecipient { handler.post { finish(this, "Host disconnected", notifyHost = false) } }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("projection-plugin").apply { start() }
        handler = Handler(thread.looper)
    }

    private fun dispatch(token: String?, action: (Client) -> Unit) {
        val uid = Binder.getCallingUid()
        if (queuedCommands.incrementAndGet() > 64) {
            queuedCommands.decrementAndGet()
            if (overflowReported.compareAndSet(false, true)) handler.post {
                client?.takeIf { it.uid == uid && it.token == token }?.let { finish(it, "Plugin command queue overflow") }
                overflowReported.set(false)
            }
            return
        }
        handler.post {
            try {
                val current = client
                if (current != null && current.uid == uid && current.token == token && !current.stopping) {
                    try { action(current) } catch (failure: Exception) { finish(current, failure.message ?: "Plugin failed") }
                }
            } finally { queuedCommands.decrementAndGet() }
        }
    }

    private val binder = object : IProjectionPlugin.Stub() {
        override fun connect(token: String, apiVersion: Int, host: IProjectionHost) {
            val uid = Binder.getCallingUid()
            val generation = bindingGeneration.get()
            if (token.length !in 1..128 || !isHostAllowed(uid)) return
            handler.post {
                if (destroyed || client != null || apiVersion != ProjectionPlugin.API_VERSION) {
                    runCatching { host.onStopped(token, true, "Plugin busy or incompatible API") }
                    return@post
                }
                try {
                    check(!terminateProcessAfterStop ||
                        (projectionProcessName().startsWith("$packageName:") && projectionProcessName() != applicationInfo.processName)) {
                        "Process shutdown requires a dedicated private service process"
                    }
                    val next = Client(token, uid, host, videoInfo().copy(processExitRequired = terminateProcessAfterStop), generation)
                    client = next
                    host.asBinder().linkToDeath(next.death, 0)
                    host.onInfo(token, next.info.toBundle())
                } catch (failure: Throwable) {
                    val current = client
                    if (current != null) finish(current, failure.message ?: "Cannot connect plugin")
                    else runCatching { host.onStopped(token, true, failure.message ?: "Cannot connect plugin") }
                }
            }
        }
        override fun start(token: String, surface: Surface) {
            // The received Surface is a separately owned handle, even when the call is rejected.
            val uid = Binder.getCallingUid()
            handler.post {
                val current = client
                if (current == null || current.uid != uid || current.token != token || current.stopping || current.started) {
                    surface.release(); return@post
                }
                current.surface = surface
                current.started = true
                try {
                    onStartProjection(surface, object : Events {
                        override fun state(state: Int, message: String) {
                            handler.post {
                                if (client !== current || current.stopping) return@post
                                if (state == ProjectionPlugin.ERROR) { finish(current, message); return@post }
                                if (state !in ProjectionPlugin.CONNECTING..ProjectionPlugin.READY) return@post
                                current.ready = state == ProjectionPlugin.READY
                                runCatching { current.host.onState(current.token, state, message.take(512)) }
                                    .onFailure { finish(current, "Host disconnected") }
                            }
                        }
                        override fun ended(message: String) { handler.post { finish(current, message) } }
                    })
                } catch (failure: Throwable) { finish(current, failure.message ?: "Plugin startup failed") }
            }
        }
        override fun touch(token: String, ids: IntArray, xs: DoubleArray, ys: DoubleArray, down: BooleanArray) {
            if (ids.size > 10 || xs.size != ids.size || ys.size != ids.size || down.size != ids.size) return
            val contacts = ids.indices.map { PluginContact(ids[it], xs[it], ys[it], down[it]) }
            dispatch(token) {
                if (it.started && (it.ready || contacts.isEmpty()) && validPluginContacts(contacts, it.info.maxTouchContacts)) onTouch(contacts)
            }
        }
        override fun setNightMode(token: String, night: Boolean) = dispatch(token) {
            if (it.started && it.info.nightMode) onNightMode(night)
        }
        override fun stop(token: String) {
            val uid = Binder.getCallingUid()
            // Do not drop stop when the input queue is full; preserve connect/start ordering.
            handler.post {
                client?.takeIf { it.uid == uid && it.token == token }?.let { finish(it, "", notifyHost = false) }
            }
        }
    }

    private fun finish(current: Client, message: String, notifyHost: Boolean = true) {
        if (client !== current || current.stopping) return
        current.stopping = true
        current.ready = false
        // Inform the host before invoking SDK cleanup, which may block or never complete.
        // ERROR already means "stop this session" in API v2; onStopped remains the cleanup barrier.
        if (notifyHost) runCatching {
            current.host.onState(current.token, ProjectionPlugin.ERROR, message.take(512))
        }
        val stopped = try {
            if (current.started) onStopProjection() else CompletableFuture.completedFuture(Unit)
        } catch (failure: Throwable) { CompletableFuture<Unit>().also { it.completeExceptionally(failure) } }
        stopped.whenComplete { _, failure -> handler.post {
            val result = runCatching {
                // In process mode native handles disappear with the process; keep this handle valid until then.
                if (failure == null && !current.info.processExitRequired) current.surface?.release()
            }.exceptionOrNull() ?: failure
            current.cleanupComplete = true
            if (result == null) {
                if (!current.info.processExitRequired) { current.surface = null; client = null }
            }
            runCatching { current.host.asBinder().unlinkToDeath(current.death, 0) }
            runCatching { current.host.onStopped(current.token, result == null,
                (result?.message ?: message).take(512)) }
            if (current.info.processExitRequired) {
                exitArmed = true
                // Host unbinding proves it consumed the acknowledgement before Binder death.
                if (unbound || destroyed) exitIfArmed()
                else handler.postDelayed(exitFallback, 2_000)
            } else if (destroyed) thread.quitSafely()
        } }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == ProjectionPlugin.ACTION) {
        bindingGeneration.incrementAndGet()
        unbound = false
        lifecycleHandler.removeCallbacks(abandonedProcess)
        binder
    } else null
    override fun onUnbind(intent: Intent): Boolean {
        val generation = bindingGeneration.get()
        unbound = true
        lifecycleHandler.removeCallbacks(abandonedProcess)
        if (terminateProcessAfterStop) lifecycleHandler.postDelayed(abandonedProcess, 8_000)
        handler.post {
            client?.takeIf { it.generation <= generation }?.let { finish(it, "Host unbound", notifyHost = false) }
            exitIfArmed()
        }
        return false
    }
    override fun onDestroy() {
        handler.post {
            destroyed = true
            val current = client
            if (exitArmed) exitIfArmed()
            if (current == null || current.cleanupComplete) thread.quitSafely() else finish(current, "Plugin service destroyed", notifyHost = false)
        }
        super.onDestroy()
    }
}
