package com.shilapi.xcertplay.projection.desktop

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
            result.completeExceptionally(IllegalStateException(availability.reason)); return result
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuDesktopUserService::class.java))
            .tag("wheelplay-desktop-v${DesktopProtocol.VERSION}-${android.os.Process.myUid()}-${UUID.randomUUID()}")
            .version(version).daemon(false).processNameSuffix("desktop")
        val main = Handler(Looper.getMainLooper())
        lateinit var binding: ServiceConnection
        fun unbind() { runCatching { Shizuku.unbindUserService(args, binding, true) } }
        val timeout = Runnable {
            if (result.completeExceptionally(IllegalStateException(context.getString(R.string.desktop_bind_timeout)))) unbind()
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
                if (result.completeExceptionally(IllegalStateException(context.getString(R.string.desktop_disconnected)))) {
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

    private class Connection(private val service: IDesktopService, private val unbind: () -> Unit) : DesktopConnection {
        private val token = UUID.randomUUID().toString()
        private val owner = Binder()
        private val dead = CountDownLatch(1)
        private val death = IBinder.DeathRecipient { dead.countDown() }
        private var started = false
        private var closed = false
        override var uid = -1
            private set
        fun handshake() {
            service.asBinder().linkToDeath(death, 0)
            uid = service.hello(DesktopProtocol.VERSION)
            check(uid == 2000 || uid == 0) { "Unsupported helper identity" }
        }
        override fun start(surface: Surface, component: String): Int {
            check(!closed && !started)
            started = true
            return service.start(token, owner, surface, component).also { check(it > 0) { "Invalid virtual display" } }
        }
        override fun touch(contacts: List<ProjectionContact>) {
            check(!closed)
            service.touch(token, DesktopProtocol.LAYOUT, contacts.map { it.id }.toIntArray(),
                contacts.map { it.x.toFloat() }.toFloatArray(), contacts.map { it.y.toFloat() }.toFloatArray(),
                contacts.map { it.down }.toBooleanArray())
        }
        override fun back() { check(!closed); service.back(token, DesktopProtocol.LAYOUT) }
        override fun healthy() = !closed && service.asBinder().isBinderAlive && service.healthy(token, DesktopProtocol.LAYOUT)
        override fun close() {
            if (closed) return
            try {
                if (service.asBinder().isBinderAlive) {
                    try { if (started) service.stop(token) } finally { runCatching { service.destroy() } }
                }
            } catch (_: Exception) {
                // A revoked Shizuku grant still permits the owning app to destroy its own helper Binder.
                runCatching { service.destroy() }
            } finally { unbind() }
            if (service.asBinder().isBinderAlive && !dead.await(5, TimeUnit.SECONDS))
                throw ProjectionCleanupException("Virtual desktop helper did not exit")
            closed = true
            runCatching { service.asBinder().unlinkToDeath(death, 0) }
        }
    }

    companion object { const val PACKAGE = "moe.shizuku.privileged.api" }
}
