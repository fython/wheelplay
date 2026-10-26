package com.shilapi.xcertplay

import android.content.res.Configuration
import android.content.ComponentName
import android.content.Intent
import android.content.pm.*
import android.os.Bundle
import com.shilapi.xcertplay.projection.plugin.projectionPlugins
import moe.feng.wheelplay.plugin.ProjectionPlugin
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.web.WebSession
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceNavigationTest {
    @Before fun provision() {
        val context = RuntimeEnvironment.getApplication()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        DiPlayPreferences.saveAutoConnect(context, false)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tabs(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single()
    private fun page(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
    private fun cards(activity: DiPlayActivity) = views(page(activity)).filterIsInstance<MaterialCardView>()
    private fun buttons(activity: DiPlayActivity) = views(page(activity)).filterIsInstance<Button>()
    private fun capture(activity: DiPlayActivity, name: String, bottom: Boolean = false) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        if (bottom) page(activity).let { it.scrollTo(0, it.getChildAt(0).height) }
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/material-ui/sources").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun cardsOnlyNavigateAndKeepConnectionControlsInTheirOwnPages() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val nav = tabs(activity)
            assertEquals("输入源", nav.menu.findItem(2).title)
            nav.selectedItemId = 2
            val titles = cards(activity).map { views(it).filterIsInstance<TextView>().first().text.toString() }
            assertEquals(listOf("CarPlay (iPhone)", "Android Auto", "ICCOA CarLink", "VNC", "Android 虚拟桌面", "插件输入源", "诊断输入源"), titles)
            assertTrue(buttons(activity).isEmpty())
            val initialSession = projectionSessions.snapshot()
            capture(activity, "index")
            cards(activity)[0].performClick()
            assertEquals(2, nav.selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接 iPhone" })
            assertFalse(buttons(activity).any { it.text == "连接本机 Android Auto" })
            capture(activity, "carplay")
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(7, cards(activity).size)
            cards(activity)[1].performClick()
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            assertTrue(buttons(activity).any { it.text == "通过 USB 连接 Android Auto" })
            assertFalse(buttons(activity).any { it.text == "连接 iPhone" })
            capture(activity, "android-auto")
            buttons(activity).single { it.text == "返回输入源" }.performClick()
            cards(activity)[2].performClick()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "安装 CarLink 插件" })
            assertFalse(buttons(activity).any { it.text == "停止投影" })
            buttons(activity).single { it.text == "插件输入源" }.performClick()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "未发现兼容的插件服务。" })
            assertTrue(buttons(activity).any { it.text == "刷新已安装插件" })
            assertSame(initialSession, projectionSessions.snapshot())
            capture(activity, "plugins-empty")
            buttons(activity).single { it.text == "返回输入源" }.performClick()
            cards(activity)[3].performClick()
            assertTrue(buttons(activity).any { it.text == "连接 VNC" })
            capture(activity, "vnc")
            buttons(activity).single { it.text == "连接 VNC" }.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val fields = views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>()
            assertEquals(3, fields.size)
            assertEquals("", fields[2].text.toString())
            fields[0].setText("hostname"); fields[1].setText("5900"); fields[2].setText("secret")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing); assertNotNull(fields[0].error)
            dialog.dismiss()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("", fields[2].text.toString())
            assertSame(initialSession, projectionSessions.snapshot())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun desktopCardOnlyNavigatesAndMissingShizukuKeepsStartDisabled() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val initial = projectionSessions.snapshot()
            tabs(activity).selectedItemId = 2
            cards(activity)[4].performClick()
            assertTrue(buttons(activity).any { it.text == "安装 / 打开 Shizuku" })
            assertTrue(buttons(activity).any { it.text == "授权 WheelPlay" })
            assertTrue(buttons(activity).any { it.text == "选择可启动的本机应用" })
            assertFalse(buttons(activity).single { it.text == "启动虚拟桌面" }.isEnabled)
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text.toString().contains("未安装 Shizuku") })
            assertSame(initial, projectionSessions.snapshot())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).boundServiceConnections.isEmpty())
            tabs(activity).selectedItemId = 1
            selectHomeSource(activity, "Android 虚拟桌面")
            buttons(activity).single { it.text == "设置并连接" }.performClick()
            assertFalse(buttons(activity).single { it.text == "启动虚拟桌面" }.isEnabled)
            assertSame(initial, projectionSessions.snapshot())
            val active = object : ProjectionSession {
                override val descriptor = SourceDescriptor("android-desktop", "Android 虚拟桌面")
                override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE))
                override val inputReady = false
                override val statusText = "desktop-test-connected"
                override fun touch(contacts: List<ProjectionContact>) = true
                override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(Unit)
            }
            assertTrue(projectionSessions.adopt(active, setOf(ProjectionResource.DISPLAY)))
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "desktop-test-connected" })
            val stop = buttons(activity).single { it.text == "停止投影" }
            assertEquals(View.VISIBLE, stop.visibility)
            stop.performClick()
            assertNull(projectionSessions.snapshot())
        } finally { projectionSessions.stop().join(); controller.pause().stop().destroy(); WebSession.stop() }
    }

    private fun installPlugin(pkg: String, sourceId: String?, api: Int = ProjectionPlugin.API_VERSION) {
        val context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = pkg; uid = 12345; enabled = true }
        pm.installPackage(PackageInfo().apply {
            packageName = pkg; applicationInfo = app; firstInstallTime = 10
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("1234"))) }
            services = arrayOf(ServiceInfo().apply {
                name = "$pkg.Source"; packageName = pkg; applicationInfo = app
                exported = true; enabled = true; nonLocalizedLabel = "ICCOA CarLink $pkg"
                metaData = Bundle().apply {
                    putInt(ProjectionPlugin.META_API_VERSION, api)
                    putString(ProjectionPlugin.META_SOURCE_ID, sourceId)
                    putString(ProjectionPlugin.META_SETTINGS_ACTIVITY, ".Settings")
                }
            })
            activities = arrayOf(ActivityInfo().apply {
                name = "$pkg.Settings"; packageName = pkg; applicationInfo = app; exported = true; enabled = true
            })
        })
    }

    private fun discoverPlugins(vararg packages: String) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setResolveInfosForIntent(Intent(ProjectionPlugin.ACTION), packages.map { pkg ->
            ResolveInfo().apply {
                serviceInfo = context.packageManager.getPackageInfo(pkg,
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA).services!!.single()
            }
        })
    }

    @Test fun iccoaUsesDeclaredIdAndShowsSettingsWithoutAutomaticallyEnablingOrStartingPlugins() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        installPlugin("test.alternative", ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        installPlugin("test.unrelated", null) // Its label also says ICCOA; only metadata may associate it.
        discoverPlugins("test.carlink", "test.alternative", "test.unrelated")
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val initialSession = projectionSessions.snapshot()
            tabs(activity).selectedItemId = 2
            val summary = views(cards(activity)[2]).filterIsInstance<TextView>().map { it.text.toString() }.joinToString()
            assertTrue(summary.contains("test.carlink")); assertFalse(summary.contains("test.unrelated"))
            cards(activity)[2].performClick()
            val text = views(page(activity)).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(text.contains("安装 CarLink 插件")); assertFalse(text.contains("暂不可用"))
            assertFalse(text.any { it.contains("test.unrelated") })
            assertEquals(2, buttons(activity).count { it.text == "启用插件" })
            assertEquals(2, buttons(activity).count { it.text == "插件设置与权限" })
            assertTrue(buttons(activity).filter { it.text == "连接插件" }.all { !it.isEnabled })
            assertSame(initialSession, projectionSessions.snapshot())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
            val pluginCard = cards(activity).single { card -> views(card).filterIsInstance<TextView>().any { it.text == "test.carlink" } }
            views(pluginCard).filterIsInstance<Button>().single { it.text == "插件设置与权限" }.performClick()
            assertEquals(ComponentName("test.carlink", "test.carlink.Settings"), shadowOf(activity).nextStartedActivity.component)
            views(pluginCard).filterIsInstance<Button>().single { it.text == "启用插件" }.performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, buttons(activity).count { it.text == "连接插件" && it.isEnabled })
            assertSame(initialSession, projectionSessions.snapshot())
            capture(activity, "iccoa-installed")
            // Returning after an incompatible update replaces the controls with a specific reason.
            controller.pause()
            installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK, api = 99)
            discoverPlugins("test.carlink")
            controller.resume()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text.toString().contains("插件接口版本不兼容") })
            assertTrue(buttons(activity).filter { it.text == "连接插件" }.all { !it.isEnabled })
            // Returning after uninstall restores the install page without leaving stale settings.
            controller.pause(); discoverPlugins(); controller.resume()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "安装 CarLink 插件" })
            assertFalse(buttons(activity).any { it.text == "插件设置与权限" })
        } finally {
            discoverPlugins(); projectionPlugins(context).refresh()
            controller.pause().stop().destroy(); WebSession.stop()
        }
    }

    private fun selectHomeSource(activity: DiPlayActivity, name: String) {
        buttons(activity).single { it.text.startsWith("切换输入源：") }.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.listView
        val index = (0 until list.count).single { list.adapter.getItem(it).toString() == name }
        list.performItemClick(null, index, index.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun homeDefaultsToCarPlayRemembersSelectionAndOpensSelectedSourceControls() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            assertTrue(buttons(activity).any { it.text == "切换输入源：CarPlay (iPhone)" })
            assertFalse(views(page(activity)).filterIsInstance<TextView>().any { it.text == "iPhone 会话" })
            val initial = projectionSessions.snapshot()
            selectHomeSource(activity, "VNC")
            assertSame(initial, projectionSessions.snapshot())
            assertEquals("vnc", DiPlayPreferences.homeSource(activity))
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "等待连接投影输入源" })
            buttons(activity).single { it.text == "设置并连接" }.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(3, views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().size)
            dialog.dismiss()
            DiPlayPreferences.saveAutoConnect(activity, true)
            controller.recreate(); activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(buttons(activity).any { it.text == "切换输入源：VNC" })
            buttons(activity).single { it.text == "输入源设置" }.performClick()
            assertTrue(buttons(activity).any { it.text == "连接 VNC" })
            tabs(activity).selectedItemId = 1
            selectHomeSource(activity, "Android Auto")
            buttons(activity).single { it.text == "设置并连接" }.performClick()
            assertTrue(buttons(activity).any { it.text == "通过 USB 连接 Android Auto" })
            assertSame(initial, projectionSessions.snapshot())
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun homeListsOnlyEnabledCompatiblePluginsAndFallsBackAfterDisable() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        installPlugin("test.disabled", null)
        installPlugin("test.incompatible", ProjectionPlugin.SOURCE_ICCOA_CARLINK, api = 99)
        discoverPlugins("test.carlink", "test.disabled", "test.incompatible")
        val registry = projectionPlugins(context)
        registry.refresh()
        registry.setEnabled(registry.entries().single { it.component.packageName == "test.carlink" }, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            buttons(activity).single { it.text.startsWith("切换输入源：") }.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val names = (0 until dialog.listView.count).map { dialog.listView.adapter.getItem(it).toString() }
            assertEquals(listOf("CarPlay (iPhone)", "Android Auto", "VNC", "Android 虚拟桌面", "ICCOA CarLink test.carlink"), names)
            dialog.dismiss()
            selectHomeSource(activity, "ICCOA CarLink test.carlink")
            assertTrue(buttons(activity).single { it.text == "连接" }.isEnabled)
            assertNull(projectionSessions.snapshot())
            assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
            capture(activity, "home-carlink", bottom = true)
            buttons(activity).single { it.text == "输入源设置" }.performClick()
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any { it.text == "ICCOA CarLink" })
            buttons(activity).single { it.text == "停用插件" }.performClick()
            tabs(activity).selectedItemId = 1
            assertTrue(buttons(activity).any { it.text == "切换输入源：CarPlay (iPhone)" })
            assertEquals("carplay", DiPlayPreferences.homeSource(activity))
        } finally {
            discoverPlugins(); registry.refresh()
            controller.pause().stop().destroy(); WebSession.stop()
        }
    }

    @Test fun pluginFailureRemainsVisibleOnHomeAndIccoaPageUntilRetry() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        discoverPlugins("test.carlink")
        val registry = projectionPlugins(context); registry.refresh()
        val plugin = registry.entries().single(); registry.setEnabled(plugin, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            selectHomeSource(activity, "ICCOA CarLink test.carlink")
            registry.recordFailure(plugin, "Nearby devices permission missing")
            controller.recreate(); activity = controller.get()
            fun hasFailure() = views(page(activity)).filterIsInstance<TextView>().any {
                it.visibility == View.VISIBLE && it.text.toString().contains("Nearby devices permission missing")
            }
            assertTrue(hasFailure())
            buttons(activity).single { it.text == "输入源设置" }.performClick()
            assertTrue(hasFailure())
            assertTrue(buttons(activity).single { it.text == "连接插件" }.isEnabled)
            registry.beginAttempt(plugin)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertFalse(hasFailure())
        } finally {
            discoverPlugins(); registry.refresh()
            controller.pause().stop().destroy(); WebSession.stop()
        }
    }

    @Test fun androidAutoPermissionDenialPreservesHomeSelectionAndContinuesWithoutMicrophone() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            AirPlayPersistence.saveMicrophoneSource(activity, CarPlayMicrophoneSource.ANDROID)
            shadowOf(activity.application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
            tabs(activity).selectedItemId = 2
            cards(activity)[1].performClick()
            buttons(activity).single { it.text == "通过 USB 连接 Android Auto" }.performClick()
            val request = shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf(android.Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
            assertEquals("android-auto", DiPlayPreferences.homeSource(activity))
            while (shadowOf(activity).nextStartedService != null) { /* Drain initial service startup. */ }
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(DiPlaySessionService::class.java.name,
                shadowOf(activity).nextStartedService.component!!.className)
            assertEquals("android-auto", DiPlayPreferences.homeSource(activity))
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun selectingAnotherSourceKeepsActiveProjectionVisibleUntilExplicitStop() {
        var stops = 0
        val active = object : ProjectionSession {
            override val descriptor = SourceDescriptor("android-auto-usb", "Android Auto · USB")
            override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE), 0)
            override val inputReady = true
            override val statusText = "USB connected"
            override fun touch(contacts: List<ProjectionContact>) = false
            override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(Unit).also { stops++ }
        }
        assertTrue(projectionSessions.adopt(active, setOf(ProjectionResource.DISPLAY)))
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            selectHomeSource(activity, "VNC")
            assertSame(active, projectionSessions.snapshot()); assertEquals(0, stops)
            assertTrue(views(page(activity)).filterIsInstance<TextView>().any {
                it.text.toString().contains("当前投影：Android Auto · USB") && it.text.toString().contains("USB connected")
            })
            buttons(activity).single { it.text == "停止投影：Android Auto · USB" }.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, stops); assertNull(projectionSessions.snapshot())
            assertTrue(buttons(activity).any { it.text == "切换输入源：VNC" })
        } finally {
            projectionSessions.ended(active)
            controller.pause().stop().destroy(); WebSession.stop()
        }
    }

    @Test fun detailSurvivesRecreationAndBackAndTabReselectionReturnToIndex() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            tabs(activity).selectedItemId = 2
            cards(activity)[1].performClick()
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            controller.recreate()
            activity = controller.get()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接本机 Android Auto" })
            tabs(activity).selectedItemId = 2
            assertTrue(buttons(activity).isEmpty())
            cards(activity)[0].performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).isEmpty())
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, tabs(activity).selectedItemId)
            // The launch-page shortcut should open CarPlay details and select the Sources tab.
            buttons(activity).single { it.text == "输入源设置" }.performClick()
            assertEquals(2, tabs(activity).selectedItemId)
            assertTrue(buttons(activity).any { it.text == "连接 iPhone" })
        } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }
}
