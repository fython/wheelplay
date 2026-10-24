package moe.feng.wheelplay.plugin

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
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
        var allowHost = true
        val started = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val stopped = CompletableFuture<Unit>()
        val touches = LinkedBlockingQueue<List<PluginContact>>()
        var beforeInfo: (() -> Unit)? = null
        override fun isHostAllowed(uid: Int) = allowHost
        override fun videoInfo(): PluginVideoInfo { beforeInfo?.invoke(); return PluginVideoInfo(640, 360, 2, true) }
        override fun onStartProjection(surface: Surface, events: Events) {
            started.countDown(); events.state(ProjectionPlugin.READY)
        }
        override fun onStopProjection(): CompletableFuture<Unit> { stopRequested.countDown(); return stopped }
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
        val info = LinkedBlockingQueue<Bundle>()
        val ready = CountDownLatch(1)
        val stopped = LinkedBlockingQueue<Boolean>()
        override fun onInfo(token: String, info: Bundle) { this.info.add(info) }
        override fun onState(token: String, state: Int, message: String) { if (state == ProjectionPlugin.READY) ready.countDown() }
        override fun onStopped(token: String, clean: Boolean, message: String) { stopped.add(clean) }
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
