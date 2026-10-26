package com.shilapi.xcertplay

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AuthenticationImportModelTest : ComposeSettingsTest() {
    private companion object {
        const val IMPORT_TIMEOUT_SECONDS = 30L
    }

    private val app
        get() = RuntimeEnvironment.getApplication()

    private val store
        get() = MfiAssetStore(app.noBackupFilesDir)

    private val keyUri = Uri.parse("content://documents/key")
    private val certificateUri = Uri.parse("content://documents/certificate")
    private val apkUri = Uri.parse("content://documents/DiPlay.apk")

    @After
    fun cleanup() {
        com.shilapi.xcertplay.web.WebSession.stop()
        CarPlayBackgroundSession.clear()
        assertFalse(DiPlayBootstrap.importing)
    }

    private fun waitForCompletion(model: AuthenticationImportModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IMPORT_TIMEOUT_SECONDS)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (model.state.value?.busy == false && !DiPlayBootstrap.importing) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Import did not complete: state=${model.state.value}, importing=${DiPlayBootstrap.importing}")
    }

    private fun apk(identity: SyntheticMfiIdentity.Identity): ByteArray =
        ByteArrayOutputStream()
            .apply {
                ZipOutputStream(this).use { zip ->
                    for ((name, bytes) in
                        mapOf(
                            "identity.pk8" to identity.key,
                            "certificate.p7b" to identity.certificate,
                        )) {
                        zip.putNextEntry(ZipEntry("assets/offline-mfi/$name"))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
            .toByteArray()

    @Test
    fun manualImportEnablesBootstrapWithoutBundledAssets() {
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(app) }
        val identity = SyntheticMfiIdentity.create()
        shadowOf(app.contentResolver).registerInputStreamSupplier(keyUri) {
            identity.key.inputStream()
        }
        shadowOf(app.contentResolver).registerInputStreamSupplier(certificateUri) {
            identity.certificate.inputStream()
        }
        val model = AuthenticationImportModel(app)
        model.import(key = keyUri, certificate = certificateUri)
        waitForCompletion(model)
        assertTrue(model.state.value!!.result!!.startsWith("认证资源已导入"))
        DiPlayBootstrap.ensure(app)
        assertArrayEquals(identity.certificate, store.load().readCertificate())
    }

    @Test
    fun failedImportLeavesReadyConfigurationAvailable() {
        val current = SyntheticMfiIdentity.create()
        store.installFiles({ current.key.inputStream() }, { current.certificate.inputStream() })
        shadowOf(app.contentResolver).registerInputStreamSupplier(apkUri) {
            "invalid APK".byteInputStream()
        }
        val model = AuthenticationImportModel(app)
        model.import(apk = apkUri)
        waitForCompletion(model)
        assertTrue(model.state.value!!.result!!.startsWith("导入失败"))
        DiPlayBootstrap.ensure(app)
        assertArrayEquals(current.certificate, store.load().readCertificate())
    }

    @Test
    fun retainedImportCompletesAfterSettingsActivityRecreation() {
        val identity = SyntheticMfiIdentity.create()
        val bytes = apk(identity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(apkUri) {
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    // The test owns this gate and releases it in finally. Activity recreation
                    // may be slow on CI; its duration must not turn the fixture into a failed import.
                    release.await()
                    return super.read(buffer, offset, length)
                }
            }
        }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val model = ViewModelProvider(controller.get())[AuthenticationImportModel::class.java]
            assertSame(app, model.getApplication<android.app.Application>())
            try {
                model.import(apk = apkUri)
                assertTrue("Import worker did not reach the blocked read",
                    entered.await(IMPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { DiPlayBootstrap.ensure(app) }
                controller.recreate()
                assertSame(
                    model,
                    ViewModelProvider(controller.get())[AuthenticationImportModel::class.java],
                )
                assertTrue("Import must remain blocked until the test releases it", DiPlayBootstrap.importing)
                assertTrue(model.state.value!!.busy)
            } finally {
                release.countDown()
                waitForCompletion(model)
            }
            // The Activity consumes the result message. Check the installed identity before
            // ensure() can fall back to bundled assets and obscure the actual import failure.
            assertTrue("Import completed without installing authentication resources", store.exists())
            assertArrayEquals(identity.certificate, store.load().readCertificate())
            DiPlayBootstrap.ensure(controller.get())
            compose.onNodeWithText("资源已就绪 · 私钥与证书校验通过").assertExists()
            assertNotNull(shadowOf(controller.get()).nextStartedService)
            val service = Robolectric.buildService(DiPlaySessionService::class.java).create()
            try {
                service.get().onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
                assertTrue(com.shilapi.xcertplay.web.WebSession.running)
            } finally {
                service.destroy()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun externalApkShareShowsSettingsAndWaitsForImportButton() {
        val controller =
            Robolectric.buildActivity(
                    DiPlayActivity::class.java,
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, apkUri),
                )
                .setup()
        compose.onNodeWithText("从此 APK 导入认证资源？").assertExists()
        assertFalse(DiPlayBootstrap.importing)
        assertFalse(store.exists())
        clickText("取消")
        assertFalse(DiPlayBootstrap.importing)
        assertFalse(store.exists())
        controller.pause().stop().destroy()
    }
}
