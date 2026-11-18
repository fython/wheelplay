package moe.feng.wheelplay.plugin.carlink

import android.app.AppComponentFactory
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CarLinkComponentFactoryTest {
    @Test fun packagedFactoryResolvesAndReplacesPrivilegedHotspotService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = context.applicationInfo.appComponentFactory
        assertEquals(CarLinkComponentFactory::class.java.name, name)
        val factory = context.classLoader.loadClass(name).getDeclaredConstructor().newInstance() as AppComponentFactory
        val service = factory.instantiateService(context.classLoader,
            "com.share.connect.wifiap.WifiApService", Intent())
        assertTrue(service is ManualHotspotService)
        // Instantiation only; do not create, modify, or stop the user's hotspot.
    }
}
