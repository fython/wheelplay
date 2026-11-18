package moe.feng.wheelplay.projection.carlife

import android.content.Context

/** Transport and protocol are independent of the phone's system settings entry. */
internal data class CarLifeConnection(val host: String, val protocol: moe.feng.wheelplay.carlife.CarLifeProtocolMode, val microphone: Boolean, val usb: Boolean = false) {
    val remote get() = usb || host != "127.0.0.1"
    companion object {
        fun mode(prefs: android.content.SharedPreferences): String =
            prefs.getString("transport", null)?.takeIf { it in setOf("local", "remote", "usb") }
                ?: if (prefs.getBoolean("remoteMode", prefs.getString("host", "").orEmpty().isNotEmpty())) "remote" else "local"
        fun protocolSelection(explicit: String?, legacy: String?, fallback: String = "vivo"): String =
            if (explicit in setOf("auto", "3.0", "4.1")) explicit!!
            else if (legacy == null) "auto"
            else if ((legacy?.takeUnless { it == "auto" } ?: fallback) == "xiaomi") "4.1" else "3.0"

        fun load(context: Context): CarLifeConnection {
            val prefs = context.getSharedPreferences("carlife_connection", Context.MODE_PRIVATE)
            val host = prefs.getString("host", "").orEmpty().trim()
            val local = CarLifeEnvironment.detect(context)
            val usb = mode(prefs) == "usb"
            val remote = mode(prefs) == "remote"
            if (remote) require(validAddress(host)) { "请输入有效的源手机 IPv4 地址" }
            val selection = if (usb) protocolSelection(prefs.getString("usbProtocolVersion", null), null)
                else if (remote) protocolSelection(prefs.getString("remoteProtocolVersion", null), prefs.getString("brand", null))
                else protocolSelection(prefs.getString("localProtocolVersion", null), prefs.getString("localProtocol", null), local.id)
            val protocol = if (selection == "auto") moe.feng.wheelplay.carlife.CarLifeProtocolMode.AUTO else if (selection == "4.1") moe.feng.wheelplay.carlife.CarLifeProtocolMode.XIAOMI_4_1 else moe.feng.wheelplay.carlife.CarLifeProtocolMode.LEGACY_3_0
            return CarLifeConnection(if (remote) host else "127.0.0.1", protocol,
                usb || remote || selection in setOf("auto", "4.1") || local.id in setOf("vivo", "xiaomi"), usb)
        }
        fun validAddress(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255 } && parts.first().toInt() in 1..223 && parts.first() != "127"
        }
    }
}
