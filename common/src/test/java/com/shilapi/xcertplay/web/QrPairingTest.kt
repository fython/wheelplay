package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test

class QrPairingTest {
    @Test fun resumedDevicesReuseTokensAndRemovalRevokesOnlyTheirSessions() {
        val registry = QrPairing { 1000 }
        val original = registry.issueToken()!!
        assertTrue(registry.bindDevice(original, "device-a"))
        repeat(40) { assertEquals(original, registry.issueToken("device-a")) }
        val other = registry.issueToken("device-b")!!
        registry.revokeDevice("device-a")
        assertFalse(registry.authorized(original))
        assertTrue(registry.authorized(other))
        assertFalse(registry.bindDevice(original, "device-a"))
        registry.clear()
        assertFalse(registry.authorized(other))
    }
    @Test fun onlyMatchingLocalScanCanApproveAndPollingNeedsSeparateSecret() {
        val registry = QrPairing { 1000 }
        val request = registry.create("192.168.1.2")!!
        assertNull(registry.get(request.id, "wrong"))
        assertNull(registry.inspect("https://evil.invalid"))
        assertFalse(registry.approve(request.payload + "x"))
        assertFalse(registry.authorized(request.payload))
        assertTrue(registry.approve(request.payload))
        assertFalse(registry.approve(request.payload))
        val token = registry.get(request.id, request.pollSecret)!!.token!!
        assertTrue(registry.authorized(token))
        assertFalse(registry.authorized(request.pollSecret))
        registry.cancel(request.id, request.pollSecret)
        assertFalse(registry.authorized(token))
    }
    @Test fun expiredRequestsAndOtherServerQrCannotBeApproved() {
        var clock = 0L
        val registry = QrPairing { clock }
        val request = registry.create("client")!!
        assertFalse(QrPairing { clock }.approve(request.payload))
        clock = 120_000
        assertNull(registry.get(request.id, request.pollSecret))
        assertFalse(registry.approve(request.payload))
        val second = registry.create("client")!!
        registry.approve(second.payload)
        val token = registry.get(second.id, second.pollSecret)!!.token!!
        clock += 12 * 60 * 60_000L
        assertFalse(registry.authorized(token))
    }
    @Test fun pendingRequestsAreBoundedAndStopRevokesCredentials() {
        val registry = QrPairing { 0 }
        repeat(4) { assertNotNull(registry.create("same client")) }
        assertNull(registry.create("same client"))
        repeat(28) { assertNotNull(registry.create("client $it")) }
        assertNull(registry.create("another client"))
        registry.clear()
        val request = registry.create("same client")!!
        registry.approve(request.payload)
        val token = registry.get(request.id, request.pollSecret)!!.token!!
        registry.revoke(token)
        assertFalse(registry.authorized(token))
        registry.clear()
        assertFalse(registry.authorized(token))
        assertFalse(registry.approve(request.payload))
    }
}
