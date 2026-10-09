package com.shilapi.xcertplay.web

import android.content.Intent
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpConfig
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
import java.io.IOException

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
    fun standaloneAndUsbPlansOnlyRouteTheAliasAndOriginalIpv6Link() {
        AllocatingBuilderShadow.plans.clear()
        val controller = Robolectric.buildService(RoutingVpnService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(CarPlayVpnService.AttachResult.Started, service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS))
            service.allocateWiredTunnel().close()
            val plans = AllocatingBuilderShadow.plans
            assertEquals(2, plans.size)
            for ((index, plan) in plans.withIndex()) {
                assertEquals(if (index == 0) 1 else 2, plan.addresses.size)
                assertEquals(if (index == 0) 1 else 2, plan.routes.size)
                assertTrue(plan.addresses.contains(TeslaHttpConfig.DEFAULT_ADDRESS to 32))
                assertTrue(plan.routes.contains(TeslaHttpConfig.DEFAULT_ADDRESS to 32))
                assertFalse("Internet traffic must not be routed into this VPN", plan.routes.any { it.second == 0 })
                assertEquals(setOf(android.system.OsConstants.AF_INET, android.system.OsConstants.AF_INET6), plan.families)
                if (index == 1) {
                    assertTrue(plan.addresses.contains("fe80::1" to 64))
                    assertTrue(plan.routes.contains("fe80::" to 64))
                }
            }
        } finally {
            controller.destroy()
            AllocatingBuilderShadow.peers.forEach { it.close() }
            AllocatingBuilderShadow.peers.clear()
            AllocatingBuilderShadow.plans.clear()
        }
    }

    // Replace only the kernel TUN allocation; exercise the real service lifecycle and resources.
    class TestVpnService : CarPlayVpnService() {
        val tunnels = mutableListOf<ParcelFileDescriptor>()
        val peers = mutableListOf<ParcelFileDescriptor>()
        var failAllocation = false
        override fun establishTunnel(linkLocal: String?): ParcelFileDescriptor {
            if (failAllocation) throw IOException("test allocation failure")
            val pipe = ParcelFileDescriptor.createPipe()
            tunnels.add(pipe[0]); peers.add(pipe[1])
            return pipe[0]
        }
    }

    @Test fun aliasSurvivesPhoneDetachButStopsWithItsWebOwner() {
        val controller = Robolectric.buildService(TestVpnService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS))
            assertFalse(service.isAttached())
            assertEquals(TeslaHttpConfig.DEFAULT_ADDRESS, service.browserStatus.address)
            assertEquals(CarPlayVpnService.AttachResult.AlreadyStarted, service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS))
            assertEquals(1, service.tunnels.size)
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                java.net.InetAddress.getByName("127.0.0.1"),
                AirPlayConfig("Test", "00:11:22:33:44:55", "00:11:22:33:44:55", "1.0",
                    AirPlayDisplayConfig(1280, 720), port = 0),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
            assertTrue(service.isAttached())
            assertEquals("Wireless attach must retain the existing alias", 1, service.tunnels.size)
            service.detach()
            assertFalse(service.isAttached())
            assertEquals(TeslaHttpConfig.DEFAULT_ADDRESS, service.browserStatus.address)
            assertEquals(2, service.tunnels.size)
            assertClosed(service.tunnels.first())
            service.clearBrowserAddress()
            assertNull(service.browserStatus.address)
            assertClosed(service.tunnels.last())
            service.detach()
            assertEquals("A later phone teardown must not resurrect a stopped alias", 2, service.tunnels.size)
        } finally { controller.destroy(); service.peers.forEach { it.close() } }
    }

    @Test fun failedAllocationIsNotAdvertisedAndCanBeRetried() {
        val controller = Robolectric.buildService(TestVpnService::class.java).create()
        val service = controller.get()
        try {
            service.failAllocation = true
            assertTrue(service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS) is CarPlayVpnService.AttachResult.Failed)
            assertNull(service.browserStatus.address)
            assertNotNull(service.browserStatus.error)
            service.failAllocation = false
            assertEquals(CarPlayVpnService.AttachResult.Started, service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS))
            assertNotNull(service.browserStatus.address)
            assertNull(service.browserStatus.error)
        } finally { controller.destroy(); service.peers.forEach { it.close() } }
    }

    @Test fun revocationWithdrawsTheAliasAndDoesNotReestablishOnDetach() {
        val controller = Robolectric.buildService(TestVpnService::class.java).create()
        val service = controller.get()
        try {
            service.setBrowserAddress(TeslaHttpConfig.DEFAULT_ADDRESS)
            service.onRevoke()
            assertNull(service.browserStatus.address)
            assertNotNull(service.browserStatus.error)
            assertClosed(service.tunnels.single())
            service.detach()
            assertEquals(1, service.tunnels.size)
            assertNotNull("Phone teardown must retain the revocation explanation", service.browserStatus.error)
        } finally { controller.destroy(); service.peers.forEach { it.close() } }
    }

    @Test fun frameworkVpnBindingUsesTheRevocationBinderAndLocalClientsUseTheLocalBinder() {
        val controller = Robolectric.buildService(TestVpnService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(service.onBind(Intent()) is CarPlayVpnService.LocalBinder)
            assertNotNull(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)))
            assertFalse(service.onBind(Intent(android.net.VpnService.SERVICE_INTERFACE)) is CarPlayVpnService.LocalBinder)
        } finally { controller.destroy() }
    }

    private fun assertClosed(fd: ParcelFileDescriptor) {
        try { fd.fd; fail("TUN descriptor must be closed") }
        catch (_: IllegalStateException) { }
    }
}
