package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.web.LanAddresses.Kind
import com.shilapi.xcertplay.web.LanAddresses.Entry

class LanAddressesTest {
    @Test fun customPortIsUsedByBothTeslaAndOtherLanEntries() {
        val owned = LanAddresses.sharedEntry("100.96.0.1", "car.example.com", 9090)
        assertEquals("http://car.example.com:9090", owned.url)
        assertEquals("http://100.96.0.1:9090", owned.ipUrl)
        val hotspot = Entry("ap0", "192.168.43.1", Kind.HOTSPOT, port = 9090)
        assertEquals("http://192.168.43.1:9090", hotspot.url)
    }

    @Test fun sharedAddressesAreShownWithoutAdvertisingArbitraryPublicAddresses() {
        fun ip(value: String) = java.net.InetAddress.getByName(value) as java.net.Inet4Address
        assertTrue(LanAddresses.isDiscoverable(ip("100.96.0.1")))
        assertTrue(LanAddresses.isDiscoverable(ip("192.168.1.1")))
        assertFalse(LanAddresses.isDiscoverable(ip("3.3.3.3")))
        assertFalse(LanAddresses.isDiscoverable(ip("127.0.0.1")))
        assertFalse(LanAddresses.isDiscoverable(ip("169.254.1.1")))
    }

    @Test fun rootSharedEntryRanksFirstWithoutPromotingOtherVpnAddresses() {
        val shared = LanAddresses.sharedEntry("100.96.0.1", "car.example.com")
        val vpn = Entry("tun0", "100.96.0.1", Kind.VPN)
        val hotspot = Entry("ap0", "192.168.43.1", Kind.HOTSPOT)
        assertEquals(Kind.TESLA_HTTP, shared.kind)
        assertEquals("http://car.example.com:8080", shared.url)
        assertEquals("http://100.96.0.1:8080", vpn.url)
        assertEquals(shared, LanAddresses.ranked(listOf(vpn, hotspot, shared)).first())
        assertEquals(hotspot, LanAddresses.ranked(listOf(vpn, hotspot)).first())
    }

    @Test fun lanBeatsPrivateCellularVpnAndCarPlayDirectAddresses() {
        val entries = listOf(
            Entry("rmnet_data0", "10.70.125.52", Kind.CELLULAR, true),
            Entry("p2p-wlan0-0", "192.168.49.1", Kind.P2P),
            Entry("tun0", "10.8.45.108", Kind.VPN, true),
            Entry("wlan0", "10.16.249.27", Kind.WIFI))
        val sorted = LanAddresses.ranked(entries)
        assertEquals(listOf(Kind.WIFI, Kind.P2P, Kind.VPN, Kind.CELLULAR), sorted.map { it.kind })
        assertEquals("http://10.16.249.27:8080", sorted.first().url)
        assertTrue(sorted.first().description.contains("wlan0"))
    }
    @Test fun frameworkTransportAndSpecialInterfaceNamesTakePriority() {
        assertEquals(Kind.VPN, LanAddresses.classify("wlan0", Kind.VPN))
        assertEquals(Kind.P2P, LanAddresses.classify("p2p-wlan0-0", Kind.WIFI))
        assertEquals(Kind.ETHERNET, LanAddresses.classify("vendor_net", Kind.ETHERNET))
        assertEquals(Kind.HOTSPOT, LanAddresses.classify("ap0"))
        assertEquals(Kind.USB, LanAddresses.classify("rndis0"))
        assertEquals(Kind.CELLULAR, LanAddresses.classify("rmnet_data2"))
        assertEquals(Kind.OTHER, LanAddresses.classify("custom0"))
    }
    @Test fun activeLanWinsTiesAndOrderingIsStableWithoutLosingInterfaces() {
        val wifi = Entry("wlan0", "192.168.1.5", Kind.WIFI, true)
        val ethernet = Entry("eth0", "192.168.1.5", Kind.ETHERNET)
        assertEquals(listOf(wifi, ethernet), LanAddresses.ranked(listOf(ethernet, wifi, wifi)))
        assertEquals(LanAddresses.ranked(listOf(wifi, ethernet)), LanAddresses.ranked(listOf(ethernet, wifi)))
        assertTrue(LanAddresses.ranked(emptyList()).isEmpty())
    }
}
