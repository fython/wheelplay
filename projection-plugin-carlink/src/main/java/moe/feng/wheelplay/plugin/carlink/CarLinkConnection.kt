package moe.feng.wheelplay.plugin.carlink

/** Saved selection is snapshotted once per session, independently of video output. */
internal enum class CarLinkConnection(val key: String, val label: String) {
    P2P("p2p", "Wi-Fi Direct（P2P）"),
    SOFT_AP("softap", "手动热点（SoftAP）"),
    USB("usb", "USB 有线");

    val wireless get() = this != USB

    companion object {
        fun fromKey(key: String?): CarLinkConnection = entries.firstOrNull { it.key == (key ?: "p2p") }
            ?: throw IllegalArgumentException("Unknown CarLink connection method")
    }
}
