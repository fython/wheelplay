package com.shilapi.xcertplay

import android.content.Intent
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.TeslaHttpCompatibility
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.WebSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HttpsSettingsUiTest : ComposeSettingsTest() {
    @Before
    fun startWithoutRootAuthorization() {
        val original = RootAccess.authorize
        try {
            RootAccess.authorize = { throw java.io.IOException("Permission denied") }
            RootAccess.request()
        } finally {
            RootAccess.authorize = original
        }
    }

    private fun openSettings(activity: DiPlayActivity) =
        SettingsNavigation.open(activity, "Tesla 兼容（实验）", compose)

    private fun requestRoot() {
        compose.onNodeWithText("请求 Root 权限").performScrollTo().performClick()
    }

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitForUi(predicate: () -> Boolean) {
        compose.waitUntil(8_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            predicate() && !RootAccess.checking
        }
    }

    @Test
    fun startupChecksRootInBackgroundAndShowsSettingsWithoutPressingTheButton() {
        val original = RootAccess.authorize
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        RootAccess.startupChecked = false
        RootAccess.authorize = {
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS))
        }
        TeslaHttpCompatibility.save(
            RuntimeEnvironment.getApplication(),
            TeslaHttpConfig(enabled = true),
        )
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            openSettings(controller.get())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            compose.onNodeWithText("请求 Root 权限").assertIsNotEnabled()
            assertFalse(hasTag("text:HTTP_MAPPING"))
            assertFalse(hasTag("text:HTTP_PORT"))
            release.countDown()
            waitForUi { RootAccess.granted && hasTag("text:HTTP_MAPPING") }
            RootAccess.checkOnStartup()
            assertEquals(1, requests.get())
            requestRoot()
            waitForUi { requests.get() == 2 }
        } finally {
            release.countDown()
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun deniedStartupCheckDoesNotRepeatUntilManualRetry() {
        val original = RootAccess.authorize
        val requests = AtomicInteger()
        RootAccess.startupChecked = false
        RootAccess.authorize = {
            requests.incrementAndGet()
            throw java.io.IOException("Permission denied")
        }
        TeslaHttpCompatibility.save(
            RuntimeEnvironment.getApplication(),
            TeslaHttpConfig(enabled = true),
        )
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            openSettings(controller.get())
            waitForUi { requests.get() == 1 }
            RootAccess.checkOnStartup()
            assertEquals(1, requests.get())
            assertFalse(RootAccess.granted)
            assertFalse(hasTag("text:HTTP_MAPPING"))
            requestRoot()
            waitForUi { requests.get() == 2 }
        } finally {
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun modeSelectorPrecedesRootRequestAndMappingControlsRequireAccess() {
        val original = RootAccess.authorize
        var requests = 0
        RootAccess.authorize = { requests++ }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            openSettings(controller.get())
            assertEquals(0, requests)
            assertFalse(hasTag("text:HTTP_MAPPING"))
            assertFalse(hasTag("text:TESLA_ADDRESS"))
            val mode = compose.onNodeWithTag("choice:TESLA_MODE").fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithText("请求 Root 权限").fetchSemanticsNode().boundsInRoot
            assertTrue(mode.top < root.top)
            requestRoot()
            waitForUi { RootAccess.granted }
            assertEquals(1, requests)
            compose.onNodeWithTag("text:HTTP_MAPPING").assertExists()
            compose.onNodeWithTag("text:HTTPS_MAPPING").assertExists()
            compose.onNodeWithTag("text:TESLA_ADDRESS").assertExists()
            assertFalse(WebSession.running)
        } finally {
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun deniedRootKeepsMappingControlsHiddenAndWebSettingsAvailable() {
        val original = RootAccess.authorize
        RootAccess.authorize = { throw java.io.IOException("Permission denied") }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            openSettings(controller.get())
            requestRoot()
            waitForUi {
                RootAccess.status.resolve(RuntimeEnvironment.getApplication()).contains("未授权")
            }
            assertFalse(hasTag("text:HTTP_MAPPING"))
            assertFalse(hasTag("text:TESLA_ADDRESS"))
            SettingsNavigation.open(controller.get(), "Web 服务", compose)
            compose.onNodeWithTag("text:HTTP_PORT").assertExists()
            compose.onNodeWithTag("text:HOSTNAME").assertExists()
            compose.onNodeWithTag("text:HTTPS_PORT").assertExists()
            compose.onNodeWithText("Web 域名同时用于 HTTP 和 HTTPS", substring = true).assertExists()
        } finally {
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun certificateTypesOpenTheSystemDocumentPickerWithoutRoot() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            SettingsNavigation.open(activity, "Web 服务", compose)
            for ((type, title) in listOf("PEM" to "选择 PEM 证书与私钥", "PKCS12" to "选择 PKCS#12 文件")) {
                clickTag("certificate:$type")
                compose.onNodeWithText(title).performScrollTo().performClick()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals("*/*", intent.type)
                assertFalse(RootAccess.granted)
            }
        } finally {
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun httpsSwitchHidesItsPortAndCertificateTypesWhileHttpStaysAvailable() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            SettingsNavigation.open(activity, "Web 服务", compose)
            compose.onNodeWithText("请求 Root 权限").assertDoesNotExist()
            clickTag("toggle:HTTPS")
            // The worker saves the preference before posting the UI refresh. Wait for the
            // rendered result too, so a slow worker cannot race the visibility assertions.
            waitForUi {
                !WebListenSettings.httpsEnabled(activity) &&
                    !hasTag("text:HTTPS_PORT") && !hasTag("certificate:DEFAULT")
            }
            compose.onNodeWithTag("toggle:HTTPS").assertIsOff()
            compose.onNodeWithTag("text:HTTPS_PORT").assertDoesNotExist()
            compose.onNodeWithTag("certificate:DEFAULT").assertDoesNotExist()
            compose.onNodeWithTag("text:HTTP_PORT").assertExists()
            compose.onNodeWithTag("text:HOSTNAME").assertExists()
        } finally {
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun httpsPortFieldShowsItsPersistedDefaultAndRejectsInvalidInput() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            SettingsNavigation.open(controller.get(), "Web 服务", compose)
            clickTag("text:HTTPS_PORT")
            compose.onNodeWithTag("settings:text-input").assertTextContains("8443")
            input("443")
            saveInput()
            assertEquals(8443, WebListenSettings.httpsPort(controller.get()))
            compose.onNodeWithTag("settings:text-input").assertExists()
            cancelInput()
        } finally {
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }

    @Test
    fun renderWebAndTeslaSettingsBeforeAndAfterRootGrant() {
        val original = RootAccess.authorize
        RootAccess.authorize = {}
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            openSettings(controller.get())
            captureSettings(controller.get(), "tesla-without-root")
            requestRoot()
            waitForUi { RootAccess.granted }
            captureSettings(controller.get(), "tesla-with-root")
            SettingsNavigation.open(controller.get(), "Web 服务", compose)
            captureSettings(controller.get(), "web-service")
        } finally {
            RootAccess.authorize = original
            controller.pause().stop().destroy()
            WebSession.stop()
        }
    }
}
