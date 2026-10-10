package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shilapi.xcertplay.web.WebSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuthenticationActivationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        WebSession.stop()
        CarPlayBackgroundSession.clear()
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun missingResourcesGuideToSettingsWithoutStartingWebOrCarPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val ui = views(activity.window.decorView)
        assertEquals(3, ui.filterIsInstance<BottomNavigationView>().single().selectedItemId)
        assertNull(shadowOf(activity).nextStartedService)
        assertFalse(WebSession.running)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertTrue(ui.filterIsInstance<TextView>().any { it.text.toString().contains("激活 Web 服务") })
        assertFalse(ui.filterIsInstance<Button>().single { it.text == "连接 iPhone" }.isEnabled)
        WebSession.start(app)
        assertFalse(WebSession.running)
        assertNull(WebSession.tls)
        assertEquals("", WebSession.code)
        controller.pause().stop().destroy()
    }

    @Test fun directServiceStartAndRestartCannotActivateWithoutResources() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(WebSession.running)
        assertNull(WebSession.tls)
        assertNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
        controller.destroy()
    }

    @Test fun usbLaunchWithoutResourcesRedirectsToSettingsAndClosesHost() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java,
            Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED")).setup()
        val host = controller.get()
        assertTrue(host.isFinishing)
        val destination = shadowOf(host).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, destination.component!!.className)
        assertEquals("settings", destination.getStringExtra("page"))
        assertNull(shadowOf(host).nextStartedService)
        assertFalse(WebSession.running)
        assertFalse(CarPlayBackgroundSession.hasSession())
        controller.pause().stop().destroy()
    }

    @Test fun invalidPairCannotActivateWeb() {
        val directory = File(app.noBackupFilesDir, "offline-mfi").apply { mkdirs() }
        val key = SyntheticMfiIdentity.create()
        val other = SyntheticMfiIdentity.create()
        File(directory, "identity.pk8").writeBytes(key.key)
        File(directory, "certificate.p7b").writeBytes(other.certificate)
        WebSession.start(app)
        assertFalse(WebSession.running)
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        controller.get().onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        assertFalse(WebSession.running)
        controller.destroy()
    }

    @Test fun bundledResourcesActivateImmediatelyAndUserReplacementStaysPreferred() {
        // A temporary synthetic APK exercises the real AssetManager path without shipping keys.
        val bundled = SyntheticMfiIdentity.create()
        val apk = File(app.cacheDir, "synthetic-auth-assets.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for ((name, bytes) in mapOf("identity.pk8" to bundled.key, "certificate.p7b" to bundled.certificate)) {
                zip.putNextEntry(ZipEntry("assets/offline-mfi/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
        val cookie = ReflectionHelpers.callInstanceMethod<Int>(app.assets, "addAssetPath",
            ReflectionHelpers.ClassParameter.from(String::class.java, apk.absolutePath))
        assertTrue(cookie > 0)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        assertNotNull(shadowOf(activity.get()).nextStartedService)
        assertArrayEquals(bundled.certificate, MfiAssetStore(app.noBackupFilesDir).load().readCertificate())
        val service = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service.get().onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        assertTrue(WebSession.running)
        val custom = SyntheticMfiIdentity.create()
        MfiAssetStore(app.noBackupFilesDir).installFiles({ custom.key.inputStream() }, { custom.certificate.inputStream() })
        WebSession.stop()
        DiPlayBootstrap.ensure(app)
        service.get().onStartCommand(null, 0, 2)
        assertTrue(WebSession.running)
        assertArrayEquals(custom.certificate, MfiAssetStore(app.noBackupFilesDir).load().readCertificate())
        service.destroy()
        activity.pause().stop().destroy()
    }
}
