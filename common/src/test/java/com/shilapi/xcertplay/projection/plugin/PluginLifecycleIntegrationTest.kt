package com.shilapi.xcertplay.projection.plugin

import android.content.*
import android.graphics.SurfaceTexture
import android.os.*
import android.view.Surface
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.projection.*
import moe.feng.wheelplay.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.*

/** Runs the real SDK service and host session together with an in-process Binder transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PluginLifecycleIntegrationTest {
    class Plugin : ProjectionPluginService() {
        lateinit var events: Events
        var rejectHandshake = false
        var reportReady = true
        val started = CountDownLatch(1)
        val cleanup = CompletableFuture<Unit>()
        val cleanupStarted = CountDownLatch(1)
        override fun isHostAllowed(uid: Int) = true
        override fun videoInfo(): PluginVideoInfo {
            check(!rejectHandshake) { "Nearby devices permission missing" }
            return PluginVideoInfo(640, 360)
        }
        override fun onStartProjection(surface: Surface, events: Events) {
            this.events = events; started.countDown()
            if (reportReady) events.state(ProjectionPlugin.READY, "Ready")
        }
        override fun onStopProjection(): CompletableFuture<Unit> {
            cleanupStarted.countDown(); return cleanup
        }
    }
    private class Output : ProjectionVideoOutput {
        val texture = SurfaceTexture(0)
        override val surface = Surface(texture)
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long = 0
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long) = false
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot) = false
        override fun close() { surface.release(); texture.release() }
    }
    private val component = ComponentName("example.plugin", "example.plugin.Source")
    private inner class Binding(val plugin: Plugin) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var unbinds = 0
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val binder = plugin.onBind(Intent(ProjectionPlugin.ACTION))!!
            Handler(Looper.getMainLooper()).post { connection.onServiceConnected(component, binder) }
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++; plugin.onUnbind(Intent(ProjectionPlugin.ACTION)) }
    }
    private fun until(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Timed out", predicate())
    }
    @Test fun spontaneousDisconnectRevokesInputAndTimesOutStalledCleanup() {
        val controller = Robolectric.buildService(Plugin::class.java).create()
        val plugin = controller.get(); val binding = Binding(plugin); val runtime = ProjectionRuntime()
        val descriptor = InstalledProjectionPlugin(component, "Plugin", Process.myUid(), "signature", 1, 1, null, null)
        val failures = mutableListOf<String>()
        val session = RemotePluginSession(binding, descriptor, runtime, { true }, { _, _ -> Output() },
            stopTimeoutMs = 100, onFailure = { failures.add(it) })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate()
        try {
            until { session.inputReady }
            plugin.events.ended("Phone disconnected")
            assertTrue(plugin.cleanupStarted.await(3, TimeUnit.SECONDS))
            until { !session.inputReady }
            assertFalse(runtime.touch(session, listOf(ProjectionContact(0, .5, .5, true))))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
            until { binding.unbinds == 1 && failures.size == 2 }
            assertSame(session, runtime.snapshot())
            assertEquals(setOf(ProjectionResource.WIRELESS), runtime.resources())
            val stopped = runtime.stop(session); until { stopped.isDone }
            assertTrue(stopped.isCompletedExceptionally)
            assertTrue(failures.last().contains("Phone disconnected"))
        } finally {
            plugin.cleanup.complete(Unit); controller.destroy()
        }
    }

    @Test fun handshakeRejectionIsReportedButSuccessfulCleanupStillReleasesResources() {
        val controller = Robolectric.buildService(Plugin::class.java).create()
        val plugin = controller.get().apply { rejectHandshake = true }
        val binding = Binding(plugin); val runtime = ProjectionRuntime()
        val descriptor = InstalledProjectionPlugin(component, "Plugin", Process.myUid(), "signature", 1, 1, null, null)
        val failures = mutableListOf<String>()
        val session = RemotePluginSession(binding, descriptor, runtime, { true }, { _, _ -> Output() },
            onFailure = { failures.add(it) })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate()
        try {
            until { binding.unbinds == 1 && runtime.snapshot() == null }
            assertEquals(listOf("Nearby devices permission missing"), failures)
            assertEquals("Nearby devices permission missing", session.statusText)
            assertTrue(runtime.resources().isEmpty())
            session.stop().join()
        } finally { controller.destroy() }
    }

    @Test fun pluginErrorBeforeReadyRetainsReasonAfterSuccessfulCleanup() {
        val controller = Robolectric.buildService(Plugin::class.java).create()
        val plugin = controller.get().apply { reportReady = false }
        val binding = Binding(plugin); val runtime = ProjectionRuntime()
        val descriptor = InstalledProjectionPlugin(component, "Plugin", Process.myUid(), "signature", 1, 1, null, null)
        val failures = mutableListOf<String>()
        val session = RemotePluginSession(binding, descriptor, runtime, { true }, { _, _ -> Output() },
            onFailure = { failures.add(it) })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate()
        try {
            until { plugin.started.count == 0L }
            plugin.events.state(ProjectionPlugin.ERROR, "SDK initialization failed")
            assertTrue(plugin.cleanupStarted.await(3, TimeUnit.SECONDS))
            until { failures.isNotEmpty() }
            plugin.cleanup.complete(Unit)
            until { runtime.snapshot() == null }
            assertEquals(listOf("SDK initialization failed"), failures)
            assertTrue(runtime.resources().isEmpty())
            session.stop().join() // Failure to start must not poison a clean resource release.
        } finally { plugin.cleanup.complete(Unit); controller.destroy() }
    }

    @Test fun explicitStopDoesNotReportAFailure() {
        val controller = Robolectric.buildService(Plugin::class.java).create()
        val plugin = controller.get(); val binding = Binding(plugin); val runtime = ProjectionRuntime()
        val descriptor = InstalledProjectionPlugin(component, "Plugin", Process.myUid(), "signature", 1, 1, null, null)
        val failures = mutableListOf<String>()
        val session = RemotePluginSession(binding, descriptor, runtime, { true }, { _, _ -> Output() },
            onFailure = { failures.add(it) })
        runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate()
        try {
            until { session.inputReady }
            val stopped = runtime.stop(session)
            until { plugin.cleanupStarted.count == 0L }
            assertFalse(stopped.isDone)
            plugin.cleanup.complete(Unit)
            until { stopped.isDone }; stopped.join()
            assertTrue(failures.isEmpty()); assertNull(runtime.snapshot())
        } finally { plugin.cleanup.complete(Unit); controller.destroy() }
    }
}
