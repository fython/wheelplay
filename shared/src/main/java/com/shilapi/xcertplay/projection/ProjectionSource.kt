package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.media.MediaControlSource
import com.shilapi.xcertplay.media.MediaOutputControl
import java.util.concurrent.CompletableFuture

/** Coordinates refer to the displayed content, excluding letterboxing. IDs survive pointer lifts. */
data class ProjectionContact(val id: Int, val x: Double, val y: Double, val down: Boolean)

enum class VideoIngressKind { ENCODED, SURFACE, FRAMEBUFFER }
enum class ProjectionResource { USB, WIRELESS, MICROPHONE, DISPLAY }
enum class ProjectionCommandResult { SENT, UNSUPPORTED, UNAVAILABLE, FAILED }
data class ProjectionCapabilities(
    val video: Set<VideoIngressKind>,
    val maxTouchContacts: Int = 0,
    val nightMode: Boolean = false,
    val audioPlayback: Boolean = false,
    val microphone: Boolean = false,
) {
    init { require(maxTouchContacts in 0..10) }
}

data class SourceDescriptor(val id: String, val displayName: String)
sealed class SourceAvailability {
    data object Ready : SourceAvailability()
    data class Unavailable(val reason: String) : SourceAvailability()
}

/** One connection attempt. Implementations must capture their own transport, never look up another source. */
interface ProjectionSession {
    val descriptor: SourceDescriptor
    val capabilities: ProjectionCapabilities
    val inputReady: Boolean
    val statusText: String? get() = null
    val media: MediaControlSource? get() = null
    val mediaOutput: MediaOutputControl? get() = null
    /** Nonblocking admission; true means accepted, not acknowledged by the remote device. */
    fun touch(contacts: List<ProjectionContact>): Boolean
    fun setNightMode(night: Boolean): ProjectionCommandResult = ProjectionCommandResult.UNSUPPORTED
    /** Completes only after transport, media producers and platform resources have stopped. */
    fun stop(): CompletableFuture<Unit>
}

/** Opening failed and cleanup could not prove that platform resources were released. */
class ProjectionCleanupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

interface ProjectionSourceProvider {
    val descriptor: SourceDescriptor
    val resources: Set<ProjectionResource>
    /** No discovery or network changes in this method. */
    fun probe(): SourceAvailability
    /** Failure completes only after partial startup resources have been cleaned up. */
    fun open(): CompletableFuture<ProjectionSession>
}
