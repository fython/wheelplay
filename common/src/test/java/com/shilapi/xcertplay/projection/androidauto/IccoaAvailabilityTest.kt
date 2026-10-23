package com.shilapi.xcertplay.projection.androidauto

import com.shilapi.xcertplay.projection.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IccoaAvailabilityTest {
    @Test fun missingSdkCannotDisconnectCurrentProjection() {
        val runtime = ProjectionRuntime()
        val current = object : ProjectionSession {
            override val descriptor = SourceDescriptor("playing", "Playing")
            override val capabilities = ProjectionCapabilities(emptySet(), audioPlayback = true, browserAudio = false)
            override val inputReady = false
            override fun touch(contacts: List<ProjectionContact>) = false
            override fun stop(): CompletableFuture<Unit> = error("Must keep current source")
        }
        runtime.adopt(current, setOf(ProjectionResource.WIRELESS))
        val source = IccoaSource(RuntimeEnvironment.getApplication())
        runtime.register(source)
        assertTrue(source.probe() is SourceAvailability.Unavailable)
        assertTrue(runtime.switchTo(source.descriptor.id).isCompletedExceptionally)
        assertSame(current, runtime.snapshot())
        assertTrue(current.capabilities.audioPlayback)
        assertFalse(current.capabilities.browserAudio)
    }
}
