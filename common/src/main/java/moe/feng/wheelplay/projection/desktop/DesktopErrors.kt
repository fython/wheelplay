package moe.feng.wheelplay.projection.desktop

import android.os.RemoteException
import com.shilapi.xcertplay.shared.R

/** Stable wire codes. Diagnostics are for logs, never parsed for control flow or displayed as UI copy. */
internal enum class DesktopErrorCode(val wire: Int, val message: Int) {
    UNKNOWN(-1, R.string.desktop_error_internal),
    PROTOCOL_MISMATCH(1, R.string.desktop_error_protocol),
    PERMISSION_DENIED(2, R.string.desktop_error_permission),
    INVALID_ARGUMENT(3, R.string.desktop_error_request),
    INVALID_STATE(4, R.string.desktop_error_session),
    UNSUPPORTED_CAPABILITY(5, R.string.desktop_error_capability),
    APP_UNAVAILABLE(6, R.string.desktop_error_app),
    DISPLAY_FAILED(7, R.string.desktop_error_display),
    LAUNCH_FAILED(8, R.string.desktop_error_launch),
    STALE_SESSION(9, R.string.desktop_error_session),
    LAYOUT_CHANGED(10, R.string.desktop_error_layout),
    INPUT_FAILED(11, R.string.desktop_error_input),
    CLEANUP_FAILED(12, R.string.desktop_cleanup_failed),
    INTERNAL(13, R.string.desktop_error_internal),
    DISCONNECTED(14, R.string.desktop_disconnected),
    BIND_TIMEOUT(15, R.string.desktop_bind_timeout),
    NO_FRAME(16, R.string.desktop_no_frame),
    INPUT_QUEUE_FULL(17, R.string.desktop_error_input),
}

internal class DesktopException(val code: Int, val diagnostic: String, cause: Throwable? = null) :
    IllegalStateException(diagnostic, cause) {
    constructor(code: DesktopErrorCode, diagnostic: String, cause: Throwable? = null) : this(code.wire, diagnostic, cause)
    val category get() = DesktopErrorCode.entries.firstOrNull { it.wire == code } ?: DesktopErrorCode.UNKNOWN
}

internal inline fun desktopCheck(condition: Boolean, code: DesktopErrorCode, diagnostic: () -> String) {
    if (!condition) throw DesktopException(code, diagnostic())
}

/** Normalize platform transport/permission failures while retaining their diagnostic causes. */
internal inline fun <T> desktopRemoteCall(block: () -> T): T = try { block() }
catch (error: RemoteException) { throw DesktopException(DesktopErrorCode.DISCONNECTED, error.toString(), error) }
catch (error: SecurityException) { throw DesktopException(DesktopErrorCode.PERMISSION_DENIED, error.toString(), error) }

internal fun desktopFailure(error: Throwable): DesktopException =
    generateSequence(error) { it.cause }.filterIsInstance<DesktopException>().firstOrNull()
        ?: DesktopException(DesktopErrorCode.INTERNAL, error.toString(), error)

/** Only control-call errors carry text; successful control results share a singleton. */
internal inline fun desktopServiceStatus(block: () -> Unit): DesktopStatus = try { block(); DesktopStatus.OK }
catch (error: DesktopException) { DesktopStatus(error.code, error.diagnostic.take(1024)) }
catch (error: IllegalArgumentException) { DesktopStatus(DesktopErrorCode.INVALID_ARGUMENT.wire, error.toString().take(1024)) }
catch (error: SecurityException) { DesktopStatus(DesktopErrorCode.PERMISSION_DENIED.wire, error.toString().take(1024)) }
catch (error: Exception) { DesktopStatus(DesktopErrorCode.INTERNAL.wire, error.toString().take(1024)) }

internal inline fun <T> desktopOperation(code: DesktopErrorCode, block: () -> T): T = try { block() }
catch (error: DesktopException) { throw error }
catch (error: SecurityException) { throw DesktopException(DesktopErrorCode.PERMISSION_DENIED, error.toString(), error) }
catch (error: Exception) { throw DesktopException(code, error.toString(), error) }
