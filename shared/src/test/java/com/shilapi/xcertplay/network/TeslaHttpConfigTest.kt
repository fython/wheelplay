package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class TeslaHttpConfigTest {
    @Test fun customPortsIncludeBoundariesAndRejectInvalidValues() {
        for (port in listOf(1, 80, 9090, 65535)) {
            val config = TeslaHttpConfig(hostname = "car.example.com", port = port)
            assertEquals("http://100.96.0.1:$port/", config.ipUrl)
            assertEquals("http://car.example.com:$port/", config.hostnameUrl)
        }
        for (port in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            try { TeslaHttpConfig(port = port); fail("Invalid port $port must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun onlySharedSpaceLiteralsAreAcceptedWithoutDns() {
        for (address in listOf("100.64.0.0", "100.96.0.1", "100.127.255.255")) {
            assertTrue(address, TeslaHttpConfig.isSharedAddress(address))
        }
        for (address in listOf("100.63.255.255", "100.128.0.0", "192.168.1.1", "3.3.3.3",
            "100.96.0.256", "100.096.0.1", "100.96.0", "100.96.0.1/32", "localhost", "::1", " 100.96.0.1")) {
            assertFalse(address, TeslaHttpConfig.isSharedAddress(address))
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
        assertEquals("http://100.96.0.1:8080/", TeslaHttpConfig().ipUrl)
        assertNull(TeslaHttpConfig().hostnameUrl)
        assertEquals("http://car.example.com:8080/", TeslaHttpConfig(hostname = "car.example.com").hostnameUrl)
    }
}
