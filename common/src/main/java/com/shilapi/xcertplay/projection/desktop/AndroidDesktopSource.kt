package com.shilapi.xcertplay.projection.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.shared.R
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.web.WebSession
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidDesktopSource(
    private val context: Context,
    private val backend: DesktopBackend = ShizukuDesktopBackend(context),
    private val output: () -> ProjectionVideoOutput = {
        BrowserProjectionVideo(context, DesktopProtocol.WIDTH, DesktopProtocol.HEIGHT, VideoIngressKind.SURFACE)
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
    @Volatile var preparing = false
        private set
    fun selectedApp(): String = preferences.getString("component", "").orEmpty()
    fun selectApp(component: String) { require(targetAvailable(component)); preferences.edit().putString("component", component).apply() }
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
        lastFailure = null
        preparing = true
        val pending = try { backend.connect() } catch (error: Throwable) {
            preparing = false; lastFailure = error.message
            return CompletableFuture<ProjectionSession>().also { it.completeExceptionally(error) }
        }
        return pending.thenApply<ProjectionSession> { Session(it, target) }.whenComplete { _, failure ->
            preparing = false
            if (failure != null) lastFailure = failure.cause?.message ?: failure.message
        }
    }

    private inner class Session(private val connection: DesktopConnection, private val target: String) : ProjectionSession {
        override val descriptor = this@AndroidDesktopSource.descriptor
        override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE), maxTouchContacts = 10, backButton = true)
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
                video = output()
                connection.start(video!!.surface, target)
                startedAt = System.nanoTime()
                started = true
            }
            try { monitor.scheduleWithFixedDelay({
                if (stopped.get()) return@scheduleWithFixedDelay
                if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(15)) {
                    fail(IllegalStateException(context.getString(R.string.desktop_disconnected)))
                } else enqueue {
                    if (!started) return@enqueue
                    val dependency = backend.probe()
                    check(dependency is SourceAvailability.Ready) { (dependency as SourceAvailability.Unavailable).reason }
                    check(connection.healthy()) { context.getString(R.string.desktop_disconnected) }
                    check(video?.hasFrame == true || System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(15)) {
                        context.getString(R.string.desktop_no_frame)
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
                if (!stopped.get()) fail(IllegalStateException("Desktop input queue overflow"))
                false
            }
        }
        override fun touch(contacts: List<ProjectionContact>): Boolean {
            if ((!inputReady && contacts.isNotEmpty()) || contacts.size > 10 || contacts.map { it.id }.distinct().size != contacts.size ||
                contacts.any { it.id !in 0..31 || !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
            val copy = contacts.toList()
            return enqueue { if (started) connection.touch(copy) }
        }
        override fun input(event: ProjectionInput): Boolean = when {
            event == ProjectionInput.Release -> touch(emptyList())
            event is ProjectionInput.Key && event.key == "BrowserBack" && inputReady ->
                if (event.down) enqueue { connection.back() } else true
            else -> false
        }
        private fun fail(error: Throwable) {
            // A failed video constructor may already have lost its object reference while
            // retaining a GL thread/Surface. Never release runtime ownership in that case.
            if (generateSequence(error) { it.cause }.any { it is ProjectionCleanupException }) startupCleanupFailure = error
            failure = context.getString(R.string.projection_failed, error.message ?: error.javaClass.simpleName)
            lastFailure = failure
            stop().whenComplete { _, cleanup ->
                if (cleanup == null) projectionSessions.ended(this)
                else { failure = context.getString(R.string.desktop_cleanup_failed); lastFailure = failure }
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
                    failure = context.getString(R.string.desktop_cleanup_failed)
                    lastFailure = failure
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
