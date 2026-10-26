package com.shilapi.xcertplay.projection.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.shared.R
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.web.WebSession
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidDesktopSource(
    private val context: Context,
    private val backend: DesktopBackend = ShizukuDesktopBackend(context),
    private val output: (DesktopDisplayConfig) -> ProjectionVideoOutput = { config ->
        BrowserProjectionVideo(context, config.width, config.height, VideoIngressKind.SURFACE)
    },
    private val targetAvailable: (String) -> Boolean = { component ->
        val target = ComponentName.unflattenFromString(component)
        target != null && context.packageManager.queryIntentActivities(launcherIntent(), 0).any {
            it.activityInfo.let { info -> info.exported && info.enabled && info.applicationInfo.enabled &&
                ComponentName(info.packageName, info.name) == target }
        }
    },
) : ProjectionSourceProvider {
    override val descriptor get() = SourceDescriptor(DesktopProtocol.SOURCE_ID, context.getString(R.string.desktop_title))
    override val resources = setOf(ProjectionResource.DISPLAY)
    private val preferences = context.getSharedPreferences("android-desktop", Context.MODE_PRIVATE)
    @Volatile var lastFailure: String? = null
        private set
    @Volatile var lastError: DesktopException? = null
        private set
    @Volatile var preparing = false
        private set
    fun selectedApp(): String = preferences.getString("component", "").orEmpty()
    fun selectApp(component: String) { require(targetAvailable(component)); preferences.edit().putString("component", component).apply() }
    fun displayConfig(): DesktopDisplayConfig = runCatching {
        val saved = preferences.all
        val defaults = DesktopDisplayConfig()
        DesktopDisplayConfig(saved["width"] as? Int ?: defaults.width,
            saved["height"] as? Int ?: defaults.height, saved["dpi"] as? Int ?: defaults.dpi)
    }.getOrDefault(DesktopDisplayConfig())
    fun configureDisplay(config: DesktopDisplayConfig) {
        preferences.edit().putInt("width", config.width).putInt("height", config.height).putInt("dpi", config.dpi).apply()
    }
    fun dependencyStatus() = backend.probe()
    override fun probe(): SourceAvailability {
        val dependency = backend.probe()
        if (dependency is SourceAvailability.Unavailable) return dependency
        return if (targetAvailable(selectedApp())) SourceAvailability.Ready
        else SourceAvailability.Unavailable(context.getString(R.string.desktop_choose_app))
    }
    override fun open(): CompletableFuture<ProjectionSession> {
        val state = probe()
        if (state is SourceAvailability.Unavailable) return CompletableFuture<ProjectionSession>().also {
            it.completeExceptionally(IllegalStateException(state.reason))
        }
        val target = selectedApp()
        val config = displayConfig()
        lastFailure = null
        lastError = null
        preparing = true
        val pending = try { backend.connect() } catch (error: Throwable) {
            preparing = false; recordFailure(error)
            return CompletableFuture<ProjectionSession>().also { it.completeExceptionally(error) }
        }
        return pending.thenApply<ProjectionSession> { Session(it, target, config) }.whenComplete { _, failure ->
            preparing = false
            if (failure != null) recordFailure(failure)
        }
    }

    private fun recordFailure(error: Throwable): String {
        val failure = desktopFailure(error)
        lastError = failure
        Log.w("AndroidDesktop", "Desktop error ${failure.code}: ${failure.diagnostic}", failure)
        return context.getString(failure.category.message).also { lastFailure = it }
    }

    private inner class Session(private val connection: DesktopConnection, private val target: String,
        private val config: DesktopDisplayConfig) : ProjectionSession {
        override val descriptor = this@AndroidDesktopSource.descriptor
        override val capabilities = connection.capabilities
        private val stopped = AtomicBoolean()
        private val activated = AtomicBoolean()
        private val completion = CompletableFuture<Unit>()
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
            ThreadFactory { Thread(it, "android-desktop-input").apply { isDaemon = true } })
        private val monitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "android-desktop-monitor").apply { isDaemon = true } }
        @Volatile private var video: ProjectionVideoOutput? = null
        @Volatile private var started = false
        @Volatile private var failure: String? = null
        @Volatile private var startupCleanupFailure: Throwable? = null
        @Volatile private var lastProgress = System.nanoTime()
        private var startedAt = 0L
        override val inputReady get() = started && !stopped.get() && video?.hasFrame == true
        override val statusText: String get() = failure ?: when {
            stopped.get() -> context.getString(R.string.desktop_stopping)
            !started || video?.hasFrame != true -> context.getString(R.string.desktop_preparing)
            !WebSession.hasViewer || video?.hasPublishedFrame != true -> context.getString(R.string.desktop_waiting_browser)
            else -> context.getString(R.string.desktop_connected, connection.uid)
        }

        override fun activate() {
            if (stopped.get() || !activated.compareAndSet(false, true)) return
            enqueue {
                video = output(config)
                connection.start(video!!.surface, target, config)
                startedAt = System.nanoTime()
                started = true
            }
            try { monitor.scheduleWithFixedDelay({
                if (stopped.get()) return@scheduleWithFixedDelay
                if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(15)) {
                    fail(DesktopException(DesktopErrorCode.DISCONNECTED, "No IPC progress for 15 seconds"))
                } else enqueue {
                    if (!started) return@enqueue
                    val dependency = backend.probe()
                    desktopCheck(dependency is SourceAvailability.Ready, DesktopErrorCode.DISCONNECTED) {
                        (dependency as SourceAvailability.Unavailable).reason
                    }
                    desktopCheck(connection.healthy(), DesktopErrorCode.DISCONNECTED) { "Helper is not healthy" }
                    desktopCheck(video?.hasFrame == true || System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(15), DesktopErrorCode.NO_FRAME) {
                        "No Surface frame within 15 seconds"
                    }
                }
            }, 500, 500, TimeUnit.MILLISECONDS) } catch (error: RejectedExecutionException) {
                if (!stopped.get()) throw error
            }
        }

        private fun enqueue(block: () -> Unit): Boolean {
            if (stopped.get()) return false
            return try {
                worker.execute {
                    if (!stopped.get()) try { block(); lastProgress = System.nanoTime() } catch (error: Throwable) { fail(error) }
                }
                true
            } catch (_: RejectedExecutionException) {
                if (!stopped.get()) fail(DesktopException(DesktopErrorCode.INPUT_QUEUE_FULL, "Desktop input queue overflow"))
                false
            }
        }
        override fun touch(contacts: List<ProjectionContact>): Boolean {
            if (capabilities.maxTouchContacts == 0 || (!inputReady && contacts.isNotEmpty()) ||
                contacts.size > capabilities.maxTouchContacts || contacts.map { it.id }.distinct().size != contacts.size ||
                contacts.any { it.id !in 0..31 || !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
            val copy = contacts.toList()
            return enqueue { if (started) connection.touch(copy) }
        }
        override fun input(event: ProjectionInput): Boolean = when {
            event == ProjectionInput.Release -> touch(emptyList())
            event is ProjectionInput.Key && event.key == "BrowserBack" && capabilities.backButton && inputReady ->
                if (event.down) enqueue { connection.back() } else true
            else -> false
        }
        private fun fail(error: Throwable) {
            // A failed video constructor may already have lost its object reference while
            // retaining a GL thread/Surface. Never release runtime ownership in that case.
            if (generateSequence(error) { it.cause }.any { it is ProjectionCleanupException }) startupCleanupFailure = error
            failure = recordFailure(error)
            stop().whenComplete { _, cleanup ->
                if (cleanup == null) projectionSessions.ended(this)
                else { failure = recordFailure(DesktopException(DesktopErrorCode.CLEANUP_FAILED, cleanup.toString(), cleanup)) }
            }
        }
        override fun stop(): CompletableFuture<Unit> {
            if (!stopped.compareAndSet(false, true)) return completion
            monitor.shutdownNow()
            worker.queue.clear()
            worker.shutdown()
            CompletableFuture.runAsync {
                try {
                    check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Desktop IPC did not stop" }
                    // Helper releases gestures/display and exits before the consumer Surface is released.
                    connection.close()
                    video?.close()
                    startupCleanupFailure?.let { throw it }
                    completion.complete(Unit)
                } catch (error: Throwable) {
                    failure = recordFailure(DesktopException(DesktopErrorCode.CLEANUP_FAILED, error.toString(), error))
                    completion.completeExceptionally(ProjectionCleanupException("Virtual desktop cleanup unconfirmed", error))
                }
            }
            return completion
        }
    }

    companion object {
        fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    }
}
