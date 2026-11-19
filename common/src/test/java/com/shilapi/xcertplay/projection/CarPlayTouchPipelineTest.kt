package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the production projection adapter as well as its controller/HID serialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayTouchPipelineTest {
    @Test fun singleFingerDragCoalescesAndCancelReleasesAtItsLastPosition() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val wire = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                bytes.write(b, off, len)
            }
        }
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
        )
        set(session, "eventSocket", object : Socket() { override fun getOutputStream() = wire })
        set(session, "eventCipher", ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        set(controller, "activeSession", session)
        val projection = CarPlaySourceSession { null }.apply { bind(controller, null) }
        try {
            // Pointer 1 arriving by itself also verifies that the adapter does not fill pointer 0 with an up.
            assertTrue(projection.touch(listOf(ProjectionContact(1, .1, .2, true))))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(300) { index ->
                assertTrue(projection.touch(listOf(ProjectionContact(1, .2 + index * .001, .3, true))))
            }
            assertTrue(projection.touch(emptyList()))
            release.countDown()
            val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            val cipher = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 })
            val requests = RtspMessage.parseMessages(cipher.decrypt(bytes.toByteArray()).data).messages
            assertEquals("adapter prevented move coalescing", 3, requests.size)
            val reports = requests.map { (BplistCodec.decode(it.body) as Map<*, *>)["hidReport"] as ByteArray }
            assertEquals(1, reports[0][1].toInt())
            assertEquals(1, reports[1][1].toInt())
            assertEquals(0, reports[2][1].toInt())
            assertEquals(399, coordinate(reports[1], 2))
            assertEquals(144, coordinate(reports[1], 4))
            assertArrayEquals("cancel moved the finger to the origin", reports[1].copyOfRange(2, 6), reports[2].copyOfRange(2, 6))
        } finally {
            release.countDown()
            session.close()
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    private fun coordinate(report: ByteArray, offset: Int) =
        (report[offset].toInt() and 0xff) or ((report[offset + 1].toInt() and 0xff) shl 8)

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
