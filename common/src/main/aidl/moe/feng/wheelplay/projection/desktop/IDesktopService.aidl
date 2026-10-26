package moe.feng.wheelplay.projection.desktop;
import moe.feng.wheelplay.projection.desktop.DesktopStartConfig;
import moe.feng.wheelplay.projection.desktop.DesktopStateResult;
import android.view.Surface;
import android.os.IBinder;
import moe.feng.wheelplay.projection.desktop.DesktopHandshakeResult;
import moe.feng.wheelplay.projection.desktop.DesktopStartResult;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;
import moe.feng.wheelplay.projection.desktop.DesktopTouchFrame;

/** Version 7. One handshake and one immutable display per helper; session calls require the host UID.
 * Control results carry DesktopStatus: zero for success, stable DesktopErrorCode plus diagnostics on failure.
 * Malformed Parcel data and Binder transport failures use Android's standard exceptions.
 */
interface IDesktopService {
    // Negotiated features are a supported subset of the host request; required bits must all be enabled.
    DesktopHandshakeResult hello(int version, long hostFeatures, long requiredFeatures) = 0;
    // Requires hello. Owner death releases the display and terminates the helper.
    DesktopStartResult start(String token, IBinder owner, in Surface surface, in DesktopStartConfig config) = 1;
    // Best-effort full snapshot, no per-frame reply. Persistent faults are reported by healthy().
    oneway void touch(String token, in DesktopTouchFrame frame) = 2;
    // Advances inputEpoch by one, discards older touches and cancels the gesture before Back.
    DesktopStatus back(String token, int layout, int inputEpoch) = 3;
    // Success confirms the owned layout and renews its watchdog; errors do not renew it.
    DesktopStateResult healthy(String token, int layout) = 4;
    // Idempotent after release; success confirms producer release, not helper process exit.
    DesktopStatus stop(String token) = 5;
    // Cancels a gesture without an UP/click and invalidates delayed old input. Synchronous control barrier.
    DesktopStatus cancelInput(String token, int layout, int inputEpoch) = 6;
    // Read-only diagnostic snapshot; does not renew the watchdog.
    DesktopStateResult getState(String token) = 7;
    // Shizuku reserved destroy transaction. Client also waits for Binder death to confirm cleanup.
    void destroy() = 16777114;
}
