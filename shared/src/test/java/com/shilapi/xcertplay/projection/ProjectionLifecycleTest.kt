package com.shilapi.xcertplay.projection

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProjectionLifecycleTest {
    private class Session : ProjectionSession {
        override val descriptor = SourceDescriptor("source", "Source")
        override val capabilities = ProjectionCapabilities(emptySet())
        override val inputReady = false // Read-only readiness must not depend on input.
        var phase = ProjectionPhase.CONNECTING
        var error: ProjectionFailure? = null
        override val state get() = ProjectionSessionState(phase, capabilities, failure = error)
        var activations = 0
        var cancellations = 0
        var stops = 0
        val released = CompletableFuture<Unit>()
        override fun activate() { activations++ }
        override fun touch(contacts: List<ProjectionContact>) = error("Cancellation must not synthesize finger-up")
        override fun cancelInput(reason: ProjectionCancelReason): Boolean { cancellations++; return true }
        override fun stop(): CompletableFuture<Unit> { stops++; return released }
    }
    private class Provider : ProjectionSourceProvider {
        override val descriptor = SourceDescriptor("source", "Source")
        override val resources = setOf(ProjectionResource.DISPLAY)
        val pending = CompletableFuture<ProjectionSession>()
        val cancelled = CountDownLatch(1)
        override fun probe() = SourceAvailability.Ready
        override fun open() = pending
        override fun open(startup: ProjectionStartup): CompletableFuture<ProjectionSession> {
            startup.onCancel { cancelled.countDown() }
            return pending
        }
    }

    @Test fun cancellationSignalsAcquisitionButWaitsForLateSessionCleanupWithoutActivation() {
        val runtime = ProjectionRuntime(); val provider = Provider(); runtime.register(provider)
        val opening = runtime.open("source")
        val stopping = runtime.stop()
        assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS))
        assertFalse(stopping.isDone)
        assertEquals(ProjectionCleanup.PENDING, runtime.stateSnapshot().cleanup)
        val session = Session(); provider.pending.complete(session)
        assertEquals(0, session.activations); assertEquals(1, session.stops)
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        session.released.complete(Unit); stopping.get(2, TimeUnit.SECONDS)
        assertTrue(opening.isCompletedExceptionally)
        assertFalse(runtime.hasSession())
        assertEquals(ProjectionErrorCategory.CANCELLED, runtime.stateSnapshot().failure!!.category)
        assertEquals(ProjectionCleanup.RELEASED, runtime.stateSnapshot().cleanup)
    }

    @Test fun cancellationTimeoutQuarantinesAndLateCompletionCannotReopenOrReleaseOwnership() {
        val runtime = ProjectionRuntime(startupTimeoutMs = 20, cleanupTimeoutMs = 30)
        val provider = Provider(); runtime.register(provider)
        val opening = runtime.open("source")
        assertTrue(provider.cancelled.await(2, TimeUnit.SECONDS))
        assertThrows(java.util.concurrent.ExecutionException::class.java) { opening.get(2, TimeUnit.SECONDS) }
        assertEquals(ProjectionCleanup.UNCONFIRMED, runtime.stateSnapshot().cleanup)
        val session = Session(); provider.pending.complete(session); session.released.complete(Unit)
        assertEquals(0, session.activations); assertEquals(1, session.stops)
        assertTrue(runtime.hasSession()); assertNull(runtime.snapshot())
        assertFalse(runtime.resources().isEmpty())
        assertTrue(runtime.open("source").isCompletedExceptionally)
    }

    @Test fun admissionAndReadOnlyReadinessAreSeparateAndTerminationRetainsTypedError() {
        val runtime = ProjectionRuntime(); val provider = Provider(); runtime.register(provider)
        val opening = runtime.open("source"); val session = Session(); provider.pending.complete(session)
        assertSame(session, opening.join()); assertEquals(1, session.activations)
        val ready = runtime.awaitReady(session); assertFalse(ready.isDone)
        session.phase = ProjectionPhase.RUNNING; runtime.changed(session)
        assertSame(session, ready.join()); assertFalse(runtime.stateSnapshot().inputReady)
        session.error = ProjectionFailure(ProjectionErrorCategory.PROTOCOL, "wire detail", "test", "42")
        session.phase = ProjectionPhase.FAILED; runtime.ended(session)
        assertNull(runtime.snapshot())
        assertEquals(session.error, runtime.stateSnapshot().failure)
        assertEquals(ProjectionPhase.FAILED, runtime.stateSnapshot().phase)
    }

    @Test fun readinessTimeoutCancelsInputAndStopsButDoesNotReleaseBeforeAcknowledgement() {
        val runtime = ProjectionRuntime(startupTimeoutMs = 30)
        val session = Session(); val stopped = CountDownLatch(1)
        runtime.subscribeState { if (it.phase == ProjectionPhase.STOPPING) stopped.countDown() }
        val provider = Provider(); runtime.register(provider)
        val opening = runtime.open("source"); provider.pending.complete(session); opening.join()
        val ready = runtime.awaitReady(session)
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertThrows(java.util.concurrent.ExecutionException::class.java) { ready.get(2, TimeUnit.SECONDS) }
        assertFalse(runtime.resources().isEmpty())
        session.released.complete(Unit)
        runtime.stop().get(2, TimeUnit.SECONDS)
        assertEquals(1, session.cancellations)
        assertEquals(ProjectionErrorCategory.TIMEOUT, runtime.stateSnapshot().failure!!.category)
    }

    @Test fun stopTimeoutRetainsOwnershipDespiteLateAcknowledgementAndEndedCallback() {
        val runtime = ProjectionRuntime(cleanupTimeoutMs = 30)
        val session = Session()
        runtime.adopt(session, setOf(ProjectionResource.DISPLAY))
        val stopping = runtime.stop()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { stopping.get(2, TimeUnit.SECONDS) }
        session.released.complete(Unit)
        runtime.ended(session)
        assertSame(session, runtime.snapshot())
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        assertEquals(ProjectionCleanup.UNCONFIRMED, runtime.stateSnapshot().cleanup)
        assertEquals(ProjectionErrorCategory.CLEANUP, runtime.stateSnapshot().failure!!.category)
        assertFalse(runtime.cancelInput(session, ProjectionCancelReason.USER_REQUEST))
    }

    @Test fun failedAcquisitionCleanupOverridesTheOriginalBackendErrorCategory() {
        val backend = ProjectionFailure(ProjectionErrorCategory.INPUT, "injection failed", "desktop", "11")
        val failure = projectionFailure(ProjectionCleanupException("producer still active", ProjectionException(backend)))
        assertEquals(ProjectionErrorCategory.CLEANUP, failure.category)
        assertEquals("desktop", failure.backend)
        assertEquals("11", failure.backendCode)
        assertTrue(failure.diagnostic.contains("producer still active"))
        assertTrue(failure.diagnostic.contains("injection failed"))
    }

    @Test fun cleanupFailureRemainsTypedAndCancellationBypassesInputCapabilities() {
        val runtime = ProjectionRuntime(); val session = Session(); runtime.adopt(session, setOf(ProjectionResource.DISPLAY))
        assertTrue(runtime.cancelInput(session, ProjectionCancelReason.FOCUS_LOST))
        val stopping = runtime.stop()
        session.released.completeExceptionally(IllegalStateException("producer still active"))
        assertTrue(stopping.isCompletedExceptionally)
        assertEquals(ProjectionErrorCategory.CLEANUP, runtime.stateSnapshot().failure!!.category)
        assertEquals(ProjectionCleanup.UNCONFIRMED, runtime.stateSnapshot().cleanup)
        assertSame(session, runtime.snapshot())
    }
}
