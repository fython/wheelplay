package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.CarPlayBackgroundSession
import java.util.concurrent.CompletableFuture

/** The only projection adapter that knows about the legacy CarPlay controller. */
internal class CarPlaySourceSession(private val status: () -> String?) : ProjectionSession {
    @Volatile private var controller: com.shilapi.xcertplay.orchestration.CarPlayController? = null
    @Volatile private var sink: com.shilapi.xcertplay.media.AndroidMediaSink? = null
    private val closed = CompletableFuture<Unit>()
    @Volatile private var stopping = false
    fun bind(controller: com.shilapi.xcertplay.orchestration.CarPlayController?, sink: com.shilapi.xcertplay.media.AndroidMediaSink?) {
        this.controller = controller
        this.sink = sink
        projectionSessions.changed(this)
    }
    fun completed(clean: Boolean) {
        if (clean) closed.complete(Unit)
        else {
            val failure = ProjectionFailure(ProjectionErrorCategory.CLEANUP, "CarPlay transport did not stop", "carplay")
            lifecycleFailure = failure
            closed.completeExceptionally(ProjectionException(failure))
        }
        projectionSessions.changed(this)
    }
    @Volatile private var lifecycleFailure: ProjectionFailure? = null
    override val state get() = ProjectionSessionState(
        if (closed.isDone) { if (lifecycleFailure == null) ProjectionPhase.STOPPED else ProjectionPhase.FAILED }
        else if (stopping) ProjectionPhase.STOPPING
        else if (inputReady) ProjectionPhase.RUNNING else ProjectionPhase.CONNECTING,
        capabilities, inputReady, lifecycleFailure)
    override val statusText get() = status()
    override val descriptor = SourceDescriptor(ProjectionSourceIds.CARPLAY, "CarPlay")
    override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.ENCODED), 2,
        nightMode = true, audioPlayback = true, microphone = true)
    override val inputReady get() = !stopping && !closed.isDone && controller?.touchReady == true
    override val media get() = controller
    override val mediaOutput get() = sink
    override fun touch(contacts: List<ProjectionContact>): Boolean {
        if (contacts.any { it.id !in 0..1 }) return false
        // The CarPlay HID device has fixed slots, even when the first finger is released.
        return controller?.sendTouch((0..1).map { id ->
            contacts.find { it.id == id } ?: ProjectionContact(id, 0.0, 0.0, false)
        }) ?: false
    }
    override fun cancelInput(reason: ProjectionCancelReason) = touch(emptyList())
    override fun setNightMode(night: Boolean) =
        if (controller?.setNightMode(night) == true) ProjectionCommandResult.SENT else ProjectionCommandResult.UNAVAILABLE
    override fun stop(): CompletableFuture<Unit> {
        stopping = true
        CarPlayBackgroundSession.stop()
        return closed
    }
}
