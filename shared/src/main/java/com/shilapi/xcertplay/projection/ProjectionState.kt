package com.shilapi.xcertplay.projection

import java.util.concurrent.CancellationException

/** Source lifecycle is independent of browser connectivity and whether input is read-only. */
enum class ProjectionPhase { CONNECTING, NEGOTIATING, RUNNING, STOPPING, STOPPED, FAILED }
enum class ProjectionCleanup { NONE, PENDING, RELEASED, UNCONFIRMED }
enum class ProjectionErrorCategory { UNAVAILABLE, PERMISSION, CONNECTION, PROTOCOL, CONFIGURATION, INPUT, TIMEOUT, CANCELLED, CLEANUP, INTERNAL }

data class ProjectionFailure(
    val category: ProjectionErrorCategory,
    val diagnostic: String,
    val backend: String? = null,
    val backendCode: String? = null,
)

open class ProjectionException(val failure: ProjectionFailure, cause: Throwable? = null) :
    IllegalStateException(failure.diagnostic, cause)

fun projectionFailure(error: Throwable): ProjectionFailure {
    val causes = generateSequence(error) { it.cause }.toList()
    val typed = causes.filterIsInstance<ProjectionException>().firstOrNull()?.failure
    if (causes.any { it is ProjectionCleanupException }) return ProjectionFailure(
        ProjectionErrorCategory.CLEANUP, causes.joinToString("; caused by: "), typed?.backend, typed?.backendCode)
    return typed ?: ProjectionFailure(
        when {
            causes.any { it is CancellationException } -> ProjectionErrorCategory.CANCELLED
            causes.any { it is SecurityException } -> ProjectionErrorCategory.PERMISSION
            causes.any { it is java.util.concurrent.TimeoutException } -> ProjectionErrorCategory.TIMEOUT
            causes.any { it is java.io.IOException } -> ProjectionErrorCategory.CONNECTION
            else -> ProjectionErrorCategory.INTERNAL
        }, causes.last().toString(),
    )
}

data class ProjectionSessionState(
    val phase: ProjectionPhase,
    val capabilities: ProjectionCapabilities,
    val inputReady: Boolean = false,
    val failure: ProjectionFailure? = null,
)

/** Retained after teardown, including the reason for termination and resource ownership. */
data class ProjectionSnapshot(
    val source: SourceDescriptor? = null,
    val phase: ProjectionPhase = ProjectionPhase.STOPPED,
    val capabilities: ProjectionCapabilities = ProjectionCapabilities(emptySet()),
    val inputReady: Boolean = false,
    val failure: ProjectionFailure? = null,
    val cleanup: ProjectionCleanup = ProjectionCleanup.RELEASED,
)

enum class ProjectionCancelReason { FOCUS_LOST, DISCONNECTED, LEASE_EXPIRED, LAYOUT_CHANGED, SOURCE_SWITCH, USER_REQUEST }
/** RELEASE_ONLY protocols cannot guarantee that releasing a held contact will not click. */
enum class ProjectionCancellation { RELEASE_ONLY, NATIVE }

/** Cooperative signal, never a cleanup acknowledgement. Callbacks must initiate cancellation without blocking. */
class ProjectionStartup {
    private val lock = Any()
    private val callbacks = linkedSetOf<() -> Unit>()
    @Volatile var cancellation: ProjectionFailure? = null
        private set
    val isCancelled get() = cancellation != null

    fun checkActive() { cancellation?.let { throw ProjectionException(it) } }
    fun onCancel(callback: () -> Unit): AutoCloseable {
        val invoke = synchronized(lock) { if (isCancelled) true else { callbacks += callback; false } }
        if (invoke) callback()
        return AutoCloseable { synchronized(lock) { callbacks -= callback } }
    }
    fun cancel(reason: ProjectionFailure = ProjectionFailure(ProjectionErrorCategory.CANCELLED, "Projection startup cancelled")) {
        val pending = synchronized(lock) {
            if (isCancelled) return
            cancellation = reason
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}
