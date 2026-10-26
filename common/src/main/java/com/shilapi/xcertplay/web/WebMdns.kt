package com.shilapi.xcertplay.web

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    autoRefresh: Boolean = true,
) : AutoCloseable {
    internal interface Responder : AutoCloseable {
        fun register(port: Int)
    }

    private data class Publication(val responder: Responder, val needsMulticast: Boolean)

    private val active = AtomicBoolean(true)
    // Each lifecycle owns its worker, responders and lock. Retiring workers never touch a successor.
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wheelplay-web-mdns").apply { isDaemon = true }
    }
    private val instances = mutableMapOf<String, Publication>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val periodicRefresh = if (autoRefresh) worker.scheduleWithFixedDelay(
        { refreshOnWorker() }, 0, 10, TimeUnit.SECONDS,
    ) else null

    /** Queue a refresh; callers never register or close a responder themselves. */
    internal fun refresh(): Future<*> {
        if (!active.get()) return CompletableFuture.completedFuture(Unit)
        return try {
            worker.submit { refreshOnWorker() }
        } catch (_: RejectedExecutionException) {
            // close() may shut down the worker between the active check and submission.
            CompletableFuture.completedFuture(Unit)
        }
    }

    private fun refreshOnWorker() {
        if (!active.get()) return
        runCatching { updatePublications() }
            .onFailure { Log.w("WebMdns", "mDNS refresh failed", it) }
    }

    private fun updatePublications() {
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
            if (!active.get()) break
            val identity = "$key/$hostname/$port"
            if (identity in instances) continue
            val needsMulticast = entry.kind in setOf(LanAddresses.Kind.WIFI, LanAddresses.Kind.HOTSPOT)
            runCatching {
                // Acquire before JmDNS opens its socket and sends the initial probes.
                if (needsMulticast) acquireMulticastLock()
                val dns = createResponder(InetAddress.getByName(entry.address), hostname.removeSuffix(".local"))
                try {
                    // Creation/registration may finish after close() invalidated this lifecycle.
                    if (active.get()) dns.register(port)
                } catch (error: Exception) {
                    runCatching { dns.close() }
                    throw error
                }
                if (active.get()) instances[identity] = Publication(dns, needsMulticast)
                else runCatching { dns.close() }
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

    /** Invalidate immediately, then let the owner finish in-flight work and clean up off-thread. */
    override fun close() {
        if (!active.compareAndSet(true, false)) return
        periodicRefresh?.cancel(false)
        worker.execute { closeOnWorker() }
        worker.shutdown()
    }

    private fun closeOnWorker() {
        // Keep multicast reception enabled until responders finish their goodbye/close.
        instances.values.forEach { runCatching { it.responder.close() } }
        instances.clear()
        releaseUnusedMulticastLock()
    }

    /** Tests can await cleanup without making service stop or hostname changes wait for it. */
    internal fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean =
        worker.awaitTermination(timeout, unit)
}
