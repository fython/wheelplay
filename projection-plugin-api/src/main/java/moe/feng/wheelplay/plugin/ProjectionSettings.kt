package moe.feng.wheelplay.plugin

import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** Independent settings protocol; does not change the projection Binder API version. */
object ProjectionSettings {
    const val VERSION = 1
    const val META_PROVIDER = "moe.feng.wheelplay.plugin.SETTINGS_PROVIDER"
    const val GET = "wheelplay.settings.get"
    const val SET = "wheelplay.settings.set"
    const val ACTION = "wheelplay.settings.action"
    const val REMOTE_COMPOSE_VERSION = "1.0.0-alpha23"
    const val MAX_SCHEMA_BYTES = 64 * 1024
    const val MAX_DOCUMENT_BYTES = 256 * 1024
    fun uri(authority: String): Uri = Uri.Builder().scheme("content").authority(authority)
        .appendPath("wheelplay-settings").build()
    fun request(revision: Long? = null, remote: Boolean = false) = Bundle().apply {
        putInt("version", VERSION)
        putBoolean("include_remote", remote)
        revision?.let { putLong("revision", it) }
    }

    fun encodeValue(value: Any?): String {
        require(value == null || value is String || value is Boolean || value is Number)
        if (value is String) require(value.length <= 4096)
        if (value is Number) require(value.toDouble().isFinite())
        return if (value is String) JSONObject.quote(value) else value?.toString() ?: "null"
    }

    fun decodeValue(encoded: String): Any? {
        require(encoded.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        val text = encoded.trim()
        require(text.startsWith('"') || text in listOf("true", "false", "null") ||
            Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(text))
        val parser = JSONTokener(text)
        val value = parser.nextValue().takeUnless { it == JSONObject.NULL }
        require(parser.nextClean() == '\u0000') { "Expected one JSON scalar" }
        encodeValue(value) // Reject arrays, objects, and nonfinite numbers.
        return value
    }
}

enum class SettingType { BOOLEAN, TEXT, INTEGER, CHOICE, ACTION }
data class SettingChoice(val value: String, val label: String)

/** Values are JSON primitives. Provider and host both validate the same schema. */
data class PluginSetting(
    val key: String,
    val title: String,
    val type: SettingType,
    val value: Any? = null,
    val summary: String = "",
    val enabled: Boolean = true,
    val restartRequired: Boolean = false,
    val choices: List<SettingChoice> = emptyList(),
    val minimum: Int = Int.MIN_VALUE,
    val maximum: Int = Int.MAX_VALUE,
    val maxLength: Int = 1024,
) {
    init {
        require(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}").matches(key)) { "Invalid setting key" }
        require(title.isNotBlank() && title.length <= 256 && summary.length <= 1024)
        require(minimum <= maximum && maxLength in 1..4096)
        require(choices.size <= 64 && choices.map { it.value }.distinct().size == choices.size)
        require(choices.all { it.value.length in 1..256 && it.label.length in 1..256 })
        if (type == SettingType.CHOICE) require(choices.isNotEmpty())
        validateValue(value)
    }

    fun validateValue(candidate: Any?) {
        require(when (type) {
            SettingType.BOOLEAN -> candidate is Boolean
            SettingType.TEXT -> candidate is String && candidate.length <= maxLength
            SettingType.INTEGER -> (candidate is Int || candidate is Long) &&
                (candidate as Number).toLong() in minimum.toLong()..maximum.toLong()
            SettingType.CHOICE -> candidate is String && choices.any { it.value == candidate }
            SettingType.ACTION -> candidate == null
        }) { "Invalid value for setting $key" }
    }

    internal fun json() = JSONObject().apply {
        put("key", key); put("title", title); put("type", type.name.lowercase(Locale.ROOT))
        put("value", value ?: JSONObject.NULL); put("summary", summary)
        put("enabled", enabled); put("restartRequired", restartRequired)
        put("minimum", minimum); put("maximum", maximum); put("maxLength", maxLength)
        put("choices", JSONArray().apply { choices.forEach {
            put(JSONObject().put("value", it.value).put("label", it.label))
        } })
    }
}

data class PluginSettingsSchema(val revision: Long, val title: String, val fields: List<PluginSetting>) {
    init {
        require(revision >= 0 && title.isNotBlank() && title.length <= 256)
        require(fields.size <= 64 && fields.map { it.key }.distinct().size == fields.size)
    }
    fun toJson(): String = JSONObject().put("version", ProjectionSettings.VERSION)
        .put("revision", revision).put("title", title)
        .put("fields", JSONArray().apply { fields.forEach { put(it.json()) } }).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= ProjectionSettings.MAX_SCHEMA_BYTES)
        }

    companion object {
        fun fromJson(text: String): PluginSettingsSchema {
            require(text.toByteArray(Charsets.UTF_8).size <= ProjectionSettings.MAX_SCHEMA_BYTES)
            var depth = 0; var quoted = false; var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 16) { "Settings schema nesting is too deep" } }
                    '}', ']' -> { depth--; require(depth >= 0) }
                }
            }
            require(depth == 0 && !quoted)
            val parser = JSONTokener(text)
            val root = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected settings object")
            require(parser.nextClean() == '\u0000') { "Expected one settings object" }
            require(root.get("version") == ProjectionSettings.VERSION) { "Unsupported settings schema version" }
            require(root.get("revision") is Int || root.get("revision") is Long) { "Invalid settings revision" }
            val array = root.getJSONArray("fields")
            require(array.length() <= 64)
            return PluginSettingsSchema(root.getLong("revision"), string(root, "title"),
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val choices = if (item.has("choices")) item.getJSONArray("choices") else JSONArray()
                    require(choices.length() <= 64)
                    PluginSetting(string(item, "key"), string(item, "title"),
                        SettingType.valueOf(string(item, "type").uppercase(Locale.ROOT)),
                        item.opt("value").takeUnless { it == JSONObject.NULL },
                        if (item.has("summary")) string(item, "summary") else "", boolean(item, "enabled", true),
                        boolean(item, "restartRequired", false),
                        (0 until choices.length()).map {
                            val choice = choices.getJSONObject(it)
                            SettingChoice(string(choice, "value"), string(choice, "label"))
                        }, integer(item, "minimum", Int.MIN_VALUE), integer(item, "maximum", Int.MAX_VALUE),
                        integer(item, "maxLength", 1024))
                })
        }
        private fun string(json: JSONObject, key: String): String =
            (json.get(key) as? String) ?: throw IllegalArgumentException("Invalid string field $key")
        private fun boolean(json: JSONObject, key: String, default: Boolean): Boolean =
            if (!json.has(key)) default else (json.get(key) as? Boolean)
                ?: throw IllegalArgumentException("Invalid boolean field $key")
        private fun integer(json: JSONObject, key: String, default: Int): Int {
            if (!json.has(key)) return default
            val value = json.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return (value as Number).toInt()
        }
    }
}

/** The bytes are a real Jetpack Remote Compose document, never HTML or RemoteViews. */
data class PluginSettingsDocument(val bytes: ByteArray, val width: Int, val height: Int) {
    init {
        require(bytes.size in 1..ProjectionSettings.MAX_DOCUMENT_BYTES)
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608)
    }
}
