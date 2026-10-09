package com.shilapi.xcertplay.network

/** A local virtual address, not a replacement for the system hotspot's DHCP subnet. */
data class TeslaHttpConfig(
    val enabled: Boolean = false,
    val address: String = DEFAULT_ADDRESS,
    val hostname: String = "",
) {
    init {
        require(isSharedAddress(address)) { "虚拟 IP 必须位于 100.64.0.0–100.127.255.255" }
        require(isHostname(hostname)) { "请输入域名，不要包含协议、端口或路径" }
    }

    val ipUrl get() = "http://$address:8080/"
    val hostnameUrl get() = hostname.takeIf { it.isNotEmpty() }?.let { "http://$it:8080/" }

    companion object {
        const val DEFAULT_ADDRESS = "100.96.0.1"

        // Parse literals without DNS, accepting only canonical dotted decimal notation.
        fun isSharedAddress(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return false
            val bytes = parts.map { it.toInt() }
            return bytes.all { it in 0..255 } && bytes[0] == 100 && bytes[1] in 64..127
        }

        fun isHostname(value: String): Boolean = value.isEmpty() ||
            (value.length <= 253 && value.contains('.') &&
                !value.all { it.isDigit() || it == '.' } &&
                value.split('.').all { Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?").matches(it) })
    }
}

/** Ready means that the local interface exists; hotspot/browser reachability still needs testing. */
data class TeslaHttpStatus(val address: String? = null, val error: String? = null)
