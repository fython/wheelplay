package moe.feng.wheelplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.*
import android.os.Bundle
import android.os.Looper
import android.annotation.SuppressLint
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.core.operations.Header
import com.shilapi.xcertplay.projection.plugin.*
import moe.feng.wheelplay.plugin.*
import moe.feng.wheelplay.settings.PluginSettingsDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressLint("RestrictedApi")
class PluginSettingsUiTest : ComposeSettingsTest() {
    class HostActivity : ComponentActivity()
    class Provider : ProjectionSettingsProvider() {
        var revision = 0L
        var counter = false
        var caption = "Initial caption"
        var reads = 0
        var remoteSupported = false
        var rejectRemote = false
        @Volatile var afterWrite: (() -> Unit)? = null
        override val supportsRemoteCompose get() = remoteSupported
        override fun isHostAllowed(uid: Int) = true
        override fun readSettings(source: ComponentName): PluginSettingsSchema {
            reads++
            return PluginSettingsSchema(revision, "Plugin settings fixture", listOf(
                PluginSetting("counter", "Counter", SettingType.BOOLEAN, counter),
                PluginSetting("caption", "Caption", SettingType.TEXT, caption, maxLength = 40)))
        }
        override fun writeSetting(source: ComponentName, key: String, value: Any?) {
            if (key == "counter") counter = value as Boolean else caption = value as String
            revision++
            afterWrite?.invoke()
        }
        fun externalChange() { counter = false; revision++; notifySettingsChanged() }
        fun externalCaptionChange() { caption = "Changed elsewhere"; revision++; notifySettingsChanged() }
        override fun remoteDocument(source: ComponentName): PluginSettingsDocument {
            require(!rejectRemote) { "Remote layout unavailable" }
            val writer = RemoteComposeWriter(RcPlatformProfiles.ANDROIDX,
                RemoteComposeWriter.hTag(Header.DOC_WIDTH, 320), RemoteComposeWriter.hTag(Header.DOC_HEIGHT, 180))
            writer.rcPaint.setColor(if (counter) Color.GREEN else Color.BLUE).commit()
            writer.drawRect(0f, 0f, 320f, 180f)
            writer.addClickArea(1, "Toggle counter", 0f, 0f, 320f, 180f, null)
            return PluginSettingsDocument(writer.encodeToByteArray(), 320, 180)
        }
        override fun onRemoteAction(source: ComponentName, actionId: Int, value: String?) {
            require(actionId == 1); counter = !counter; revision++
        }
    }
    private fun install(): Pair<InstalledProjectionPlugin, Provider> {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName("test.plugin", "test.plugin.Source")
        val application = ApplicationInfo().apply { packageName = "test.plugin"; uid = 12345; enabled = true }
        val service = ServiceInfo().apply {
            name = component.className; packageName = component.packageName; applicationInfo = application
            enabled = true; exported = true
            metaData = Bundle().apply {
                putInt(ProjectionPlugin.META_API_VERSION, ProjectionPlugin.API_VERSION)
                putString(ProjectionSettings.META_PROVIDER, "test.plugin.settings")
            }
        }
        val providerInfo = ProviderInfo().apply {
            name = Provider::class.java.name; packageName = component.packageName; applicationInfo = application
            authority = "test.plugin.settings"; enabled = true; exported = true
        }
        val pkg = PackageInfo().apply {
            packageName = component.packageName; applicationInfo = application
            versionCode = 1; lastUpdateTime = 10; firstInstallTime = 5
            services = arrayOf(service); providers = arrayOf(providerInfo)
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("1234"))) }
        }
        val pm = shadowOf(app.packageManager)
        pm.installPackage(pkg)
        pm.setResolveInfosForIntent(Intent(ProjectionPlugin.ACTION), listOf(ResolveInfo().apply { serviceInfo = service }))
        val pluginContext = object : ContextWrapper(app) {
            override fun getPackageName() = component.packageName
            override fun getApplicationInfo() = application
        }
        val provider = Provider().apply { attachInfo(pluginContext, providerInfo) }
        ShadowContentResolver.registerProviderInternal(providerInfo.authority, provider)
        val registry = projectionPlugins(app)
        registry.refresh()
        val plugin = registry.entries().single()
        assertFalse(registry.enabled(plugin)) // Setup must be accessible before projection is enabled.
        return plugin to provider
    }
    private fun waitFor(predicate: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        predicate()
    }
    private fun clickEnabledText(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        // Provider writes and observer reloads run outside Compose's idling
        // resources. A visible button can still be disabled by an in-flight call.
        waitFor { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performClick()
    }
    private fun editCaption(value: String) {
        clickEnabledText("Edit")
        waitFor { compose.onAllNodesWithTag("plugin:setting-input").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("plugin:setting-input").performTextReplacement(value)
    }

    @Test fun hostActivityRendersSchemaPersistsEditsAndObservesExternalChangesWithoutLaunchingPluginActivity() {
        val (plugin, provider) = install()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val wrote = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        provider.afterWrite = {
            wrote.countDown()
            check(releaseWrite.await(10, TimeUnit.SECONDS)) { "Test did not release the settings write" }
        }
        try {
            activity.setContent { PluginSettingsDialog(plugin, activity.lifecycle, {}, null) }
            waitFor { compose.onAllNodesWithTag("plugin:setting:counter:switch").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("plugin:setting:counter:switch").assertIsOff().performClick()
            waitFor { wrote.count == 0L }
            assertTrue(provider.counter)
            compose.onNodeWithText("Edit").assertIsNotEnabled()
            releaseWrite.countDown()
            editCaption("Saved inside host")
            compose.onNodeWithTag("plugin:setting:counter:switch").assertIsOn()
            compose.onNodeWithText("Save").performClick()
            waitFor { compose.onAllNodesWithText("Saved inside host").fetchSemanticsNodes().size == 1 }
            assertEquals("Saved inside host", provider.caption)
            provider.externalChange()
            waitFor { compose.onNodeWithTag("plugin:setting:counter:switch").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off }
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { releaseWrite.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun leavingTheActivityStopsObservingAndResumeReloadsCurrentSettings() {
        val (plugin, provider) = install()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContent { PluginSettingsDialog(plugin, activity.lifecycle, {}, null) }
            waitFor { provider.reads >= 2 }
            compose.waitForIdle()
            controller.pause().stop()
            val reads = provider.reads
            provider.externalChange()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(reads, provider.reads)
            controller.restart().start().resume()
            waitFor { provider.reads > reads }
            compose.onNodeWithTag("plugin:setting:counter:switch").assertIsOff()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun anOpenEditorCannotOverwriteAConcurrentExternalChange() {
        val (plugin, provider) = install()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContent { PluginSettingsDialog(plugin, activity.lifecycle, {}, null) }
            waitFor { compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
            editCaption("Old editor value")
            val reads = provider.reads
            provider.externalCaptionChange()
            waitFor { provider.reads > reads }
            compose.onNodeWithText("Save").performClick()
            waitFor { compose.onAllNodesWithText("Settings changed elsewhere. The latest values have been loaded.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Changed elsewhere", provider.caption)
            assertEquals(1L, provider.revision)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun remoteComposeIsRenderedInTheHostAndScaledTouchUpdatesTheSchema() {
        val (plugin, provider) = install()
        provider.remoteSupported = true
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContent { PluginSettingsDialog(plugin, activity.lifecycle, {}, null) }
            waitFor { compose.onAllNodesWithText("Plugin layout").fetchSemanticsNodes().isNotEmpty() }
            clickEnabledText("Plugin layout")
            waitFor { compose.onAllNodesWithTag("plugin:remote-compose").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("plugin:remote-compose").performScrollTo().performTouchInput {
                click(center)
            }
            waitFor { provider.counter }
            clickEnabledText("Standard settings")
            waitFor { compose.onAllNodesWithTag("plugin:setting:counter:switch").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("plugin:setting:counter:switch").assertIsOn()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedRemoteLayoutCanReturnToTheStandardForm() = checkRemoteLayoutFailure(immediate = false)

    @Test fun immediateRemoteFailureDoesNotLeaveTheStandardFormButtonBusy() = checkRemoteLayoutFailure(immediate = true)

    private fun checkRemoteLayoutFailure(immediate: Boolean) {
        val (plugin, provider) = install()
        provider.remoteSupported = true
        provider.rejectRemote = true
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            // A worker can finish before requestAsync suspends. Exercise that ordering
            // deterministically instead of depending on CI thread scheduling.
            activity.setContent {
                PluginSettingsDialog(plugin, activity.lifecycle, {}, null,
                    requestSettings = { client, method, extras ->
                        if (immediate) client.request(method, extras) else client.requestAsync(method, extras)
                    })
            }
            waitFor { compose.onAllNodesWithText("Plugin layout").fetchSemanticsNodes().isNotEmpty() }
            repeat(3) {
                clickEnabledText("Plugin layout")
                waitFor { compose.onAllNodesWithText("Remote layout unavailable").fetchSemanticsNodes().isNotEmpty() }
                clickEnabledText("Standard settings")
                waitFor { compose.onAllNodesWithTag("plugin:setting:counter:switch").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("plugin:setting:counter:switch").assertIsOff()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
