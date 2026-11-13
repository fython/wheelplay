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
        override fun startEncoded(token: String, output: ParcelFileDescriptor) { starts++; output.close(); host.onState(token, ProjectionPlugin.READY, "Ready") }
        override fun touch(token: String, ids: IntArray, xs: DoubleArray, ys: DoubleArray, down: BooleanArray) { touches.offer(ids) }
        val displayRequests = LinkedBlockingQueue<Triple<Long, Int, Int>>()
        override fun updateDisplaySize(token: String, requestId: Long, width: Int, height: Int) {
            displayRequests.add(Triple(requestId, width, height))
        }
        override fun setNightMode(token: String, night: Boolean) = Unit
        override fun stop(token: String) { stops++ }
        fun acknowledge(clean: Boolean = true) = host.onStopped(token, clean, "Stopped")
    }
    private inner class Binding(val peer: Peer) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        var bindIntent: Intent? = null
        var binds = 0
        var unbinds = 0
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindIntent = intent
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
    @Test fun hostUiRequestsRequireNegotiationCurrentSessionAndValidToken() {
        for (version in listOf(0, 1)) {
            val peer = Peer().apply { info = PluginVideoInfo(640, 360, hostUiVersion = version).toBundle() }
            val binding = Binding(peer); val runtime = ProjectionRuntime()
            var requests = 0; var allowed = true
            val session = RemotePluginSession(binding, plugin, runtime, { allowed }, { _, _ -> Output() },
                requestHostUi = { requests++ })
            session.activate(); until { session.inputReady }
            assertEquals(1, binding.bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_HOST_UI_VERSION, 0))
            fun request(token: String = peer.token) {
                peer.host.onHostUiRequested(token); shadowOf(Looper.getMainLooper()).idle()
            }
            request(); assertEquals(0, requests) // Not the runtime's active session yet.
            runtime.adopt(session, setOf(ProjectionResource.DISPLAY))
            request("retired-session"); assertEquals(0, requests)
            request(); assertEquals(version, requests)
            assertTrue(session.inputReady); assertEquals(0, peer.stops)
            allowed = false; request(); assertEquals(version, requests)
            allowed = true
            peer.host.onState(peer.token, ProjectionPlugin.CONNECTING, "Waiting")
            shadowOf(Looper.getMainLooper()).idle(); request(); assertEquals(version, requests)
            val stopped = runtime.stop(); until { peer.stops == 1 }
            request(); assertEquals(version, requests)
            peer.acknowledge(); until { stopped.isDone }
            request(); assertEquals(version, requests)
        }
    }

    @Test fun pairingCodeIsNegotiatedLocalOnlyReplacedAndClearedOnReadyOrStop() {
        for (version in listOf(0, 1)) {
            val peer = Peer().apply { info = PluginVideoInfo(640, 360, interactionVersion = version).toBundle() }
            val binding = Binding(peer); val runtime = ProjectionRuntime()
            val session = RemotePluginSession(binding, plugin, runtime, { true }, { _, _ -> Output() })
            runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate(); until { session.inputReady }
            assertEquals(1, binding.bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_INTERACTION_VERSION, 0))
            peer.host.onState(peer.token, ProjectionPlugin.CONNECTING, "Waiting for phone")
            shadowOf(Looper.getMainLooper()).idle()
            fun send(code: String, token: String = peer.token) {
                peer.host.onInteraction(token, PluginInteraction("pairing", code, "MiAutoCar").toBundle())
                shadowOf(Looper.getMainLooper()).idle()
            }
            send("123456", "retired-session"); assertNull(session.interaction)
            send("123456")
            if (version == 1) {
                assertEquals("123456", session.interaction?.pairingCode)
                assertEquals("Waiting for phone", session.statusText)
                send("654321"); assertEquals("654321", session.interaction?.pairingCode)
                peer.host.onInteraction(peer.token, Bundle().apply { putInt("version", 1); putString("phase", "pairing"); putString("pairingCode", "bad") })
                shadowOf(Looper.getMainLooper()).idle(); assertNull(session.interaction)
                send("123456")
                peer.host.onState(peer.token, ProjectionPlugin.READY, "Ready")
                shadowOf(Looper.getMainLooper()).idle(); assertNull(session.interaction)
                send("654321"); assertNull(session.interaction)
                peer.host.onState(peer.token, ProjectionPlugin.CONNECTING, "Waiting")
                shadowOf(Looper.getMainLooper()).idle(); send("123456")
            } else assertNull(session.interaction)
            val stopped = runtime.stop(); until { peer.stops == 1 }
            assertNull(session.interaction); send("654321"); assertNull(session.interaction)
            peer.acknowledge(); until { stopped.isDone }
        }
    }

    @Test fun viewportChangesCoalesceAndRestartOnlyAfterMatchingReplyAndCleanup() {
        val web = com.shilapi.xcertplay.web.WebSession
        val oldAdaptive = web.adaptiveBrowserSize; val oldViewport = web.currentBrowserViewport
        val runtime = ProjectionRuntime()
        val peers = mutableListOf<Peer>(); val bindings = mutableListOf<Binding>()
        fun newSession(): RemotePluginSession {
            val peer = Peer().apply { info = PluginVideoInfo(640, 360, processExitRequired = true, displaySizeVersion = 1).toBundle() }
            val binding = Binding(peer); peers.add(peer); bindings.add(binding)
            return RemotePluginSession(binding, plugin, runtime, { true }, { _, _ -> Output() })
        }
        runtime.register(object : ProjectionSourceProvider {
            override val descriptor = SourceDescriptor(plugin.id, "Plugin")
            override val resources = setOf(ProjectionResource.DISPLAY)
            override fun probe() = SourceAvailability.Ready
            override fun open() = CompletableFuture.completedFuture<ProjectionSession>(newSession())
        })
        try {
            web.setAdaptiveBrowserSize(true); web.reportBrowserViewport(1200, 800)
            val session = runtime.open(plugin.id).join(); until { session.inputReady }
            val peer = peers.single()
            assertEquals(1, bindings.single().bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_DISPLAY_SIZE_VERSION, 0))
            web.reportBrowserViewport(1400, 900); web.reportBrowserViewport(1600, 900)
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(peer.displayRequests.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            until { peer.displayRequests.size == 1 }
            val first = peer.displayRequests.remove(); assertEquals(1600, first.second)
            peer.host.onDisplaySizeResult(peer.token, first.first, false)
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, peer.stops)
            web.reportBrowserViewport(1800, 1000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            until { peer.displayRequests.size == 1 }
            val second = peer.displayRequests.remove()
            peer.host.onDisplaySizeResult("foreign", second.first, true)
            peer.host.onDisplaySizeResult(peer.token, first.first, true)
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, peer.stops)
            peer.host.onDisplaySizeResult(peer.token, second.first, true)
            until { peer.stops == 1 }; assertEquals(1, peers.size)
            web.reportBrowserViewport(1900, 1000) // Latest value wins even during shutdown.
            peer.acknowledge()
            until { bindings.first().unbinds == 1 }; assertEquals(1, peers.size)
            peer.deathRecipient!!.binderDied()
            until { peers.size == 2 && runtime.snapshot()?.inputReady == true }
            assertEquals(1900, bindings.last().bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_DISPLAY_WIDTH, 0))
            assertEquals(1000, bindings.last().bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_DISPLAY_HEIGHT, 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertTrue(peer.displayRequests.isEmpty()) // Old subscription was removed.
            val stopped = runtime.stop(); until { peers.last().stops == 1 }; peers.last().acknowledge()
            until { bindings.last().unbinds == 1 }; peers.last().deathRecipient!!.binderDied()
            until { stopped.isDone }; stopped.join()
        } finally {
            oldViewport?.let { web.reportBrowserViewport(it.first, it.second) }
            web.setAdaptiveBrowserSize(oldAdaptive)
        }
    }

    @Test fun adaptiveToggleNotifiesPluginWithoutReplacingCarPlayListener() {
        val web = com.shilapi.xcertplay.web.WebSession
        val oldAdaptive = web.adaptiveBrowserSize; val oldViewport = web.currentBrowserViewport
        val legacy = mutableListOf<Pair<Int?, Int?>>()
        val peer = Peer().apply { info = PluginVideoInfo(640, 360, displaySizeVersion = 1).toBundle() }
        val runtime = ProjectionRuntime(); val binding = Binding(peer)
        try {
            web.setAdaptiveBrowserSize(true); web.reportBrowserViewport(1400, 900)
            web.setBrowserViewportListener { w, h -> legacy.add(w to h) }
            val session = RemotePluginSession(binding, plugin, runtime, { true }, { _, _ -> Output() })
            runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate(); until { session.inputReady }
            web.setAdaptiveBrowserSize(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); until { peer.displayRequests.size == 1 }
            assertEquals(1280 to 720, peer.displayRequests.remove().let { it.second to it.third })
            web.reportBrowserViewport(1600, 1000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertTrue(peer.displayRequests.isEmpty())
            web.setAdaptiveBrowserSize(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); until { peer.displayRequests.size == 1 }
            assertEquals(1600 to 1000, peer.displayRequests.remove().let { it.second to it.third })
            assertEquals(listOf(1400 to 900, null to null, 1600 to 1000), legacy)
            val stopped = runtime.stop(); until { peer.stops == 1 }; peer.acknowledge(); until { stopped.isDone }
        } finally {
            web.setBrowserViewportListener(null)
            oldViewport?.let { web.reportBrowserViewport(it.first, it.second) }; web.setAdaptiveBrowserSize(oldAdaptive)
        }
    }

    @Test fun browserSizeIsPassedAtBindAndPluginStillChoosesOutputSize() {
        val web = com.shilapi.xcertplay.web.WebSession
        val oldAdaptive = web.adaptiveBrowserSize
        val oldViewport = web.currentBrowserViewport
        try {
            web.setAdaptiveBrowserSize(true)
            assertTrue(web.reportBrowserViewport(1440, 900))
            val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
            val session = RemotePluginSession(context, plugin, runtime, { true }, { w, h ->
                assertEquals(640, w); assertEquals(360, h); output
            })
            runtime.adopt(session, setOf(ProjectionResource.DISPLAY)); session.activate(); until { session.inputReady }
            assertEquals(1440, context.bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_DISPLAY_WIDTH, 0))
            assertEquals(900, context.bindIntent!!.getIntExtra(ProjectionPlugin.EXTRA_DISPLAY_HEIGHT, 0))
            web.reportBrowserViewport(1600, 1000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(peer.displayRequests.isEmpty()) // Older v2 plugin never receives the new transaction.
            val stopped = runtime.stop(); until { peer.stops == 1 }; peer.acknowledge(); until { stopped.isDone }; stopped.join()
        } finally {
            oldViewport?.let { web.reportBrowserViewport(it.first, it.second) }
            web.setAdaptiveBrowserSize(oldAdaptive)
        }
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
    @Test fun failedCleanupPreservesPluginDiagnosticAndKeepsResourcesQuarantined() {
        val peer = Peer(); val context = Binding(peer); val runtime = ProjectionRuntime(); val output = Output()
        val session = RemotePluginSession(context, plugin, runtime, { true }, { _, _ -> output })
        runtime.adopt(session, setOf(ProjectionResource.WIRELESS)); session.activate(); until { session.inputReady }
        val stopped = runtime.stop(session); until { peer.stops == 1 }
        peer.host.onStopped(peer.token, false, "SDK video session remains active")
        until { stopped.isDone }
        assertTrue(stopped.isCompletedExceptionally)
        assertTrue(session.statusText!!.contains("SDK video session remains active"))
        assertEquals(setOf(ProjectionResource.WIRELESS), runtime.resources())
        assertSame(session, runtime.snapshot())
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
