package com.shilapi.xcertplay.projection.plugin

import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import com.shilapi.xcertplay.projection.*
import moe.feng.wheelplay.plugin.ProjectionPlugin
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import com.shilapi.xcertplay.shared.R as UiR

internal data class InstalledProjectionPlugin(
    val component: ComponentName,
    val name: String,
    val uid: Int,
    val signer: String,
    val updatedAt: Long,
    val version: Long,
    val settings: ComponentName?,
    val problem: String?,
    val installedAt: Long = 0,
    val sourceId: String? = null,
) {
    val id get() = PREFIX + component.flattenToString()
    val consentKey get() = "$signer@$installedAt"
    companion object { const val PREFIX = "plugin-" }
}

/** Package metadata only: discovering/listing an installed APK never binds or starts its code. */
internal class ProjectionPluginRegistry(private val context: Context, private val runtime: ProjectionRuntime) {
    private val preferences = context.getSharedPreferences("projection_plugins", Context.MODE_PRIVATE)
    @Volatile private var installed = emptyList<InstalledProjectionPlugin>()
    private val main = Handler(Looper.getMainLooper())
    private var watching = false
    fun entries() = installed
    fun entriesForSource(sourceId: String) = installed.filter { it.sourceId == sourceId }
    fun enabled(plugin: InstalledProjectionPlugin) = plugin.signer.isNotEmpty() &&
        preferences.getString(plugin.id, null) == plugin.consentKey
    fun available(plugin: InstalledProjectionPlugin): Boolean = installed.any {
        it.component == plugin.component && it.uid == plugin.uid && it.signer == plugin.signer &&
            it.updatedAt == plugin.updatedAt && it.version == plugin.version && it.installedAt == plugin.installedAt && it.problem == null
    } && enabled(plugin) && plugin.problem == null

    fun setEnabled(plugin: InstalledProjectionPlugin, enabled: Boolean) {
        if (enabled) {
            require(installed.contains(plugin) && plugin.problem == null && plugin.signer.isNotEmpty())
            preferences.edit().putString(plugin.id, plugin.consentKey).apply()
        } else preferences.edit().remove(plugin.id).apply()
        refresh()
    }

    fun watch() {
        if (watching) return
        watching = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    val active = runtime.snapshot() as? RemotePluginSession
                    if (active?.plugin?.component?.packageName == intent.data?.schemeSpecificPart) runtime.stop(active)
                    return // Preserve consent across the temporary removal in a signed APK update.
                }
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    val pkg = intent.data?.schemeSpecificPart
                    preferences.edit().also { edit ->
                        installed.filter { it.component.packageName == pkg }.forEach { edit.remove(it.id) }
                    }.apply()
                }
                refresh()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            // All filter actions are protected package-manager broadcasts on older Android releases.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized fun refresh() {
        val pm = context.packageManager
        installed = pm.queryIntentServices(Intent(ProjectionPlugin.ACTION),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS).mapNotNull { result ->
            val service = result.serviceInfo ?: return@mapNotNull null
            runCatching {
                val pkg = pm.getPackageInfo(service.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signer = pkg.signingInfo?.apkContentsSigners?.map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
                }?.sorted()?.joinToString(":").orEmpty()
                val component = ComponentName(service.packageName, service.name)
                val version = service.metaData?.getInt(ProjectionPlugin.META_API_VERSION, -1)
                val problem = when {
                    !service.exported || !service.enabled || !service.applicationInfo.enabled -> context.getString(UiR.string.plugin_unavailable)
                    version != ProjectionPlugin.API_VERSION -> context.getString(UiR.string.plugin_incompatible)
                    signer.isEmpty() -> context.getString(UiR.string.plugin_untrusted)
                    service.permission != null && pm.checkPermission(service.permission, context.packageName) != PackageManager.PERMISSION_GRANTED -> context.getString(UiR.string.plugin_unavailable)
                    else -> null
                }
                val settings = service.metaData?.getString(ProjectionPlugin.META_SETTINGS_ACTIVITY)?.let { name ->
                    val activity = ComponentName(service.packageName, if (name.startsWith('.')) service.packageName + name else name)
                    runCatching { pm.getActivityInfo(activity, 0) }.getOrNull()?.takeIf {
                        it.exported && it.enabled && it.applicationInfo.enabled &&
                            (it.permission == null || pm.checkPermission(it.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
                    }?.let { activity }
                }
                InstalledProjectionPlugin(component, service.loadLabel(pm).toString().take(120),
                    service.applicationInfo.uid, signer, pkg.lastUpdateTime, pkg.longVersionCode, settings, problem, pkg.firstInstallTime,
                    service.metaData?.getString(ProjectionPlugin.META_SOURCE_ID))
            }.getOrNull()
        }.distinctBy { it.id }.sortedBy { it.name }
        // An uninstall observed while WheelPlay was stopped must revoke remembered consent too.
        val ids = installed.map { it.id }.toSet()
        preferences.edit().also { edit -> preferences.all.keys.filter { it !in ids }.forEach(edit::remove) }.apply()
        runtime.replaceProviders(InstalledProjectionPlugin.PREFIX, installed.map { plugin ->
            RemotePluginProvider(context, plugin, this, runtime)
        })
        val active = runtime.snapshot() as? RemotePluginSession
        if (active != null && !available(active.plugin)) main.post { runtime.stop(active) }
    }
}

internal class RemotePluginProvider(
    private val context: Context, private val plugin: InstalledProjectionPlugin,
    private val registry: ProjectionPluginRegistry, private val runtime: ProjectionRuntime,
) : ProjectionSourceProvider {
    override val descriptor = SourceDescriptor(plugin.id, plugin.name)
    // Reserve resources conservatively for SDKs which own USB, Wi-Fi and microphone internally.
    override val resources = ProjectionResource.entries.toSet()
    override fun probe(): SourceAvailability = when {
        plugin.problem != null -> SourceAvailability.Unavailable(plugin.problem)
        !registry.available(plugin) -> SourceAvailability.Unavailable(context.getString(UiR.string.plugin_disabled))
        else -> SourceAvailability.Ready
    }
    override fun open(): CompletableFuture<ProjectionSession> = CompletableFuture.completedFuture(
        RemotePluginSession(context, plugin, runtime, { registry.available(plugin) }, { width, height ->
            BrowserProjectionVideo(context, width, height, VideoIngressKind.SURFACE)
        }))
}

private var registry: ProjectionPluginRegistry? = null
@Synchronized internal fun projectionPlugins(context: Context): ProjectionPluginRegistry = registry ?: run {
    ProjectionPluginRegistry(context.applicationContext, projectionSessions).also { registry = it; it.watch(); it.refresh() }
}
