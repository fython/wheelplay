package moe.feng.wheelplay.plugin

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionSettingsProviderTest {
    class Provider : ProjectionSettingsProvider() {
        var allowed = true
        var revision = 0L
        var value = false
        var writes = 0
        override fun isHostAllowed(uid: Int) = allowed
        override val supportsRemoteCompose = true
        override fun readSettings(source: ComponentName) = PluginSettingsSchema(revision, "Test", listOf(
            PluginSetting("enabled", "Enabled", SettingType.BOOLEAN, value),
            PluginSetting("locked", "Locked", SettingType.BOOLEAN, false, enabled = false),
            PluginSetting("fps", "FPS", SettingType.INTEGER, 15, minimum = 5, maximum = 30),
        ))
        override fun writeSetting(source: ComponentName, key: String, value: Any?) {
            writes++; revision++
            if (key == "enabled") this.value = value as Boolean
        }
        override fun remoteDocument(source: ComponentName) = PluginSettingsDocument(byteArrayOf(1, 2, 3), 320, 180)
        override fun onRemoteAction(source: ComponentName, actionId: Int, value: String?) {
            require(actionId == 7); this.value = !this.value; revision++
        }
        override fun onNamedRemoteAction(source: ComponentName, name: String, value: Any?) {
            require(name == "toggle" && value is Boolean); this.value = value; revision++
        }
    }
    private lateinit var context: Context
    private lateinit var provider: Provider
    private lateinit var source: ComponentName
    private val authority = "test.plugin.settings"
    @Before fun install() {
        context = RuntimeEnvironment.getApplication()
        source = ComponentName(context.packageName, context.packageName + ".ProjectionService")
        val info = ProviderInfo().apply {
            name = Provider::class.java.name; packageName = context.packageName
            applicationInfo = context.applicationInfo; this.authority = this@ProjectionSettingsProviderTest.authority
            exported = true; enabled = true
        }
        val service = ServiceInfo().apply {
            name = source.className; packageName = context.packageName; applicationInfo = context.applicationInfo
            enabled = true; exported = true
            metaData = Bundle().apply { putInt(ProjectionPlugin.META_API_VERSION, ProjectionPlugin.API_VERSION) }
        }
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA).apply {
            services = arrayOf(service); providers = arrayOf(info)
        }
        shadowOf(context.packageManager).installPackage(pkg)
        provider = Provider().apply { attachInfo(this@ProjectionSettingsProviderTest.context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun call(method: String = ProjectionSettings.GET, bundle: Bundle = ProjectionSettings.request()): Bundle =
        requireNotNull(context.contentResolver.call(ProjectionSettings.uri(authority), method, source.flattenToString(), bundle))
    private fun set(key: String, value: Any?, revision: Long = provider.revision) = call(ProjectionSettings.SET,
        ProjectionSettings.request(revision).apply { putString("key", key); putString("value", ProjectionSettings.encodeValue(value)) })

    @Test fun schemaRoundTripUsesStrictTypesAndBounds() {
        val schema = PluginSettingsSchema(4, "Settings", listOf(
            PluginSetting("text", "Text", SettingType.TEXT, "a\"b", maxLength = 8),
            PluginSetting("flag", "Flag", SettingType.BOOLEAN, true),
            PluginSetting("choice", "Choice", SettingType.CHOICE, "a",
                choices = listOf(SettingChoice("a", "A"), SettingChoice("b", "B"))),
        ))
        assertEquals(schema, PluginSettingsSchema.fromJson(schema.toJson()))
        assertEquals("a\"b", ProjectionSettings.decodeValue(ProjectionSettings.encodeValue("a\"b")))
        for (invalid in listOf("true", 1)) assertThrows(IllegalArgumentException::class.java) {
            schema.fields[1].validateValue(invalid)
        }
        assertThrows(IllegalArgumentException::class.java) { schema.fields.first().validateValue("123456789") }
        assertThrows(IllegalArgumentException::class.java) { schema.fields.last().validateValue("unknown") }
        assertThrows(IllegalArgumentException::class.java) { PluginSettingsSchema.fromJson(schema.toJson() + " true") }
        val locale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val integer = PluginSettingsSchema(0, "Integer", listOf(PluginSetting("fps", "FPS", SettingType.INTEGER, 15)))
            assertEquals(integer, PluginSettingsSchema.fromJson(integer.toJson()))
        } finally { java.util.Locale.setDefault(locale) }
    }
    @Test fun realContentResolverReadWriteAndRevisionConflicts() {
        assertEquals(0L, PluginSettingsSchema.fromJson(call().getString("schema")!!).revision)
        assertEquals("ok", set("enabled", true).getString("status"))
        assertTrue(provider.value); assertEquals(1L, provider.revision)
        val stale = set("enabled", false, 0)
        assertEquals("conflict", stale.getString("status"))
        assertTrue(provider.value); assertEquals(1, provider.writes)
        assertEquals(1L, PluginSettingsSchema.fromJson(stale.getString("schema")!!).revision)
    }
    @Test fun disabledUnknownAndInvalidValuesNeverReachPluginPersistence() {
        for ((key, value) in listOf("locked" to true, "unknown" to true, "enabled" to "true", "fps" to 31, "fps" to 5.5))
            assertEquals("invalid", set(key, value).getString("status"))
        assertEquals(0, provider.writes)
    }
    @Test fun unauthorizedCallersAndForeignSourcesAreRejected() {
        provider.allowed = false
        assertThrows(SecurityException::class.java) { call() }
        provider.allowed = true
        assertThrows(IllegalArgumentException::class.java) {
            provider.call(ProjectionSettings.GET, "other.apk/.Source", ProjectionSettings.request())
        }
        assertThrows(IllegalArgumentException::class.java) {
            call(bundle = ProjectionSettings.request().apply { putInt("version", 99) })
        }
    }
    @Test fun remoteDocumentsAndBothActionKindsShareTheSchemaRevision() {
        val first = call(bundle = ProjectionSettings.request(remote = true))
        assertTrue(first.getBoolean("supports_remote"))
        assertArrayEquals(byteArrayOf(1, 2, 3), first.getByteArray("remote_document"))
        assertEquals("ok", call(ProjectionSettings.ACTION, ProjectionSettings.request(0, true).apply {
            putInt("action_id", 7)
        }).getString("status"))
        assertTrue(provider.value)
        assertEquals("ok", call(ProjectionSettings.ACTION, ProjectionSettings.request(1).apply {
            putString("action_name", "toggle"); putString("action_json", "false")
        }).getString("status"))
        assertFalse(provider.value); assertEquals(2L, provider.revision)
    }
    @Test fun successfulChangesNotifyObserversAndReadsDoNot() {
        var changes = 0
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { changes++ }
        }
        context.contentResolver.registerContentObserver(ProjectionSettings.uri(authority), true, observer)
        try {
            call(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, changes)
            set("enabled", true); shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, changes)
        } finally { context.contentResolver.unregisterContentObserver(observer) }
    }
    @Test fun schemasAndDocumentsHaveBoundedSizeAndUniqueKeys() {
        val field = PluginSetting("value", "Value", SettingType.TEXT, "")
        assertThrows(IllegalArgumentException::class.java) { PluginSettingsSchema(0, "Test", listOf(field, field)) }
        assertThrows(IllegalArgumentException::class.java) { PluginSettingsDocument(ByteArray(ProjectionSettings.MAX_DOCUMENT_BYTES + 1), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { PluginSettingsDocument(byteArrayOf(1), 4096, 4096) }
        assertThrows(IllegalArgumentException::class.java) {
            PluginSettingsSchema(0, "Large", (0 until 64).map {
                PluginSetting("text$it", "Text", SettingType.TEXT, "x".repeat(4096), maxLength = 4096)
            }).toJson()
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectionSettings.decodeValue("[1,2]") }
    }
}
