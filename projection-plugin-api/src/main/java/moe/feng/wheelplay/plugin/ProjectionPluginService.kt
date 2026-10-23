package moe.feng.wheelplay.plugin

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional service base. Hooks run serially off the main/Binder threads and must return promptly.
 * onStopProjection completes only after SDK shutdown, producers and owned services have stopped.
 * A failed cleanup keeps this service quarantined until its process is restarted.
 */
abstract class ProjectionPluginService : Service() {
    protected abstract fun videoInfo(): PluginVideoInfo
    protected abstract fun onStartProjection(surface: Surface, events: Events)
    protected abstract fun onStopProjection(): CompletableFuture<Unit>
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
    private var client: Client? = null
    private var destroyed = false
    private val queuedCommands = AtomicInteger()
    private val overflowReported = AtomicBoolean()
    private inner class Client(val token: String, val uid: Int, val host: IProjectionHost, val info: PluginVideoInfo) {
        var surface: Surface? = null
        var started = false
        var stopping = false
        var ready = false
        var cleanupComplete = false
        val death = IBinder.DeathRecipient { handler.post { finish(this, "Host disconnected") } }
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
            if (token.length !in 1..128 || !isHostAllowed(uid)) return
            handler.post {
                if (destroyed || client != null || apiVersion != ProjectionPlugin.API_VERSION) {
                    runCatching { host.onStopped(token, true, "Plugin busy or incompatible API") }
                    return@post
                }
                try {
                    val next = Client(token, uid, host, videoInfo())
                    client = next
                    host.asBinder().linkToDeath(next.death, 0)
                    host.onInfo(token, next.info.toBundle())
                } catch (failure: Exception) {
                    client?.let { finish(it, failure.message ?: "Cannot connect plugin") }
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
                } catch (failure: Exception) { finish(current, failure.message ?: "Plugin startup failed") }
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
        override fun stop(token: String) = dispatch(token) { finish(it, "") }
    }

    private fun finish(current: Client, message: String) {
        if (client !== current || current.stopping) return
        current.stopping = true
        current.ready = false
        val stopped = try {
            if (current.started) onStopProjection() else CompletableFuture.completedFuture(Unit)
        } catch (failure: Exception) { CompletableFuture<Unit>().also { it.completeExceptionally(failure) } }
        stopped.whenComplete { _, failure -> handler.post {
            current.cleanupComplete = true
            if (failure == null) {
                current.surface?.release()
                current.surface = null
                client = null
            }
            runCatching { current.host.asBinder().unlinkToDeath(current.death, 0) }
            runCatching { current.host.onStopped(current.token, failure == null,
                (failure?.message ?: message).take(512)) }
            if (destroyed) thread.quitSafely()
        } }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == ProjectionPlugin.ACTION) binder else null
    override fun onUnbind(intent: Intent): Boolean {
        handler.post { client?.let { finish(it, "Host unbound") } }
        return false
    }
    override fun onDestroy() {
        handler.post {
            destroyed = true
            val current = client
            if (current == null || current.cleanupComplete) thread.quitSafely() else finish(current, "Plugin service destroyed")
        }
        super.onDestroy()
    }
}
