package moe.feng.wheelplay.plugin

import android.os.Bundle

/** Version 2 adds an acknowledged, dedicated-process shutdown barrier. */
object ProjectionPlugin {
    const val API_VERSION = 2
    const val ACTION = "moe.feng.wheelplay.action.PROJECTION_SOURCE"
    const val META_API_VERSION = "moe.feng.wheelplay.plugin.API_VERSION"
    /** Optional UI association; never replaces the component-derived runtime identity or consent. */
    const val META_SOURCE_ID = "moe.feng.wheelplay.plugin.SOURCE_ID"
    const val SOURCE_ICCOA_CARLINK = "iccoa-carlink"
    const val META_SETTINGS_ACTIVITY = "moe.feng.wheelplay.plugin.SETTINGS_ACTIVITY"
    const val CONNECTING = 0
    const val READY = 1
    const val ERROR = 2
}

data class PluginVideoInfo(
    val width: Int,
    val height: Int,
    val maxTouchContacts: Int = 0,
    val nightMode: Boolean = false,
    val processExitRequired: Boolean = false,
) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608)
        require(maxTouchContacts in 0..10)
    }
    fun toBundle() = Bundle().apply {
        putInt("apiVersion", ProjectionPlugin.API_VERSION)
        putInt("width", width); putInt("height", height)
        putInt("maxTouchContacts", maxTouchContacts); putBoolean("nightMode", nightMode)
        putBoolean("processExitRequired", processExitRequired)
    }
    companion object {
        fun fromBundle(bundle: Bundle): PluginVideoInfo {
            require(bundle.getInt("apiVersion") == ProjectionPlugin.API_VERSION) { "Unsupported plugin API" }
            return PluginVideoInfo(bundle.getInt("width"), bundle.getInt("height"),
                bundle.getInt("maxTouchContacts"), bundle.getBoolean("nightMode"), bundle.getBoolean("processExitRequired"))
        }
    }
}

data class PluginContact(val id: Int, val x: Double, val y: Double, val down: Boolean)

/** Shared validation for both IPC endpoints; an empty snapshot releases all contacts. */
fun validPluginContacts(contacts: List<PluginContact>, maximum: Int): Boolean =
    contacts.size <= maximum && contacts.map { it.id }.distinct().size == contacts.size && contacts.all {
        it.id in 0..31 && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
    }
