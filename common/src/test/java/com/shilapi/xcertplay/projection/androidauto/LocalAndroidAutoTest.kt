package com.shilapi.xcertplay.projection.androidauto

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.projection.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalAndroidAutoTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("projection", 0)
    private fun source(mode: AndroidAutoMode) = AndroidAutoSource(context, mode) { error("Must not create video") }

    @Before fun clearPreferences() { prefs.edit().clear().commit() }

    private fun install(enabled: Boolean = true) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = LocalAndroidAuto.PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = LocalAndroidAuto.PACKAGE
                this.enabled = enabled
            }
        })
    }

    @Test fun unavailableLocalAppCannotStopCurrentSource() {
        val runtime = ProjectionRuntime()
        val playing = object : ProjectionSession {
            override val descriptor = SourceDescriptor("playing", "Playing")
            override val capabilities = ProjectionCapabilities(emptySet())
            override val inputReady = false
            override fun touch(contacts: List<ProjectionContact>) = false
            override fun stop(): CompletableFuture<Unit> = error("Must preserve the current source")
        }
        runtime.adopt(playing, setOf(ProjectionResource.DISPLAY))
        val local = source(AndroidAutoMode.LOCAL)
        runtime.register(local)
        assertTrue(runtime.switchTo(local.descriptor.id).isCompletedExceptionally)
        assertSame(playing, runtime.snapshot())
    }

    @Test fun disabledAppIsUnavailableAndEnabledAppCanBeSelectedWithoutRemoteSettings() {
        install(enabled = false)
        assertTrue(source(AndroidAutoMode.LOCAL).probe() is SourceAvailability.Unavailable)
        install()
        assertEquals(SourceAvailability.Ready, source(AndroidAutoMode.LOCAL).probe())
        assertTrue(source(AndroidAutoMode.TCP).probe() is SourceAvailability.Unavailable)
    }

    @Test fun localConnectionIgnoresAndPreservesRemoteAddress() {
        prefs.edit().putString("aa_host", "192.168.1.80").putInt("aa_port", 1234).commit()
        val local = source(AndroidAutoMode.LOCAL)
        assertEquals("127.0.0.1" to 5277, local.tcpEndpoint())
        local.open().get().stop().get() // Opening is side-effect free until activate.
        assertEquals("192.168.1.80" to 1234, source(AndroidAutoMode.TCP).tcpEndpoint())
        assertFalse(local.resources.contains(ProjectionResource.WIRELESS))
        assertFalse(local.resources.contains(ProjectionResource.USB))
    }

    @Test fun localConnectionFailureRestoresSetupGuideButRemoteFailureDoesNot() {
        for (id in listOf("android-auto-tcp", LocalAndroidAuto.SOURCE_ID)) {
            prefs.edit().putBoolean(LocalAndroidAuto.SETUP_SEEN, true).commit()
            val closed = CountDownLatch(1)
            val connection = object : AapConnection {
                override fun connect() { throw ConnectException("Connection refused") }
                override val input get() = error("No stream before connect")
                override val output get() = error("No stream before connect")
                override fun close() { closed.countDown() }
            }
            val session = AndroidAutoSession(context, SourceDescriptor(id, id), connection, false) { error("No video before connect") }
            session.activate()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            session.stop().get(5, TimeUnit.SECONDS)
            assertEquals(id != LocalAndroidAuto.SOURCE_ID, prefs.getBoolean(LocalAndroidAuto.SETUP_SEEN, false))
        }
    }
}
