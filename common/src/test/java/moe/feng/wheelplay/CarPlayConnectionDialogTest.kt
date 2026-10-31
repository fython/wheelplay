package moe.feng.wheelplay

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.web.WebSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayConnectionDialogTest : ComposeSettingsTest() {
    private val app
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun prepare() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.teardownFinished(true)
        WebSession.stop()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(app.noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        AirPlayPersistence.saveWirelessEnabled(app, true)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun cleanup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.teardownFinished(true)
        WebSession.stop()
    }

    private fun host() =
        Robolectric.buildActivity(
                CarPlayHostActivity::class.java,
                Intent(app, CarPlayHostActivity::class.java)
                    .putExtra("return_page", "source-carplay"),
            )
            .setup()

    private fun reporter(host: CarPlayHostActivity): (CarPlayStatus) -> Unit =
        ReflectionHelpers.callInstanceMethod(
            host,
            "createStatusReporter",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
        )

    @Test
    fun connectionIsAModalOverATransparentHostAndBackgroundKeepsItAlive() {
        val activity = host()
        try {
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("carplay:connection-progress").assertExists()
            captureDialog()
            val attributes =
                activity
                    .get()
                    .obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            assertTrue(attributes.getBoolean(0, false))
            attributes.recycle()
            compose.onNodeWithText("后台连接").performClick()
            val destination = shadowOf(activity.get()).nextStartedActivity
            assertEquals(WheelPlayActivity::class.java.name, destination.component!!.className)
            assertEquals("source-carplay", destination.getStringExtra("page"))
            assertFalse(activity.get().isFinishing)
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun captureDialog() {
        compose.waitForIdle()
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/reports/compose-settings/carplay-connection-dialog.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun terminalFailureStopsProgressAndOffersWifiRecovery() {
        val activity = host()
        try {
            reporter(activity.get())(
                CarPlayStatus.Failed(
                    "The previous connection needs a reset",
                    wifiResetRequired = true,
                    automaticRetry = false,
                )
            )
            compose.onNodeWithText("连接失败").assertExists()
            compose.onNodeWithTag("carplay:connection-progress").assertDoesNotExist()
            activity.configurationChange(
                Configuration(activity.get().resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
            )
            compose.onNodeWithText("连接失败").assertExists()
            compose.onNodeWithText("重置 CarPlay Wi-Fi").performClick()
            assertEquals(
                "wireless-recovery",
                shadowOf(activity.get()).nextStartedActivity.getStringExtra("page"),
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun automaticRetryKeepsProgressButTerminalFailureDoesNotClaimToRetry() {
        val activity = host()
        try {
            val report = reporter(activity.get())
            report(CarPlayStatus.Failed("test failure", automaticRetry = true))
            compose.onNodeWithTag("carplay:connection-progress").assertExists()
            report(CarPlayStatus.Failed("test failure", automaticRetry = false))
            compose.onNodeWithText("请检查连接设置和权限后重试。").assertExists()
            compose.onNodeWithTag("carplay:connection-progress").assertDoesNotExist()
            compose.onNodeWithText("连接设置").performClick()
            assertEquals(
                "settings",
                shadowOf(activity.get()).nextStartedActivity.getStringExtra("page"),
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun cancellingWaitsForResourceReleaseAndIgnoresLateStatus() {
        val activity = host()
        val release = CountDownLatch(1)
        try {
            val current = activity.get()
            val report = reporter(current)
            assertTrue(CarPlayBackgroundSession.reserve(current, { null }, { it() }))
            val executor: ExecutorService = ReflectionHelpers.getField(current, "teardownExecutor")
            executor.execute { release.await(8, TimeUnit.SECONDS) }
            compose.onNodeWithText("取消连接").performClick()
            compose.onNodeWithText("正在取消连接").assertExists()
            compose.onNodeWithTag("carplay:connection-cancel").assertIsNotEnabled()
            assertFalse(current.isFinishing)
            current.onBackPressedDispatcher.onBackPressed()
            report(CarPlayStatus.WaitingForIphone)
            compose.onNodeWithText("正在取消连接").assertExists()
            release.countDown()
            compose.waitUntil(8_000) {
                compose.waitForIdle()
                current.isFinishing
            }
            assertFalse(CarPlayBackgroundSession.hasSession())
            assertNull(com.shilapi.xcertplay.projection.projectionSessions.snapshot())
            assertEquals(
                "source-carplay",
                shadowOf(current).nextStartedActivity.getStringExtra("page"),
            )
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun cancelBeforePermissionsDoesNotStopADifferentOwner() {
        val activity = host()
        val other = Any()
        try {
            assertTrue(CarPlayBackgroundSession.reserve(other, { null }, { it() }))
            activity.get().onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.get().isFinishing)
            assertTrue(CarPlayBackgroundSession.isOwner(other))
            assertTrue(CarPlayBackgroundSession.hasSession())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun successDismissesDialogWithoutStoppingTheSession() {
        val activity = host()
        try {
            val current = activity.get()
            assertTrue(CarPlayBackgroundSession.reserve(current, { null }, { it() }))
            CarPlayBackgroundSession.active = true
            ReflectionHelpers.callInstanceMethod<Unit>(current, "dismissConnectedDialog")
            compose.onNode(isDialog()).assertDoesNotExist()
            assertFalse(current.isFinishing)
            assertTrue(CarPlayBackgroundSession.hasSession())
            assertEquals(
                "source-carplay",
                shadowOf(current).nextStartedActivity.getStringExtra("page"),
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun backgroundSuccessDoesNotBringTheAppToTheForeground() {
        val activity = host()
        try {
            // Discard the startup permission request before observing new foreground launches.
            while (shadowOf(activity.get()).nextStartedActivity != null) Unit
            activity.pause().stop()
            CarPlayBackgroundSession.active = true
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "dismissConnectedDialog")
            assertNull(shadowOf(activity.get()).nextStartedActivity)
            activity.restart().start().resume().postResume()
            compose.waitForIdle()
            assertEquals(
                "source-carplay",
                shadowOf(activity.get()).nextStartedActivity.getStringExtra("page"),
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun cancelDuringAnExistingShutdownWaitsForTheSameCleanup() {
        val activity = host()
        val release = CountDownLatch(1)
        var firstCompleted = false
        try {
            val current = activity.get()
            assertTrue(CarPlayBackgroundSession.reserve(current, { null }, { it() }))
            val executor: ExecutorService = ReflectionHelpers.getField(current, "teardownExecutor")
            executor.execute { release.await(8, TimeUnit.SECONDS) }
            ReflectionHelpers.callInstanceMethod<Unit>(
                current,
                "shutdown",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ReflectionHelpers.ClassParameter.from(String::class.java, "test startup failure"),
                ReflectionHelpers.ClassParameter.from(
                    Function0::class.java,
                    { firstCompleted = true },
                ),
            )
            compose.onNodeWithText("取消连接").performClick()
            assertFalse(current.isFinishing)
            assertFalse(firstCompleted)
            release.countDown()
            compose.waitUntil(8_000) {
                compose.waitForIdle()
                current.isFinishing
            }
            assertTrue(firstCompleted)
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [33])
    fun channelExplanationUsesComposeAndWaitsForAChoice() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        val activity = host()
        try {
            val current = activity.get()
            assertTrue(
                ReflectionHelpers.callInstanceMethod<Boolean>(
                    current,
                    "requestManualHotspotChannelPermissionIfNeeded",
                )
            )
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("carplay:connection-dialog").assertDoesNotExist()
            compose
                .onNodeWithText(
                    app.getString(com.shilapi.xcertplay.shared.R.string.host_continue_with_auto)
                )
                .performClick()
            assertTrue(AirPlayPersistence.loadManualHotspotChannelPermissionPromptDismissed(app))
            compose.onNodeWithTag("carplay:connection-dialog").assertExists()
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun reconnectingAfterShutdownStartsAFreshHost() {
        val activity = host()
        var stopped = false
        try {
            val current = activity.get()
            ReflectionHelpers.callInstanceMethod<Unit>(
                current,
                "shutdown",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ReflectionHelpers.ClassParameter.from(String::class.java, "test startup failure"),
                ReflectionHelpers.ClassParameter.from(Function0::class.java, { stopped = true }),
            )
            compose.waitUntil(8_000) {
                compose.waitForIdle()
                stopped
            }
            activity.newIntent(
                Intent(app, CarPlayHostActivity::class.java)
                    .putExtra("return_page", "source-carplay")
            )
            assertTrue(current.isFinishing)
            val launch = shadowOf(current).nextStartedActivity
            assertEquals(CarPlayHostActivity::class.java.name, launch.component!!.className)
            assertEquals("source-carplay", launch.getStringExtra("return_page"))
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
