package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.web.LanAddresses.Kind
import com.shilapi.xcertplay.web.LanAddresses.Entry

class LanAddressesTest {
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
