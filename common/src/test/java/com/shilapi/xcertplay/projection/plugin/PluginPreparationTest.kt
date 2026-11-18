package com.shilapi.xcertplay.projection.plugin

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PluginPreparationTest {
    @Test fun resultOnlyResumesOriginalAvailableIdleSource() {
        val plugin = InstalledProjectionPlugin(ComponentName("test.plugin", "test.plugin.Service"), "Plugin", 10,
            "signer", 100, 1, null, null, preparation = ComponentName("test.plugin", "test.plugin.Prepare"))
        val original = PluginPreparation.create(plugin, 3)
        val pending = PluginPreparation.fromBundle(original.toBundle())!!
        assertEquals(original, pending)
        assertTrue(pending.accepts(plugin, plugin.id, 3, false, true, pending.request))
        assertFalse(pending.accepts(plugin, "another-source", 3, false, true, pending.request))
        assertFalse(pending.accepts(plugin, plugin.id, 4, false, true, pending.request))
        assertFalse(pending.accepts(plugin, plugin.id, 3, true, true, pending.request))
        assertFalse(pending.accepts(plugin, plugin.id, 3, false, false, pending.request))
        assertFalse(pending.accepts(plugin, plugin.id, 3, false, true, "old-request"))
        assertFalse(pending.accepts(plugin.copy(updatedAt = 101), plugin.id, 3, false, true, pending.request))
        assertFalse(pending.accepts(plugin.copy(signer = "other"), plugin.id, 3, false, true, pending.request))
        assertFalse(pending.accepts(null, plugin.id, 3, false, true, pending.request))
    }
}
