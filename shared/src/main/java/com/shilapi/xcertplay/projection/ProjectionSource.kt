package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.media.MediaControlSource
import com.shilapi.xcertplay.media.MediaOutputControl
import java.util.concurrent.CompletableFuture

/** Coordinates refer to the displayed content, excluding letterboxing. IDs survive pointer lifts. */
data class ProjectionContact(val id: Int, val x: Double, val y: Double, val down: Boolean)

/** Desktop input uses normalized coordinates, DOM button bits and semantic key names/Unicode. */
sealed interface ProjectionInput {
    data class Pointer(val x: Double, val y: Double, val buttons: Int) : ProjectionInput
    data class Scroll(val x: Double, val y: Double, val dx: Int, val dy: Int) : ProjectionInput
    data class Key(val key: String, val down: Boolean) : ProjectionInput
    data object Release : ProjectionInput
    data object KeepAlive : ProjectionInput
}

enum class VideoIngressKind { ENCODED, SURFACE, FRAMEBUFFER }
enum class ProjectionResource { USB, WIRELESS, MICROPHONE, DISPLAY }
enum class ProjectionCommandResult { SENT, UNSUPPORTED, UNAVAILABLE, FAILED }
data class ProjectionCapabilities(
    val video: Set<VideoIngressKind>,
    val maxTouchContacts: Int = 0,
    val nightMode: Boolean = false,
    val audioPlayback: Boolean = false,
    val microphone: Boolean = false,
    /** Source-managed audio may be available locally without a browser route. */
    val browserAudio: Boolean = audioPlayback,
    val browserMicrophone: Boolean = microphone,
    val pointerInput: Boolean = false,
    val keyboardInput: Boolean = false,
    /** Discrete Android Back; does not imply general keyboard/text support. */
    val backButton: Boolean = false,
    val cancellation: ProjectionCancellation = ProjectionCancellation.RELEASE_ONLY,
) {
    init {
        require(maxTouchContacts in 0..10)
        require(!browserAudio || audioPlayback)
        require(!browserMicrophone || microphone)
    }
}

data class SourceDescriptor(val id: String, val displayName: String)
sealed class SourceAvailability {
    data object Ready : SourceAvailability()
    data class Unavailable(val reason: String, val failure: ProjectionFailure = ProjectionFailure(ProjectionErrorCategory.UNAVAILABLE, reason)) : SourceAvailability()
}

/** One connection attempt. Implementations must capture their own transport, never look up another source. */
interface ProjectionSession {
    val descriptor: SourceDescriptor
    val capabilities: ProjectionCapabilities
    val inputReady: Boolean
    val statusText: String? get() = null
    val state: ProjectionSessionState get() = ProjectionSessionState(
        if (inputReady) ProjectionPhase.RUNNING else ProjectionPhase.CONNECTING, capabilities, inputReady)
    val media: MediaControlSource? get() = null
    val mediaOutput: MediaOutputControl? get() = null
    /** Called after runtime admission. Start asynchronous producers here; stop may race activation. */
    fun activate() = Unit
    /** Nonblocking admission; true means accepted, not acknowledged by the remote device. */
    fun touch(contacts: List<ProjectionContact>): Boolean
    fun input(event: ProjectionInput): Boolean = false
    /** Nonblocking cancellation admission; normal touch(emptyList()) still means finger-up. */
    fun cancelInput(reason: ProjectionCancelReason): Boolean {
        val touchReleased = touch(emptyList())
        val keysReleased = input(ProjectionInput.Release)
        return touchReleased || keysReleased
    }
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
    /** Complete only after acquisition or failed/cancelled acquisition cleanup. Runtime disposes late sessions without activation. */
    fun open(startup: ProjectionStartup): CompletableFuture<ProjectionSession> {
        startup.checkActive()
        return open()
    }
}
