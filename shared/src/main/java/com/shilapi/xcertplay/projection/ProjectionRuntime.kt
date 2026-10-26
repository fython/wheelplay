package com.shilapi.xcertplay.projection

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single active source. A failed stop retains ownership; a second source must not steal its resources. */
class ProjectionRuntime(private val startupTimeoutMs: Long = 60_000, private val cleanupTimeoutMs: Long = 15_000) {
    init { require(startupTimeoutMs > 0 && cleanupTimeoutMs > 0) }
    private val lock = Any()
    private val stateListeners = CopyOnWriteArrayList<(ProjectionSnapshot) -> Unit>()
    private var lastState = ProjectionSnapshot()
    private var activeFailure: ProjectionFailure? = null
    private var startup = ProjectionStartup()
    private var openingCancel: CompletableFuture<Unit>? = null
    private var deadline: ScheduledFuture<*>? = null
    private val listeners = CopyOnWriteArrayList<(ProjectionSession?) -> Unit>()
    private val providers = linkedMapOf<String, ProjectionSourceProvider>()
    @Volatile private var current: ProjectionSession? = null
    private var stopping: CompletableFuture<Unit>? = null
    private var opening: CompletableFuture<ProjectionSession>? = null
    private var held = emptySet<ProjectionResource>()
    private var stopFailed = false
    private var quarantined = false

    fun register(provider: ProjectionSourceProvider) = synchronized(lock) {
        require(provider.descriptor.id !in providers) { "Duplicate projection source" }
        providers[provider.descriptor.id] = provider
    }
    /** Replace a discovery namespace. Existing sessions retain their original provider/resources until stopped. */
    fun replaceProviders(prefix: String, discovered: List<ProjectionSourceProvider>) = synchronized(lock) {
        require(prefix.isNotBlank())
        require(discovered.all { it.descriptor.id.startsWith(prefix) })
        require(discovered.map { it.descriptor.id }.distinct().size == discovered.size)
        providers.keys.removeAll { it.startsWith(prefix) }
        discovered.forEach { providers[it.descriptor.id] = it }
    }
    fun sources(): List<ProjectionSourceProvider> = synchronized(lock) { providers.values.toList() }
    fun snapshot(): ProjectionSession? = current
    fun hasSession(): Boolean = synchronized(lock) { current != null || opening != null || stopping != null || quarantined }
    fun resources(): Set<ProjectionResource> = synchronized(lock) { held.toSet() }
    fun subscribe(listener: (ProjectionSession?) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(current)
        return AutoCloseable { listeners.remove(listener) }
    }
    fun stateSnapshot(): ProjectionSnapshot = synchronized(lock) {
        current?.let { session ->
            val state = session.state
            ProjectionSnapshot(session.descriptor,
                when { stopFailed -> ProjectionPhase.FAILED; stopping != null -> ProjectionPhase.STOPPING; else -> state.phase },
                state.capabilities, inputReady(session), activeFailure ?: state.failure,
                when { stopFailed -> ProjectionCleanup.UNCONFIRMED; stopping != null -> ProjectionCleanup.PENDING; else -> ProjectionCleanup.NONE })
        } ?: lastState
    }
    fun subscribeState(listener: (ProjectionSnapshot) -> Unit): AutoCloseable {
        stateListeners += listener
        listener(stateSnapshot())
        return AutoCloseable { stateListeners -= listener }
    }
    private fun publish() {
        listeners.forEach { runCatching { it(current) } }
        stateListeners.forEach { runCatching { it(stateSnapshot()) } }
    }
    /** open() acknowledges admission. This future separately waits for source readiness, including read-only sources. */
    fun awaitReady(expected: ProjectionSession): CompletableFuture<ProjectionSession> {
        val result = CompletableFuture<ProjectionSession>()
        val subscription = subscribeState { state ->
            if (current === expected && state.phase == ProjectionPhase.RUNNING) result.complete(expected)
            else if (current !== expected || state.phase in setOf(ProjectionPhase.STOPPING, ProjectionPhase.STOPPED, ProjectionPhase.FAILED))
                result.completeExceptionally(ProjectionException(state.failure ?: ProjectionFailure(ProjectionErrorCategory.CANCELLED, "Source stopped before readiness")))
        }
        result.whenComplete { _, _ -> subscription.close() }
        return result
    }
    private fun watchReadiness(session: ProjectionSession) {
        synchronized(lock) {
            if (current !== session || stopping != null || session.state.phase == ProjectionPhase.RUNNING) return
            deadline = schedule(startupTimeoutMs) {
                synchronized(lock) {
                    if (current !== session || stopping != null || session.state.phase == ProjectionPhase.RUNNING) return@schedule
                    activeFailure = ProjectionFailure(ProjectionErrorCategory.TIMEOUT, "Source readiness timed out")
                }
                stop(session)
            }
        }
    }
    private fun schedule(delay: Long, action: () -> Unit) = timer.schedule({ CompletableFuture.runAsync(action) }, delay, TimeUnit.MILLISECONDS)


    /** Compatibility admission for an existing platform-owned connection flow. */
    fun adopt(session: ProjectionSession, resources: Set<ProjectionResource>): Boolean {
        synchronized(lock) {
            if (current === session) return true
            if (current != null || opening != null || stopping != null || quarantined) return false
            current = session
            activeFailure = null
            stopFailed = false
            held = resources.toSet()
        }
        publish()
        return true
    }

    fun changed(expected: ProjectionSession) {
        synchronized(lock) {
            if (current !== expected) return
            if (expected.state.phase == ProjectionPhase.RUNNING) deadline?.cancel(false)
        }
        publish()
        if (expected.state.phase == ProjectionPhase.FAILED) stop(expected)
    }

    /** Natural session teardown; callers must have already stopped all producers. */
    fun ended(expected: ProjectionSession) {
        synchronized(lock) {
            if (current !== expected || stopping != null || stopFailed || quarantined) return
            deadline?.cancel(false)
            lastState = stateSnapshot().copy(phase = if (activeFailure != null || expected.state.failure != null) ProjectionPhase.FAILED else ProjectionPhase.STOPPED,
                inputReady = false, cleanup = ProjectionCleanup.RELEASED)
            current = null
            held = emptySet()
        }
        publish()
    }

    fun open(id: String): CompletableFuture<ProjectionSession> {
        val provider: ProjectionSourceProvider
        val attempt: ProjectionStartup
        val result = CompletableFuture<ProjectionSession>()
        synchronized(lock) {
            provider = providers[id] ?: return failed("Unknown projection source: $id")
            if (current != null || opening != null || stopping != null || quarantined) return failed("A projection source still owns resources")
            val available = provider.probe()
            if (available is SourceAvailability.Unavailable) return CompletableFuture<ProjectionSession>().also { it.completeExceptionally(ProjectionException(available.failure)) }
            opening = result
            openingCancel = null
            startup = ProjectionStartup()
            attempt = startup
            activeFailure = null
            lastState = ProjectionSnapshot(provider.descriptor, ProjectionPhase.CONNECTING, cleanup = ProjectionCleanup.NONE)
            deadline = schedule(startupTimeoutMs) {
                synchronized(lock) {
                    if (opening === result) cancelOpening(result, ProjectionFailure(ProjectionErrorCategory.TIMEOUT, "Source acquisition timed out"))
                }
            }
            held = provider.resources.toSet()
        }
        publish()
        try {
            provider.open(attempt).whenComplete { session, failure ->
                val cancelled = synchronized(lock) {
                    if (opening !== result || openingCancel != null) true else {
                        deadline?.cancel(false)
                        opening = null
                        if (failure == null) { current = session; stopFailed = false }
                        else {
                            quarantined = cleanupFailed(failure)
                            if (!quarantined) held = emptySet()
                            lastState = lastState.copy(phase = ProjectionPhase.FAILED, failure = projectionFailure(failure),
                                cleanup = if (quarantined) ProjectionCleanup.UNCONFIRMED else ProjectionCleanup.RELEASED)
                        }
                        false
                    }
                }
                if (cancelled) {
                    val cleanup = if (session != null) runCatching { session.stop() }.getOrElse { failedCleanup(it) }
                        else if (failure != null && cleanupFailed(failure)) failedCleanup(failure) else CompletableFuture.completedFuture(Unit)
                    cleanup.whenComplete { _, error -> finishCancelledOpening(result, attempt, error) }
                    return@whenComplete
                }
                publish()
                if (failure == null) {
                    try { session.activate(); watchReadiness(session); publish(); result.complete(session) }
                    catch (activationFailure: Throwable) {
                        synchronized(lock) { activeFailure = projectionFailure(activationFailure) }
                        stop(session).whenComplete { _, cleanupFailure ->
                            if (cleanupFailure != null) activationFailure.addSuppressed(cleanupFailure)
                            result.completeExceptionally(activationFailure)
                        }
                    }
                } else result.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            if (synchronized(lock) { openingCancel != null || opening !== result }) {
                finishCancelledOpening(result, attempt, if (cleanupFailed(failure)) failure else null)
                return result
            }
            synchronized(lock) {
                deadline?.cancel(false)
                opening = null
                quarantined = cleanupFailed(failure)
                lastState = lastState.copy(phase = ProjectionPhase.FAILED, failure = projectionFailure(failure),
                    cleanup = if (quarantined) ProjectionCleanup.UNCONFIRMED else ProjectionCleanup.RELEASED)
                if (!quarantined) held = emptySet()
            }
            result.completeExceptionally(failure)
            publish()
        }
        return result
    }

    private fun failedCleanup(error: Throwable) = CompletableFuture<Unit>().also { it.completeExceptionally(error) }

    /** Called with lock held; signal callbacks and all cleanup I/O execute outside it. */
    private fun cancelOpening(result: CompletableFuture<ProjectionSession>, reason: ProjectionFailure): CompletableFuture<Unit> {
        openingCancel?.let { return it }
        val cancelled = CompletableFuture<Unit>().also { openingCancel = it }
        val attempt = startup
        lastState = lastState.copy(phase = ProjectionPhase.STOPPING, failure = reason, cleanup = ProjectionCleanup.PENDING)
        deadline?.cancel(false)
        deadline = schedule(cleanupTimeoutMs) {
            finishCancelledOpening(result, attempt, ProjectionCleanupException("Startup cancellation cleanup timed out"))
        }
        CompletableFuture.runAsync { attempt.cancel(reason); publish() }
        return cancelled
    }

    private fun finishCancelledOpening(result: CompletableFuture<ProjectionSession>, attempt: ProjectionStartup, error: Throwable?) {
        val cancelled: CompletableFuture<Unit>?
        val failure: ProjectionFailure
        synchronized(lock) {
            if (opening !== result) return // Late completion must never admit a session or release a quarantined lease.
            cancelled = openingCancel
            deadline?.cancel(false)
            opening = null
            openingCancel = null
            quarantined = error != null
            failure = if (error != null) ProjectionFailure(ProjectionErrorCategory.CLEANUP, error.toString())
                else attempt.cancellation ?: lastState.failure ?: ProjectionFailure(ProjectionErrorCategory.CANCELLED, "Startup cancelled")
            lastState = lastState.copy(phase = if (error != null || failure.category != ProjectionErrorCategory.CANCELLED) ProjectionPhase.FAILED else ProjectionPhase.STOPPED,
                failure = failure, cleanup = if (quarantined) ProjectionCleanup.UNCONFIRMED else ProjectionCleanup.RELEASED)
            if (!quarantined) held = emptySet()
        }
        publish()
        result.completeExceptionally(ProjectionException(failure, error))
        if (error == null) cancelled?.complete(Unit) else cancelled?.completeExceptionally(error)
    }

    fun stop(expected: ProjectionSession? = null): CompletableFuture<Unit> {
        val session: ProjectionSession
        val result: CompletableFuture<Unit>
        synchronized(lock) {
            if (expected != null && current !== expected) return CompletableFuture.completedFuture(Unit)
            stopping?.let { return it }
            if (quarantined || stopFailed) return failed("Source resources did not stop; restart the application")
            // An opening provider must finish/cancel its own startup before resources can be released.
            opening?.let { pending -> return cancelOpening(pending, ProjectionFailure(ProjectionErrorCategory.CANCELLED, "Source startup cancelled")) }
            session = current ?: return CompletableFuture.completedFuture(Unit)
            if (expected != null && expected !== session) return CompletableFuture.completedFuture(Unit)
            result = CompletableFuture()
            stopping = result
            deadline?.cancel(false)
            deadline = schedule(cleanupTimeoutMs) {
                val error = ProjectionCleanupException("Source stop cleanup timed out")
                synchronized(lock) {
                    if (stopping !== result) return@schedule
                    activeFailure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, error.message.orEmpty())
                    stopFailed = true
                    stopping = null
                }
                publish()
                result.completeExceptionally(error)
            }
        }
        // Invalidate input before calling transport code. Never hold the runtime lock during I/O.
        publish()
        runCatching { session.cancelInput(ProjectionCancelReason.SOURCE_SWITCH) }
        val close = try { session.stop() } catch (failure: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(failure) }
        }
        close.whenComplete { _, failure ->
            synchronized(lock) {
                if (stopping !== result) return@whenComplete
                deadline?.cancel(false)
                if (failure != null) activeFailure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, failure.toString())
                lastState = stateSnapshot().copy(phase = if (failure != null || activeFailure != null || session.state.failure != null) ProjectionPhase.FAILED else ProjectionPhase.STOPPED,
                    inputReady = false, failure = activeFailure ?: session.state.failure,
                    cleanup = if (failure == null) ProjectionCleanup.RELEASED else ProjectionCleanup.UNCONFIRMED)
                if (failure == null && current === session) { current = null; held = emptySet() }
                stopFailed = failure != null
                stopping = null
            }
            publish()
            if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
        }
        return result
    }

    fun switchTo(id: String): CompletableFuture<ProjectionSession> {
        val provider = synchronized(lock) { providers[id] } ?: return failed("Unknown projection source: $id")
        val available = provider.probe()
        if (available is SourceAvailability.Unavailable) return CompletableFuture<ProjectionSession>().also { it.completeExceptionally(ProjectionException(available.failure)) }
        return stop().thenCompose { open(id) }
    }

    fun setNightMode(expected: ProjectionSession, night: Boolean): ProjectionCommandResult {
        synchronized(lock) {
            if (current !== expected || stopping != null || stopFailed) return ProjectionCommandResult.UNAVAILABLE
            if (!expected.capabilities.nightMode) return ProjectionCommandResult.UNSUPPORTED
        }
        // Capture the target, but do not hold the state lock across legacy protocol I/O.
        return expected.setNightMode(night)
    }

    fun inputReady(expected: ProjectionSession?): Boolean = synchronized(lock) {
        expected != null && current === expected && stopping == null && !stopFailed && expected.inputReady
    }
    fun touch(expected: ProjectionSession, contacts: List<ProjectionContact>): Boolean = synchronized(lock) {
        if (!inputReady(expected) && contacts.isNotEmpty()) return false
        if (current !== expected) return false
        val max = expected.capabilities.maxTouchContacts
        if (contacts.size > max || contacts.map { it.id }.distinct().size != contacts.size || contacts.any {
                it.id !in 0..31 || !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0
            }) return false
        expected.touch(contacts)
    }
    fun cancelInput(expected: ProjectionSession, reason: ProjectionCancelReason): Boolean = synchronized(lock) {
        if (current !== expected || stopFailed) return false
        expected.cancelInput(reason)
    }
    fun input(expected: ProjectionSession, event: ProjectionInput): Boolean = synchronized(lock) {
        if (event == ProjectionInput.Release) return cancelInput(expected, ProjectionCancelReason.USER_REQUEST)
        if (current !== expected || !inputReady(expected)) return false
        fun point(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
        val supported = when (event) {
            is ProjectionInput.Pointer -> expected.capabilities.pointerInput && point(event.x, event.y) && event.buttons in 0..7
            is ProjectionInput.Scroll -> expected.capabilities.pointerInput && point(event.x, event.y) && event.dx in -10..10 && event.dy in -10..10
            is ProjectionInput.Key -> (expected.capabilities.keyboardInput && event.key.length in 1..32) ||
                (expected.capabilities.backButton && event.key == "BrowserBack")
            else -> expected.capabilities.pointerInput || expected.capabilities.keyboardInput || expected.capabilities.backButton
        }
        supported && expected.input(event)
    }
    private fun cleanupFailed(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { it is ProjectionCleanupException }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "projection-startup-deadline").apply { isDaemon = true } }
    }
    private fun <T> failed(message: String) = CompletableFuture<T>().also {
        it.completeExceptionally(IllegalStateException(message))
    }
}
