package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RememberedBrowsersTest {
    @Test fun credentialsSurviveNewRegistryAndOnlyHashesArePersisted() {
        val context = RuntimeEnvironment.getApplication()
        var clock = 1000L
        val devices = RememberedBrowsers(context) { clock }
        val credential = devices.remember("Chrome · Android", "192.168.1.2")!!
        assertEquals(32, credential.secret.length)
        assertFalse(context.getSharedPreferences("wheelplay_browser_devices", 0)
            .getString("devices", "")!!.contains(credential.secret))
        val reopened = RememberedBrowsers(context) { clock }
        assertNull(reopened.authenticate(credential.device.id, "x".repeat(32), "other"))
        assertNull(reopened.authenticate("other-id", credential.secret, "other"))
        clock = 5000
        val device = reopened.authenticate(credential.device.id, credential.secret, "192.168.2.3")!!
        assertEquals(1000, device.createdAt)
        assertEquals(5000, device.lastUsedAt)
        assertEquals("192.168.2.3", device.peer)
        assertEquals(device, RememberedBrowsers(context).list().single())
    }

    @Test fun renamedAndRemovedDevicesStayChangedAfterRestart() {
        val context = RuntimeEnvironment.getApplication()
        val devices = RememberedBrowsers(context)
        val first = devices.remember("Chrome", "client")!!
        val second = devices.remember("Safari", "other")!!
        devices.rename(first.device.id, "  车机\n浏览器  ")
        devices.rename(first.device.id, "   ")
        assertEquals("车机浏览器", RememberedBrowsers(context).list().first { it.id == first.device.id }.name)
        devices.remove(first.device.id)
        val reopened = RememberedBrowsers(context)
        assertNull(reopened.authenticate(first.device.id, first.secret, "client"))
        assertNotNull(reopened.authenticate(second.device.id, second.secret, "other"))
        reopened.clear()
        assertTrue(RememberedBrowsers(context).list().isEmpty())
    }

    @Test fun boundedRegistryDoesNotEvictAnExistingBrowser() {
        val context = RuntimeEnvironment.getApplication()
        val devices = RememberedBrowsers(context)
        val first = devices.remember("First browser", "client")!!
        repeat(RememberedBrowsers.MAX_DEVICES - 1) { assertNotNull(devices.remember("Browser $it", "client")) }
        assertNull(devices.remember("Overflow", "client"))
        assertEquals(RememberedBrowsers.MAX_DEVICES, RememberedBrowsers(context).list().size)
        assertNotNull(devices.authenticate(first.device.id, first.secret, "client"))
        devices.remove(first.device.id)
        assertNotNull(devices.remember("Replacement", "client"))
    }
}
