package com.shilapi.xcertplay.projection

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class ProjectionRuntimeTest {
    private class Session(val id: String) : ProjectionSession {
        override val descriptor = SourceDescriptor(id, id)
        override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.FRAMEBUFFER), 5)
        override val inputReady = true
        val reports = mutableListOf<List<ProjectionContact>>()
        val stopped = CompletableFuture<Unit>()
        var stopCalls = 0
        override fun touch(contacts: List<ProjectionContact>): Boolean { reports += contacts; return true }
        override fun stop(): CompletableFuture<Unit> { stopCalls++; return stopped }
    }
    private class Provider(val id: String) : ProjectionSourceProvider {
        override val descriptor = SourceDescriptor(id, id)
        override val resources = setOf(ProjectionResource.DISPLAY)
        val opened = CompletableFuture<ProjectionSession>()
        var opens = 0
        var available: SourceAvailability = SourceAvailability.Ready
        override fun probe() = available
        override fun open(): CompletableFuture<ProjectionSession> { opens++; return opened }
    }
    private val runtime = ProjectionRuntime()
    private val old = Session("old")
    private val next = Provider("next")
    private val finger = ProjectionContact(0, .2, .8, true)
    private fun setup() { runtime.adopt(old, setOf(ProjectionResource.DISPLAY)); runtime.register(next) }

    @Test fun backCapabilityDoesNotGrantKeyboardAndRejectsPreviousSession() {
        val keys = mutableListOf<ProjectionInput>()
        val desktop = object : ProjectionSession {
            override val descriptor = SourceDescriptor("desktop", "desktop")
            override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE), 10, backButton = true)
            override val inputReady = true
            override fun touch(contacts: List<ProjectionContact>) = true
            override fun input(event: ProjectionInput): Boolean { keys += event; return true }
            override fun stop() = CompletableFuture.completedFuture(Unit)
        }
        runtime.adopt(desktop, setOf(ProjectionResource.DISPLAY))
        assertTrue(runtime.input(desktop, ProjectionInput.Key("BrowserBack", true)))
        assertFalse(runtime.input(desktop, ProjectionInput.Key("a", true)))
        assertFalse(runtime.input(desktop, ProjectionInput.Key("Home", true)))
        assertTrue(runtime.input(desktop, ProjectionInput.Release))
        runtime.stop().join()
        runtime.adopt(old, emptySet())
        assertFalse(runtime.input(desktop, ProjectionInput.Key("BrowserBack", true)))
        assertFalse(runtime.touch(desktop, listOf(finger)))
        assertEquals(1, keys.count { it == ProjectionInput.Key("BrowserBack", true) })
    }

    @Test fun discoveryRemovalCannotReleaseAnActiveSessionOrReplaceBuiltins() {
        setup()
        val plugin = Provider("plugin-example")
        runtime.replaceProviders("plugin-", listOf(plugin))
        assertEquals(setOf("next", "plugin-example"), runtime.sources().map { it.descriptor.id }.toSet())
        runtime.replaceProviders("plugin-", emptyList())
        assertEquals(listOf("next"), runtime.sources().map { it.descriptor.id })
        assertSame(old, runtime.snapshot())
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        assertThrows(IllegalArgumentException::class.java) { runtime.replaceProviders("plugin-", listOf(next)) }
        assertEquals(listOf("next"), runtime.sources().map { it.descriptor.id })
    }

    @Test fun switchingCancelsBeforeStopAndWaitsForResourceRelease() {
        setup()
        assertTrue(runtime.touch(old, listOf(finger)))
        val switched = runtime.switchTo("next")
        assertTrue(old.reports.last().isEmpty())
        assertFalse(runtime.touch(old, listOf(finger)))
        assertEquals(0, next.opens)
        assertFalse(switched.isDone)
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        old.stopped.complete(Unit)
        assertEquals(1, next.opens)
        val active = Session("next")
        next.opened.complete(active)
        assertSame(active, switched.join())
        assertFalse(runtime.touch(old, listOf(finger)))
        runtime.ended(old)
        runtime.stop(old).join()
        assertEquals(ProjectionCommandResult.UNAVAILABLE, runtime.setNightMode(old, true))
        assertEquals(ProjectionCommandResult.UNSUPPORTED, runtime.setNightMode(active, true))
        assertEquals(0, active.stopCalls)
        assertSame(active, runtime.snapshot())
        assertTrue(runtime.touch(active, listOf(finger)))
    }
    @Test fun failedStopKeepsResourcesAndInputDisabled() {
        setup()
        val switched = runtime.switchTo("next")
        old.stopped.completeExceptionally(IllegalStateException("still writing"))
        assertTrue(switched.isCompletedExceptionally)
        assertSame(old, runtime.snapshot())
        assertFalse(runtime.inputReady(old))
        assertFalse(runtime.touch(old, listOf(finger)))
        assertTrue(runtime.open("next").isCompletedExceptionally)
        assertEquals(0, next.opens)
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
    }
    @Test fun failedProbeDoesNotTearDownCurrentSource() {
        setup(); next.available = SourceAvailability.Unavailable("SDK missing")
        assertTrue(runtime.switchTo("next").isCompletedExceptionally)
        assertEquals(0, old.stopCalls)
        assertTrue(runtime.inputReady(old))
    }
    @Test fun stopDuringOpeningWaitsAndIsIdempotent() {
        runtime.register(next)
        runtime.open("next")
        runtime.stop(old).join() // Late old-source timeout must not affect the opening source.
        val stopped = runtime.stop()
        assertFalse(stopped.isDone)
        val active = Session("next")
        next.opened.complete(active)
        assertEquals(1, active.stopCalls)
        val repeated = runtime.stop()
        active.stopped.complete(Unit)
        stopped.join(); repeated.join()
        assertNull(runtime.snapshot())
        assertTrue(runtime.resources().isEmpty())
    }
    @Test fun openingFailureReleasesReservation() {
        runtime.register(next)
        val opened = runtime.open("next")
        assertTrue(runtime.hasSession())
        assertFalse(runtime.adopt(old, emptySet()))
        next.opened.completeExceptionally(IllegalStateException("connection refused"))
        assertTrue(opened.isCompletedExceptionally)
        assertFalse(runtime.hasSession())
        assertTrue(runtime.resources().isEmpty())
        assertTrue(runtime.adopt(old, emptySet()))
    }
    @Test fun failedStartupCleanupCannotReleaseResourcesOrAdmitAnotherSource() {
        runtime.register(next)
        runtime.open("next")
        next.opened.completeExceptionally(ProjectionCleanupException("GL thread still running"))
        assertTrue(runtime.hasSession())
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        assertTrue(runtime.stop().isCompletedExceptionally)
        assertTrue(runtime.open("next").isCompletedExceptionally)
        assertFalse(runtime.adopt(old, emptySet()))
    }
    @Test fun inputsRespectSourceCapabilitiesAndCoordinates() {
        setup()
        assertTrue(runtime.touch(old, (0..4).map { finger.copy(id = it) }))
        assertFalse(runtime.touch(old, (0..5).map { finger.copy(id = it) }))
        assertFalse(runtime.touch(old, listOf(finger, finger)))
        assertFalse(runtime.touch(old, listOf(finger.copy(x = Double.NaN))))
        assertFalse(runtime.touch(old, listOf(finger.copy(y = 1.1))))
        assertFalse(runtime.touch(old, listOf(finger.copy(id = -1))))
    }
    @Test fun activationRunsAfterAdmissionAndFailureWaitsForCleanup() {
        val runtime = ProjectionRuntime()
        val closed = CompletableFuture<Unit>()
        val session = object : ProjectionSession {
            override val descriptor = SourceDescriptor("activate", "Activate")
            override val capabilities = ProjectionCapabilities(emptySet())
            override val inputReady = false
            override fun activate() {
                assertSame(this, runtime.snapshot())
                throw IllegalStateException("failed after admission")
            }
            override fun touch(contacts: List<ProjectionContact>) = false
            override fun stop() = closed
        }
        runtime.register(object : ProjectionSourceProvider {
            override val descriptor = session.descriptor
            override val resources = setOf(ProjectionResource.USB)
            override fun probe() = SourceAvailability.Ready
            override fun open() = CompletableFuture.completedFuture<ProjectionSession>(session)
        })
        val result = runtime.open("activate")
        assertFalse(result.isDone)
        assertSame(session, runtime.snapshot())
        closed.complete(Unit)
        assertTrue(result.isCompletedExceptionally)
        assertNull(runtime.snapshot())
    }

}
