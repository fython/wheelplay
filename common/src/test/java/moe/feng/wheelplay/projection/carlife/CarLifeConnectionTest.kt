package moe.feng.wheelplay.projection.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeConnectionTest {
    @Test fun preservesExistingModesAndExplicitProtocolOverridesLegacyBrand() {
        assertEquals("4.1", CarLifeConnection.protocolSelection(null, "xiaomi"))
        assertEquals("3.0", CarLifeConnection.protocolSelection(null, "vivo"))
        assertEquals("4.1", CarLifeConnection.protocolSelection(null, "auto", "xiaomi"))
        assertEquals("3.0", CarLifeConnection.protocolSelection(null, "auto", "vivo"))
        assertEquals("3.0", CarLifeConnection.protocolSelection("3.0", "xiaomi"))
        assertEquals("4.1", CarLifeConnection.protocolSelection("4.1", "vivo"))
    }

    @Test fun newSettingsDefaultToAutoAndExplicitAutoOverridesLegacy() {
        assertEquals("auto", CarLifeConnection.protocolSelection(null, null))
        assertEquals("auto", CarLifeConnection.protocolSelection(null, null, "xiaomi"))
        assertEquals("auto", CarLifeConnection.protocolSelection("auto", "xiaomi"))
        assertEquals("auto", CarLifeConnection.protocolSelection("auto", "vivo"))
    }

    @Test fun acceptsUnicastIpv4AndRejectsUrlsAndInvalidAddresses() {
        assertTrue(CarLifeConnection.validAddress("192.168.32.226"))
        assertTrue(CarLifeConnection.validAddress("10.0.0.2"))
        for (value in listOf("", "http://192.168.1.2", "192.168.1.2:7240", "192.168.1.256", "1.2.3", "127.0.0.1", "0.0.0.0", "224.0.0.1", "99999999999999.1.1.1")) {
            assertFalse(value, CarLifeConnection.validAddress(value))
        }
    }
}
