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
    const val META_PREPARE_ACTIVITY = "moe.feng.wheelplay.plugin.PREPARE_ACTIVITY"
    const val EXTRA_PREPARE_REQUEST = "moe.feng.wheelplay.plugin.PREPARE_REQUEST"
    const val EXTRA_INTERACTION_VERSION = "moe.feng.wheelplay.plugin.INTERACTION_VERSION"
    const val INTERACTION_VERSION = 1
    const val META_SETTINGS_ACTIVITY = "moe.feng.wheelplay.plugin.SETTINGS_ACTIVITY"
    /** Optional bind-time pixel size; plugins may constrain it and return their actual size in onInfo. */
    const val EXTRA_DISPLAY_WIDTH = "moe.feng.wheelplay.plugin.DISPLAY_WIDTH"
    const val EXTRA_DISPLAY_HEIGHT = "moe.feng.wheelplay.plugin.DISPLAY_HEIGHT"
    /** Optional ResultReceiver capability granted only when the host opens plugin settings. */
    const val EXTRA_SETTINGS_BRIDGE = "moe.feng.wheelplay.plugin.SETTINGS_BRIDGE"
    const val EXTRA_ENCODED_VIDEO_VERSION = "moe.feng.wheelplay.plugin.ENCODED_VIDEO_VERSION"
    const val ENCODED_VIDEO_VERSION = 1
    const val EXTRA_DISPLAY_SIZE_VERSION = "moe.feng.wheelplay.plugin.DISPLAY_SIZE_VERSION"
    const val DISPLAY_SIZE_VERSION = 1
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
    val nativeCancelInput: Boolean = false,
    /** 0: Surface; 1: complete H.264 Annex-B access units through EncodedVideoWire. */
    val encodedVideoVersion: Int = 0,
    /** 1: accepts viewport requests and replies whether a clean session restart is needed. */
    val displaySizeVersion: Int = 0,
    val interactionVersion: Int = 0,
) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608)
        require(maxTouchContacts in 0..10)
        require(encodedVideoVersion in 0..ProjectionPlugin.ENCODED_VIDEO_VERSION)
        require(displaySizeVersion in 0..ProjectionPlugin.DISPLAY_SIZE_VERSION)
        require(interactionVersion in 0..ProjectionPlugin.INTERACTION_VERSION)
    }
    fun toBundle() = Bundle().apply {
        putInt("apiVersion", ProjectionPlugin.API_VERSION)
        putInt("width", width); putInt("height", height)
        putInt("maxTouchContacts", maxTouchContacts); putBoolean("nightMode", nightMode)
        putBoolean("processExitRequired", processExitRequired); putBoolean("nativeCancelInput", nativeCancelInput)
        putInt("encodedVideoVersion", encodedVideoVersion)
        putInt("displaySizeVersion", displaySizeVersion)
        putInt("interactionVersion", interactionVersion)
    }
    companion object {
        fun fromBundle(bundle: Bundle): PluginVideoInfo {
            require(bundle.getInt("apiVersion") == ProjectionPlugin.API_VERSION) { "Unsupported plugin API" }
            return PluginVideoInfo(bundle.getInt("width"), bundle.getInt("height"),
                bundle.getInt("maxTouchContacts"), bundle.getBoolean("nightMode"), bundle.getBoolean("processExitRequired"), bundle.getBoolean("nativeCancelInput"), bundle.getInt("encodedVideoVersion"), bundle.getInt("displaySizeVersion"), bundle.getInt("interactionVersion"))
        }
    }
}

data class PluginContact(val id: Int, val x: Double, val y: Double, val down: Boolean)

/** Shared validation for both IPC endpoints; an empty snapshot releases all contacts. */
fun validPluginContacts(contacts: List<PluginContact>, maximum: Int): Boolean =
    contacts.size <= maximum && contacts.map { it.id }.distinct().size == contacts.size && contacts.all {
        it.id in 0..31 && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
    }

/** Strip the local subclass name before crossing an APK class-loader boundary. */
fun android.os.ResultReceiver.forRemoteProcess(): android.os.ResultReceiver {
    val parcel = android.os.Parcel.obtain()
    return try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        android.os.ResultReceiver.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}

/** Ephemeral local UI only: never include this payload in status text, logs or diagnostics. */
data class PluginInteraction(val phase: String, val pairingCode: String? = null, val deviceName: String = "") {
    init {
        require(phase in setOf("preparing", "waiting-device", "pairing", "waiting-video", "ready", "failed"))
        require(pairingCode == null || (phase == "pairing" && pairingCode.matches(Regex("[0-9]{6}"))))
        require(deviceName.length <= 120 && deviceName.none { it.isISOControl() })
    }
    fun toBundle() = Bundle().apply {
        putInt("version", ProjectionPlugin.INTERACTION_VERSION)
        putString("phase", phase); putString("pairingCode", pairingCode); putString("deviceName", deviceName)
    }
    companion object {
        fun fromBundle(value: Bundle): PluginInteraction {
            require(value.getInt("version") == ProjectionPlugin.INTERACTION_VERSION)
            return PluginInteraction(requireNotNull(value.getString("phase")), value.getString("pairingCode"), value.getString("deviceName").orEmpty())
        }
    }
}
