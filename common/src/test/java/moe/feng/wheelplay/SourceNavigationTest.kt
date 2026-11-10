package moe.feng.wheelplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.*
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.projection.plugin.projectionPlugins
import com.shilapi.xcertplay.web.WebSession
import moe.feng.wheelplay.plugin.ProjectionPlugin
import moe.feng.wheelplay.projection.desktop.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CompletableFuture
import com.shilapi.xcertplay.shared.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceNavigationTest : ComposeSettingsTest() {
    @Before fun provision() {
        val context = RuntimeEnvironment.getApplication()
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        WheelPlayPreferences.saveAutoConnect(context, false)
        WheelPlayPreferences.saveHomeSource(context, "carplay")
    }
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!tag.startsWith("nav:") && !tag.startsWith("dialog:") && tag != "source:back") node.performScrollTo()
        node.performClick()
    }
    private fun source(name: String) { tap("nav:phone"); tap("source-$name") }
    private fun action(name: String) = tap("action:$name")
    private fun text(value: String) = compose.onNodeWithText(value, substring = true)
    private fun refresh() { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)); compose.waitForIdle() }
    private fun chooseHome(name: String) { action("CHOOSE_HOME"); text(name).performClick() }
    private fun field(key: String, value: String) { compose.onNodeWithTag("form:$key").performScrollTo().performTextReplacement(value) }
    private fun cancel() { compose.onNodeWithText("取消").performClick() }
    private fun withActivity(block: (org.robolectric.android.controller.ActivityController<WheelPlayActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(WheelPlayActivity::class.java).setup()
        try { block(controller) } finally { controller.pause().stop().destroy(); WebSession.stop() }
    }

    @Test fun sourceRowsOnlyNavigateAndConnectionModesShowTheirOwnActions() = withActivity { controller ->
        val activity = controller.get()
        val initial = projectionSessions.snapshot()
        tap("nav:phone")
        for (id in listOf("carplay", "android-auto", "iccoa", "vnc", "desktop", "plugins", "diagnostics")) compose.onNodeWithTag("source-$id").assertExists()
        tap("source-carplay"); compose.onNodeWithTag("action:CARPLAY_CONNECT").assertExists()
        compose.onNodeWithTag("action:AA_LOCAL").assertDoesNotExist()
        tap("source:back"); tap("source-android-auto")
        compose.onNodeWithTag("action:AA_LOCAL").assertExists()
        tap("aa:USB"); compose.onNodeWithTag("action:AA_USB").assertExists(); compose.onNodeWithTag("action:AA_LOCAL").assertDoesNotExist()
        tap("aa:NETWORK"); compose.onNodeWithTag("action:AA_NETWORK").assertExists()
        assertSame(initial, projectionSessions.snapshot()); assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun invalidVncAddressStaysEditableAndSecretsDoNotSurviveDismissalOrRecreation() = withActivity { controller ->
        source("vnc"); action("VNC")
        field("host", "hostname"); field("port", "5900"); field("password", "secret")
        tap("dialog:confirm"); compose.onNodeWithTag("form:error").assertExists()
        assertNull(projectionSessions.snapshot())
        cancel(); action("VNC"); compose.onNodeWithTag("form:password").assertTextContains("")
        field("password", "private"); controller.recreate()
        compose.onNodeWithTag("form:password").assertDoesNotExist()
        action("VNC"); compose.onNodeWithTag("form:password").assertTextEquals(controller.get().getString(UiR.string.vnc_password), "")
    }

    @Test fun missingShizukuDisablesStartAndActiveDesktopCanStillStop() = withActivity { controller ->
        source("desktop")
        compose.onNodeWithTag("action:DESKTOP_START").assertIsNotEnabled()
        val provider = projectionSessions.sources().filterIsInstance<AndroidDesktopSource>().single()
        text((provider.probe() as SourceAvailability.Unavailable).reason).assertExists()
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).boundServiceConnections.isEmpty())
        val active = fakeSession("android-desktop", "desktop-test-connected")
        try {
            assertTrue(projectionSessions.adopt(active, setOf(ProjectionResource.DISPLAY))); refresh()
            text("desktop-test-connected").assertExists(); action("STOP_SOURCE")
            assertNull(projectionSessions.snapshot())
        } finally { projectionSessions.stop().join() }
    }

    @Test fun desktopDisplayValidatesSavesCancelsAndRestoresDefaultsWithoutConnecting() = withActivity { _ ->
        source("desktop")
        val provider = projectionSessions.sources().filterIsInstance<AndroidDesktopSource>().single()
        action("DESKTOP_DISPLAY"); field("width", "99999999999"); tap("dialog:confirm")
        compose.onNodeWithTag("form:error").assertExists(); assertEquals(1280, provider.displayConfig().width)
        field("width", "1080"); field("height", "1920"); field("dpi", "320"); tap("dialog:confirm")
        assertEquals(DesktopDisplayConfig(1080, 1920, 320), provider.displayConfig())
        action("DESKTOP_DISPLAY"); tap("form:reset"); cancel(); assertEquals(320, provider.displayConfig().dpi)
        action("DESKTOP_DISPLAY"); tap("form:reset"); tap("dialog:confirm")
        assertEquals(DesktopDisplayConfig(), provider.displayConfig()); assertNull(projectionSessions.snapshot())
    }

    @Test fun homeStopCancelsPendingAcquisitionBeforeASessionExists() = withActivity { _ ->
        val pending = CompletableFuture<ProjectionSession>()
        val cancelled = java.util.concurrent.CountDownLatch(1)
        projectionSessions.register(object : ProjectionSourceProvider {
            override val descriptor = SourceDescriptor("pending-test", "Pending source")
            override val resources = setOf(ProjectionResource.DISPLAY)
            override fun probe() = SourceAvailability.Ready
            override fun open() = pending
            override fun open(startup: ProjectionStartup): CompletableFuture<ProjectionSession> {
                startup.onCancel { pending.completeExceptionally(java.util.concurrent.CancellationException()); cancelled.countDown() }
                return pending
            }
        })
        try {
            val opening = projectionSessions.open("pending-test"); refresh()
            assertNull(projectionSessions.snapshot()); action("HOME_STOP")
            assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(opening.isCompletedExceptionally)
            assertFalse(projectionSessions.hasSession())
        } finally { pending.completeExceptionally(java.util.concurrent.CancellationException()); projectionSessions.stop().join() }
    }

    @Test fun installedCarLinkUsesDeclaredIdAndRequiresExplicitEnableBeforeConnect() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        installPlugin("test.unrelated", null)
        discoverPlugins("test.carlink", "test.unrelated")
        try { withActivity { controller ->
            source("iccoa")
            text("test.unrelated").assertDoesNotExist()
            compose.onNodeWithTag("plugin:connect:test.carlink").assertDoesNotExist()
            tap("plugin:settings:test.carlink")
            assertEquals(ComponentName("test.carlink", "test.carlink.Settings"), shadowOf(controller.get()).nextStartedActivity.component)
            tap("plugin:enable:test.carlink")
            compose.onNodeWithText(controller.get().getString(com.shilapi.xcertplay.host.R.string.settings_confirm)).performClick()
            compose.onNodeWithTag("plugin:connect:test.carlink").assertIsEnabled()
            assertNull(projectionSessions.snapshot()); assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
            controller.pause(); installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK, 99); discoverPlugins("test.carlink"); controller.resume()
            text(projectionPlugins(context).entries().single().problem!!).assertExists()
            compose.onNodeWithTag("plugin:connect:test.carlink").assertDoesNotExist()
            controller.pause(); discoverPlugins(); controller.resume(); text("安装 CarLink 插件").assertExists()
        } } finally { discoverPlugins(); projectionPlugins(context).refresh() }
    }

    @Test fun homeDefaultsToCarPlayAndRemembersSelectionWithoutStartingIt() = withActivity { controller ->
        text("切换输入源：CarPlay (iPhone)").assertExists()
        chooseHome("VNC"); assertEquals("vnc", WheelPlayPreferences.homeSource(controller.get()))
        action("HOME_CONNECT"); compose.onNodeWithTag("form:host").assertExists(); cancel()
        WheelPlayPreferences.saveAutoConnect(controller.get(), true); controller.recreate()
        text("切换输入源：VNC").assertExists(); assertNull(shadowOf(controller.get()).nextStartedActivity)
        action("HOME_SETTINGS"); compose.onNodeWithTag("action:VNC").assertExists()
        tap("nav:service"); chooseHome("Android Auto"); action("HOME_CONNECT")
        compose.onNodeWithTag("aa:LOCAL").assertExists(); assertNull(projectionSessions.snapshot())
    }

    @Test fun homeListsOnlyEnabledCompatiblePluginsAndFallsBackWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK); installPlugin("test.disabled", null)
        installPlugin("test.incompatible", ProjectionPlugin.SOURCE_ICCOA_CARLINK, 99)
        discoverPlugins("test.carlink", "test.disabled", "test.incompatible")
        val registry = projectionPlugins(context); registry.refresh(); registry.setEnabled(registry.entries().single { it.component.packageName == "test.carlink" }, true)
        try { withActivity { _ ->
            action("CHOOSE_HOME"); text("test.disabled").assertDoesNotExist(); text("test.incompatible").assertDoesNotExist()
            text("ICCOA CarLink test.carlink").performClick(); action("HOME_SETTINGS")
            tap("plugin:manage:test.carlink"); tap("plugin:disable:test.carlink"); tap("nav:service")
            text("切换输入源：CarPlay (iPhone)").assertExists(); assertNull(projectionSessions.snapshot())
        } } finally { discoverPlugins(); registry.refresh() }
    }

    @Test fun pluginFailureRemainsVisibleAcrossPagesUntilRetry() {
        val context = RuntimeEnvironment.getApplication()
        installPlugin("test.carlink", ProjectionPlugin.SOURCE_ICCOA_CARLINK); discoverPlugins("test.carlink")
        val registry = projectionPlugins(context); registry.refresh(); val plugin = registry.entries().single(); registry.setEnabled(plugin, true)
        try { withActivity { controller ->
            chooseHome("ICCOA CarLink test.carlink"); registry.recordFailure(plugin, "Nearby devices permission missing"); controller.recreate()
            text("Nearby devices permission missing").assertExists(); action("HOME_SETTINGS")
            text("Nearby devices permission missing").assertExists(); registry.beginAttempt(plugin); refresh()
            text("Nearby devices permission missing").assertDoesNotExist()
        } } finally { discoverPlugins(); registry.refresh() }
    }

    @Test fun permissionDenialPreservesAndroidAutoSelectionAndContinuesWithoutMicrophone() = withActivity { controller ->
        val activity = controller.get()
        AirPlayPersistence.saveMicrophoneSource(activity, CarPlayMicrophoneSource.ANDROID)
        shadowOf(activity.application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        source("android-auto"); tap("aa:USB"); action("AA_USB")
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(android.Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
        assertEquals("android-auto", WheelPlayPreferences.homeSource(activity))
        while (shadowOf(activity).nextStartedService != null) {}
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        refresh(); assertEquals(WheelPlaySessionService::class.java.name, shadowOf(activity).nextStartedService.component!!.className)
        projectionSessions.stop().join()
    }

    private fun fakeSession(id: String, status: String, cleanup: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)) = object : ProjectionSession {
        override val descriptor = SourceDescriptor(id, status)
        override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.SURFACE))
        override val inputReady = true
        override val statusText = status
        override fun touch(contacts: List<ProjectionContact>) = false
        override fun stop() = cleanup
    }

    @Test fun choosingAnotherSourceKeepsRunningProjectionUntilExplicitStop() {
        val cleanup = CompletableFuture<Unit>()
        val active = fakeSession("demo-surface", "Active demo", cleanup)
        assertTrue(projectionSessions.adopt(active, setOf(ProjectionResource.DISPLAY)))
        try { withActivity { _ ->
            chooseHome("VNC"); assertSame(active, projectionSessions.snapshot()); text("当前投影：Active demo").assertExists()
            action("HOME_STOP"); assertTrue(projectionSessions.hasSession()); cleanup.complete(Unit); refresh()
            assertNull(projectionSessions.snapshot()); assertFalse(projectionSessions.hasSession())
        } } finally { cleanup.complete(Unit); projectionSessions.stop().join() }
    }

    @Test fun indexDistinguishesHomeSelectionFromRunningAndStoppingSession() {
        val cleanup = CompletableFuture<Unit>()
        val active = fakeSession(ProjectionSourceIds.ANDROID_AUTO_USB, "Android Auto · USB", cleanup)
        assertTrue(projectionSessions.adopt(active, setOf(ProjectionResource.DISPLAY)))
        try { withActivity { controller ->
            chooseHome("VNC"); tap("nav:phone")
            compose.onNodeWithTag("source-vnc").assert(hasText("首页默认", substring = true))
            compose.onNodeWithTag("source-android-auto").assert(hasText(controller.get().getString(UiR.string.projection_state_running), substring = true))
            val stopped = projectionSessions.stop(active); refresh()
            compose.onNodeWithTag("source-android-auto").assert(hasText(controller.get().getString(UiR.string.projection_state_stopping), substring = true))
            assertFalse(stopped.isDone); cleanup.complete(Unit); stopped.join(); refresh()
            compose.onNodeWithTag("source-vnc").assert(hasText("首页默认", substring = true))
        } } finally { cleanup.complete(Unit); projectionSessions.stop().join() }
    }

    @Test fun connectionModeAndDetailSurviveRecreationAndBackReturnsToIndex() = withActivity { controller ->
        source("android-auto"); tap("aa:NETWORK"); controller.recreate()
        compose.onNodeWithTag("action:AA_NETWORK").assertExists(); compose.onNodeWithTag("action:AA_LOCAL").assertDoesNotExist()
        assertNull(projectionSessions.snapshot()); tap("aa:USB"); compose.onNodeWithTag("action:AA_USB").assertExists()
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("source-android-auto").assertExists()
        tap("source-carplay"); tap("nav:phone"); compose.onNodeWithTag("source-carplay").assertExists()
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav:service").assertIsSelected()
    }

    @Test fun emptyPluginPageHasInstallHelpWithoutFakeConnectionState() {
        discoverPlugins()
        withActivity { _ ->
            source("plugins"); text("尚未安装插件").assertExists(); text("等待连接").assertDoesNotExist(); text("未连接").assertDoesNotExist()
            tap("plugin:install-help"); text("从插件开发者处获取").assertExists()
            compose.onNodeWithTag("action:STOP_SOURCE").assertDoesNotExist()
        }
    }

    @Test fun sourceScreensReflowAndRetainAccessibleNavigation() {
        try {
            for ((width, height, scale) in listOf(Triple(320, 640, 1f), Triple(360, 800, 1.3f), Triple(320, 640, 2f), Triple(800, 400, 1f))) {
                RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h${height}dp-mdpi"); RuntimeEnvironment.setFontScale(scale)
                withActivity { controller ->
                    tap("nav:phone"); captureSettings(controller.get(), "sources-$width-$scale")
                    val first = compose.onNodeWithTag("source-carplay").fetchSemanticsNode().positionInRoot
                    val second = compose.onNodeWithTag("source-android-auto").fetchSemanticsNode().positionInRoot
                    if (width >= 600) assertEquals(first.y, second.y) else assertTrue(second.y > first.y)
                    for (id in listOf("carplay", "android-auto", "iccoa", "vnc", "plugins")) {
                        tap("source-$id"); compose.onNodeWithContentDescription(controller.get().getString(UiR.string.source_back)).assertExists()
                        captureSettings(controller.get(), "source-$id-$width-$scale")
                        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                        for (index in texts.fetchSemanticsNodes().indices) {
                            val layouts = mutableListOf<TextLayoutResult>()
                            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            layouts.forEach { layout ->
                                // Compose may retain a wider paragraph allocation after sizing to
                                // intrinsic text width. Check actual glyph lines rather than that box.
                                for (line in 0 until layout.lineCount) {
                                    assertFalse("Ellipsized: ${layout.layoutInput.text}", layout.isLineEllipsized(line))
                                    assertTrue("Clipped line: ${layout.layoutInput.text}", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1)
                                }
                                assertTrue("Clipped height: ${layout.layoutInput.text}", layout.multiParagraph.height <= layout.size.height + 1)
                            }
                        }
                        tap("source:back")
                    }
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f); RuntimeEnvironment.setQualifiers("zh-rCN-mdpi") }
    }

    @Test fun sourceNavigationUsesEnglishAndTraditionalChineseResources() {
        try {
            for (locale in listOf("en-rUS", "zh-rTW")) {
                RuntimeEnvironment.setQualifiers("$locale-mdpi")
                withActivity { controller ->
                    source("android-auto"); compose.onNodeWithContentDescription(controller.get().getString(UiR.string.source_back)).assertExists()
                    captureSettings(controller.get(), "source-aa-$locale")
                }
            }
        } finally { RuntimeEnvironment.setQualifiers("zh-rCN-mdpi") }
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

}
