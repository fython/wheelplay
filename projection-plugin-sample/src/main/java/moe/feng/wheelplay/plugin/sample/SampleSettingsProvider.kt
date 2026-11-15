package moe.feng.wheelplay.plugin.sample

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.Color
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.core.operations.Header
import moe.feng.wheelplay.plugin.*

/** The schema and Remote Compose page edit the same plugin-owned preferences. */
class SampleSettingsProvider : ProjectionSettingsProvider() {
    private val preferences get() = requireNotNull(context).getSharedPreferences("sample-settings", 0)
    override val supportsRemoteCompose = true

    override fun readSettings(source: ComponentName) = PluginSettingsSchema(
        preferences.getLong("revision", 0), "Surface demo settings", listOf(
            PluginSetting("counter", "Show frame counter", SettingType.BOOLEAN,
                preferences.getBoolean("counter", true), restartRequired = true),
            PluginSetting("fps", "Animation frame rate", SettingType.INTEGER,
                preferences.getInt("fps", 15), minimum = 5, maximum = 30, restartRequired = true),
            PluginSetting("caption", "Caption", SettingType.TEXT,
                preferences.getString("caption", "Independent APK · Surface plugin"), maxLength = 80, restartRequired = true),
            PluginSetting("resolution", "Resolution", SettingType.CHOICE,
                preferences.getString("resolution", "360p"), restartRequired = true,
                choices = listOf(SettingChoice("360p", "640 × 360"), SettingChoice("720p", "1280 × 720"))),
            PluginSetting("reset", "Reset demo settings", SettingType.ACTION),
        ))

    override fun writeSetting(source: ComponentName, key: String, value: Any?) {
        val next = preferences.getLong("revision", 0) + 1
        val edit = preferences.edit()
        when (key) {
            "counter" -> edit.putBoolean(key, value as Boolean)
            "fps" -> edit.putInt(key, (value as Number).toInt())
            "caption", "resolution" -> edit.putString(key, value as String)
            "reset" -> edit.clear()
            else -> error("Unknown sample setting")
        }
        check(edit.putLong("revision", next).commit()) { "Could not save settings" }
    }

    override fun onRemoteAction(source: ComponentName, actionId: Int, value: String?) {
        when (actionId) {
            1 -> writeSetting(source, "counter", !preferences.getBoolean("counter", true))
            2 -> writeSetting(source, "fps", (preferences.getInt("fps", 15) + 5).let { if (it > 30) 5 else it })
            else -> throw IllegalArgumentException("Unknown sample action")
        }
    }

    @SuppressLint("RestrictedApi")
    override fun remoteDocument(source: ComponentName): PluginSettingsDocument {
        val writer = RemoteComposeWriter(RcPlatformProfiles.ANDROIDX,
            RemoteComposeWriter.hTag(Header.DOC_WIDTH, 720), RemoteComposeWriter.hTag(Header.DOC_HEIGHT, 360),
            RemoteComposeWriter.hTag(Header.DOC_CONTENT_DESCRIPTION, "Surface demo settings"))
        val paint = writer.rcPaint
        paint.setColor(Color.rgb(24, 32, 48)).commit()
        writer.drawRect(0f, 0f, 720f, 360f)
        fun text(value: String, y: Float, size: Float = 24f) {
            paint.setColor(Color.WHITE).setTextSize(size).commit()
            writer.drawTextRun(value, 0, value.length, 0, value.length, 24f, y, false)
        }
        text("Remote Compose · Surface demo", 44f, 30f)
        text("Changes apply on the next projection connection", 80f, 21f)
        paint.setColor(Color.rgb(49, 75, 106)).commit()
        writer.drawRect(16f, 108f, 704f, 196f)
        writer.drawRect(16f, 212f, 704f, 300f)
        text("Frame counter: ${if (preferences.getBoolean("counter", true)) "on" else "off"} · tap to toggle", 160f)
        text("Animation: ${preferences.getInt("fps", 15)} fps · tap for next speed", 266f)
        writer.addClickArea(1, "Toggle frame counter", 16f, 108f, 704f, 196f, null)
        writer.addClickArea(2, "Change animation frame rate", 16f, 212f, 704f, 300f, null)
        return PluginSettingsDocument(writer.encodeToByteArray(), 720, 360)
    }
}
