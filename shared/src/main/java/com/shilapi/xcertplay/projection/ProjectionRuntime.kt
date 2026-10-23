package com.shilapi.xcertplay.projection

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** Single active source. A failed stop retains ownership; a second source must not steal its resources. */
class ProjectionRuntime {
    private val lock = Any()
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
    fun sources(): List<ProjectionSourceProvider> = synchronized(lock) { providers.values.toList() }
    fun snapshot(): ProjectionSession? = current
    fun hasSession(): Boolean = synchronized(lock) { current != null || opening != null || stopping != null || quarantined }
    fun resources(): Set<ProjectionResource> = synchronized(lock) { held.toSet() }
    fun subscribe(listener: (ProjectionSession?) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(current)
        return AutoCloseable { listeners.remove(listener) }
    }
    private fun publish() { listeners.forEach { runCatching { it(current) } } }

    /** Compatibility admission for an existing platform-owned connection flow. */
    fun adopt(session: ProjectionSession, resources: Set<ProjectionResource>): Boolean {
        synchronized(lock) {
            if (current === session) return true
            if (current != null || opening != null || stopping != null || quarantined) return false
            current = session
            stopFailed = false
            held = resources.toSet()
        }
        publish()
        return true
    }

    fun changed(expected: ProjectionSession) { if (current === expected) publish() }

    /** Natural session teardown; callers must have already stopped all producers. */
    fun ended(expected: ProjectionSession) {
        synchronized(lock) {
            if (current !== expected || stopping != null) return
            current = null
            held = emptySet()
        }
        publish()
    }

    fun open(id: String): CompletableFuture<ProjectionSession> {
        val provider: ProjectionSourceProvider
        val result = CompletableFuture<ProjectionSession>()
        synchronized(lock) {
            provider = providers[id] ?: return failed("Unknown projection source: $id")
            if (current != null || opening != null || stopping != null || quarantined) return failed("A projection source still owns resources")
            val available = provider.probe()
            if (available is SourceAvailability.Unavailable) return failed(available.reason)
            opening = result
            held = provider.resources.toSet()
        }
        try {
            provider.open().whenComplete { session, failure ->
                synchronized(lock) {
                    opening = null
                    if (failure == null) { current = session; stopFailed = false }
                    else {
                        quarantined = cleanupFailed(failure)
                        if (!quarantined) held = emptySet()
                    }
                }
                publish()
                if (failure == null) {
                    try { session.activate(); result.complete(session) }
                    catch (activationFailure: Throwable) {
                        stop(session).whenComplete { _, cleanupFailure ->
                            if (cleanupFailure != null) activationFailure.addSuppressed(cleanupFailure)
                            result.completeExceptionally(activationFailure)
                        }
                    }
                } else result.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                opening = null
                quarantined = cleanupFailed(failure)
                if (!quarantined) held = emptySet()
            }
            result.completeExceptionally(failure)
        }
        return result
    }

    fun stop(expected: ProjectionSession? = null): CompletableFuture<Unit> {
        val session: ProjectionSession
        val result: CompletableFuture<Unit>
        synchronized(lock) {
            if (expected != null && current !== expected) return CompletableFuture.completedFuture(Unit)
            stopping?.let { return it }
            if (quarantined) return failed("Source startup resources did not stop; restart the application")
            // An opening provider must finish/cancel its own startup before resources can be released.
            opening?.let { pending -> return pending.handle { _, _ -> Unit }.thenCompose { stop() } }
            session = current ?: return CompletableFuture.completedFuture(Unit)
            if (expected != null && expected !== session) return CompletableFuture.completedFuture(Unit)
            result = CompletableFuture()
            stopping = result
        }
        // Invalidate input before calling transport code. Never hold the runtime lock during I/O.
        publish()
        runCatching { session.touch(emptyList()) }
        runCatching { session.input(ProjectionInput.Release) }
        val close = try { session.stop() } catch (failure: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(failure) }
        }
        close.whenComplete { _, failure ->
            synchronized(lock) {
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
        if (available is SourceAvailability.Unavailable) return failed(available.reason)
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
    fun input(expected: ProjectionSession, event: ProjectionInput): Boolean = synchronized(lock) {
        if (current !== expected || (!inputReady(expected) && event != ProjectionInput.Release)) return false
        fun point(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
        val supported = when (event) {
            is ProjectionInput.Pointer -> expected.capabilities.pointerInput && point(event.x, event.y) && event.buttons in 0..7
            is ProjectionInput.Scroll -> expected.capabilities.pointerInput && point(event.x, event.y) && event.dx in -10..10 && event.dy in -10..10
            is ProjectionInput.Key -> expected.capabilities.keyboardInput && event.key.length in 1..32
            else -> expected.capabilities.pointerInput || expected.capabilities.keyboardInput
        }
        supported && expected.input(event)
    }
    private fun cleanupFailed(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { it is ProjectionCleanupException }

    private fun <T> failed(message: String) = CompletableFuture<T>().also {
        it.completeExceptionally(IllegalStateException(message))
    }
}
