package moe.feng.wheelplay.plugin.carlink

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLinkSettingsProviderTest {
    @Test fun connectionSelectionPreservesVideoSettingsAndRejectsUnknownValuesAtomically() {
        val controller = Robolectric.buildContentProvider(CarLinkSettingsProvider::class.java).create()
        val provider = controller.get()
        try {
            assertEquals("p2p", provider.call("config", null, null).getString("connection"))
            provider.call("save", null, Bundle().apply {
                putString("mode", "custom"); putFloat("scale", 1.5f); putInt("dpi", 240)
                putString("videoOutput", "webrtc")
            })
            for (connection in listOf("softap", "usb", "p2p")) {
                provider.call("save", null, Bundle().apply { putString("connection", connection) })
                val config = provider.call("config", null, null)
                assertEquals(connection, config.getString("connection"))
                assertEquals("custom", config.getString("mode"))
                assertEquals(1.5f, config.getFloat("scale"))
                assertEquals(240, config.getInt("dpi"))
                assertEquals("webrtc", config.getString("videoOutput"))
            }
            provider.call("save", null, Bundle().apply { putString("connection", "usb") })
            provider.call("save", null, Bundle().apply { putInt("dpi", 200) })
            assertEquals("usb", provider.call("config", null, null).getString("connection"))
            assertThrows(IllegalArgumentException::class.java) {
                provider.call("save", null, Bundle().apply { putString("connection", "invalid"); putInt("dpi", 320) })
            }
            assertEquals("usb", provider.call("config", null, null).getString("connection"))
            assertEquals(200, provider.call("config", null, null).getInt("dpi"))
        } finally { controller.shutdown() }
    }

    @Test fun defaultsToSurfaceAndPersistsModeAcrossUnrelatedSettingEdits() {
        val controller = Robolectric.buildContentProvider(CarLinkSettingsProvider::class.java).create()
        val provider = controller.get()
        try {
            assertEquals("surface", provider.call("config", null, null).getString("videoOutput"))
            provider.call("save", null, Bundle().apply { putString("videoOutput", "webrtc") })
            assertEquals("webrtc", provider.call("config", null, null).getString("videoOutput"))
            provider.call("save", null, Bundle().apply { putInt("dpi", 240) })
            assertEquals("webrtc", provider.call("config", null, null).getString("videoOutput"))
            assertThrows(IllegalArgumentException::class.java) {
                provider.call("save", null, Bundle().apply { putString("videoOutput", "unknown") })
            }
            assertEquals("webrtc", provider.call("config", null, null).getString("videoOutput"))
            provider.call("save", null, Bundle().apply { putString("videoOutput", "surface") })
            assertEquals("surface", provider.call("config", null, null).getString("videoOutput"))
        } finally { controller.shutdown() }
    }
}
