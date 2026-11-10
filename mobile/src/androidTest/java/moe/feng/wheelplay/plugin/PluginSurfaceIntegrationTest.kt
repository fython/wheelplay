package moe.feng.wheelplay.plugin

import android.content.*
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Install the separate sample APK first. Exercises real Binder, UID checks and Surface buffer transfer. */
@RunWith(AndroidJUnit4::class)
class PluginSurfaceIntegrationTest {
    @Test fun independentApkDrawsReceivesTouchesAndAcknowledgesCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Android 16 may defer one-way Binder callbacks to cached processes. Exercise the
        // same foreground binding importance as a user connecting from the host UI.
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PluginTestHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try { repeat(2) { runSession() } }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun runSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName("moe.feng.wheelplay.plugin.sample", "moe.feng.wheelplay.plugin.sample.SampleProjectionService")
        val service = context.packageManager.queryIntentServices(Intent(ProjectionPlugin.ACTION), 0)
            .single { it.serviceInfo.packageName == component.packageName }.serviceInfo
        assertNotEquals(android.os.Process.myUid(), service.applicationInfo.uid)
        val connected = CompletableFuture<IProjectionPlugin>()
        val info = CompletableFuture<PluginVideoInfo>()
        val ready = CompletableFuture<Unit>()
        val stopped = CompletableFuture<Boolean>()
        val exited = CountDownLatch(1)
        val token = UUID.randomUUID().toString()
        val host = object : IProjectionHost.Stub() {
            override fun onInfo(id: String, bundle: Bundle) {
                runCatching {
                    assertEquals("Plugin callback UID", service.applicationInfo.uid, Binder.getCallingUid())
                    assertEquals("Plugin callback token", token, id)
                    PluginVideoInfo.fromBundle(bundle)
                }.onSuccess { info.complete(it) }.onFailure { info.completeExceptionally(it) }
            }
            override fun onState(id: String, state: Int, message: String) {
                if (id != token) return
                if (state == ProjectionPlugin.READY) ready.complete(Unit)
                if (state == ProjectionPlugin.ERROR) {
                    val failure = AssertionError("Plugin error before readiness: $message")
                    info.completeExceptionally(failure)
                    ready.completeExceptionally(failure)
                }
            }
            override fun onStopped(id: String, clean: Boolean, message: String) {
                if (id != token) return
                stopped.complete(clean)
                val failure = AssertionError("Plugin stopped before handshake/readiness: clean=$clean, $message")
                info.completeExceptionally(failure)
                ready.completeExceptionally(failure)
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching {
                    binder.linkToDeath({
                        exited.countDown()
                        val failure = AssertionError("Plugin process died before handshake/readiness")
                        info.completeExceptionally(failure)
                        ready.completeExceptionally(failure)
                    }, 0)
                    IProjectionPlugin.Stub.asInterface(binder)
                }.onSuccess { connected.complete(it) }.onFailure { connected.completeExceptionally(it) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                stopped.complete(false)
                info.completeExceptionally(AssertionError("Plugin service disconnected before handshake"))
                ready.completeExceptionally(AssertionError("Plugin service disconnected before readiness"))
            }
            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(AssertionError("Plugin returned a null binding: $name"))
            }
            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(AssertionError("Plugin binding died: $name"))
                onServiceDisconnected(name)
            }
        }
        val worker = HandlerThread("plugin-integration-images").apply { start() }
        var reader: ImageReader? = null
        var remote: IProjectionPlugin? = null
        var bound = false
        val colors = ArrayBlockingQueue<Pair<Int, Int>>(8)
        try {
            bound = context.bindService(Intent(ProjectionPlugin.ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            remote = await("service binding", connected)
            remote.connect(token, ProjectionPlugin.API_VERSION, host)
            val video = await("onInfo handshake", info)
            assertTrue(video.processExitRequired)
            reader = ImageReader.newInstance(video.width, video.height, PixelFormat.RGBA_8888, 3).apply {
                setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val pixels = pixel(image, 512, 216) to pixel(image, 1, 1)
                        if (!colors.offer(pixels)) { colors.poll(); colors.offer(pixels) }
                    }
                }, Handler(worker.looper))
            }
            remote.start(token, reader.surface)
            await("first-frame readiness", ready)
            assertNotNull(colors.poll(10, TimeUnit.SECONDS))
            fun awaitColor(predicate: (Pair<Int, Int>) -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < end) {
                    val color = colors.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (predicate(color)) return
                }
                fail("Expected pixel feedback from the independent plugin APK")
            }
            colors.clear()
            remote.touch(token, intArrayOf(4), doubleArrayOf(.8), doubleArrayOf(.6), booleanArrayOf(true))
            awaitColor { it.first == 0x00ffff }
            colors.clear()
            remote.touch(token, intArrayOf(), doubleArrayOf(), doubleArrayOf(), booleanArrayOf())
            awaitColor { it.first == 0x194664 }
            colors.clear()
            remote.setNightMode(token, true)
            awaitColor { it.second == 0x101420 }
            remote.stop(token)
            assertTrue(await("cleanup acknowledgement", stopped))
            context.unbindService(connection); bound = false
            assertTrue("Dedicated plugin process did not exit", exited.await(10, TimeUnit.SECONDS))
        } finally {
            if (!stopped.isDone) {
                runCatching { remote?.stop(token); stopped.get(5, TimeUnit.SECONDS) }
            }
            if (bound) context.unbindService(connection)
            val released = CountDownLatch(1)
            Handler(worker.looper).post {
                reader?.setOnImageAvailableListener(null, null)
                reader?.close()
                released.countDown()
            }
            assertTrue(released.await(5, TimeUnit.SECONDS))
            worker.quitSafely(); worker.join(3000)
        }
    }

    private fun <T> await(stage: String, future: CompletableFuture<T>): T = try {
        future.get(10, TimeUnit.SECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError("Timed out waiting for plugin $stage; see plugin-runtime.log and runtime-logcat.log", failure)
    }

    private fun pixel(image: Image, x: Int, y: Int): Int {
        val plane = image.planes[0]
        val offset = y * plane.rowStride + x * plane.pixelStride
        val bytes = plane.buffer
        return ((bytes.get(offset).toInt() and 255) shl 16) or
            ((bytes.get(offset + 1).toInt() and 255) shl 8) or (bytes.get(offset + 2).toInt() and 255)
    }
}
