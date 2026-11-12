package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayHostUiTest {
    @Test fun activeOemRequestReachesServiceCallbackWithoutAnActivityListener() {
        val config = AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02",
            "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val listener = object : AirPlaySessionListener {}
        var requests = 0
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "1", "1", "1", 3)),
            config, identity, PairingStore(), listener, object : AirPlayMediaHandler {}, {},
            requestHostUi = { requests++ })
        val session = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            listener, object : AirPlayMediaHandler {})
        val events = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        try {
            events.onHostUiRequested(session)
            assertEquals(0, requests)
            events.onSessionActive(session)
            ReflectionHelpers.setField(controller, "uiListener", null)
            events.onHostUiRequested(session)
            assertEquals(1, requests)
            events.onSessionEnded(session)
            events.onHostUiRequested(session)
            assertEquals(1, requests)
            assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
            session.close()
        }
    }
}
