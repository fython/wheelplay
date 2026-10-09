package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.system.OsConstants
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
open class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    private var browserAddress: String? = null
    @Volatile var browserStatus = TeslaHttpStatus()
        private set

    /** Changes are deferred by the UI while CarPlay is connected; never replace a live NCM tunnel. */
    @Synchronized
    fun setBrowserAddress(address: String?): AttachResult {
        if (address != null && !TeslaHttpConfig.isSharedAddress(address)) {
            return AttachResult.Failed("虚拟 IP 必须位于 100.64.0.0–100.127.255.255")
        }
        if (address == browserAddress && (address == null || tun != null)) {
            if (address == null) browserStatus = TeslaHttpStatus()
            return AttachResult.AlreadyStarted
        }
        if (bridge != null) return AttachResult.Failed("请先断开 iPhone，再修改 Tesla HTTP 模式")
        closeTunnelLocked()
        browserAddress = address
        browserStatus = TeslaHttpStatus()
        return restoreBrowserTunnelLocked()
    }

    /** Used only when the owning web service is stopping; keep a live USB tunnel intact. */
    @Synchronized
    fun clearBrowserAddress() {
        browserAddress = null
        browserStatus = TeslaHttpStatus()
        // An established VPN's addresses are immutable. Remove the alias when the USB
        // attachment closes, without disturbing it during the asynchronous service teardown.
        if (bridge == null) closeTunnelLocked()
    }

    /** No default route, DNS override or interception of the phone's Internet traffic. */
    protected open fun establishTunnel(linkLocal: String? = null): ParcelFileDescriptor {
        val builder = Builder()
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setSession(SESSION_NAME)
            .setMtu(TUN_MTU)
            .setBlocking(true)
        if (linkLocal != null) {
            builder.addAddress(linkLocal, LINK_PREFIX).addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
        }
        browserAddress?.let { builder.addAddress(it, 32).addRoute(it, 32) }
        return builder.establish() ?: throw IOException("本地 VPN 未获授权，请重新开启 Tesla HTTP 模式")
    }

    private fun restoreBrowserTunnelLocked(): AttachResult {
        val address = browserAddress ?: return AttachResult.Started
        return try {
            if (tun == null) tun = establishTunnel()
            browserStatus = TeslaHttpStatus(address)
            AttachResult.Started
        } catch (error: Exception) {
            browserStatus = TeslaHttpStatus(error = "虚拟地址启动失败：${error.javaClass.simpleName}")
            AttachResult.Failed(browserStatus.error!!)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        // The replacement TUN carries both the USB IPv6 link and the optional browser alias.
        releaseLocked()
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = establishTunnel(linkLocal)
            tun = tunFd
            browserStatus = TeslaHttpStatus(browserAddress)

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            restoreBrowserTunnelLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        restoreBrowserTunnelLocked()
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            restoreBrowserTunnelLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
        restoreBrowserTunnelLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    @Synchronized
    override fun onRevoke() {
        val listener = if (bridge != null) attachment?.listener else null
        browserAddress = null
        // Wireless AirPlay does not depend on the VPN; withdrawing the browser alias
        // must not close its independent TCP listener.
        if (bridge != null) releaseLocked() else closeTunnelLocked()
        browserStatus = TeslaHttpStatus(error = "本地 VPN 授权已撤销，请重新开启 Tesla HTTP 模式")
        listener?.onTransportError("CarPlay VPN permission revoked")
        super.onRevoke()
    }

    @Synchronized
    override fun onDestroy() {
        browserAddress = null
        releaseLocked()
        browserStatus = TeslaHttpStatus()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val server = ServerSocket()
        server.bind(InetSocketAddress(replacement.address, replacement.config.port))
        attachment = replacement
        serverSocket = server
        Thread(
            { acceptLoop(generation, server) },
            "airplay-accept",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    restoreBrowserTunnelLocked()
                }
                listener.onTransportError(message)
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        closeSessionsLocked()
        closeTunnelLocked()
    }

    private fun closeTunnelLocked() {
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
        browserStatus = TeslaHttpStatus(error = browserStatus.error)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
