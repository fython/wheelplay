package moe.feng.wheelplay.projection.desktop;
import android.view.Surface;
import android.os.IBinder;
import moe.feng.wheelplay.projection.desktop.DesktopHandshakeResult;
import moe.feng.wheelplay.projection.desktop.DesktopStartResult;
import moe.feng.wheelplay.projection.desktop.DesktopStatus;
import moe.feng.wheelplay.projection.desktop.DesktopTouchFrame;

/** Version 5. One handshake and one immutable display per helper; session calls require the host UID.
 * Control results carry DesktopStatus: zero for success, stable DesktopErrorCode plus diagnostics on failure.
 * Malformed Parcel data and Binder transport failures use Android's standard exceptions.
 */
interface IDesktopService {
    // Unknown feature bits are ignored; enabled features are the intersection of host and helper support.
    DesktopHandshakeResult hello(int version, long hostFeatures) = 0;
    // Requires hello. Owner death releases the display and terminates the helper.
    DesktopStartResult start(String token, IBinder owner, in Surface surface, String component, int width, int height, int dpi) = 1;
    // Best-effort full snapshot, no per-frame reply. Persistent faults are reported by healthy().
    oneway void touch(String token, in DesktopTouchFrame frame) = 2;
    DesktopStatus back(String token, int layout) = 3;
    // Success confirms the owned layout and renews its watchdog; errors do not renew it.
    DesktopStatus healthy(String token, int layout) = 4;
    // Idempotent after release; success confirms producer release, not helper process exit.
    DesktopStatus stop(String token) = 5;
    // Shizuku reserved destroy transaction. Client also waits for Binder death to confirm cleanup.
    void destroy() = 16777114;
}
