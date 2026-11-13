package moe.feng.wheelplay.plugin

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionPluginServiceTest {
    open class Service : ProjectionPluginService() {
        val displayHint get() = requestedDisplaySize
        var allowHost = true
        var nativeCancel = false
        var encoded = false
        var displaySizeVersion = 0
        var interactionVersion = 0
        var hostUiVersion = 0
        var audioVersion = 0
        var mediaVersion = 0
        val mediaCommands = LinkedBlockingQueue<Int>()
        override fun onMediaCommand(command: Int): CompletableFuture<Boolean> { mediaCommands.add(command); return CompletableFuture.completedFuture(true) }
        val audioAttached = CountDownLatch(1)
        var audioDescriptors: Pair<ParcelFileDescriptor, ParcelFileDescriptor>? = null
        override fun onAudioTransport(playback: ParcelFileDescriptor, microphone: ParcelFileDescriptor) {
            audioDescriptors = playback to microphone; audioAttached.countDown()
        }
        lateinit var sessionEvents: Events
        val displayRequests = LinkedBlockingQueue<Pair<Int, Int>>()
        override fun onDisplaySizeChanged(width: Int, height: Int): Boolean { displayRequests.add(width to height); return true }
        var encodedDescriptor: ParcelFileDescriptor? = null
        val cancellations = LinkedBlockingQueue<Boolean>()
        val started = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val stopped = CompletableFuture<Unit>()
        val touches = LinkedBlockingQueue<List<PluginContact>>()
        var beforeInfo: (() -> Unit)? = null
        override fun isHostAllowed(uid: Int) = allowHost
        override fun videoInfo(): PluginVideoInfo { beforeInfo?.invoke(); return PluginVideoInfo(640, 360, 2, true, nativeCancelInput = nativeCancel, encodedVideoVersion = if (encoded) 1 else 0, displaySizeVersion = displaySizeVersion, interactionVersion = interactionVersion, hostUiVersion = hostUiVersion, audioVersion = audioVersion, mediaVersion = mediaVersion) }
        override fun onStartProjection(surface: Surface, events: Events) {
            sessionEvents = events; started.countDown(); events.state(ProjectionPlugin.READY)
        }
        override fun onStartEncodedProjection(output: ParcelFileDescriptor, events: Events) {
            encodedDescriptor = output; started.countDown(); events.state(ProjectionPlugin.READY)
        }
        override fun onStopProjection(): CompletableFuture<Unit> { stopRequested.countDown(); return stopped }
        override fun onCancelInput() { cancellations.offer(true) }
        override fun onTouch(contacts: List<PluginContact>) { touches.add(contacts) }
    }
    class DedicatedService : Service() {
        override val terminateProcessAfterStop = true
        var processNameOverride: String? = null
        val exits = LinkedBlockingQueue<Boolean>()
        override fun projectionProcessName() = processNameOverride ?: "$packageName:projection"
        override fun exitProjectionProcess() { exits.add(true) }
    }
    private class Host : IProjectionHost.Stub() {
        val mediaStates = LinkedBlockingQueue<Bundle>()
        val mediaResults = LinkedBlockingQueue<Pair<Long, Boolean>>()
        override fun onMediaState(token: String, state: android.os.Bundle) { mediaStates.add(state) }
        override fun onMediaCommandResult(token: String, requestId: Long, sent: Boolean) { mediaResults.add(requestId to sent) }
        val info = LinkedBlockingQueue<Bundle>()
        val displayResults = LinkedBlockingQueue<Pair<Long, Boolean>>()
        override fun onDisplaySizeResult(token: String, requestId: Long, restartRequired: Boolean) { displayResults.add(requestId to restartRequired) }
        val ready = CountDownLatch(1)
        val stopped = LinkedBlockingQueue<Boolean>()
        val hostUiRequests = LinkedBlockingQueue<String>()
        override fun onHostUiRequested(token: String) { hostUiRequests.add(token) }
        val interactions = LinkedBlockingQueue<Bundle>()
        override fun onInteraction(token: String, interaction: android.os.Bundle) { interactions.add(interaction) }
        override fun onInfo(token: String, info: Bundle) { this.info.add(info) }
        override fun onState(token: String, state: Int, message: String) { if (state == ProjectionPlugin.READY) ready.countDown() }
        override fun onStopped(token: String, clean: Boolean, message: String) { stopped.add(clean) }
    }
    @Test fun mediaNegotiationScopesSnapshotsAndCommandsToActiveClient() {
        for (version in listOf(0, 1)) {
            val controller = Robolectric.buildService(Service::class.java).create()
            val service = controller.get().apply { mediaVersion = version }
            val host = Host(); val texture = SurfaceTexture(0); val surface = Surface(texture)
            try {
                val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                    .putExtra(ProjectionPlugin.EXTRA_MEDIA_VERSION, 1)))
                binder.connect("media", 2, host); assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
                binder.start("media", surface); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
                val snapshot = PluginMediaState(true, true, "Song")
                service.sessionEvents.media(snapshot)
                binder.mediaCommand("wrong", 1, PluginMediaState.PLAY)
                binder.mediaCommand("media", 1, PluginMediaState.PAUSE)
                if (version == 1) {
                    assertEquals(snapshot, PluginMediaState.fromBundle(host.mediaStates.poll(3, TimeUnit.SECONDS)!!))
                    assertEquals(PluginMediaState.PAUSE, service.mediaCommands.poll(3, TimeUnit.SECONDS))
                    assertEquals(1L to true, host.mediaResults.poll(3, TimeUnit.SECONDS))
                } else {
                    assertNull(host.mediaStates.poll(100, TimeUnit.MILLISECONDS))
                    assertNull(service.mediaCommands.poll(100, TimeUnit.MILLISECONDS))
                }
                binder.mediaCommand("media", 1, PluginMediaState.NEXT) // replay
                binder.mediaCommand("media", 2, 99) // invalid
                binder.stop("media"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
                service.sessionEvents.media(snapshot)
                binder.mediaCommand("media", 3, PluginMediaState.PLAY)
                assertNull(service.mediaCommands.poll(100, TimeUnit.MILLISECONDS))
                assertTrue(host.mediaStates.isEmpty())
            } finally { service.stopped.complete(Unit); surface.release(); texture.release(); controller.destroy() }
        }
    }

    @Test fun mediaStateRejectsUnboundedTextAndInvalidTimes() {
        assertThrows(IllegalArgumentException::class.java) { PluginMediaState(title = "x".repeat(1025)) }
        assertThrows(IllegalArgumentException::class.java) { PluginMediaState(durationMs = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PluginMediaState(positionMs = -2) }
        val state = PluginMediaState(true, false, "Song", "Artist", "Album", "42", 100000, 2000)
        assertEquals(state, PluginMediaState.fromBundle(state.toBundle()))
    }

    @Test fun audioNegotiationRequiresHostAndAttachmentBeforeStart() {
        for (host in listOf(0, 1)) {
            val controller = Robolectric.buildService(Service::class.java).create()
            val service = controller.get().apply { audioVersion = 1 }
            val callbacks = Host(); val texture = SurfaceTexture(0); val surface = Surface(texture)
            try {
                val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                    .putExtra(ProjectionPlugin.EXTRA_AUDIO_VERSION, host)))
                binder.connect("audio", 2, callbacks)
                if (host == 0) {
                    assertEquals(true, callbacks.stopped.poll(3, TimeUnit.SECONDS))
                    assertTrue(callbacks.info.isEmpty())
                } else {
                    assertNotNull(callbacks.info.poll(3, TimeUnit.SECONDS))
                    binder.start("audio", surface)
                    assertEquals(true, callbacks.stopped.poll(3, TimeUnit.SECONDS))
                    assertEquals(1L, service.started.count)
                }
            } finally { service.stopped.complete(Unit); surface.release(); texture.release(); controller.destroy() }
        }
    }

    @Test fun audioDescriptorsAreAcceptedOnceForMatchingTokenAndClosedAfterCleanup() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get().apply { audioVersion = 1 }; val host = Host()
        val down = ParcelFileDescriptor.createPipe(); val up = ParcelFileDescriptor.createPipe()
        val foreign = ParcelFileDescriptor.createPipe(); val duplicate = ParcelFileDescriptor.createPipe()
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try {
            val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                .putExtra(ProjectionPlugin.EXTRA_AUDIO_VERSION, 1)))
            binder.connect("audio", 2, host); assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
            binder.attachAudio("foreign", foreign[0], foreign[1])
            binder.attachAudio("audio", down[1], up[0]); assertTrue(service.audioAttached.await(3, TimeUnit.SECONDS))
            binder.attachAudio("audio", duplicate[0], duplicate[1])
            binder.start("audio", surface); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
            assertSame(down[1], service.audioDescriptors!!.first)
            assertThrows(IllegalStateException::class.java) { foreign[0].fd }
            assertThrows(IllegalStateException::class.java) { duplicate[0].fd }
            binder.stop("audio"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            assertTrue(down[1].fd >= 0) // Stop acknowledgement is still the resource barrier.
            service.stopped.complete(Unit); assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { down[1].fd }
            assertThrows(IllegalStateException::class.java) { up[0].fd }
        } finally {
            service.stopped.complete(Unit)
            (down + up + foreign + duplicate).forEach { runCatching { it.close() } }
            surface.release(); texture.release(); controller.destroy()
        }
    }

    @Test fun hostUiRequiresBothPeersAndReadySession() {
        for ((hostVersion, pluginVersion) in listOf(0 to 1, 1 to 0, 1 to 1)) {
            val controller = Robolectric.buildService(Service::class.java).create()
            val service = controller.get().apply { hostUiVersion = pluginVersion }
            val host = Host(); val texture = SurfaceTexture(0); val surface = Surface(texture)
            try {
                val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                    .putExtra(ProjectionPlugin.EXTRA_HOST_UI_VERSION, hostVersion)))
                binder.connect("session", 2, host)
                assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
                binder.start("session", surface); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
                service.sessionEvents.requestHostUi()
                assertEquals(if (hostVersion == 1 && pluginVersion == 1) "session" else null,
                    host.hostUiRequests.poll(300, TimeUnit.MILLISECONDS))
                service.sessionEvents.state(ProjectionPlugin.CONNECTING)
                service.sessionEvents.requestHostUi()
                assertNull(host.hostUiRequests.poll(100, TimeUnit.MILLISECONDS))
                binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
                service.sessionEvents.requestHostUi()
                assertNull(host.hostUiRequests.poll(100, TimeUnit.MILLISECONDS))
            } finally { service.stopped.complete(Unit); surface.release(); texture.release(); controller.destroy() }
        }
    }

    @Test fun pairingInteractionRequiresBothPeersAndStopsWithSession() {
        for ((hostVersion, pluginVersion) in listOf(0 to 1, 1 to 0, 1 to 1)) {
            val controller = Robolectric.buildService(Service::class.java).create()
            val service = controller.get().apply { interactionVersion = pluginVersion }
            val host = Host(); val texture = SurfaceTexture(0); val surface = Surface(texture)
            try {
                val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                    .putExtra(ProjectionPlugin.EXTRA_INTERACTION_VERSION, hostVersion)))
                binder.connect("session", 2, host)
                assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
                binder.start("session", surface); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
                service.sessionEvents.interaction(PluginInteraction("pairing", "123456", "Car"))
                val payload = host.interactions.poll(300, TimeUnit.MILLISECONDS)
                if (hostVersion == 1 && pluginVersion == 1) assertEquals("123456", PluginInteraction.fromBundle(payload!!).pairingCode)
                else assertNull(payload)
                binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
                service.sessionEvents.interaction(PluginInteraction("pairing", "654321", "Car"))
                assertNull(host.interactions.poll(100, TimeUnit.MILLISECONDS))
            } finally { service.stopped.complete(Unit); surface.release(); texture.release(); controller.destroy() }
        }
    }

    @Test fun encodedSelectionRejectsOldHostBeforeStartingResources() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get().apply { encoded = true }; val host = Host()
        try {
            val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
            binder.connect("session", 2, host)
            assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertTrue(host.info.isEmpty()); assertEquals(1L, service.started.count)
        } finally { controller.destroy() }
    }
    @Test fun encodedStartRequiresNegotiationAndOwnsDescriptorUntilCleanup() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get().apply { encoded = true }; val host = Host()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                .putExtra(ProjectionPlugin.EXTRA_ENCODED_VIDEO_VERSION, 1)))
            binder.connect("session", 2, host)
            assertEquals(1, PluginVideoInfo.fromBundle(host.info.poll(3, TimeUnit.SECONDS)!!).encodedVideoVersion)
            binder.startEncoded("session", pipe[1]); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
            assertSame(pipe[1], service.encodedDescriptor)
            binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            assertTrue(host.stopped.isEmpty())
            service.stopped.complete(Unit); assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { pipe[1].fd }
        } finally { service.stopped.complete(Unit); pipe.forEach { it.close() }; controller.destroy() }
    }

    @Test fun displayUpdatesRequireNegotiationAndRejectInvalidStaleOrForeignRequests() {
        for ((hostVersion, pluginVersion) in listOf(0 to 1, 1 to 0, 1 to 1)) {
            val controller = Robolectric.buildService(Service::class.java).create()
            val service = controller.get().apply { displaySizeVersion = pluginVersion }
            val host = Host(); val texture = SurfaceTexture(0)
            try {
                val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)
                    .putExtra(ProjectionPlugin.EXTRA_DISPLAY_SIZE_VERSION, hostVersion)))
                binder.connect("session", 2, host); assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
                binder.updateDisplaySize("session", 1, 1000, 600) // Before start.
                binder.start("session", Surface(texture)); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
                binder.updateDisplaySize("foreign", 2, 1000, 600)
                binder.updateDisplaySize("session", 2, Int.MAX_VALUE, 600)
                binder.updateDisplaySize("session", 0, 1000, 600)
                binder.updateDisplaySize("session", 3, 1440, 900)
                binder.updateDisplaySize("session", 2, 1000, 600)
                binder.updateDisplaySize("session", 3, 1440, 900)
                // FIFO stop is a barrier for every request above.
                binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
                if (hostVersion == 1 && pluginVersion == 1) {
                    assertEquals(1440 to 900, service.displayRequests.poll())
                    assertEquals(3L to true, host.displayResults.poll())
                }
                assertTrue(service.displayRequests.isEmpty()); assertTrue(host.displayResults.isEmpty())
                binder.updateDisplaySize("session", 4, 1200, 800)
                service.stopped.complete(Unit); assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
                assertTrue(service.displayRequests.isEmpty())
            } finally { service.stopped.complete(Unit); controller.destroy(); texture.release() }
        }
    }

    @Test fun displayHintIsOptionalAndRejectsInvalidSizes() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get()
        try {
            service.onBind(Intent(ProjectionPlugin.ACTION))
            assertNull(service.displayHint)
            service.onBind(Intent(ProjectionPlugin.ACTION)
                .putExtra(ProjectionPlugin.EXTRA_DISPLAY_WIDTH, 1440)
                .putExtra(ProjectionPlugin.EXTRA_DISPLAY_HEIGHT, 900))
            assertEquals(1440 to 900, service.displayHint)
            service.onBind(Intent(ProjectionPlugin.ACTION)
                .putExtra(ProjectionPlugin.EXTRA_DISPLAY_WIDTH, Int.MAX_VALUE)
                .putExtra(ProjectionPlugin.EXTRA_DISPLAY_HEIGHT, 900))
            assertNull(service.displayHint)
        } finally { controller.destroy() }
    }

    @Test fun nativeCancellationIsNegotiatedAndRejectsForeignTokens() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get().apply { nativeCancel = true }; val host = Host()
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertTrue(PluginVideoInfo.fromBundle(host.info.poll(3, TimeUnit.SECONDS)!!).nativeCancelInput)
            binder.start("session", surface); assertTrue(host.ready.await(3, TimeUnit.SECONDS))
            binder.cancelInput("foreign"); binder.cancelInput("session")
            assertEquals(true, service.cancellations.poll(3, TimeUnit.SECONDS))
            binder.touch("session", intArrayOf(0), doubleArrayOf(.5), doubleArrayOf(.5), booleanArrayOf(true))
            assertEquals(1, service.touches.poll(3, TimeUnit.SECONDS)!!.size)
            assertTrue(service.cancellations.isEmpty())
            assertFalse(PluginVideoInfo.fromBundle(PluginVideoInfo(640, 360).toBundle()).nativeCancelInput)
        } finally { service.stopped.complete(Unit); controller.destroy(); texture.release() }
    }

    @Test fun sdkAcknowledgesStopOnlyAfterProducerCompletionAndRejectsForeignTokens() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get(); val host = Host()
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertEquals(640, PluginVideoInfo.fromBundle(host.info.poll(3, TimeUnit.SECONDS)!!).width)
            binder.start("session", surface)
            assertTrue(host.ready.await(3, TimeUnit.SECONDS))
            binder.touch("old", intArrayOf(0), doubleArrayOf(.5), doubleArrayOf(.5), booleanArrayOf(true))
            binder.touch("session", intArrayOf(1), doubleArrayOf(.25), doubleArrayOf(.75), booleanArrayOf(true))
            assertEquals(1, service.touches.poll(3, TimeUnit.SECONDS)!!.single().id)
            binder.stop("session")
            assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            assertTrue(host.stopped.isEmpty())
            service.stopped.complete(Unit)
            assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertTrue(service.touches.isEmpty())
        } finally { service.stopped.complete(Unit); controller.destroy(); texture.release() }
    }
    @Test fun failedCleanupCannotAdmitAnotherSession() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get(); val host = Host(); val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host); assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
            binder.start("session", surface); assertTrue(service.started.await(3, TimeUnit.SECONDS))
            binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            service.stopped.completeExceptionally(IllegalStateException("SDK still running"))
            assertEquals(false, host.stopped.poll(3, TimeUnit.SECONDS))
            val another = Host(); binder.connect("next", ProjectionPlugin.API_VERSION, another)
            assertEquals(true, another.stopped.poll(3, TimeUnit.SECONDS)) // Nothing was acquired for this rejected request.
            assertTrue(another.info.isEmpty())
        } finally { controller.destroy(); surface.release(); texture.release() }
    }
    @Test fun wireValidationRejectsUnsafeDimensionsAndTouchCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { PluginVideoInfo(4096, 4096) }
        assertThrows(IllegalArgumentException::class.java) { PluginVideoInfo.fromBundle(Bundle()) }
        assertFalse(validPluginContacts(listOf(PluginContact(1, Double.NaN, .5, true)), 2))
        assertFalse(validPluginContacts(listOf(PluginContact(1, .5, .5, true), PluginContact(1, .5, .5, false)), 2))
        assertTrue(validPluginContacts(emptyList(), 0))
    }
    @Test fun unauthorizedHostCannotAcquireSessionOrReceiveInformation() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get(); val denied = Host(); val allowed = Host()
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            service.allowHost = false; binder.connect("denied", ProjectionPlugin.API_VERSION, denied)
            service.allowHost = true; binder.connect("allowed", ProjectionPlugin.API_VERSION, allowed)
            assertNotNull(allowed.info.poll(3, TimeUnit.SECONDS))
            assertTrue(denied.info.isEmpty()); assertTrue(denied.stopped.isEmpty())
            binder.stop("allowed")
            assertEquals(true, allowed.stopped.poll(3, TimeUnit.SECONDS))
            assertEquals(1L, service.started.count); assertEquals(1L, service.stopRequested.count)
        } finally { controller.destroy() }
    }
    @Test fun dedicatedProcessWaitsForCleanupAndHostUnbindBeforeExit() {
        val controller = Robolectric.buildService(DedicatedService::class.java).create()
        val service = controller.get(); val host = Host(); val texture = SurfaceTexture(0)
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertTrue(PluginVideoInfo.fromBundle(host.info.poll(3, TimeUnit.SECONDS)!!).processExitRequired)
            binder.start("session", Surface(texture)); assertTrue(service.started.await(3, TimeUnit.SECONDS))
            binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            assertTrue(service.exits.isEmpty()); assertTrue(host.stopped.isEmpty())
            service.stopped.complete(Unit)
            assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS)); assertTrue(service.exits.isEmpty())
            service.onUnbind(Intent(ProjectionPlugin.ACTION))
            assertEquals(true, service.exits.poll(3, TimeUnit.SECONDS))
            service.onUnbind(Intent(ProjectionPlugin.ACTION))
        } finally { service.stopped.complete(Unit); controller.destroy(); texture.release() }
    }
    @Test fun mainProcessCannotOptIntoProcessTermination() {
        val controller = Robolectric.buildService(DedicatedService::class.java).create()
        val service = controller.get(); service.processNameOverride = service.packageName
        val host = Host()
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertTrue(host.info.isEmpty()); assertTrue(service.exits.isEmpty()); assertEquals(1L, service.started.count)
        } finally { controller.destroy() }
    }
    @Test fun abandonedDedicatedProcessIsReclaimedWithoutClaimingCleanShutdown() {
        val controller = Robolectric.buildService(DedicatedService::class.java).create()
        val service = controller.get(); val host = Host(); val texture = SurfaceTexture(0)
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertNotNull(host.info.poll(3, TimeUnit.SECONDS))
            binder.start("session", Surface(texture)); assertTrue(service.started.await(3, TimeUnit.SECONDS))
            binder.stop("session"); assertTrue(service.stopRequested.await(3, TimeUnit.SECONDS))
            service.onUnbind(Intent(ProjectionPlugin.ACTION))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(8))
            assertEquals(true, service.exits.poll(3, TimeUnit.SECONDS))
            assertTrue(host.stopped.isEmpty())
        } finally { service.stopped.complete(Unit); controller.destroy(); texture.release() }
    }
    @Test fun unbindingDuringHandshakeDoesNotLeaveAnOrphanedClient() {
        val controller = Robolectric.buildService(Service::class.java).create()
        val service = controller.get(); val host = Host()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        service.beforeInfo = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val binder = IProjectionPlugin.Stub.asInterface(service.onBind(Intent(ProjectionPlugin.ACTION)))
        try {
            binder.connect("session", ProjectionPlugin.API_VERSION, host)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            service.onUnbind(Intent(ProjectionPlugin.ACTION)); release.countDown()
            assertEquals(true, host.stopped.poll(3, TimeUnit.SECONDS))
            assertEquals(1L, service.started.count)
        } finally { release.countDown(); controller.destroy() }
    }
}
