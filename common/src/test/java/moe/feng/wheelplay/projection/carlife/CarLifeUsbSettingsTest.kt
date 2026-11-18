package moe.feng.wheelplay.projection.carlife

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.projection.ProjectionResource
import moe.feng.wheelplay.carlife.CarLifeProtocolMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLifeUsbSettingsTest {
    @Test fun usbUsesItsOwnProtocolAndDoesNotDependOnSavedNetworkAddress() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("carlife_connection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("host", "invalid-old-address").putBoolean("remoteMode", true)
            .putString("transport", "usb").putString("usbProtocolVersion", "4.1")
            .putString("localProtocolVersion", "3.0").putString("remoteProtocolVersion", "3.0").commit()
        val connection = CarLifeConnection.load(context)
        assertTrue(connection.usb); assertTrue(connection.remote); assertTrue(connection.microphone)
        assertEquals(CarLifeProtocolMode.XIAOMI_4_1, connection.protocol)
        val provider = CarLifeSource(context)
        assertTrue(provider.resources.contains(ProjectionResource.USB))
        assertFalse(provider.resources.contains(ProjectionResource.WIRELESS))
        // Admission does not touch a USB device before activation and a viewport.
        val session = provider.open().get() as CarLifeProjectionSession
        assertTrue(session.media.publishNativeSession)
        val stopped = session.stop(); shadowOf(Looper.getMainLooper()).idle(); stopped.get(3, TimeUnit.SECONDS)
    }
    @Test fun explicitLocalModeIgnoresOldRemoteAddressAndLegacyModesMigrate() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("carlife_connection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("host", "192.168.1.3").commit()
        assertEquals("remote", CarLifeConnection.mode(prefs))
        prefs.edit().putBoolean("remoteMode", false).commit()
        assertEquals("local", CarLifeConnection.mode(prefs))
        assertEquals("127.0.0.1", CarLifeConnection.load(context).host)
        prefs.edit().putString("transport", "usb").commit()
        assertEquals(CarLifeProtocolMode.AUTO, CarLifeConnection.load(context).protocol)
    }
}
