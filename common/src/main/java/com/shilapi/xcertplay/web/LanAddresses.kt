package com.shilapi.xcertplay.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shilapi.xcertplay.network.TeslaHttpConfig
import java.net.Inet4Address
import java.net.NetworkInterface

/** Keep interface provenance; a private address alone does not imply LAN reachability. */
internal object LanAddresses {
    enum class Kind(val label: String, val priority: Int) {
        TESLA_HTTP("Tesla HTTP（实验）", 5), ETHERNET("以太网", 10), WIFI("Wi-Fi", 10), HOTSPOT("热点", 20),
        USB("USB 网络", 30), OTHER("其它网络", 50), P2P("Wi-Fi Direct", 60),
        VPN("VPN / 隧道", 90), CELLULAR("移动网络", 100)
    }
    data class Entry(val interfaceName: String, val address: String, val kind: Kind, val active: Boolean = false, val hostname: String = "") {
        val url get() = "http://${hostname.ifEmpty { address }}:8080"
        val ipUrl get() = "http://$address:8080"
        val description get() = "${kind.label} · $interfaceName" + when (kind) {
            Kind.TESLA_HTTP -> " · 请将车机连接此设备热点；HTTP 可用性需实测"
            Kind.P2P -> " · 通常用于 iPhone 连接"
            Kind.VPN, Kind.CELLULAR -> " · 通常无法从局域网访问"
            else -> ""
        }
    }
    fun classify(name: String, transport: Kind? = null): Kind {
        val n = name.lowercase()
        return when {
            transport == Kind.VPN || n.startsWith("tun") || n.startsWith("tap") || n.startsWith("wg") || n.startsWith("ipsec") -> Kind.VPN
            n.contains("p2p") -> Kind.P2P
            transport != null -> transport
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> Kind.CELLULAR
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> Kind.HOTSPOT
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> Kind.USB
            n.startsWith("wlan") || n.startsWith("wifi") -> Kind.WIFI
            n.startsWith("eth") || n.matches(Regex("en[0-9]+")) -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }
    fun ranked(entries: List<Entry>): List<Entry> = entries.distinctBy { it.interfaceName to it.address }
        .sortedWith(compareBy<Entry> { it.kind.priority }.thenByDescending { it.active }
            .thenBy { it.interfaceName }.thenBy { it.address })

    fun isDiscoverable(address: Inet4Address): Boolean =
        address.isSiteLocalAddress || TeslaHttpConfig.isSharedAddress(address.hostAddress ?: "")

    fun entry(name: String, address: String, kind: Kind, active: Boolean,
              teslaAddress: String?, hostname: String): Entry =
        if (kind == Kind.VPN && address == teslaAddress) Entry(name, address, Kind.TESLA_HTTP, active, hostname)
        else Entry(name, address, kind, active)

    fun discover(context: Context, teslaAddress: String? = null, hostname: String = ""): List<Entry> {
        val transports = mutableMapOf<String, Kind>()
        var activeInterface: String? = null
        // Framework metadata is authoritative; interface-name heuristics cover tethering/P2P.
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            activeInterface = manager.activeNetwork?.let { manager.getLinkProperties(it)?.interfaceName }
            for (network in manager.allNetworks) {
                val name = manager.getLinkProperties(network)?.interfaceName ?: continue
                val caps = manager.getNetworkCapabilities(network) ?: continue
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Kind.VPN
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                    else -> null
                }
                if (kind != null) transports[name] = kind
            }
        }
        val entries = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
                runCatching {
                    if (!iface.isUp || iface.isLoopback) emptyList() else iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>().filter(::isDiscoverable)
                        .map { entry(iface.name, it.hostAddress!!, classify(iface.name, transports[iface.name]),
                            iface.name == activeInterface, teslaAddress, hostname) }
                }.getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())
        return ranked(entries)
    }
}
