package moe.feng.wheelplay.projection.desktop

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesktopErrorsTest {
    class PlatformApi(private val failure: Throwable) {
        fun call(): Unit = throw failure
    }

    private fun invoke(failure: Throwable) = PlatformApi::class.java.getMethod("call").invoke(PlatformApi(failure))

    @Test fun reflectedPermissionFailuresKeepTheirCategoryCauseAndWireDiagnostic() {
        for (operation in listOf(DesktopErrorCode.INPUT_FAILED, DesktopErrorCode.LAUNCH_FAILED)) {
            val denied = SecurityException("INJECT_EVENTS permission denied by system")
            val failure = assertThrows(DesktopException::class.java) { desktopOperation(operation) { invoke(denied) } }
            assertEquals(DesktopErrorCode.PERMISSION_DENIED, failure.category)
            assertSame(denied, failure.cause)
            val wire = parcelRoundTrip(desktopServiceStatus { throw failure }, DesktopStatus.CREATOR)
            assertEquals(DesktopErrorCode.PERMISSION_DENIED.wire, wire.code)
            assertEquals(denied.toString(), wire.diagnostic)
        }
    }

    @Test fun nestedReflectionRetainsBusinessErrorsAndOperationSpecificFallback() {
        val known = DesktopException(9001, "platform-specific diagnostic")
        assertSame(known, assertThrows(DesktopException::class.java) {
            desktopOperation(DesktopErrorCode.LAUNCH_FAILED) { invoke(InvocationTargetException(known)) }
        })
        val cause = IllegalStateException("Activity launch rejected by system")
        val failure = assertThrows(DesktopException::class.java) {
            desktopOperation(DesktopErrorCode.LAUNCH_FAILED) { invoke(InvocationTargetException(cause)) }
        }
        assertEquals(DesktopErrorCode.LAUNCH_FAILED, failure.category)
        assertSame(cause, failure.cause)
        assertEquals(cause.toString(), failure.diagnostic)
    }

    @Test fun directControlCallsUnwrapReflectionAndStillBoundDiagnostics() {
        assertEquals(DesktopErrorCode.INVALID_ARGUMENT.wire,
            desktopServiceStatus { invoke(IllegalArgumentException("invalid target")) }.code)
        val status = desktopServiceStatus { invoke(SecurityException("denied ".repeat(300))) }
        assertEquals(DesktopErrorCode.PERMISSION_DENIED.wire, status.code)
        assertEquals(1024, status.diagnostic!!.length)
        assertTrue(status.diagnostic!!.startsWith("java.lang.SecurityException: denied"))
    }

    @Test fun fatalPlatformErrorsAreNotConvertedToRecoverableStatus() {
        val fatal = AssertionError("fatal platform error")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            desktopServiceStatus { desktopOperation(DesktopErrorCode.INPUT_FAILED) { invoke(fatal) } }
        })
    }
}
