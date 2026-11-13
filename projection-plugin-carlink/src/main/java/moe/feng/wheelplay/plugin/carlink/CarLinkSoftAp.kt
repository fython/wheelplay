package moe.feng.wheelplay.plugin.carlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Attaches to the user's hotspot; never starts, configures or stops tethering. */
internal object CarLinkSoftAp {
    class Credentials(val ssid: String, val password: String) {
        fun ssidProblem(): String? = when {
            ssid.isBlank() || ssid.codePointCount(0, ssid.length) !in 9..32 || ssid.any { it.isISOControl() } -> "ICCOA 要求热点名称为 9～32 个字符"
            ssid.toByteArray(Charsets.UTF_8).size > 32 -> "热点名称不能超过 32 字节，请缩短中文等多字节字符组成的名称"
            else -> null
        }
        fun passwordProblem(): String? = when {
            password.length !in 8..63 || password.any { it.code !in 32..126 } -> "请输入与系统热点一致的 WPA2 密码（8–63 位英文、数字或符号）"
            else -> null
        }
        fun problem(): String? = ssidProblem() ?: passwordProblem()
        override fun toString() = "HotspotCredentials(redacted)"
    }
    class Network(val interfaceName: String, val address: String, val frequency: Int)
    data class Candidate(val name: String, val address: String)

    fun credentials(config: Bundle) = Credentials(config.getString("hotspotSsid", "")!!, config.getString("hotspotPassword", "")!!)

    fun enabled(context: Context): Boolean? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        return runCatching { WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == 13 }
            .recoverCatching { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean }.getOrNull()
    }

    fun problem(context: Context, config: Bundle = context.settingsCall("config")): String? =
        credentials(config).problem() ?: if (enabled(context) == false) "请先在系统设置中开启接收手机的热点" else null

    /** Excludes the Wi-Fi uplink and P2P, rather than guessing from a private IP alone. */
    fun select(candidates: List<Candidate>, upstreams: Set<String>, tethered: Set<String>?): Candidate? =
        candidates.filter { candidate ->
            if (tethered != null) candidate.name in tethered
            else candidate.name !in upstreams && !candidate.name.contains("p2p", true) &&
                (candidate.name.startsWith("wlan") || candidate.name.startsWith("swlan") ||
                    candidate.name.startsWith("ap") || candidate.name.startsWith("wifi") || candidate.name.contains("softap", true))
        }.singleOrNull()

    fun network(context: Context, credentials: Credentials): Network {
        credentials.problem()?.let { error(it) }
        check(enabled(context) != false) { "请先在系统设置中开启接收手机的热点" }
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val upstreams = connectivity.allNetworks.mapNotNull { net ->
            val caps = connectivity.getNetworkCapabilities(net)
            connectivity.getLinkProperties(net)?.interfaceName?.takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.toSet()
        val tethered = runCatching {
            val names = ConnectivityManager::class.java.getMethod("getTetheredIfaces").invoke(connectivity) as Array<*>
            val patterns = (ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs").invoke(connectivity) as Array<*>)
                .filterIsInstance<String>().map(::Regex)
            names.filterIsInstance<String>().filter { name -> patterns.any { it.matches(name) } }.toSet().takeIf { it.isNotEmpty() }
        }.getOrNull()
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { iface ->
            if (!iface.isUp || iface.isLoopback) emptyList()
            else Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.map { Candidate(iface.name, it.hostAddress!!) }
        }
        val selected = checkNotNull(select(candidates, upstreams, tethered)) {
            "未找到唯一的本机热点网络，请开启热点并关闭 Wi-Fi Direct 后重试"
        }
        // Reading configuration is optional. Lack of privileged access never blocks manual mode.
        val wifi = context.getSystemService(WifiManager::class.java)
        val ap = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wifi) }.getOrNull()
        val actualSsid = ap?.let { runCatching { it.javaClass.getMethod("getSsid").invoke(it) as? String }.getOrNull() }
        check(actualSsid == null || actualSsid == credentials.ssid) { "插件中的热点名称与系统热点不一致，请重新填写" }
        val channel = ap?.let { runCatching { it.javaClass.getMethod("getChannel").invoke(it) as Int }.getOrNull() } ?: 0
        val band = ap?.let { runCatching { it.javaClass.getMethod("getBand").invoke(it) as Int }.getOrNull() } ?: 0
        val frequency = when {
            band == 1 && channel in 1..13 -> 2407 + channel * 5
            band == 1 && channel == 14 -> 2484
            band == 2 && channel in 32..177 -> 5000 + channel * 5
            else -> 0 // Unknown: ask the phone to discover by SSID; never advertise a made-up channel.
        }
        return Network(selected.name, selected.address, frequency)
    }
}
