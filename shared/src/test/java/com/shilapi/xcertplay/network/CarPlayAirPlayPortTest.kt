package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayAirPlayPortTest {
    @Test fun occupiedPreferredPortFallsBackToAReachableListener() {
        // Keep the competing listener open throughout attachment; no free-port probe race.
        ServerSocket(0, 1, loopback).use { occupied ->
            withService { service ->
                val events = LinkedBlockingQueue<AirPlayTcpAccepted>()
                val owner = AirPlayListenerIdentity(1)
                val listener = object : AirPlaySessionListener {
                    override fun onTcpAccepted(event: AirPlayTcpAccepted) { events.add(event) }
                }
                val result = attach(service, occupied.localPort, listener, owner)

                assertStarted(service, result)
                val server = listenerSocket(service)
                assertTrue(server.localPort in 1..65535)
                assertNotEquals(occupied.localPort, server.localPort)
                assertEquals(loopback, server.inetAddress)
                Socket().use { client ->
                    client.connect(InetSocketAddress(loopback, server.localPort), 2_000)
                    val accepted = events.poll(2, TimeUnit.SECONDS)
                    assertNotNull("The fallback listener must accept a real TCP connection", accepted)
                    assertSame(owner, accepted!!.listener)
                }

                service.detach()
                assertTrue(server.isClosed)
                assertReleased(service)
                assertFalse("The competing listener belongs to another owner", occupied.isClosed)
            }
        }
    }

    @Test fun explicitEphemeralPortIsAllocatedAndReleasedOnDetach() {
        withService { service ->
            assertStarted(service, attach(service, 0))
            val server = listenerSocket(service)
            val port = server.localPort
            assertTrue(port in 1..65535)

            service.detach()
            service.detach()
            assertTrue(server.isClosed)
            assertReleased(service)
            ServerSocket().use { replacement ->
                replacement.bind(InetSocketAddress(loopback, port))
                assertEquals(port, replacement.localPort)
            }
        }
    }

    @Test fun occupiedIpv6PortFallsBackOnTheSameAddress() {
        val ipv6 = InetAddress.getByName("::1")
        val competitor = try {
            ServerSocket(0, 1, ipv6)
        } catch (error: IOException) {
            assumeNoException("Host IPv6 loopback binding is unavailable", error)
            return
        }
        competitor.use { occupied ->
            withService { service ->
                val result = assertStarted(service,
                    attach(service, occupied.localPort, bindAddress = ipv6))
                assertNotEquals(occupied.localPort, result.port)
                val server = listenerSocket(service)
                assertEquals(ipv6, server.inetAddress)
                Socket().use { client ->
                    client.connect(InetSocketAddress(ipv6, result.port), 2_000)
                }
                service.detach()
                assertTrue(server.isClosed)
                assertReleased(service)
                assertFalse(occupied.isClosed)
            }
        }
    }

    @Test fun availablePreferredPortIsPreservedOnReattachment() {
        withService { service ->
            assertStarted(service, attach(service, 0))
            val first = listenerSocket(service)
            val preferredPort = first.localPort
            service.detach()
            assertTrue(first.isClosed)

            assertStarted(service, attach(service, preferredPort))
            assertEquals(preferredPort, listenerSocket(service).localPort)
        }
    }

    @Test fun invalidPortFailsWithoutLeavingAttachmentAndAllowsAnotherAttempt() {
        withService { service ->
            assertTrue(attach(service, -1) is CarPlayVpnService.AttachResult.Failed)
            assertReleased(service)

            assertStarted(service, attach(service, 0))
            assertTrue(listenerSocket(service).localPort > 0)
            service.detach()
            assertReleased(service)
        }
    }

    @Test fun boundPortIsEncodedInStartSessionAndNsdPublication() {
        ServerSocket(0, 1, loopback).use { occupied ->
            withService { service ->
                val result = assertStarted(service, attach(service, occupied.localPort))
                assertNotEquals(occupied.localPort, result.port)
                val boundConfig = attachmentConfig(service)

                // Exercise protocol conversion of the bound result, without physical USB/Bluetooth.
                val wired = Iap2WiredControlClient.carPlayStartSession(Iap2WiredCarPlayEndpoint(
                    ipv6Addresses = listOf("fe80::2"), airPlayPort = result.port,
                    publicKey = identity.publicKeyHex, sourceVersion = boundConfig.sourceVersion,
                ))
                val wireless = Iap2WirelessControlClient.carPlayStartSession(Iap2WirelessCarPlayEndpoint(
                    ssid = "port-test", passphrase = "test-password", channel = 36,
                    security = Iap2WirelessSecurity.WPA_WPA2, ipAddresses = listOf("192.168.43.1"),
                    airPlayPort = result.port, deviceIdentifier = boundConfig.deviceId,
                    publicKey = identity.publicKeyHex, sourceVersion = boundConfig.sourceVersion,
                ))
                assertEquals(result.port.toLong(), Iap2BodyReader.of(wired).u32(2))
                assertEquals(result.port.toLong(), Iap2BodyReader.of(wireless).u32(2))

                val context = RuntimeEnvironment.getApplication()
                val nsd = shadowOf(context.getSystemService(Context.NSD_SERVICE) as NsdManager)
                CarPlayBonjour(context, boundConfig, identity).use { bonjour ->
                    bonjour.start()
                    val registration = ReflectionHelpers.getField<NsdManager.RegistrationListener>(
                        bonjour, "registrationListener",
                    )
                    val published = nsd.getRegisteredServiceInfo(registration)
                    assertNotNull(published)
                    assertEquals(result.port, published!!.port)
                }
            }
        }
    }

    @Test fun oldAttachmentResultKeepsItsPortAfterRebinding() {
        withService { service ->
            val first = assertStarted(service, attach(service, 0))
            val firstPort = first.port
            val firstServer = listenerSocket(service)
            service.detach()
            assertTrue(firstServer.isClosed)

            ServerSocket().use { occupied ->
                occupied.bind(InetSocketAddress(loopback, firstPort))
                val replacement = assertStarted(service, attach(service, firstPort))
                assertNotEquals(firstPort, replacement.port)
                assertEquals(firstPort, first.port)
                assertFalse(occupied.isClosed)
            }
        }
    }

    private fun withService(check: (CarPlayVpnService) -> Unit) {
        val lifecycle = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try { check(lifecycle.get()) } finally { lifecycle.destroy() }
    }

    private fun attach(
        service: CarPlayVpnService,
        port: Int,
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
        owner: AirPlayListenerIdentity? = null,
        bindAddress: InetAddress = loopback,
    ): CarPlayVpnService.AttachResult = service.attachWireless(
        bindAddress,
        AirPlayConfig("port-test", "00:00:00:00:00:01", "00:00:00:00:00:02", "1",
            AirPlayDisplayConfig(800, 480), port = port),
        identity,
        PairingStore(), null, listener, object : AirPlayMediaHandler {}, listenerIdentity = owner,
    )

    private fun listenerSocket(service: CarPlayVpnService): ServerSocket =
        ReflectionHelpers.getField(service, "serverSocket")

    private fun attachmentConfig(service: CarPlayVpnService): AirPlayConfig =
        ReflectionHelpers.getField<Any>(service, "attachment").let {
            ReflectionHelpers.getField(it, "config")
        }

    private fun assertStarted(
        service: CarPlayVpnService,
        result: CarPlayVpnService.AttachResult,
    ): CarPlayVpnService.AttachResult.Started {
        assertTrue("AirPlay attachment must succeed: $result",
            result is CarPlayVpnService.AttachResult.Started)
        return (result as CarPlayVpnService.AttachResult.Started).also {
            assertEquals(listenerSocket(service).localPort, it.port)
            assertEquals(it.port, attachmentConfig(service).port)
        }
    }

    private fun assertReleased(service: CarPlayVpnService) {
        assertFalse(service.isAttached())
        assertFalse(ReflectionHelpers.getField<AtomicBoolean>(service, "active").get())
        assertNull(ReflectionHelpers.getField<Any?>(service, "attachment"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "serverSocket"))
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "port-test")
}
