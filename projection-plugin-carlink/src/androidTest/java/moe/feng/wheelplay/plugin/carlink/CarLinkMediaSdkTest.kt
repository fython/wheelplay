package moe.feng.wheelplay.plugin.carlink

import androidx.test.platform.app.InstrumentationRegistry
import moe.feng.wheelplay.plugin.PluginMediaState
import org.junit.Assert.*
import org.junit.Test

/** Runs against the pinned vendor DEX in the installed APK, never the compile-only signatures. */
class CarLinkMediaSdkTest {
    @Test fun actualSdkMusicCallbackMapsMetadataAndMediaKeysExist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val received = mutableListOf<PluginMediaState>()
        val engine = SdkCarLinkEngine(context, null, media = received::add)
        val infoClass = Class.forName("com.ucar.vehiclesdk.UCarCommon\$MusicInfo")
        val info = infoClass.getConstructor(String::class.java, String::class.java, String::class.java,
            String::class.java, Long::class.javaPrimitiveType, String::class.java, String::class.java,
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance("Artist", "Album", "", "", 120000L, "Song", "", "", "", 2000, false, true)
        infoClass.getMethod("setAppId", Int::class.javaPrimitiveType).invoke(info, 42)
        val listener = engine.javaClass.getDeclaredField("phoneData").apply { isAccessible = true }.get(engine)
        Class.forName("com.ucar.vehiclesdk.IPhoneDataListener")
            .getMethod("onMusicInfoReceived", String::class.java, infoClass).invoke(listener, "synthetic", info)
        assertEquals(PluginMediaState(true, true, "Song", "Artist", "Album", "42", 120000, 2000), received.single())
        infoClass.getMethod("setDestroyed", Boolean::class.javaPrimitiveType).invoke(info, true)
        Class.forName("com.ucar.vehiclesdk.IPhoneDataListener")
            .getMethod("onMusicInfoReceived", String::class.java, infoClass).invoke(listener, "synthetic", info)
        assertEquals(PluginMediaState(), received.last())
        val action = Class.forName("com.ucar.vehiclesdk.UCarCommon\$KeyEventActionType")
        val key = Class.forName("com.ucar.vehiclesdk.UCarCommon\$KeyCodeType")
        Class.forName("com.ucar.vehiclesdk.UCarAdapter").getMethod("sendKeyEvent", action, key, Int::class.javaPrimitiveType)
        val keyMethod = CarLinkMedia::class.java.getDeclaredMethod("key", Int::class.javaPrimitiveType)
        for (command in 1..4) assertNotNull(keyMethod.invoke(CarLinkMedia, command))
        // No SDK init, advertise, network connection, microphone capture, or command transmission.
    }
}
