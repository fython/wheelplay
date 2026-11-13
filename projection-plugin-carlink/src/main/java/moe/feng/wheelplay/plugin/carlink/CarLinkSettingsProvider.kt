package moe.feng.wheelplay.plugin.carlink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Same-UID IPC; keeps PINs in memory, never in logs or multi-process SharedPreferences. */
class CarLinkSettingsProvider : ContentProvider() {
    private var status = Bundle().apply { putString("message", "尚未连接") }
    private var display = Bundle()
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid())
        val prefs = requireNotNull(context).getSharedPreferences("settings", 0)
        return when (method) {
            "status" -> Bundle(status).apply { putAll(display) }
            "encoded" -> {
                val width = extras?.getInt("encodedWidth") ?: 0
                val height = extras?.getInt("encodedHeight") ?: 0
                moe.feng.wheelplay.plugin.PluginVideoInfo(width, height)
                display.putInt("encodedWidth", width); display.putInt("encodedHeight", height)
                Bundle.EMPTY
            }
            "display" -> { display = Bundle(extras ?: Bundle()); Bundle.EMPTY }
            "update" -> { status = Bundle(extras ?: Bundle()); Bundle.EMPTY }
            "save" -> {
                val values = requireNotNull(extras)
                val connection = CarLinkConnection.fromKey(values.getString("connection", prefs.getString("connection", "p2p")))
                val output = values.getString("videoOutput", prefs.getString("videoOutput", "surface"))!!
                require(output in listOf("surface", "webrtc"))
                val mode = values.getString("mode", prefs.getString("mode", "balanced"))!!
                val scale = values.getFloat("scale", prefs.getFloat("scale", 1f))
                val dpi = values.getInt("dpi", prefs.getInt("dpi", 160))
                require(mode in listOf("balanced", "native", "custom") && scale.isFinite() && scale in .5f..2f && dpi in 120..480)
                val hotspotSsid = values.getString("hotspotSsid", prefs.getString("hotspotSsid", ""))!!
                val hotspotPassword = values.getString("hotspotPassword", prefs.getString("hotspotPassword", ""))!!
                if (values.containsKey("hotspotSsid") || values.containsKey("hotspotPassword")) {
                    if (hotspotSsid.isNotEmpty() || hotspotPassword.isNotEmpty()) {
                        require(CarLinkSoftAp.Credentials(hotspotSsid, hotspotPassword).problem() == null) { "Invalid hotspot credentials" }
                    }
                }
                prefs.edit().putString("hotspotSsid", hotspotSsid).putString("hotspotPassword", hotspotPassword)
                    .putString("connection", connection.key).putString("videoOutput", output).putString("mode", mode).putFloat("scale", scale).putInt("dpi", dpi).apply()
                Bundle.EMPTY
            }
            "config" -> Bundle().apply {
                putString("connection", prefs.getString("connection", "p2p"))
                putString("hotspotSsid", prefs.getString("hotspotSsid", ""))
                putString("hotspotPassword", prefs.getString("hotspotPassword", ""))
                putString("videoOutput", prefs.getString("videoOutput", "surface"))
                putString("mode", prefs.getString("mode", "balanced"))
                putFloat("scale", prefs.getFloat("scale", 1f))
                putInt("dpi", prefs.getInt("dpi", 160))
                var id = prefs.getString("carId", null)
                if (id == null) {
                    val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
                    bytes[0] = ((bytes[0].toInt() or 2) and 0xfe).toByte()
                    id = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                    prefs.edit().putString("carId", id).apply()
                }
                putString("carId", id)
            }
            else -> throw IllegalArgumentException("Unknown operation")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal fun android.content.Context.settingsCall(method: String, extras: Bundle? = null): Bundle =
    contentResolver.call(Uri.parse("content://$packageName.settings"), method, null, extras) ?: Bundle.EMPTY
