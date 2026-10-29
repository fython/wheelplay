package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Keeps the bound socket, so selecting an available port cannot race with another listener. */
internal fun bindAirPlayListener(
    address: InetAddress,
    preferredPort: Int,
    socketFactory: () -> ServerSocket = ::ServerSocket,
): ServerSocket {
    require(preferredPort in 0..65535) { "AirPlay port must be in 0..65535" }

    fun bind(port: Int): ServerSocket {
        val server = socketFactory()
        try {
            server.bind(InetSocketAddress(address, port))
            return server
        } catch (error: Exception) {
            try {
                server.close()
            } catch (closeError: Exception) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
    }

    return try {
        bind(preferredPort)
    } catch (original: BindException) {
        if (preferredPort == 0) throw original
        try {
            // Keep the same interface/address. A missing address still fails instead of
            // silently exposing the listener on every network interface.
            bind(0)
        } catch (fallback: Exception) {
            if (fallback !== original) fallback.addSuppressed(original)
            throw fallback
        }
    }
}
