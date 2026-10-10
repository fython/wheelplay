package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class TeslaHttpConfigTest {
    @Test fun mappedPortsArePrivilegedDistinctPorts() {
        for (port in listOf(1, 80, 1023)) {
            val config = TeslaHttpConfig(httpMappingPort = port)
            assertEquals("http://100.96.0.1:$port/", config.ipUrl)
        }
        for (port in listOf(-1, 0, 1024, 65535, Int.MAX_VALUE)) {
            assertTrue(runCatching { TeslaHttpConfig(httpMappingPort = port) }.isFailure)
            assertTrue(runCatching { TeslaHttpConfig(httpsMappingPort = port) }.isFailure)
        }
        assertTrue(runCatching { TeslaHttpConfig(httpMappingPort = 80, httpsMappingPort = 80) }.isFailure)
    }

    @Test fun rootModeAcceptsGeneralUnicastAddressesWithoutDns() {
        for (address in listOf("100.64.0.0", "100.96.0.1", "100.127.255.255", "192.168.1.2", "10.0.0.1", "3.3.3.3")) {
            assertTrue(address, TeslaHttpConfig.isHttpAddress(address))
            assertEquals(address, TeslaHttpConfig(address = address).address)
        }
        for (address in listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "169.254.1.1", "224.0.0.1", "255.255.255.255",
            "100.96.0.256", "100.096.0.1", "100.96.0", "100.96.0.1/32", "localhost", "::1", " 100.96.0.1")) {
            assertFalse(address, TeslaHttpConfig.isHttpAddress(address))
        }
    }

    @Test fun hostnameCannotInjectAnOriginPortCredentialsOrPath() {
        for (hostname in listOf("", "car.example.com", "device-1.example.com", "xn--fsqu00a.example")) {
            assertTrue(hostname, TeslaHttpConfig.isHostname(hostname))
        }
        for (hostname in listOf("http://car.example.com", "car.example.com:8080", "car.example.com/",
            "a@car.example.com", "car..example.com", "-car.example.com", "car_.example.com",
            "100.96.0.1", "localhost", "car.example.com\n", "a".repeat(64) + ".example.com")) {
            assertFalse(hostname, TeslaHttpConfig.isHostname(hostname))
        }
        assertEquals("http://100.96.0.1:80/", TeslaHttpConfig().ipUrl)
    }
}
