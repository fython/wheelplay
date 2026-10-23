package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.transport.Iap2LocationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserLocationBridgeTest {
    private fun command(type: String) = JSONObject().put("type", type)
    private fun fix(timestamp: Long = 1700000000000) = command("location")
        .put("latitude", 25.033).put("longitude", 121.5654).put("accuracy", 5)
        .put("altitude", 12.5).put("heading", 90).put("speed", 10)
        .put("timestamp", timestamp).put("ageMillis", 0)

    @Test fun defaultAndInsecureOrNonOwningClientsCannotSupplyLocation() {
        val bridge = BrowserLocationBridge { 0L }; val owner = Any(); val stranger = Any()
        val provider = bridge.createProvider(); assertTrue(provider.start())
        assertNull(provider.latestNmea())
        bridge.attach(owner, true)
        bridge.control(owner, fix()); assertNull(provider.latestNmea())
        bridge.control(stranger, command("location-start"))
        bridge.control(stranger, fix()); assertNull(provider.latestNmea())
        bridge.detach(owner); bridge.attach(owner, false)
        bridge.control(owner, command("location-start")); bridge.control(owner, fix())
        assertNull(provider.latestNmea())
    }

    @Test fun authenticatedFixIsEncodedForIap2AndExpiresOnTheHostMonotonicClock() {
        var time = 100L
        val bridge = BrowserLocationBridge { time }; val owner = Any(); val provider = bridge.createProvider()
        provider.start(); bridge.attach(owner, true); bridge.control(owner, command("location-start"))
        bridge.control(owner, fix().put("ageMillis", 1000))
        val nmea = provider.latestNmea()!!
        assertTrue(nmea.startsWith("\$GPGGA,221320.00,2501.9800,N,12133.9240,E,"))
        assertTrue(nmea.contains("12.5,M")); assertTrue(nmea.contains(",19.44,90.00,141123,,"))
        assertEquals(2, nmea.split("\r\n").filter { it.isNotEmpty() }.size)
        time += 28_999; assertNotNull(provider.latestNmea())
        // Replayed timestamps must not extend freshness.
        bridge.control(owner, fix())
        time++; assertNull(provider.latestNmea())
    }

    @Test fun stopClearAndDisconnectRemoveCoordinatesAndOldOwnersCannotReviveThem() {
        val bridge = BrowserLocationBridge { 0L }; val owner = Any(); val provider = bridge.createProvider()
        provider.start(); bridge.attach(owner, true)
        for (type in listOf("location-clear", "location-stop")) {
            bridge.control(owner, command("location-start")); bridge.control(owner, fix())
            assertNotNull(provider.latestNmea()); bridge.control(owner, command(type)); assertNull(provider.latestNmea())
        }
        bridge.control(owner, fix()); assertNull(provider.latestNmea())
        bridge.control(owner, command("location-start")); bridge.control(owner, fix()); bridge.detach(owner)
        assertNull(provider.latestNmea())
        val replacement = Any(); bridge.attach(replacement, true)
        bridge.control(owner, command("location-start")); bridge.control(owner, fix())
        bridge.detach(owner); assertNull(provider.latestNmea())
        bridge.control(replacement, command("location-start")); bridge.control(replacement, fix())
        assertNotNull(provider.latestNmea())
    }

    @Test fun malformedCoordinatesAndOptionalValuesAreRejectedWhileMissingOptionalValuesAreAllowed() {
        val bridge = BrowserLocationBridge { 0L }; val owner = Any(); val provider = bridge.createProvider()
        provider.start(); bridge.attach(owner, true); bridge.control(owner, command("location-start"))
        for ((key, value) in listOf("latitude" to 91, "longitude" to -181, "accuracy" to -1,
            "speed" to -1, "heading" to 360, "ageMillis" to 30_001, "timestamp" to 0,
            "latitude" to "NaN", "latitude" to JSONObject.NULL)) {
            assertThrows(Exception::class.java) { bridge.control(owner, fix().put(key, value)) }
            assertNull(provider.latestNmea())
        }
        bridge.control(owner, fix().put("altitude", JSONObject.NULL).put("heading", JSONObject.NULL).put("speed", JSONObject.NULL))
        assertNotNull(provider.latestNmea())
    }

    @Test fun browserTakesPriorityAndAndroidFallbackFollowsSubscriptionLifecycle() {
        var starts = 0; var stops = 0; var closes = 0
        val android = object : Iap2LocationProvider {
            override fun start(): Boolean { starts++; return true }
            override fun stop() { stops++ }
            override fun close() { closes++ }
            override fun latestNmea() = "android"
        }
        val bridge = BrowserLocationBridge { 0L }; val owner = Any(); val provider = bridge.createProvider(android)
        assertNull(provider.latestNmea()); provider.start(); assertEquals(1, starts)
        assertEquals("android", provider.latestNmea())
        bridge.attach(owner, true); bridge.control(owner, command("location-start")); bridge.control(owner, fix())
        assertTrue(provider.latestNmea()!!.startsWith("\$GPGGA"))
        provider.stop(); assertEquals(1, stops); assertNull(provider.latestNmea())
        provider.start(); assertNotNull(provider.latestNmea())
        bridge.control(owner, command("location-stop")); assertEquals("android", provider.latestNmea())
        provider.close(); assertEquals(1, closes); assertNull(provider.latestNmea())
    }
}
