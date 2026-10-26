package moe.feng.wheelplay.projection.desktop

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.shared.R
import com.shilapi.xcertplay.projection.*
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ShizukuDesktopBackend(private val context: Context) : DesktopBackend {
    override fun probe(): SourceAvailability {
        val reason = when {
            Build.VERSION.SDK_INT < 29 -> R.string.desktop_unsupported
            !Shizuku.pingBinder() -> if (runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess)
                R.string.desktop_not_running else R.string.desktop_not_installed
            else -> try {
                when {
                    Shizuku.getVersion() < 13 -> R.string.desktop_unsupported
                    // Shizuku.checkSelfPermission() caches a granted result. Query the pinned
                    // v13 Binder contract so external revocation is observed during a session.
                    IShizukuService.Stub.asInterface(Shizuku.getBinder())?.checkSelfPermission() != true -> R.string.desktop_not_authorized
                    Shizuku.getUid() !in setOf(0, 2000) -> R.string.desktop_unsupported
                    else -> return SourceAvailability.Ready
                }
            } catch (_: Exception) { R.string.desktop_not_running }
        }
        val message = context.getString(reason)
        return SourceAvailability.Unavailable(message, ProjectionFailure(
            if (reason == R.string.desktop_not_authorized) ProjectionErrorCategory.PERMISSION else ProjectionErrorCategory.UNAVAILABLE,
            message, "shizuku"))
    }

    override fun connect() = connect(ProjectionStartup())

    override fun connect(startup: ProjectionStartup): CompletableFuture<DesktopConnection> {
        startup.checkActive()
        val result = CompletableFuture<DesktopConnection>()
        val availability = probe()
        if (availability is SourceAvailability.Unavailable) {
            result.completeExceptionally(ProjectionException(availability.failure)); return result
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuDesktopUserService::class.java))
            .tag("wheelplay-desktop-v${DesktopProtocol.VERSION}-${android.os.Process.myUid()}-${UUID.randomUUID()}")
            .version(version).daemon(false).processNameSuffix("desktop")
        val main = Handler(Looper.getMainLooper())
        lateinit var binding: ServiceConnection
        var bound = false
        var connection: Connection? = null // Binding lifecycle is serialized on main.
        val aborting = java.util.concurrent.atomic.AtomicBoolean()
        fun unbind() { Shizuku.unbindUserService(args, binding, true) }
        fun abort(failure: Throwable) {
            if (result.isDone || !aborting.compareAndSet(false, true)) return
            val acquired = connection
            CompletableFuture.runAsync {
                val cleanup = runCatching { if (acquired != null) acquired.close() else if (bound) unbind() }.exceptionOrNull()
                result.completeExceptionally(if (cleanup == null) failure else
                    ProjectionCleanupException("Desktop bind cancellation cleanup unconfirmed", cleanup).apply { addSuppressed(failure) })
            }
        }
        val timeout = Runnable { abort(DesktopException(DesktopErrorCode.BIND_TIMEOUT, "Helper bind/handshake exceeded 10 seconds")) }
        binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val acquired = runCatching { Connection(IDesktopService.Stub.asInterface(binder), ::unbind) }.getOrElse { abort(it); return }
                connection = acquired
                CompletableFuture.runAsync {
                    try {
                        // Cancellation before acquisition never sends hello/start or admits a late helper.
                        if (result.isDone || aborting.get() || startup.isCancelled) { acquired.close(); return@runAsync }
                        acquired.handshake()
                        main.post {
                            if (startup.isCancelled || aborting.get() || !result.complete(acquired)) {
                                CompletableFuture.runAsync { acquired.close() }
                            }
                        }
                    } catch (failure: Throwable) {
                        val cleanup = runCatching { acquired.close() }.exceptionOrNull()
                        main.post { abort(if (cleanup == null) failure else ProjectionCleanupException("Handshake cleanup failed", cleanup)) }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                abort(DesktopException(DesktopErrorCode.DISCONNECTED, "Helper disconnected while binding"))
            }
        }
        val cancellation = startup.onCancel { main.post { abort(ProjectionException(checkNotNull(startup.cancellation))) } }
        result.whenComplete { _, _ -> main.removeCallbacks(timeout); cancellation.close() }
        main.post {
            if (startup.isCancelled) { abort(ProjectionException(checkNotNull(startup.cancellation))); return@post }
            main.postDelayed(timeout, 10_000)
            try { bound = true; Shizuku.bindUserService(args, binding) } catch (failure: Throwable) { abort(failure) }
        }
        return result
    }

    internal class Connection(private val service: IDesktopService, private val unbind: () -> Unit,
        private val requestedFeatures: Long = DesktopFeatures.HOST_SUPPORTED,
        private val requiredFeatures: Long = DesktopFeatures.REQUIRED,
        private val sessionFeatures: Long = requestedFeatures) : DesktopConnection {
        private val token = UUID.randomUUID().toString()
        private val owner = Binder()
        private val dead = CountDownLatch(1)
        private val death = IBinder.DeathRecipient { dead.countDown() }
        private var started = false
        private var closed = false
        // Touch and Back are submitted by the session's single input worker.
        private var inputEpoch = 0
        private lateinit var info: DesktopServiceInfo
        private var actual: DesktopDisplayInfo? = null
        private var enabledFeatures = 0L
        override val uid get() = info.uid
        override lateinit var capabilities: ProjectionCapabilities
            private set
        init { service.asBinder().linkToDeath(death, 0) }
        fun handshake() {
            desktopRemoteCall {
                val reply = service.hello(DesktopProtocol.VERSION, requestedFeatures, requiredFeatures)
                reply.status.checkSuccess()
                info = reply.info ?: throw DesktopException(DesktopErrorCode.PROTOCOL_MISMATCH, "Handshake omitted service info")
                info.validate(requestedFeatures, requiredFeatures)
                capabilities = DesktopFeatures.projection(info.enabledFeatures, info.maxTouchContacts)
            }
        }
        override fun start(surface: Surface, component: String, config: DesktopDisplayConfig): DesktopDisplayInfo {
            check(!closed && !started)
            desktopCheck(info.accepts(config), DesktopErrorCode.INVALID_ARGUMENT) { "Display configuration exceeds helper limits" }
            started = true
            return desktopRemoteCall {
                val request = DesktopStartConfig().apply {
                    this.component = component; width = config.width; height = config.height; dpi = config.dpi
                    requestedFeatures = sessionFeatures; requiredFeatures = this@Connection.requiredFeatures
                }
                val reply = service.start(token, owner, surface, request)
                reply.status.checkSuccess()
                desktopCheck(reply.enabledFeatures and (info.enabledFeatures and sessionFeatures) == reply.enabledFeatures &&
                    reply.enabledFeatures and (requiredFeatures or DesktopFeatures.REQUIRED) == (requiredFeatures or DesktopFeatures.REQUIRED),
                    DesktopErrorCode.PROTOCOL_MISMATCH) { "Invalid enabled session features" }
                val actual = reply.display ?: throw DesktopException(DesktopErrorCode.PROTOCOL_MISMATCH, "Missing display info")
                actual.validate(config)
                capabilities = DesktopFeatures.projection(reply.enabledFeatures, info.maxTouchContacts)
                this.actual = actual
                enabledFeatures = reply.enabledFeatures
                actual
            }
        }
        override fun touch(contacts: List<ProjectionContact>) {
            check(!closed)
            desktopCheck(capabilities.maxTouchContacts > 0, DesktopErrorCode.UNSUPPORTED_CAPABILITY) { "Touch was not negotiated" }
            desktopCheck(contacts.size <= capabilities.maxTouchContacts, DesktopErrorCode.INVALID_ARGUMENT) { "Too many contacts" }
            desktopRemoteCall { service.touch(token, DesktopTouchFrame.from(contacts, inputEpoch)) }
        }
        override fun back() {
            check(!closed)
            desktopCheck(capabilities.backButton, DesktopErrorCode.UNSUPPORTED_CAPABILITY) { "Back was not negotiated" }
            inputEpoch = Math.incrementExact(inputEpoch)
            desktopRemoteCall { service.back(token, DesktopProtocol.LAYOUT, inputEpoch).checkSuccess() }
        }
        override fun cancelInput() {
            check(!closed)
            inputEpoch = Math.incrementExact(inputEpoch)
            desktopRemoteCall { service.cancelInput(token, DesktopProtocol.LAYOUT, inputEpoch).checkSuccess() }
        }
        private fun DesktopStateResult.checkedState(): DesktopSessionState {
            status.checkSuccess()
            val snapshot = state ?: throw DesktopException(DesktopErrorCode.PROTOCOL_MISMATCH, "Missing desktop state")
            desktopCheck(snapshot.phase in DesktopSessionState.IDLE..DesktopSessionState.FAILED,
                DesktopErrorCode.PROTOCOL_MISMATCH) { "Unknown desktop phase ${snapshot.phase}" }
            if (snapshot.phase == DesktopSessionState.RUNNING) {
                desktopCheck(snapshot.enabledFeatures == enabledFeatures && snapshot.inputEpoch == inputEpoch,
                    DesktopErrorCode.PROTOCOL_MISMATCH) { "Unexpected active session capabilities/epoch" }
                val expected = actual ?: throw DesktopException(DesktopErrorCode.INVALID_STATE, "Display has not started")
                desktopCheck(snapshot.display == expected, DesktopErrorCode.LAYOUT_CHANGED) { "Actual layout changed" }
            }
            return snapshot
        }
        override fun healthy(): DesktopSessionState = desktopRemoteCall {
            check(!closed)
            service.healthy(token, DesktopProtocol.LAYOUT).checkedState().also {
                desktopCheck(it.phase == DesktopSessionState.RUNNING, DesktopErrorCode.INVALID_STATE) { "Helper is not running" }
                it.failure?.throwIfError()
            }
        }
        override fun state(): DesktopSessionState = desktopRemoteCall { service.getState(token).checkedState() }

        @Synchronized override fun close() {
            if (closed) return
            try {
                if (service.asBinder().isBinderAlive) {
                    try { if (started) service.stop(token).checkSuccess() } finally { runCatching { service.destroy() } }
                }
            } catch (_: Exception) {
                // A revoked Shizuku grant still permits the owning app to destroy its own helper Binder.
                runCatching { service.destroy() }
            } finally { unbind() }
            if (service.asBinder().isBinderAlive && !dead.await(5, TimeUnit.SECONDS))
                throw ProjectionCleanupException("Virtual desktop helper did not exit",
                    DesktopException(DesktopErrorCode.CLEANUP_FAILED, "Helper Binder remained alive after destroy"))
            closed = true
            runCatching { service.asBinder().unlinkToDeath(death, 0) }
        }
    }

    companion object { const val PACKAGE = "moe.shizuku.privileged.api" }
}
