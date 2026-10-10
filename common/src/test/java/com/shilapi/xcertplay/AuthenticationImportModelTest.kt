package com.shilapi.xcertplay

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuthenticationImportModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = MfiAssetStore(app.noBackupFilesDir)
    private val keyUri = Uri.parse("content://documents/key")
    private val certificateUri = Uri.parse("content://documents/certificate")
    private val apkUri = Uri.parse("content://documents/DiPlay.apk")

    @After fun cleanup() {
        com.shilapi.xcertplay.web.WebSession.stop()
        CarPlayBackgroundSession.clear()
        assertFalse(DiPlayBootstrap.importing)
    }

    private fun waitForCompletion(model: AuthenticationImportModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (model.state.value?.busy == false && !DiPlayBootstrap.importing) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Import did not complete")
    }

    private fun apk(identity: SyntheticMfiIdentity.Identity): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            for ((name, bytes) in mapOf("identity.pk8" to identity.key, "certificate.p7b" to identity.certificate)) {
                zip.putNextEntry(ZipEntry("assets/offline-mfi/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun manualImportEnablesBootstrapWithoutBundledAssets() {
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(app) }
        val identity = SyntheticMfiIdentity.create()
        shadowOf(app.contentResolver).registerInputStreamSupplier(keyUri) { identity.key.inputStream() }
        shadowOf(app.contentResolver).registerInputStreamSupplier(certificateUri) { identity.certificate.inputStream() }
        val model = AuthenticationImportModel(app)
        model.import(key = keyUri, certificate = certificateUri)
        waitForCompletion(model)
        assertTrue(model.state.value!!.result!!.startsWith("认证资源已导入"))
        DiPlayBootstrap.ensure(app)
        assertArrayEquals(identity.certificate, store.load().readCertificate())
    }

    @Test fun failedImportLeavesReadyConfigurationAvailable() {
        val current = SyntheticMfiIdentity.create()
        store.installFiles({ current.key.inputStream() }, { current.certificate.inputStream() })
        shadowOf(app.contentResolver).registerInputStreamSupplier(apkUri) { "invalid APK".byteInputStream() }
        val model = AuthenticationImportModel(app)
        model.import(apk = apkUri)
        waitForCompletion(model)
        assertTrue(model.state.value!!.result!!.startsWith("导入失败"))
        DiPlayBootstrap.ensure(app)
        assertArrayEquals(current.certificate, store.load().readCertificate())
    }

    @Test fun retainedImportCompletesAfterSettingsActivityRecreation() {
        val identity = SyntheticMfiIdentity.create()
        val bytes = apk(identity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(apkUri) {
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }
        }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val model = ViewModelProvider(controller.get())[AuthenticationImportModel::class.java]
        assertSame(app, model.getApplication<android.app.Application>())
        try {
            model.import(apk = apkUri)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(app) }
            controller.recreate()
            assertSame(model, ViewModelProvider(controller.get())[AuthenticationImportModel::class.java])
        } finally {
            release.countDown()
            waitForCompletion(model)
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        DiPlayBootstrap.ensure(controller.get())
        assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>().any {
            it.text.toString().startsWith("资源已就绪")
        })
        assertNotNull(shadowOf(controller.get()).nextStartedService)
        assertArrayEquals(identity.certificate, store.load().readCertificate())
        val service = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service.get().onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        assertTrue(com.shilapi.xcertplay.web.WebSession.running)
        service.destroy()
        controller.pause().stop().destroy()
    }

    @Test fun externalApkShareShowsSettingsAndWaitsForImportButton() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, apkUri)).setup()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        assertFalse(DiPlayBootstrap.importing)
        assertFalse(store.exists())
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        assertFalse(DiPlayBootstrap.importing)
        assertFalse(store.exists())
        controller.pause().stop().destroy()
    }
}
