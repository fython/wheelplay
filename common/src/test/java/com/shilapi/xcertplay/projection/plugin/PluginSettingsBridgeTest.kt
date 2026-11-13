package com.shilapi.xcertplay.projection.plugin

import android.os.*
import moe.feng.wheelplay.plugin.forRemoteProcess
import moe.feng.wheelplay.AirPlayPersistence
import com.shilapi.xcertplay.web.ImageFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PluginSettingsBridgeTest {
    @Test fun updatesHostPreferencesAndRejectsInvalidUpdateAtomically() {
        val app = RuntimeEnvironment.getApplication()
        val bridge = PluginSettingsBridge(app)
        assertEquals(ResultReceiver::class.java, bridge.forRemoteProcess().javaClass)
        var response = Bundle()
        var code = -1
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                code = resultCode; response = resultData!!
            }
        }
        fun save(quality: Int, fps: Int) {
            bridge.send(1, Bundle().apply {
                putParcelable("reply", reply); putString("format", "JPEG")
                putInt("quality", quality); putInt("fps", fps)
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        save(92, 30)
        assertEquals(0, code)
        assertEquals(92, AirPlayPersistence.loadImageQuality(app))
        assertEquals(ImageFormat.JPEG, AirPlayPersistence.loadImageFormat(app))
        assertEquals(30, response.getInt("fps"))
        save(85, 60)
        assertEquals(1, code)
        assertEquals(92, AirPlayPersistence.loadImageQuality(app))
        assertEquals(30, AirPlayPersistence.loadBrowserImageFps(app))
    }
}
