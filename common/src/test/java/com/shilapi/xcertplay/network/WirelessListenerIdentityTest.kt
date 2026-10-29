package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WirelessListenerIdentityTest {
    @Test fun localProbesAreMarkedInternalAndRebindingUsesTheNewListenerIdentity() {
        val lifecycle = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = lifecycle.get()
        val events = LinkedBlockingQueue<AirPlayTcpAccepted>()
        val listener = object : AirPlaySessionListener {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) { events.add(event) }
        }
        try {
            repeat(2) { generation ->
                val identity = AirPlayListenerIdentity(generation)
                val result = service.attachWireless(
                    InetAddress.getLoopbackAddress(), AirPlayConfig("test", "00:00:00:00:00:01",
                        "00:00:00:00:00:02", "1", AirPlayDisplayConfig(800, 480), port = 0),
                    AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
                    listener, object : AirPlayMediaHandler {}, listenerIdentity = identity)
                assertTrue(result is CarPlayVpnService.AttachResult.Started)
                val server = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
                assertEquals(server.localPort, (result as CarPlayVpnService.AttachResult.Started).port)
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
                    val accepted = requireNotNull(events.poll(2, TimeUnit.SECONDS))
                    assertSame(identity, accepted.listener)
                    assertTrue(accepted.internal)
                    assertTrue(accepted.acceptedAtNanos > 0)
                }
            }
            assertTrue(events.isEmpty())
        } finally { lifecycle.destroy() }
    }
}
