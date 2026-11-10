package moe.feng.wheelplay

import moe.feng.wheelplay.settings.SettingsDialogHost
import org.junit.Assert.*
import org.junit.Test

class SettingsDialogHostTest {
    @Test
    fun completingAnInputDoesNotCancelOrDismissTheNextPasswordStep() {
        val host = SettingsDialogHost()
        var cancelled = 0
        host.input("file password", "", true, false, { cancelled++ }) {
            host.input("key password", "", true, false, { cancelled++ }) {}
        }
        val first = host.request as SettingsDialogHost.Request.Input
        host.complete(first) { first.onSave("secret") }
        host.cancel(first)
        assertEquals("key password", (host.request as SettingsDialogHost.Request.Input).title)
        assertEquals(0, cancelled)
        host.cancel()
        host.cancel()
        assertNull(host.request)
        assertEquals(1, cancelled)
    }
}
