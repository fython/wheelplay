package com.shilapi.xcertplay.web

import android.content.Intent
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeslaHttpLifecycleTest {
    class RoutingVpnService : CarPlayVpnService() {
        fun allocateWiredTunnel() = establishTunnel("fe80::1")
    }

    @Implements(android.net.VpnService.Builder::class)
    class AllocatingBuilderShadow {
        @RealObject lateinit var builder: android.net.VpnService.Builder
        val addresses = mutableListOf<Pair<String, Int>>()
        val routes = mutableListOf<Pair<String, Int>>()
        val families = mutableSetOf<Int>()
        @Implementation fun addAddress(address: String, prefix: Int): android.net.VpnService.Builder {
            addresses.add(address to prefix); return builder
        }
        @Implementation fun addRoute(address: String, prefix: Int): android.net.VpnService.Builder {
            routes.add(address to prefix); return builder
        }
        @Implementation fun allowFamily(family: Int): android.net.VpnService.Builder {
            families.add(family); return builder
        }
        @Implementation fun establish(): ParcelFileDescriptor {
            plans.add(this)
            val pipe = ParcelFileDescriptor.createPipe()
            peers.add(pipe[1])
            return pipe[0]
        }
        companion object {
            val plans = mutableListOf<AllocatingBuilderShadow>()
            val peers = mutableListOf<ParcelFileDescriptor>()
        }
    }

    @Test
    @Config(shadows = [AllocatingBuilderShadow::class])
    fun wiredVpnOnlyRoutesItsOriginalIpv6Link() {
        AllocatingBuilderShadow.plans.clear()
        val controller = Robolectric.buildService(RoutingVpnService::class.java).create()
        try {
            controller.get().allocateWiredTunnel().close()
            val plan = AllocatingBuilderShadow.plans.single()
            assertEquals(listOf("fe80::1" to 64), plan.addresses)
            assertEquals(listOf("fe80::" to 64), plan.routes)
            assertEquals(setOf(android.system.OsConstants.AF_INET, android.system.OsConstants.AF_INET6), plan.families)
        } finally {
            controller.destroy()
            AllocatingBuilderShadow.peers.forEach { it.close() }
            AllocatingBuilderShadow.peers.clear()
            AllocatingBuilderShadow.plans.clear()
        }
    }

    @Test fun wirelessAttachDetachAndVpnRevocationNeverAllocateATunnel() {
        val controller = Robolectric.buildService(RoutingVpnService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                java.net.InetAddress.getByName("127.0.0.1"),
                AirPlayConfig("Test", "00:11:22:33:44:55", "00:11:22:33:44:55", "1.0",
                    AirPlayDisplayConfig(1280, 720), port = 0),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
            assertTrue(service.isAttached())
            service.onRevoke()
            assertTrue("Wireless listener is independent of the wired VPN", service.isAttached())
            service.detach()
            assertFalse(service.isAttached())
        } finally { controller.destroy() }
    }

    @Test fun tetheringUsesSystemReportedInterfacesAndRejectsShellInput() {
        val intent = Intent("android.net.conn.TETHER_STATE_CHANGED")
            .putStringArrayListExtra("tetherArray", arrayListOf("ap0", "wlan1", "ap0", "lo", "ap0;id"))
        assertEquals(setOf("ap0", "wlan1"), TeslaHttpCompatibility.tetheredInterfaces(intent))
        assertTrue(TeslaHttpCompatibility.tetheredInterfaces(Intent()).isEmpty())
        assertEquals(setOf("ap0"), TeslaHttpCompatibility.tetheredInterfaces(
            Intent().putExtra("tetherArray", arrayOf("ap0"))))
    }

    @Test fun frameworkVpnBindingUsesTheRevocationBinderAndLocalClientsUseTheLocalBinder() {
        val controller = Robolectric.buildService(RoutingVpnService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(service.onBind(Intent()) is CarPlayVpnService.LocalBinder)
            assertNotNull(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)))
            assertFalse(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)) is CarPlayVpnService.LocalBinder)
        } finally { controller.destroy() }
    }

}
