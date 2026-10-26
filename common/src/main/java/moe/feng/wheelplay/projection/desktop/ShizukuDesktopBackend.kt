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
        return SourceAvailability.Unavailable(context.getString(reason))
    }

    override fun connect(): CompletableFuture<DesktopConnection> {
        val result = CompletableFuture<DesktopConnection>()
        val availability = probe()
        if (availability is SourceAvailability.Unavailable) {
            result.completeExceptionally(DesktopException(DesktopErrorCode.DISCONNECTED, availability.reason)); return result
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuDesktopUserService::class.java))
            .tag("wheelplay-desktop-v${DesktopProtocol.VERSION}-${android.os.Process.myUid()}-${UUID.randomUUID()}")
            .version(version).daemon(false).processNameSuffix("desktop")
        val main = Handler(Looper.getMainLooper())
        lateinit var binding: ServiceConnection
        fun unbind() { runCatching { Shizuku.unbindUserService(args, binding, true) } }
        val timeout = Runnable {
            if (result.completeExceptionally(DesktopException(DesktopErrorCode.BIND_TIMEOUT, "Helper bind/handshake exceeded 10 seconds"))) unbind()
        }
        binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // Handshaking is Binder I/O and must not block the Activity/main thread.
                CompletableFuture.runAsync {
                    val connection = Connection(IDesktopService.Stub.asInterface(binder), ::unbind)
                    try {
                        connection.handshake()
                        if (!result.complete(connection)) connection.close()
                    } catch (failure: Throwable) {
                        val cleanup = runCatching { connection.close() }.exceptionOrNull()
                        result.completeExceptionally(if (cleanup == null) failure else
                            ProjectionCleanupException("Desktop helper startup cleanup unconfirmed", cleanup).apply { addSuppressed(failure) })
                    } finally { main.removeCallbacks(timeout) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (result.completeExceptionally(DesktopException(DesktopErrorCode.DISCONNECTED, "Helper disconnected while binding"))) {
                    main.removeCallbacks(timeout); unbind()
                }
            }
        }
        main.postDelayed(timeout, 10_000)
        try { Shizuku.bindUserService(args, binding) } catch (failure: Throwable) {
            main.removeCallbacks(timeout); unbind(); result.completeExceptionally(failure)
        }
        return result
    }

    internal class Connection(private val service: IDesktopService, private val unbind: () -> Unit) : DesktopConnection {
        private val token = UUID.randomUUID().toString()
        private val owner = Binder()
        private val dead = CountDownLatch(1)
        private val death = IBinder.DeathRecipient { dead.countDown() }
        private var started = false
        private var closed = false
        // Touch and Back are submitted by the session's single input worker.
        private var inputEpoch = 0
        private lateinit var info: DesktopServiceInfo
        override val uid get() = info.uid
        override lateinit var capabilities: ProjectionCapabilities
            private set
        fun handshake() {
            desktopRemoteCall {
                service.asBinder().linkToDeath(death, 0)
                val reply = service.hello(DesktopProtocol.VERSION, DesktopFeatures.HOST_SUPPORTED)
                reply.status.checkSuccess()
                info = reply.info ?: throw DesktopException(DesktopErrorCode.PROTOCOL_MISMATCH, "Handshake omitted service info")
                info.validate()
                capabilities = DesktopFeatures.projection(info.enabledFeatures, info.maxTouchContacts)
            }
        }
        override fun start(surface: Surface, component: String, config: DesktopDisplayConfig): Int {
            check(!closed && !started)
            desktopCheck(info.accepts(config), DesktopErrorCode.INVALID_ARGUMENT) { "Display configuration exceeds helper limits" }
            started = true
            return desktopRemoteCall {
                val reply = service.start(token, owner, surface, component, config.width, config.height, config.dpi)
                reply.status.checkSuccess()
                reply.displayId
            }.also { desktopCheck(it > 0, DesktopErrorCode.DISPLAY_FAILED) { "Invalid virtual display" } }
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
        override fun healthy(): Boolean {
            if (closed || !service.asBinder().isBinderAlive) return false
            desktopRemoteCall { service.healthy(token, DesktopProtocol.LAYOUT).checkSuccess() }
            return true
        }
        override fun close() {
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
