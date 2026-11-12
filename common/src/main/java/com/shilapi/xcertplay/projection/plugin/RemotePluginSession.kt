package com.shilapi.xcertplay.projection.plugin

import android.content.*
import android.os.*
import android.content.res.Configuration
import moe.feng.wheelplay.AirPlayPersistence
import com.shilapi.xcertplay.projection.*
import moe.feng.wheelplay.plugin.*
import java.util.UUID
import java.util.concurrent.*
import com.shilapi.xcertplay.shared.R as UiR

/** Lifecycle transitions run on main; Binder writes and media setup/teardown never run under runtime locks. */
internal class RemotePluginSession(
    private val context: Context,
    val plugin: InstalledProjectionPlugin,
    private val runtime: ProjectionRuntime,
    private val stillAllowed: () -> Boolean,
    private val outputFactory: (Int, Int) -> ProjectionVideoOutput,
    private val connectTimeoutMs: Long = 15_000,
    private val stopTimeoutMs: Long = 15_000,
    private val startupTimeoutMs: Long = 60_000,
    private val onFailure: (String) -> Unit = {},
) : ProjectionSession {
    override val descriptor = SourceDescriptor(plugin.id, plugin.name)
    @Volatile override var capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE))
        private set
    @Volatile override var statusText: String? = context.getString(UiR.string.plugin_connecting)
        private set
    @Volatile private var ready = false
    @Volatile private var stopping = false
    override val inputReady get() = ready && !stopping
    private val admission = Any()
    private val main = Handler(Looper.getMainLooper())
    internal val token = UUID.randomUUID().toString()
    private val completion = CompletableFuture<Unit>()
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "projection-plugin-ipc").apply { isDaemon = true } })
    private val mediaWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "projection-plugin-surface").apply { isDaemon = true } }
    private var failureReason: String? = null
    @Volatile private var lifecycleFailure: ProjectionFailure? = null
    override val state get() = ProjectionSessionState(
        when { completion.isDone -> if (lifecycleFailure == null) ProjectionPhase.STOPPED else ProjectionPhase.FAILED
            stopping -> ProjectionPhase.STOPPING; ready -> ProjectionPhase.RUNNING
            infoReceived -> ProjectionPhase.NEGOTIATING; else -> ProjectionPhase.CONNECTING },
        capabilities, inputReady, lifecycleFailure)
    private var activated = false
    private var bound = false
    @Volatile private var remote: IProjectionPlugin? = null
    private var remoteBinder: IBinder? = null
    private var infoReceived = false
    private var startSent = false
    private var closing = false
    private var stopSent = false
    private var processExitRequired = false
    private var awaitingProcessExit = false
    private var output: CompletableFuture<ProjectionVideoOutput>? = null
    private val deadline = Runnable { fail(context.getString(UiR.string.plugin_timeout)) }
    private val stopDeadline = Runnable { finish(false, context.getString(UiR.string.plugin_cleanup_failed)) }
    private val death = IBinder.DeathRecipient { main.post {
        if (awaitingProcessExit) finish(true, statusText.orEmpty()) else lostConnection()
    } }

    private fun rememberFailure(message: String) {
        if (failureReason != null) return
        failureReason = message.take(512)
        lifecycleFailure = ProjectionFailure(ProjectionErrorCategory.CONNECTION, message.take(512), "plugin", ProjectionPlugin.ERROR.toString())
        runCatching { onFailure(failureReason!!) }
    }
    private fun notifyStatus(message: String) {
        statusText = message.take(512)
        runtime.changed(this)
    }
    private fun callback(id: String, action: () -> Unit) {
        if (id != token || Binder.getCallingUid() != plugin.uid) return
        main.post { if (!closing) action() }
    }
    internal val callbacks = object : IProjectionHost.Stub() {
        override fun onInfo(token: String, info: Bundle) = callback(token) {
            if (stopping || infoReceived) return@callback
            try {
                check(stillAllowed()) { context.getString(UiR.string.plugin_disabled) }
                val video = PluginVideoInfo.fromBundle(info)
                infoReceived = true
                processExitRequired = video.processExitRequired
                main.removeCallbacks(deadline)
                main.postDelayed(deadline, startupTimeoutMs)
                capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE), video.maxTouchContacts, video.nightMode,
                    cancellation = if (video.nativeCancelInput) ProjectionCancellation.NATIVE else ProjectionCancellation.RELEASE_ONLY)
                runtime.changed(this@RemotePluginSession)
                output = CompletableFuture.supplyAsync({ outputFactory(video.width, video.height) }, mediaWorker).also { pending ->
                    pending.whenComplete { port, failure -> main.post {
                        if (closing) return@post
                        if (failure != null) { fail(failure.message ?: "Cannot create video output"); return@post }
                        if (stopping) { beginStop(); return@post }
                        if (!stillAllowed()) { fail(context.getString(UiR.string.plugin_disabled)); return@post }
                        startSent = true
                        enqueue { it.start(this@RemotePluginSession.token, port.surface) }
                    } }
                }
            } catch (failure: Exception) { fail(failure.message ?: "Invalid plugin information") }
        }
        override fun onState(token: String, state: Int, message: String) = callback(token) {
            if (stopping) return@callback
            // A handshake failure may arrive before start/Surface allocation.
            if (state == ProjectionPlugin.ERROR) {
                fail(message.ifBlank { context.getString(UiR.string.plugin_disconnected) })
                return@callback
            }
            if (!startSent) return@callback
            when (state) {
                ProjectionPlugin.CONNECTING -> { ready = false; notifyStatus(message) }
                ProjectionPlugin.READY -> {
                    ready = true
                    main.removeCallbacks(deadline)
                    notifyStatus(message.ifBlank { context.getString(UiR.string.plugin_connected) })
                    // Initial appearance can arrive before asynchronous capability discovery completes.
                    if (capabilities.nightMode) setNightMode(AirPlayPersistence.loadAppearance(context).night(
                        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES))
                }
                else -> fail("Invalid plugin state")
            }
        }
        override fun onStopped(token: String, clean: Boolean, message: String) = callback(token) {
            if (awaitingProcessExit) return@callback
            // Older v2 plugins can reject connect or end a session using onStopped alone.
            if (!stopping) rememberFailure(message.ifBlank { context.getString(UiR.string.plugin_disconnected) })
            if (!clean || !processExitRequired) { finish(clean, message); return@callback }
            awaitingProcessExit = true
            synchronized(admission) { stopping = true; ready = false }
            notifyStatus(context.getString(UiR.string.plugin_waiting_for_exit))
            main.removeCallbacks(deadline)
            if (!stopSent) main.postDelayed(stopDeadline, stopTimeoutMs)
            stopSent = true
            io.shutdownNow()
            // Keep the death recipient and runtime lease until this exact Binder dies.
            // onServiceDisconnected alone is not proof that the SDK process exited.
            if (bound) {
                runCatching { context.unbindService(connection) }.onFailure {
                    finish(false, it.message ?: "Cannot unbind plugin")
                }
                bound = false
            }
            if (remoteBinder?.isBinderAlive == false) finish(true, message)
        }
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closing || stopping || !bound) return
            if (!stillAllowed()) { fail(context.getString(UiR.string.plugin_disabled)); return }
            try {
                remoteBinder = service
                remote = IProjectionPlugin.Stub.asInterface(service)
                service.linkToDeath(death, 0)
                enqueue { it.connect(token, ProjectionPlugin.API_VERSION, callbacks) }
            } catch (failure: Exception) { lostConnection() }
        }
        override fun onServiceDisconnected(name: ComponentName) = lostConnection()
        override fun onBindingDied(name: ComponentName) = lostConnection()
        override fun onNullBinding(name: ComponentName) = lostConnection()
    }

    override fun activate() {
        main.post {
            if (activated || closing) return@post
            activated = true
            if (stopping) { beginStop(); return@post }
            if (!stillAllowed()) { fail(context.getString(UiR.string.plugin_disabled)); return@post }
            main.postDelayed(deadline, connectTimeoutMs)
            try {
                bound = context.bindService(Intent(ProjectionPlugin.ACTION).setComponent(plugin.component), connection, Context.BIND_AUTO_CREATE)
                if (!bound) fail(context.getString(UiR.string.plugin_unavailable))
            } catch (failure: Exception) { fail(failure.message ?: "Cannot bind plugin") }
        }
    }

    private fun enqueue(action: (IProjectionPlugin) -> Unit): Boolean {
        return try {
            io.execute {
                try { remote?.let(action) ?: error("Plugin disconnected") }
                catch (failure: Exception) { main.post { fail(failure.message ?: "Plugin IPC failed") } }
            }
            true
        } catch (_: RejectedExecutionException) {
            main.post { fail("Plugin control queue overflow") }
            false
        }
    }
    override fun touch(contacts: List<ProjectionContact>): Boolean = synchronized(admission) {
        if (stopping || (!ready && contacts.isNotEmpty())) return false
        val copy = contacts.map { PluginContact(it.id, it.x, it.y, it.down) }
        if (!validPluginContacts(copy, capabilities.maxTouchContacts)) return false
        enqueue { it.touch(token, copy.map { c -> c.id }.toIntArray(), copy.map { c -> c.x }.toDoubleArray(),
            copy.map { c -> c.y }.toDoubleArray(), copy.map { c -> c.down }.toBooleanArray()) }
    }
    override fun cancelInput(reason: ProjectionCancelReason): Boolean = synchronized(admission) {
        if (stopping || !startSent) return false
        if (capabilities.cancellation == ProjectionCancellation.NATIVE) enqueue { it.cancelInput(token) }
        else touch(emptyList())
    }
    override fun setNightMode(night: Boolean): ProjectionCommandResult = synchronized(admission) {
        if (stopping || !ready) return ProjectionCommandResult.UNAVAILABLE
        if (!capabilities.nightMode) return ProjectionCommandResult.UNSUPPORTED
        if (enqueue { it.setNightMode(token, night) }) ProjectionCommandResult.SENT else ProjectionCommandResult.FAILED
    }
    override fun stop(): CompletableFuture<Unit> {
        synchronized(admission) { stopping = true; ready = false }
        main.post { beginStop() }
        return completion
    }
    private fun fail(reason: String) {
        if (closing || stopping) return
        rememberFailure(reason)
        stop()
        notifyStatus(reason)
    }
    private fun beginStop() {
        if (closing || completion.isDone || stopSent) return
        notifyStatus(context.getString(UiR.string.plugin_stopping))
        main.removeCallbacks(deadline)
        if (!startSent) { finish(true, statusText.orEmpty()); return }
        if (remote == null) { finish(false, context.getString(UiR.string.plugin_cleanup_failed)); return }
        stopSent = true
        // Stop is queued after any in-flight start/touch. Pending input is obsolete now.
        io.queue.clear()
        enqueue { it.stop(token) }
        main.removeCallbacks(stopDeadline)
        main.postDelayed(stopDeadline, stopTimeoutMs)
    }
    private fun lostConnection() {
        if (!closing && !awaitingProcessExit) {
            rememberFailure(context.getString(UiR.string.plugin_disconnected))
            finish(!startSent, context.getString(UiR.string.plugin_disconnected))
        }
    }
    private fun finish(clean: Boolean, message: String) {
        if (closing) return
        closing = true
        synchronized(admission) { stopping = true; ready = false }
        main.removeCallbacks(deadline); main.removeCallbacks(stopDeadline)
        io.shutdownNow()
        remoteBinder?.let { runCatching { it.unlinkToDeath(death, 0) } }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        // Abandon the host consumer, including on failure. This does NOT prove remote SDK resources were released.
        // Failed or absent acknowledgement keeps the runtime lease quarantined even if the host closes successfully.
        val pending = output ?: CompletableFuture.completedFuture(null)
        pending.handleAsync({ port, allocationFailure ->
            var failure: Throwable? = if (clean) null else ProjectionCleanupException(context.getString(UiR.string.plugin_cleanup_failed))
            if (allocationFailure != null && generateSequence(allocationFailure) { it.cause }.any { it is ProjectionCleanupException }) failure = allocationFailure
            try { port?.close() } catch (closeFailure: Throwable) { failure = closeFailure }
            failure
        }, mediaWorker).whenComplete { cleanupFailure, unexpected -> main.post {
            mediaWorker.shutdown()
            val failure = cleanupFailure ?: unexpected
            if (failure == null) {
                notifyStatus(failureReason ?: message)
                completion.complete(Unit)
                runtime.ended(this)
            } else {
                lifecycleFailure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, failure.toString(), "plugin")
                val cleanupMessage = context.getString(UiR.string.plugin_cleanup_failed)
                // Preserve the original failure too, but make the unsafe cleanup actionable.
                val report = failureReason?.let { "$it\n$cleanupMessage" } ?: cleanupMessage
                runCatching { onFailure(report) }
                notifyStatus(report)
                completion.completeExceptionally(failure)
                runtime.changed(this)
            }
        } }
    }
}
