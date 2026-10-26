package moe.feng.wheelplay.projection.desktop

import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import com.shilapi.xcertplay.projection.ProjectionContact
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise generated AIDL proxy/stub marshalling instead of local-interface shortcuts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesktopBinderProtocolTest {
    private class Helper : IDesktopService.Stub() {
        var offered = DesktopFeatures.SERVICE_SUPPORTED
        var status = DesktopStatus.OK
        var config: DesktopDisplayConfig? = null
        var frame: DesktopTouchFrame? = null
        var omitStatus = false
        var backCalls = 0
        var touchFlags = 0
        var touchReplyWasNull = false
        override fun hello(version: Int, hostFeatures: Long): DesktopHandshakeResult {
            val enabled = offered and hostFeatures
            val serviceInfo = DesktopNegotiation.negotiate(version = version, requested = enabled, helperUid = 2000)
                .apply { supportedFeatures = offered }
            return DesktopHandshakeResult().apply {
                status = if (omitStatus) null else this@Helper.status
                info = if (this@Helper.status.code == 0) serviceInfo else null
            }
        }
        override fun start(token: String, owner: IBinder, surface: Surface, component: String, width: Int, height: Int, dpi: Int): DesktopStartResult {
            config = DesktopDisplayConfig(width, height, dpi)
            return DesktopStartResult().apply {
                status = if (omitStatus) null else this@Helper.status
                displayId = if (this@Helper.status.code == 0) 42 else 0
            }
        }
        override fun touch(token: String, frame: DesktopTouchFrame) { this.frame = frame }
        override fun back(token: String, layout: Int): DesktopStatus { backCalls++; return status }
        override fun healthy(token: String, layout: Int) = status
        override fun stop(token: String) = status
        override fun destroy() = Unit
    }

    private fun connect(helper: Helper): ShizukuDesktopBackend.Connection {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.FIRST_CALL_TRANSACTION + 2) {
                    helper.touchFlags = flags
                    helper.touchReplyWasNull = reply == null
                }
                return helper.asBinder().transact(code, data, reply, flags)
            }
        }
        val proxy = IDesktopService.Stub.asInterface(remote)
        assertNotSame(helper, proxy)
        return ShizukuDesktopBackend.Connection(proxy) {}.apply { handshake() }
    }

    @Test fun generatedProxyTransportsHandshakeDisplaySettingsTouchAndCodedFailures() {
        val helper = Helper()
        val connection = connect(helper)
        assertEquals(2000, connection.uid)
        assertEquals(10, connection.capabilities.maxTouchContacts)
        assertTrue(connection.capabilities.backButton)
        val surface = Surface(SurfaceTexture(0))
        try {
            val config = DesktopDisplayConfig(1080, 1920, 320)
            assertEquals(42, connection.start(surface, "test.app/.Main", config))
            assertEquals(config, helper.config)
            connection.touch(listOf(ProjectionContact(7, .25, .75, false)))
            assertEquals(IBinder.FLAG_ONEWAY, helper.touchFlags and IBinder.FLAG_ONEWAY)
            assertTrue(helper.touchReplyWasNull)
            assertArrayEquals(intArrayOf(7), helper.frame!!.ids)
            assertArrayEquals(floatArrayOf(.25f), helper.frame!!.xs, 0f)
            assertArrayEquals(booleanArrayOf(false), helper.frame!!.down)
            connection.back(); assertEquals(1, helper.backCalls)
            assertTrue(connection.healthy())
            helper.status = DesktopStatus(DesktopErrorCode.LAYOUT_CHANGED.wire, "expected 1080x1920; received 1920x1080")
            val error = assertThrows(DesktopException::class.java) { connection.healthy() }
            assertEquals(DesktopErrorCode.LAYOUT_CHANGED, error.category)
            assertEquals(helper.status.diagnostic, error.diagnostic)
            helper.status = DesktopStatus(9001, "future helper failure")
            connection.touch(emptyList()) // No per-frame business failure is returned.
            assertEquals(9001, assertThrows(DesktopException::class.java) { connection.healthy() }.code)
        } finally { surface.release() }
    }

    @Test fun negotiatedReadOnlyHelperDoesNotReceiveTouchOrBack() {
        val helper = Helper().apply { offered = DesktopFeatures.REQUIRED }
        val connection = connect(helper)
        assertEquals(0, connection.capabilities.maxTouchContacts)
        assertFalse(connection.capabilities.backButton)
        assertEquals(DesktopErrorCode.UNSUPPORTED_CAPABILITY, assertThrows(DesktopException::class.java) {
            connection.touch(listOf(ProjectionContact(0, .5, .5, true)))
        }.category)
        assertThrows(DesktopException::class.java) { connection.back() }
        assertNull(helper.frame); assertEquals(0, helper.backCalls)
    }

    @Test fun rejectedHandshakeRetainsErrorCodeAndDiagnosticAcrossBinder() {
        val helper = Helper().apply { status = DesktopStatus(DesktopErrorCode.PROTOCOL_MISMATCH.wire, "incompatible helper") }
        val error = assertThrows(DesktopException::class.java) { connect(helper) }
        assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, error.category)
        assertEquals("incompatible helper", error.diagnostic)
    }

    @Test fun missingStructuredStatusIsRejectedForHandshakeAndStart() {
        assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, assertThrows(DesktopException::class.java) {
            connect(Helper().apply { omitStatus = true })
        }.category)
        val helper = Helper()
        val connection = connect(helper)
        helper.omitStatus = true
        val surface = Surface(SurfaceTexture(0))
        try {
            assertEquals(DesktopErrorCode.PROTOCOL_MISMATCH, assertThrows(DesktopException::class.java) {
                connection.start(surface, "test.app/.Main", DesktopDisplayConfig())
            }.category)
        } finally { surface.release() }
    }
}
