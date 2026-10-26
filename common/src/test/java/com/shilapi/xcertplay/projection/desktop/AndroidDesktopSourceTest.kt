package com.shilapi.xcertplay.projection.desktop

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.VideoCodec
import com.shilapi.xcertplay.projection.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidDesktopSourceTest {
    private val events = CopyOnWriteArrayList<String>()
    private val firstTouch = CountDownLatch(1)
    private val back = CountDownLatch(1)
    private val started = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private var touchGate: CountDownLatch? = null
    private var closeFailure = false
    private var startFailure = false
    private var appExists = true
    private var pendingConnection: CompletableFuture<DesktopConnection>? = null
    private var startedConfig: DesktopDisplayConfig? = null
    private var outputConfig: DesktopDisplayConfig? = null
    @Volatile private var available: SourceAvailability = SourceAvailability.Ready
    private val connection = object : DesktopConnection {
        override val uid = 2000
        override fun start(surface: Surface, component: String, config: DesktopDisplayConfig): Int {
            startedConfig = config
            events += "start:$component"; started.countDown()
            if (startFailure) error("display rejected")
            return 12
        }
        override fun touch(contacts: List<ProjectionContact>) { events += "touch:${contacts.size}"; firstTouch.countDown(); touchGate?.await(3, TimeUnit.SECONDS) }
        override fun back() { events += "back"; back.countDown() }
        override fun healthy() = true
        override fun close() { events += "release-display"; if (closeFailure) error("still owned") }
    }
    private val backend = object : DesktopBackend {
        override fun probe() = available
        override fun connect(): CompletableFuture<DesktopConnection> { events += "bind"; return pendingConnection ?: CompletableFuture.completedFuture(connection) }
    }
    private val output = object : ProjectionVideoOutput {
        override val surface = Surface(SurfaceTexture(0))
        override var hasFrame = false
        override val hasPublishedFrame get() = hasFrame
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit) = error("Surface only")
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long) = false
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot) = false
        override fun close() { events += "release-surface"; surface.release(); finished.countDown() }
    }
    private fun source() = AndroidDesktopSource(RuntimeEnvironment.getApplication(), backend,
        { outputConfig = it; events += "surface"; output }, { appExists && it == "test.app/.Main" }).apply { selectApp("test.app/.Main") }
    private fun ready(session: ProjectionSession) {
        assertTrue(started.await(3, TimeUnit.SECONDS))
        output.hasFrame = true
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!session.inputReady && System.nanoTime() < deadline) Thread.yield()
        assertTrue(session.inputReady)
    }
    @Test fun displaySettingsPersistAndInvalidStoredDimensionsFallBackToDefaults() {
        val provider = source()
        assertEquals(DesktopDisplayConfig(1280, 720, 160), provider.displayConfig())
        val custom = DesktopDisplayConfig(1080, 1920, 320)
        provider.configureDisplay(custom)
        assertEquals(custom, source().displayConfig())
        RuntimeEnvironment.getApplication().getSharedPreferences("android-desktop", 0)
            .edit().putInt("width", Int.MAX_VALUE).apply()
        assertEquals(DesktopDisplayConfig(), provider.displayConfig())
        assertTrue(events.isEmpty())
        output.close()
    }

    @Test fun pendingSessionKeepsItsDisplayConfigWhenSettingsChange() {
        val provider = source()
        val original = DesktopDisplayConfig(1920, 1080, 240)
        val next = DesktopDisplayConfig(720, 1280, 320)
        provider.configureDisplay(original)
        pendingConnection = CompletableFuture()
        val opening = provider.open()
        provider.configureDisplay(next)
        pendingConnection!!.complete(connection)
        val session = opening.get(3, TimeUnit.SECONDS)
        try {
            session.activate(); ready(session)
            assertEquals(original, outputConfig)
            assertEquals(original, startedConfig)
            assertEquals(next, source().displayConfig())
        } finally { session.stop().get(3, TimeUnit.SECONDS) }
    }

    @Test fun missingDependenciesDoNotStopTheCurrentSourceOrBindHelper() {
        val runtime = ProjectionRuntime()
        val old = object : ProjectionSession {
            override val descriptor = SourceDescriptor("carplay", "CarPlay")
            override val capabilities = ProjectionCapabilities(emptySet())
            override val inputReady = true
            override fun touch(contacts: List<ProjectionContact>) = true
            override fun stop(): CompletableFuture<Unit> = error("Must not stop current source")
        }
        runtime.adopt(old, emptySet())
        val provider = source(); runtime.register(provider)
        for (reason in listOf("not installed", "not started", "permission denied", "unsupported")) {
            available = SourceAvailability.Unavailable(reason)
            assertTrue(runtime.switchTo(DesktopProtocol.SOURCE_ID).isCompletedExceptionally)
            assertSame(old, runtime.snapshot()); assertTrue(events.isEmpty())
        }
        available = SourceAvailability.Ready; appExists = false
        assertTrue(runtime.switchTo(DesktopProtocol.SOURCE_ID).isCompletedExceptionally)
        assertSame(old, runtime.snapshot()); assertTrue(events.isEmpty())
        output.close()
    }
    @Test fun activationWaitsForAdmissionAndFirstFrameAndStopsProducerBeforeSurface() {
        val session = source().open().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("bind"), events)
        assertFalse(session.inputReady)
        session.activate(); ready(session)
        assertFalse(session.capabilities.browserAudio); assertFalse(session.capabilities.browserMicrophone)
        assertFalse(session.capabilities.keyboardInput); assertTrue(session.capabilities.backButton)
        assertTrue(session.touch(listOf(ProjectionContact(2, .2, .8, true))))
        assertTrue(firstTouch.await(3, TimeUnit.SECONDS))
        assertTrue(session.input(ProjectionInput.Key("BrowserBack", true)))
        assertTrue(back.await(3, TimeUnit.SECONDS))
        assertFalse(session.input(ProjectionInput.Key("a", true)))
        session.stop().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("release-display", "release-surface"), events.takeLast(2))
        assertSame(session.stop(), session.stop()); assertFalse(session.inputReady)
        assertFalse(session.touch(listOf(ProjectionContact(2, .2, .8, true))))
    }
    @Test fun displayStartupFailureCleansPartialResourcesAndReportsFailure() {
        startFailure = true
        val provider = source()
        val session = provider.open().get()
        session.activate()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("release-display", "release-surface"), events.takeLast(2))
        assertNotNull(provider.lastFailure)
        assertFalse(session.inputReady)
    }
    @Test fun unconfirmedCleanupRetainsRuntimeOwnershipAndConsumerSurface() {
        val runtime = ProjectionRuntime(); val provider = source(); runtime.register(provider)
        val session = runtime.open(DesktopProtocol.SOURCE_ID).get()
        ready(session); closeFailure = true
        assertThrows(ExecutionException::class.java) { runtime.stop().get(3, TimeUnit.SECONDS) }
        assertSame(session, runtime.snapshot())
        assertEquals(setOf(ProjectionResource.DISPLAY), runtime.resources())
        assertFalse(events.contains("release-surface")); assertFalse(runtime.inputReady(session))
        output.close()
    }
    @Test fun revokedAuthorizationStopsTheHelperAndVideo() {
        val provider = source()
        val session = provider.open().get()
        session.activate(); ready(session)
        available = SourceAvailability.Unavailable("permission revoked")
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertFalse(session.inputReady)
        assertTrue(provider.lastFailure!!.contains("permission revoked"))
        assertEquals(listOf("release-display", "release-surface"), events.takeLast(2))
    }

    @Test fun failedVideoConstructorCleanupCannotReleaseRuntimeOwnership() {
        val attempted = CountDownLatch(1)
        val provider = AndroidDesktopSource(RuntimeEnvironment.getApplication(), backend, {
            attempted.countDown()
            throw ProjectionCleanupException("GL worker still running")
        }, { true }).apply { selectApp("test.app/.Main") }
        val runtime = ProjectionRuntime(); runtime.register(provider)
        val session = runtime.open(DesktopProtocol.SOURCE_ID).get()
        assertTrue(attempted.await(3, TimeUnit.SECONDS))
        assertThrows(ExecutionException::class.java) { session.stop().get(3, TimeUnit.SECONDS) }
        assertSame(session, runtime.snapshot())
        assertTrue(runtime.hasSession()); assertFalse(session.inputReady)
        output.close()
    }

    @Test fun saturatedInputQueueStopsTheProducerInsteadOfDroppingReleases() {
        val gate = CountDownLatch(1); touchGate = gate
        val provider = source()
        val session = provider.open().get()
        session.activate(); ready(session)
        assertTrue(session.touch(listOf(ProjectionContact(0, .2, .8, true))))
        assertTrue(firstTouch.await(3, TimeUnit.SECONDS))
        try {
            repeat(80) { session.touch(listOf(ProjectionContact(0, .3, .7, true))) }
            assertFalse(session.inputReady)
            assertNotNull(provider.lastFailure)
        } finally { gate.countDown() }
        session.stop().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("release-display", "release-surface"), events.takeLast(2))
    }

    @Test fun stopBeforeActivationDoesNotCreateDisplayOrSurface() {
        val session = source().open().get()
        session.stop().get(3, TimeUnit.SECONDS)
        session.activate()
        assertEquals(listOf("bind", "release-display"), events)
        output.close()
    }
}
