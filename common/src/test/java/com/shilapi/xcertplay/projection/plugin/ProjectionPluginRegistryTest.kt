package com.shilapi.xcertplay.projection.plugin

import android.content.*
import android.content.pm.*
import android.os.Bundle
import com.shilapi.xcertplay.projection.*
import moe.feng.wheelplay.plugin.ProjectionPlugin
import moe.feng.wheelplay.plugin.ProjectionSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionPluginRegistryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pm get() = shadowOf(context.packageManager)
    private val component = ComponentName("test.plugin", "test.plugin.Source")
    private fun install(signature: String = "1234", version: Int = 1, exported: Boolean = true, api: Int = ProjectionPlugin.API_VERSION, installedAt: Long = 10, sourceId: String? = null, requiresViewport: Boolean = false, component: ComponentName = this.component) {
        val app = ApplicationInfo().apply { packageName = component.packageName; uid = 12345; enabled = true }
        pm.installPackage(PackageInfo().apply {
            packageName = component.packageName; applicationInfo = app; lastUpdateTime = version.toLong()
            firstInstallTime = installedAt
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(signature))) }
            services = arrayOf(ServiceInfo().apply {
                name = component.className; packageName = component.packageName; applicationInfo = app
                this.exported = exported; enabled = true
                metaData = Bundle().apply { putInt(ProjectionPlugin.META_API_VERSION, api); putString(ProjectionPlugin.META_SOURCE_ID, sourceId); putBoolean(ProjectionPlugin.META_REQUIRES_INITIAL_VIEWPORT, requiresViewport) }
            })
        })
        val service = context.packageManager.getPackageInfo(component.packageName, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA).services!!.single()
        pm.setResolveInfosForIntent(Intent(ProjectionPlugin.ACTION), listOf(ResolveInfo().apply { serviceInfo = service }))
    }
    @Test fun legacyStandaloneCarLifeIsIgnoredWithoutAffectingBuiltInSource() {
        val legacyPackage = InstalledProjectionPlugin.LEGACY_CARLIFE_PACKAGE
        install(sourceId = ProjectionPlugin.SOURCE_CARLIFE_LOOPBACK,
            component = ComponentName(legacyPackage, "$legacyPackage.CarLifeService"))
        val runtime = ProjectionRuntime()
        runtime.register(moe.feng.wheelplay.projection.carlife.CarLifeSource(context))
        val registry = ProjectionPluginRegistry(context, runtime)
        registry.refresh()
        assertTrue(registry.entries().isEmpty())
        assertEquals(listOf(ProjectionSourceIds.CARLIFE), runtime.sources().map { it.descriptor.id })
    }

    @Test fun nativeCarLifeIsIndependentOfPluginDiscoveryAndSettingsStayPrivate() {
        val runtime = ProjectionRuntime()
        val source = moe.feng.wheelplay.projection.carlife.CarLifeSource(context)
        runtime.register(source)
        val registry = ProjectionPluginRegistry(context, runtime)
        registry.refresh()
        assertEquals(SourceAvailability.Ready, source.probe())
        assertFalse(registry.allEntries().any { it.id == ProjectionSourceIds.CARLIFE })
        assertSame(source, runtime.sources().single { it.descriptor.id == ProjectionSourceIds.CARLIFE })
        val settings = ComponentName(context, moe.feng.wheelplay.projection.carlife.CarLifeSettingsActivity::class.java)
        assertFalse(context.packageManager.getActivityInfo(settings, 0).exported)
    }

    @Test fun installationIsDisabledByDefaultAndSignedUpdatePreservesConsentButNotOldProvider() {
        install()
        val runtime = ProjectionRuntime(); val registry = ProjectionPluginRegistry(context, runtime)
        registry.refresh()
        val original = registry.entries().single()
        assertNull(original.problem)
        assertEquals("plugin-test.plugin/test.plugin.Source", original.id)
        assertFalse(registry.enabled(original)); assertFalse(registry.available(original))
        assertTrue(runtime.sources().single { it.descriptor.id.startsWith(InstalledProjectionPlugin.PREFIX) }.probe() is SourceAvailability.Unavailable)
        registry.setEnabled(original, true)
        assertTrue(registry.available(original)); assertEquals(SourceAvailability.Ready, runtime.sources().single { it.descriptor.id.startsWith(InstalledProjectionPlugin.PREFIX) }.probe())
        install(version = 2); registry.refresh()
        assertFalse(registry.available(original)); assertTrue(registry.enabled(registry.entries().single()))
        assertEquals(SourceAvailability.Ready, runtime.sources().single { it.descriptor.id.startsWith(InstalledProjectionPlugin.PREFIX) }.probe())
        install(signature = "5678", version = 3); registry.refresh()
        assertFalse(registry.enabled(registry.entries().single()))
        assertTrue(runtime.sources().single { it.descriptor.id.startsWith(InstalledProjectionPlugin.PREFIX) }.probe() is SourceAvailability.Unavailable)
    }
    @Test fun initialViewportRequirementIsOptInMetadata() {
        val registry = ProjectionPluginRegistry(context, ProjectionRuntime())
        install(); registry.refresh()
        assertFalse(registry.entries().single().requiresInitialViewport)
        install(requiresViewport = true); registry.refresh()
        assertTrue(registry.entries().single().requiresInitialViewport)
    }

    @Test fun sourceAssociationIsOptionalAndDoesNotChangeRuntimeIdentityOrGrantConsent() {
        val registry = ProjectionPluginRegistry(context, ProjectionRuntime())
        install(); registry.refresh()
        val id = registry.entries().single().id
        assertTrue(registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK).isEmpty())
        install(sourceId = "ICCOA CarLink"); registry.refresh()
        assertTrue(registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK).isEmpty())
        install(sourceId = ProjectionPlugin.SOURCE_ICCOA_CARLINK); registry.refresh()
        val entry = registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK).single()
        assertEquals(id, entry.id)
        assertFalse(registry.enabled(entry)); assertFalse(registry.available(entry))
    }

    @Test fun failureSurvivesRefreshAndClearsOnRetryOrPackageChange() {
        install(); val runtime = ProjectionRuntime(); val registry = ProjectionPluginRegistry(context, runtime)
        registry.refresh(); val original = registry.entries().single()
        registry.setEnabled(original, true)
        registry.recordFailure(original, "Permission missing")
        registry.refresh()
        assertEquals("Permission missing", registry.lastFailure(original.id))
        runtime.sources().single { it.descriptor.id.startsWith(InstalledProjectionPlugin.PREFIX) }.open() // An explicit retry clears the previous failure.
        assertNull(registry.lastFailure(original.id))
        registry.recordFailure(original, "Failed again")
        install(version = 2); registry.refresh()
        assertNull(registry.lastFailure(original.id))
        registry.recordFailure(original, "Late callback from old APK")
        assertNull(registry.lastFailure(original.id))
    }

    @Test fun uninstalledSourceIsRemovedAndReinstallRequiresEnableAgain() {
        install(); val runtime = ProjectionRuntime(); val registry = ProjectionPluginRegistry(context, runtime)
        registry.refresh(); registry.setEnabled(registry.entries().single(), true)
        pm.removePackage(component.packageName)
        pm.setResolveInfosForIntent(Intent(ProjectionPlugin.ACTION), emptyList())
        registry.refresh()
        assertTrue(registry.entries().isEmpty()); assertTrue(runtime.sources().isEmpty())
        install(); registry.refresh(); assertFalse(registry.enabled(registry.entries().single()))
    }
    @Test fun incompatibleAndPrivateServicesCannotBeEnabled() {
        val registry = ProjectionPluginRegistry(context, ProjectionRuntime())
        for ((exported, api) in listOf(false to ProjectionPlugin.API_VERSION, true to 99, true to 1)) {
            install(exported = exported, api = api); registry.refresh()
            val entry = registry.entries().single()
            assertNotNull(entry.problem)
            assertThrows(IllegalArgumentException::class.java) { registry.setEnabled(entry, true) }
        }
    }
    @Test fun reinstallWhileHostWasStoppedDoesNotReuseConsentEvenWithSameSignature() {
        install(); val registry = ProjectionPluginRegistry(context, ProjectionRuntime())
        registry.refresh(); registry.setEnabled(registry.entries().single(), true)
        pm.removePackage(component.packageName)
        install(installedAt = 20) // No intermediate refresh or package broadcast was observed by the host.
        registry.refresh()
        assertFalse(registry.enabled(registry.entries().single()))
    }

    @Test fun embeddedSettingsOnlyAcceptAnAccessibleProviderFromTheSamePluginUid() {
        val registry = ProjectionPluginRegistry(context, ProjectionRuntime())
        install()
        val pkg = context.packageManager.getPackageInfo(component.packageName, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)
        pkg.services!!.single().metaData.putString(ProjectionSettings.META_PROVIDER, "test.plugin.settings")
        val provider = ProviderInfo().apply {
            name = "test.plugin.Settings"; packageName = component.packageName
            authority = "test.plugin.settings"; applicationInfo = pkg.applicationInfo
            exported = true; enabled = true
        }
        pkg.providers = arrayOf(provider)
        pm.installPackage(pkg)
        pm.setResolveInfosForIntent(Intent(ProjectionPlugin.ACTION), listOf(ResolveInfo().apply {
            serviceInfo = pkg.services!!.single()
        }))
        registry.refresh()
        assertEquals("test.plugin.settings", registry.entries().single().settingsProvider)
        assertFalse(registry.enabled(registry.entries().single()))
        provider.exported = false; pkg.providers = arrayOf(provider); pm.installPackage(pkg); registry.refresh()
        assertNull(registry.entries().single().settingsProvider)
        provider.exported = true
        // An authority owned by another installed APK must not become a settings entry.
        pm.removeProvider(ComponentName(component.packageName, provider.name))
        val foreignApp = ApplicationInfo(pkg.applicationInfo).apply { packageName = "other.plugin"; uid += 1 }
        pm.installPackage(PackageInfo().apply {
            packageName = foreignApp.packageName; applicationInfo = foreignApp
            providers = arrayOf(ProviderInfo(provider).apply {
                packageName = foreignApp.packageName; applicationInfo = foreignApp
            })
        })
        registry.refresh()
        assertNull(registry.entries().single().settingsProvider)
    }
}
