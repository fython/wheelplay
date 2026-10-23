package com.shilapi.xcertplay.web

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes the Web listener on each reachable LAN interface. */
internal class WebMdns(
    private val context: Context,
    private val wifiManager: () -> WifiManager? = {
        context.applicationContext.getSystemService(WifiManager::class.java)
    },
    private val discover: () -> List<LanAddresses.Entry> = { LanAddresses.discover(context) },
    private val createResponder: (InetAddress, String) -> Responder = { address, hostname ->
        val dns = JmDNS.create(address, hostname)
        object : Responder {
            override fun register(port: Int) {
                dns.registerService(ServiceInfo.create("_http._tcp.local.", "WheelPlay", port, "path=/"))
            }
            override fun close() = dns.close()
        }
    },
    startWorker: Boolean = true,
) : AutoCloseable {
    internal interface Responder : AutoCloseable {
        fun register(port: Int)
    }

    private data class Publication(val responder: Responder, val needsMulticast: Boolean)

    @Volatile private var active = true
    private val instances = mutableMapOf<String, Publication>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val worker = if (startWorker) Thread({
        while (active) {
            runCatching { refresh() }.onFailure { Log.w("WebMdns", "mDNS refresh failed", it) }
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
        }
    }, "wheelplay-web-mdns").apply { isDaemon = true; start() } else null

    @Synchronized internal fun refresh() {
        if (!active) return
        val hostname = WebListenSettings.mdnsHostname(context)
        val port = WebSession.httpPort
        val addresses = discover().filter {
            it.kind !in setOf(LanAddresses.Kind.TESLA_HTTP, LanAddresses.Kind.VPN,
                LanAddresses.Kind.CELLULAR, LanAddresses.Kind.P2P)
        }.associateBy { "${it.interfaceName}/${it.address}/${it.kind}" }
        val target = addresses.keys.map { "$it/$hostname/$port" }.toSet()
        instances.keys.filter { it !in target }.forEach { key ->
            runCatching { instances.remove(key)?.responder?.close() }
        }
        releaseUnusedMulticastLock()
        for ((key, entry) in addresses) {
            val identity = "$key/$hostname/$port"
            if (identity in instances) continue
            val needsMulticast = entry.kind in setOf(LanAddresses.Kind.WIFI, LanAddresses.Kind.HOTSPOT)
            runCatching {
                // Acquire before JmDNS opens its socket and sends the initial probes.
                if (needsMulticast) acquireMulticastLock()
                val dns = createResponder(InetAddress.getByName(entry.address), hostname.removeSuffix(".local"))
                try {
                    dns.register(port)
                    instances[identity] = Publication(dns, needsMulticast)
                } catch (error: Exception) {
                    runCatching { dns.close() }
                    throw error
                }
            }.onFailure { Log.w("WebMdns", "mDNS publication failed on $key", it) }
            releaseUnusedMulticastLock()
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val lock = checkNotNull(wifiManager()) { "Wi-Fi manager unavailable" }
            .createMulticastLock("wheelplay-web-mdns")
        try {
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (error: Exception) {
            runCatching { if (lock.isHeld) lock.release() }
            throw error
        }
    }

    private fun releaseUnusedMulticastLock() {
        if (instances.values.any { it.needsMulticast }) return
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w("WebMdns", "mDNS multicast lock release failed", it) }
    }

    @Synchronized override fun close() {
        active = false
        worker?.interrupt()
        // Keep multicast reception enabled until responders finish their goodbye/close.
        instances.values.forEach { runCatching { it.responder.close() } }
        instances.clear()
        releaseUnusedMulticastLock()
    }
}
