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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemotePluginSessionTest {
    private val component = ComponentName("example.plugin", "example.plugin.Source")
    private val plugin get() = InstalledProjectionPlugin(component, "Plugin", Process.myUid(), "signature", 1, 1, null, null)
    private class Output : ProjectionVideoOutput {
        val texture = SurfaceTexture(0)
        override val surface = Surface(texture)
        @Volatile var closed = false
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long = error("No encoded frames")
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long) = false
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot) = false
        override fun close() { closed = true; surface.release(); texture.release() }
    }
    private class Peer : IProjectionPlugin.Stub() {
        lateinit var host: IProjectionHost
        lateinit var token: String
        var info = PluginVideoInfo(640, 360, 5, true).toBundle()
        @Volatile var starts = 0
        @Volatile var stops = 0
        var deathRecipient: IBinder.DeathRecipient? = null
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { deathRecipient = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean { deathRecipient = null; return true }
        val touches = LinkedBlockingQueue<IntArray>()
        override fun connect(token: String, apiVersion: Int, host: IProjectionHost) {
            this.host = host; this.token = token; host.onInfo(token, info)
        }
        val cancellations = LinkedBlockingQueue<String>()
        override fun cancelInput(token: String) { cancellations.offer(token) }
        override fun start(token: String, surface: Surface) { starts++; host.onState(token, ProjectionPlugin.READY, "Ready") }
        override fun touch(token: String, ids: IntArray, xs: DoubleArray, ys: DoubleArray, down: BooleanArray) { touches.offer(ids) }
        override fun setNightMode(token: String, night: Boolean) = Unit
        override fun stop(token: String) { stops++ }
        fun acknowledge(clean: Boolean = true) = host.onStopped(token, clean, "Stopped")
    }
    private inner class Binding(val peer: Peer) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        var binds = 0
        var unbinds = 0
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(component, intent.component)
            connection = conn; binds++
            Handler(Looper.getMainLooper()).post { conn.onServiceConnected(component, peer) }
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbinds++ }
    }
    private fun until(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("Timed out", predicate())
    }
    @Test fun nativeCancelIsCapabilityGatedAndLegacyPluginsReceiveOnlyRelease() {
        for (native in listOf(false, true)) {
            val peer = Peer().apply { info = PluginVideoInfo(640, 360, 5, nativeCancelInput = native).toBundle() }
            val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
            val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output })
            runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate(); until { session.inputReady }
            assertTrue(runtime.cancelInput(session, ProjectionCancelReason.FOCUS_LOST))
            if (native) {
                assertEquals(peer.token, peer.cancellations.poll(2, TimeUnit.SECONDS))
                assertTrue(peer.touches.isEmpty())
            } else {
                assertArrayEquals(intArrayOf(), peer.touches.poll(2, TimeUnit.SECONDS))
                assertTrue(peer.cancellations.isEmpty())
            }
            val stopped = runtime.stop(); until { peer.stops == 1 }; peer.acknowledge(); until { stopped.isDone }; stopped.join()
        }
    }

    @Test fun touchAndStopWaitForAcknowledgementAndIgnoreStaleCallbacks() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate()
        until { session.inputReady }
        assertTrue(runtime.touch(session, listOf(ProjectionContact(4, .25, .75, true))))
        assertArrayEquals(intArrayOf(4), peer.touches.poll(2, TimeUnit.SECONDS))
        val stopped = runtime.stop(session)
        until { peer.stops == 1 }
        assertFalse(stopped.isDone); assertFalse(output.closed); assertFalse(session.inputReady)
        assertEquals(setOf(ProjectionResource.WIRELESS), runtime.resources())
        peer.host.onStopped("old-token", true, "Old session")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(stopped.isDone)
        peer.acknowledge()
        until { stopped.isDone }
        stopped.join(); assertTrue(output.closed); assertNull(runtime.snapshot()); assertEquals(1, context.unbinds)
        peer.host.onState(peer.token, ProjectionPlugin.READY, "Late")
        shadowOf(Looper.getMainLooper()).idle(); assertFalse(session.inputReady)
    }
    @Test fun missingStopAcknowledgementQuarantinesResourcesAndRepeatedStopCannotExtendDeadline() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output }, stopTimeoutMs = 100)
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate(); until { session.inputReady }
        val stopped = runtime.stop(session); until { peer.stops == 1 }
        session.stop(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        until { stopped.isDone }
        assertTrue(stopped.isCompletedExceptionally); assertSame(session, runtime.snapshot())
        assertEquals(setOf(ProjectionResource.WIRELESS), runtime.resources()); assertTrue(output.closed)
        peer.acknowledge(); shadowOf(Looper.getMainLooper()).idle()
        assertSame(session, runtime.snapshot()); assertFalse(session.inputReady)
    }
    @Test fun serviceDeathAfterStartCannotReleaseSdkResources() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output })
        runtime.adopt(session, setOf(ProjectionResource.USB)); session.activate(); until { session.inputReady }
        context.connection.onServiceDisconnected(component)
        until { output.closed }
        val stopped = runtime.stop(session); until { stopped.isDone }
        assertTrue(stopped.isCompletedExceptionally); assertSame(session, runtime.snapshot())
    }
    @Test fun stopBeforeActivationNeverBindsAndInvalidHandshakeNeverCreatesOutput() {
        val context = Binding(Peer()); val runtime = ProjectionRuntime()
        val unused = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> error("No output") })
        val stopped = unused.stop(); unused.activate(); until { stopped.isDone }; stopped.join()
        assertEquals(0, context.binds)
        context.peer.info.putInt("apiVersion", 99)
        val invalid = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> error("No output") })
        runtime.adopt(invalid, emptySet()); invalid.activate(); until { runtime.snapshot() == null }
        assertEquals(0, context.peer.starts)
    }
    @Test fun callbackFromUnexpectedUidCannotStartVideoEvenWithCorrectToken() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime()
        val session = RemotePluginSession(context, plugin.copy(uid = Process.myUid() + 1), runtime, { true },
            { _, _ -> error("Untrusted callback must not create output") }, connectTimeoutMs = 50)
        runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate()
        until { context.binds == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(51))
        until { runtime.snapshot() == null }
        assertEquals(0, peer.starts); assertFalse(session.inputReady); assertTrue(runtime.resources().isEmpty())
    }
    @Test fun stopDuringSurfaceAllocationClosesLateOutputWithoutStartingProducer() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val allocating = CountDownLatch(1); val proceed = CountDownLatch(1)
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ ->
            allocating.countDown(); check(proceed.await(3, TimeUnit.SECONDS)); output
        })
        runtime.adopt(session, emptySet()); session.activate()
        until { allocating.count == 0L }
        val stopped = runtime.stop(session); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(stopped.isDone); proceed.countDown(); until { stopped.isDone }
        stopped.join(); assertTrue(output.closed); assertEquals(0, peer.starts); assertNull(runtime.snapshot())
    }
    @Test fun dedicatedProcessAcknowledgementMustBeFollowedByBinderDeath() {
        val peer = Peer().apply { info = PluginVideoInfo(640, 360, processExitRequired = true).toBundle() }
        val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate(); until { session.inputReady }
        val stopped = runtime.stop(session); until { peer.stops == 1 }
        peer.acknowledge(); until { context.unbinds == 1 }
        assertFalse(stopped.isDone); assertFalse(output.closed)
        context.connection.onServiceDisconnected(component)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(stopped.isDone); assertFalse(session.inputReady)
        assertEquals(setOf(ProjectionResource.WIRELESS), runtime.resources())
        peer.deathRecipient!!.binderDied()
        until { stopped.isDone }; stopped.join()
        assertTrue(output.closed); assertNull(runtime.snapshot()); assertEquals(1, context.unbinds)
    }
    @Test fun processThatAcknowledgesButDoesNotExitIsQuarantined() {
        val peer = Peer().apply { info = PluginVideoInfo(640, 360, processExitRequired = true).toBundle() }
        val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output }, stopTimeoutMs = 100)
        runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate(); until { session.inputReady }
        val stopped = runtime.stop(session); until { peer.stops == 1 }
        peer.acknowledge(); until { context.unbinds == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        until { stopped.isDone }
        assertTrue(stopped.isCompletedExceptionally); assertSame(session, runtime.snapshot()); assertTrue(output.closed)
    }
}
