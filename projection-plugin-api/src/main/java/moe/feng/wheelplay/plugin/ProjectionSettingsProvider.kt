package moe.feng.wheelplay.plugin

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** A plugin owns persistence and permission flows; reads must not start projection resources. */
abstract class ProjectionSettingsProvider : ContentProvider() {
    protected abstract fun readSettings(source: ComponentName): PluginSettingsSchema
    protected abstract fun writeSetting(source: ComponentName, key: String, value: Any?)
    protected open val supportsRemoteCompose: Boolean = false
    protected open fun remoteDocument(source: ComponentName): PluginSettingsDocument? = null
    protected open fun onRemoteAction(source: ComponentName, actionId: Int, value: String?) {
        throw UnsupportedOperationException("Unknown remote settings action")
    }
    protected open fun onNamedRemoteAction(source: ComponentName, name: String, value: Any?) {
        throw UnsupportedOperationException("Unknown named remote settings action")
    }
    protected open fun isHostAllowed(uid: Int): Boolean = uid == Process.myUid() ||
        requireNotNull(context).packageManager.getPackagesForUid(uid).orEmpty().any {
            it == "moe.feng.wheelplay" || it == "moe.feng.wheelplay.debug"
        }
    final override fun onCreate() = true

    @Suppress("DEPRECATION")
    @Synchronized
    final override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isHostAllowed(Binder.getCallingUid())) throw SecurityException("Settings caller not allowed")
        require(extras?.getInt("version") == ProjectionSettings.VERSION) { "Unsupported settings protocol" }
        val source = requireNotNull(arg?.let(ComponentName::unflattenFromString)) { "Missing settings source" }
        require(source.packageName == requireNotNull(context).packageName) { "Settings source belongs to another APK" }
        val service = requireNotNull(context).packageManager.getServiceInfo(source, PackageManager.GET_META_DATA)
        require(service.metaData?.getInt(ProjectionPlugin.META_API_VERSION) == ProjectionPlugin.API_VERSION) {
            "Not a projection source"
        }
        return try {
            val before = readSettings(source)
            if (method != ProjectionSettings.GET && extras.getLong("revision", -1) != before.revision)
                return snapshot(source, extras.getBoolean("include_remote"), "conflict")
            when (method) {
                ProjectionSettings.GET -> Unit
                ProjectionSettings.SET -> {
                    val key = requireNotNull(extras.getString("key"))
                    val field = requireNotNull(before.fields.singleOrNull { it.key == key }) { "Unknown setting" }
                    require(field.enabled) { "Setting is disabled" }
                    val encoded = requireNotNull(extras.getString("value"))
                    val value = ProjectionSettings.decodeValue(encoded)
                    field.validateValue(value)
                    writeSetting(source, key, value)
                }
                ProjectionSettings.ACTION -> {
                    require(supportsRemoteCompose)
                    val name = extras.getString("action_name")
                    if (name != null) {
                        require(name.length in 1..128)
                        onNamedRemoteAction(source, name,
                            ProjectionSettings.decodeValue(requireNotNull(extras.getString("action_json"))))
                    } else {
                        require(extras.containsKey("action_id"))
                        val value = extras.getString("action_value")
                        require(value == null || value.length <= 4096)
                        onRemoteAction(source, extras.getInt("action_id"), value)
                    }
                }
                else -> throw UnsupportedOperationException("Unknown settings method")
            }
            snapshot(source, extras.getBoolean("include_remote")).also {
                if (method != ProjectionSettings.GET) notifySettingsChanged()
            }
        } catch (error: IllegalArgumentException) {
            failure("invalid", error)
        } catch (error: UnsupportedOperationException) {
            failure("unsupported", error)
        } catch (error: Exception) {
            failure("error", error)
        }
    }

    private fun failure(status: String, error: Exception) = Bundle().apply {
        putInt("version", ProjectionSettings.VERSION); putString("status", status)
        putString("message", error.message.orEmpty().take(256))
    }

    private fun snapshot(source: ComponentName, includeRemote: Boolean, status: String = "ok") = Bundle().apply {
        putInt("version", ProjectionSettings.VERSION); putString("status", status)
        putString("schema", readSettings(source).toJson())
        putBoolean("supports_remote", supportsRemoteCompose)
        if (includeRemote && supportsRemoteCompose) {
            val document = requireNotNull(remoteDocument(source)) { "Remote Compose document unavailable" }
            putString("remote_version", ProjectionSettings.REMOTE_COMPOSE_VERSION)
            putByteArray("remote_document", document.bytes)
            putInt("remote_width", document.width); putInt("remote_height", document.height)
        }
    }

    /** Also call after configuration changes outside this provider. */
    protected fun notifySettingsChanged() {
        val authority = requireNotNull(context).packageManager.getProviderInfo(
            ComponentName(requireNotNull(context), javaClass), 0).authority
        authority.split(';').forEach {
            requireNotNull(context).contentResolver.notifyChange(ProjectionSettings.uri(it), null)
        }
    }

    final override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.wheelplay.plugin-settings"
    final override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    final override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    final override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
