package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import moe.feng.wheelplay.plugin.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

internal data class PluginSettingsSnapshot(
    val schema: PluginSettingsSchema,
    val supportsRemoteCompose: Boolean,
    val document: PluginSettingsDocument?,
    val conflict: Boolean = false,
)

/** Blocking transport; callers must use a worker. No plugin classes cross this boundary. */
internal class PluginSettingsClient(
    private val context: Context,
    val plugin: InstalledProjectionPlugin,
    private val isRegistered: () -> Boolean,
) {
    val uri = ProjectionSettings.uri(requireNotNull(plugin.settingsProvider))

    suspend fun requestAsync(method: String, extras: Bundle): PluginSettingsSnapshot = suspendCancellableCoroutine { continuation ->
        val task = FutureTask<Unit> {
            try { continuation.resumeWith(Result.success(request(method, extras))) }
            catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
        }
        continuation.invokeOnCancellation { task.cancel(true); workers.remove(task) }
        try { workers.execute(task) }
        catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
    }

    companion object {
        // A hung provider cannot create an unbounded number of blocked host threads.
        private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
            { runnable -> Thread(runnable, "plugin-settings-io").apply { isDaemon = true } })
    }

    @Suppress("DEPRECATION")
    fun checkIdentity() {
        check(isRegistered()) { "Plugin changed or was removed" }
        val pkg = context.packageManager.getPackageInfo(plugin.component.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(pkg.lastUpdateTime == plugin.updatedAt && pkg.longVersionCode == plugin.version &&
            pkg.firstInstallTime == plugin.installedAt) { "Plugin was updated" }
        val signer = pkg.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }?.sorted()?.joinToString(":").orEmpty()
        check(signer.isNotEmpty() && signer == plugin.signer) { "Plugin signer changed" }
        val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)
        check(provider != null && provider.packageName == plugin.component.packageName &&
            provider.applicationInfo.uid == plugin.uid && provider.enabled && provider.exported && provider.applicationInfo.enabled) {
            "Plugin settings provider unavailable"
        }
    }

    fun request(method: String = ProjectionSettings.GET, extras: Bundle = ProjectionSettings.request()): PluginSettingsSnapshot {
        checkIdentity()
        val response = requireNotNull(context.contentResolver.acquireUnstableContentProviderClient(uri)) {
            "Plugin settings provider unavailable"
        }.use { it.call(method, plugin.component.flattenToString(), extras) }
        checkIdentity() // Discard replies from an APK that changed during the call.
        requireNotNull(response)
        require(response.getInt("version") == ProjectionSettings.VERSION) { "Unsupported settings protocol" }
        val status = response.getString("status")
        check(status == "ok" || status == "conflict") {
            response.getString("message").orEmpty().take(256).ifBlank { "Plugin settings request failed" }
        }
        val schema = PluginSettingsSchema.fromJson(requireNotNull(response.getString("schema")))
        val document = response.getByteArray("remote_document")?.let { bytes ->
            require(response.getString("remote_version") == ProjectionSettings.REMOTE_COMPOSE_VERSION) {
                "Unsupported Remote Compose version"
            }
            PluginSettingsDocument(bytes, response.getInt("remote_width"), response.getInt("remote_height"))
        }
        return PluginSettingsSnapshot(schema, response.getBoolean("supports_remote"), document, status == "conflict")
    }
}
